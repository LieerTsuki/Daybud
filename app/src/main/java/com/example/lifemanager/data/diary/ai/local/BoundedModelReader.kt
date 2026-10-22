package com.example.lifemanager.data.diary.ai.local

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A single-owner, bounded window for the GGUF directory; tensor data is never buffered here. */
internal class BoundedModelReader(file: File, capacity: Int = 64 * 1024) : Closeable {
    private val buffer = ByteArray(capacity.also { require(it in 1..64 * 1024) })
    private val input = RandomAccessFile(file, "r")
    private val size = input.length()
    private var closed = false
    private var windowStart = 0L
    private var windowSize = 0
    var filePointer: Long = 0
        private set

    fun length(): Long { checkOpen(); return size }

    fun seek(position: Long) {
        checkOpen()
        if (position < 0) throw IOException("Negative model offset")
        filePointer = position
    }

    fun readUnsignedByte(): Int {
        val offset = currentWindowOffset()
        filePointer++
        return buffer[offset].toInt() and 255
    }

    fun readFully(output: ByteArray) {
        checkOpen()
        if (filePointer > size || output.size.toLong() > size - filePointer) throw EOFException()
        var copied = 0
        while (copied < output.size) {
            val offset = currentWindowOffset()
            val count = minOf(windowSize - offset, output.size - copied)
            buffer.copyInto(output, copied, offset, offset + count)
            copied += count
            filePointer += count
        }
    }

    private fun currentWindowOffset(): Int {
        checkOpen()
        if (filePointer >= size) throw EOFException()
        if (filePointer < windowStart || filePointer - windowStart >= windowSize) {
            input.seek(filePointer)
            windowStart = filePointer
            windowSize = input.read(buffer)
            if (windowSize <= 0) throw EOFException()
        }
        return (filePointer - windowStart).toInt()
    }

    private fun checkOpen() {
        if (closed) throw IOException("Model reader is closed")
    }

    override fun close() {
        if (!closed) { closed = true; input.close() }
    }
}
