package com.example.lifemanager.data.diary.ai.local

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedModelReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unsignedReadsAndBulkReadsRemainExactAcrossBufferEdges() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(0, 127, -128, -1, 4, 5, 6, 7, 8, 9)) }
        BoundedModelReader(file, capacity = 4).use { input ->
            assertEquals(0, input.readUnsignedByte())
            assertEquals(127, input.readUnsignedByte())
            val output = ByteArray(6)
            input.readFully(output)
            assertArrayEquals(byteArrayOf(-128, -1, 4, 5, 6, 7), output)
            assertEquals(8L, input.filePointer)
            assertEquals(8, input.readUnsignedByte())
            assertEquals(9, input.readUnsignedByte())
            assertEquals(10L, input.length())
            expectEof { input.readUnsignedByte() }
        }
    }

    @Test fun seekingInsideAndOutsideCachedWindowUsesLogicalPosition() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17, 18)) }
        BoundedModelReader(file, capacity = 4).use { input ->
            assertEquals(10, input.readUnsignedByte())
            input.seek(3); assertEquals(13, input.readUnsignedByte())
            input.seek(1); assertEquals(11, input.readUnsignedByte())
            input.seek(8); assertEquals(18, input.readUnsignedByte())
            input.seek(0)
            val output = ByteArray(5)
            input.readFully(output)
            assertArrayEquals(byteArrayOf(10, 11, 12, 13, 14), output)
        }
    }

    @Test fun eofAndLargeSeeksCannotWrapPositionOrReturnCachedBytes() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        BoundedModelReader(file, capacity = 2).use { input ->
            input.seek(2)
            expectEof { input.readFully(ByteArray(2)) }
            input.seek(3); expectEof { input.readUnsignedByte() }
            input.seek(Long.MAX_VALUE); expectEof { input.readUnsignedByte() }
            assertEquals(Long.MAX_VALUE, input.filePointer)
            input.seek(0); assertEquals(1, input.readUnsignedByte())
            try { input.seek(-1); fail("Negative seek accepted") } catch (_: IOException) { }
        }
    }

    @Test fun emptyReadAtEofDoesNotRefillAndClosedReaderCannotUseCachedBytes() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val input = BoundedModelReader(file, capacity = 2)
        assertEquals(1, input.readUnsignedByte())
        input.seek(2); input.readFully(ByteArray(0))
        input.seek(0); input.close(); input.close()
        try { input.readUnsignedByte(); fail("Closed reader returned cached data") } catch (_: IOException) { }
        try { input.seek(0); fail("Closed reader accepted a seek") } catch (_: IOException) { }
    }

    @Test fun invalidCapacityIsRejectedBeforeOpeningFile() {
        val missing = java.io.File(temporary.root, "missing")
        for (capacity in listOf(0, -1, 65_537, Int.MAX_VALUE)) {
            try { BoundedModelReader(missing, capacity); fail("Unbounded capacity accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun expectEof(action: () -> Unit) {
        try { action(); fail("Expected EOF") } catch (_: EOFException) { }
    }
}
