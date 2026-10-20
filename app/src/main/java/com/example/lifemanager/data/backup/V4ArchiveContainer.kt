package com.example.lifemanager.data.backup

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class V4MediaRecord(val id:String, val bytes:Long, val sha256:String)

/** Bounded streaming container. JSON semantics belong to V4BackupManifest, never to ZIP paths. */
class V4ArchiveContainer(
    private val maxManifestBytes:Int=64*1024*1024,
    private val maxMediaBytes:Long=8L*1024*1024*1024,
    private val maxArchiveEntries:Int=100_001,
    private val maxFileBytes:Long=500L*1024*1024,
) {
    fun write(output:OutputStream, manifest:ByteArray, media:List<V4MediaRecord>, open:(V4MediaRecord)->InputStream) {
        require(manifest.size in 1..maxManifestBytes) { "备份清单超限" }
        validateRecords(media)
        ZipOutputStream(output).use { zip ->
            // Original media is already compressed; level zero also avoids decompression bombs.
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry()
            for(record in media) {
                zip.putNextEntry(ZipEntry("media/${record.id}"))
                open(record).use { input -> copyChecked(input,zip,record) }
                zip.closeEntry()
            }
        }
    }

    fun read(input:InputStream, expected:(ByteArray)->List<V4MediaRecord>, accept:(V4MediaRecord,InputStream)->Unit):ByteArray {
        val raw=TailInput(input, maxMediaBytes+maxManifestBytes+maxArchiveEntries.toLong()*1024+1024*1024)
        ZipInputStream(raw).use { zip ->
            val first=zip.nextEntry ?: error("备份归档为空")
            require(!first.isDirectory && first.name=="manifest.json") { "备份清单必须位于首项" }
            val manifest=bounded(zip,maxManifestBytes)
            zip.closeEntry();checkRatio(first)
            val records=expected(manifest)
            validateRecords(records)
            val remaining=records.associateBy { "media/${it.id}" }.toMutableMap()
            val seen=mutableSetOf("manifest.json")
            var entries=1
            while(true) {
                val entry=zip.nextEntry ?: break
                require(++entries<=maxArchiveEntries && !entry.isDirectory && seen.add(entry.name)) { "备份含重复或过多条目" }
                val record=remaining.remove(entry.name) ?: error("备份含未知媒体或不安全路径")
                val checked=CheckedInput(zip,record)
                accept(record,checked)
                val buffer=ByteArray(64*1024)
                while(checked.read(buffer)>=0) { /* Validate even consumers that stop early. */ }
                checked.finish()
                zip.closeEntry();checkRatio(entry)
            }
            require(remaining.isEmpty()) { "备份缺少媒体文件" }
            // ZipInputStream accepts an absent central directory. Independently require the
            // complete EOCD/ZIP64 footer after draining bytes beyond its internal read-ahead.
            val buffer=ByteArray(64*1024)
            while(raw.read(buffer)>=0) { }
            raw.verifyFooter(entries)
            return manifest
        }
    }

    private fun validateRecords(records:List<V4MediaRecord>) {
        require(records.size+1<=maxArchiveEntries && records.map { it.id }.toSet().size==records.size) { "备份媒体重复或超限" }
        var total=0L
        records.forEach {
            require(runCatching { UUID.fromString(it.id).toString()==it.id }.getOrDefault(false)) { "无效媒体标识" }
            require(it.bytes in 1..maxFileBytes && it.sha256.matches(Regex("[0-9a-f]{64}"))) { "无效媒体摘要或大小" }
            require(total<=maxMediaBytes-it.bytes) { "备份媒体总量超限" };total+=it.bytes
        }
    }

    private fun bounded(input:InputStream,limit:Int):ByteArray {
        val out=ByteArrayOutputStream(minOf(limit,8192));val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer);if(count<0)break
            require(count<=limit-out.size()) { "备份清单超限" };out.write(buffer,0,count)
        }
        return out.toByteArray().also { require(it.isNotEmpty()) { "备份清单为空" } }
    }

    private fun copyChecked(input:InputStream,output:OutputStream,record:V4MediaRecord) {
        val checked=CheckedInput(input,record);val buffer=ByteArray(64*1024)
        while(true) { val count=checked.read(buffer);if(count<0)break;output.write(buffer,0,count) }
        checked.finish()
    }

    private fun checkRatio(entry:ZipEntry) {
        require(entry.size<1024*1024 || entry.compressedSize<0 || entry.size<=maxOf(1,entry.compressedSize)*200) { "备份压缩膨胀超限" }
    }

    private class CheckedInput(input:InputStream,private val record:V4MediaRecord):FilterInputStream(input) {
        private val hash=MessageDigest.getInstance("SHA-256");private var count=0L
        override fun read():Int { val value=`in`.read();if(value>=0) { require(count<record.bytes) { "媒体大小不符" };count++;hash.update(value.toByte()) };return value }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            val n=`in`.read(buffer,offset,length)
            if(n>0) { require(n.toLong()<=record.bytes-count) { "媒体实际大小超限" };count+=n;hash.update(buffer,offset,n) };return n
        }
        override fun close() { /* Entry ownership remains with the archive. */ }
        fun finish() { require(count==record.bytes && hash.digest().hex()==record.sha256) { "媒体大小或SHA256不符" } }
    }

    private class TailInput(input:InputStream,private val maximum:Long):FilterInputStream(input) {
        private val ring=ByteArray(66*1024);private var count=0L
        private fun append(bytes:ByteArray,offset:Int,length:Int) {
            require(length.toLong()<=maximum-count) { "归档文件过大" }
            var position=offset;var left=length
            while(left>0) { val index=(count%ring.size).toInt();val n=minOf(left,ring.size-index);bytes.copyInto(ring,index,position,position+n);count+=n;position+=n;left-=n }
        }
        override fun read():Int { val value=`in`.read();if(value>=0)append(byteArrayOf(value.toByte()),0,1);return value }
        override fun read(bytes:ByteArray,offset:Int,length:Int):Int { val n=`in`.read(bytes,offset,length);if(n>0)append(bytes,offset,n);return n }
        fun verifyFooter(expectedCount:Int) {
            val size=minOf(count,ring.size.toLong()).toInt()
            val tail=ByteArray(size) { i -> ring[((count-size+i)%ring.size).toInt()] }
            fun unsigned(position:Int,n:Int):Long { require(position>=0 && position+n<=tail.size);var value=0L;for(i in 0 until n)value=value or ((tail[position+i].toLong() and 255) shl (8*i));return value }
            val end=(tail.size-22 downTo 0).firstOrNull { unsigned(it,4)==0x06054b50L && it+22+unsigned(it+20,2)==tail.size.toLong() }
                ?: error("备份ZIP中央目录不完整")
            require(unsigned(end+4,2)==0L && unsigned(end+6,2)==0L) { "不支持分卷备份" }
            val items=unsigned(end+10,2)
            if(items!=65535L) require(items==expectedCount.toLong()) { "备份目录数量不符" }
            else {
                val locator=end-20;require(unsigned(locator,4)==0x07064b50L && unsigned(locator+4,4)==0L && unsigned(locator+16,4)==1L) { "ZIP64目录无效" }
                val zip64=locator-56
                require(unsigned(zip64,4)==0x06064b50L && unsigned(zip64+4,8)==44L && unsigned(zip64+32,8)==expectedCount.toLong()) { "ZIP64记录数量不符" }
            }
        }
    }
}

private fun ByteArray.hex()=joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
