package com.example.lifemanager.data.diary.ai.local

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI resolution without a model or diary input; no business storage is opened. */
@RunWith(AndroidJUnit4::class)
class NativeEngineSelectionDeviceTest {
    @Test fun exactlyOneCompleteEngineIsMappedBeforeTheFirstJniCall() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        assumeTrue("Inference has explicit unsupported shims for 32-bit devices", abi in setOf("arm64-v8a", "x86_64"))
        // Rejected before model/backend setup, after the VM loads and resolves its engine.
        assertThrows(IllegalArgumentException::class.java) { NativeLlama.open(-1, contextTokens = 0) }
        val mappings = File("/proc/self/maps").readText()
        val mapped = mappedLibraries(mappings, abi!!)
        val libraries = mapped.filter { it == "libdiary_llama.so" || it == "libdiary_llama_dotprod.so" }.toSet()
        assertEquals("Two complete engines would make identical JNI lookup ambiguous", 1, libraries.size)
        if (!BuildConfig.DIARY_DOTPROD_CANDIDATE || abi != "arm64-v8a") {
            assertEquals(setOf("libdiary_llama.so"), libraries)
            assertFalse("The default/non-ARM path must not initialize the CPU probe", "libdiary_cpu_features.so" in mapped)
        } else {
            assertTrue("The ARM candidate must query a separate baseline probe before choosing", "libdiary_cpu_features.so" in mapped)
        }
    }

    /** Uncompressed .so entries can be mapped directly from base.apk instead of an extracted path. */
    private fun mappedLibraries(maps: String, abi: String): Set<String> {
        val names = Regex("libdiary_(?:llama(?:_dotprod)?|cpu_features)\\.so").findAll(maps)
            .map { it.value }.toMutableSet()
        val info = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        for (apk in listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()) {
            val offsets = maps.lineSequence().filter { it.substringAfterLast(' ').removeSuffix(" (deleted)") == apk }
                .mapNotNull { it.trim().split(Regex("\\s+"), limit = 6).getOrNull(2)?.toLongOrNull(16) }.toList()
            if (offsets.isEmpty()) continue
            RandomAccessFile(apk, "r").use { zip ->
                fun u16(): Int = zip.readUnsignedByte() or (zip.readUnsignedByte() shl 8)
                fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
                var end = zip.length() - 22
                val floor = maxOf(0, zip.length() - 65557)
                while (end >= floor) {
                    zip.seek(end)
                    if (u32() == 0x06054b50L) break
                    end--
                }
                check(end >= floor) { "APK ZIP directory missing" }
                zip.seek(end + 10)
                val count = u16()
                zip.seek(end + 16)
                var central = u32()
                repeat(count) {
                    zip.seek(central)
                    check(u32() == 0x02014b50L)
                    zip.seek(central + 10)
                    val method = u16()
                    zip.seek(central + 20)
                    val compressed = u32()
                    zip.seek(central + 28)
                    val nameSize = u16(); val extraSize = u16(); val commentSize = u16()
                    zip.seek(central + 42)
                    val local = u32()
                    val entry = ByteArray(nameSize).also { zip.readFully(it) }.toString(Charsets.UTF_8)
                    if (method == 0 && entry.startsWith("lib/$abi/libdiary_") && entry.endsWith(".so")) {
                        zip.seek(local)
                        check(u32() == 0x04034b50L)
                        zip.seek(local + 26)
                        val localName = u16(); val localExtra = u16()
                        val start = local + 30 + localName + localExtra
                        if (offsets.any { it >= start && it < start + compressed }) names += entry.substringAfterLast('/')
                    }
                    central += 46 + nameSize + extraSize + commentSize
                }
            }
        }
        return names
    }
}
