package com.example.lifemanager.data.diary

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** Small actual H.264 MP4, encoded on the target device; no network or bundled foreign media. */
internal fun createDiaryDeviceVideo(target: File, frameCount: Int = 6) {
    val width = 32
    val height = 32
    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, 10)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val encoderName = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)) {
        "The device must support a flexible YUV420 H.264 encoder for this media integration test"
    }
    val codec = MediaCodec.createByCodecName(encoderName)
    val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var codecStarted = false
    var muxerStarted = false
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start(); codecStarted = true
        val frame = ByteArray(width * height * 3 / 2) { if (it < width * height) 70.toByte() else 128.toByte() }
        var sent = 0
        var inputEnded = false
        var outputEnded = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out encoding the synthetic MP4" }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (sent == frameCount) {
                        codec.queueInputBuffer(index, 0, 0, sent * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.getInputBuffer(index)!!.apply { clear(); put(frame) }
                        codec.queueInputBuffer(index, 0, frame.size, sent * 100_000L, 0)
                        sent++
                    }
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        check(muxerStarted)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        check(muxerStarted) { "Synthetic MP4 did not produce a video track" }
    } finally {
        try { if (codecStarted) codec.stop() } finally { codec.release() }
        try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
    }
    check(target.length() > 0)
}
