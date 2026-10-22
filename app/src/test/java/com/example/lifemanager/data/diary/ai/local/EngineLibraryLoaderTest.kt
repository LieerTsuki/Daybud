package com.example.lifemanager.data.diary.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineLibraryLoaderTest {
    @Test fun `disabled candidate never probes and loads baseline once`() {
        val libraries = mutableListOf<String>()
        val loader = EngineLibraryLoader(true, false, { error("must not probe") }, libraries::add)
        loader.load()
        loader.load()
        assertEquals(listOf("diary_llama"), libraries)
    }

    @Test fun `other ABIs never probe or choose ARM extension`() {
        val libraries = mutableListOf<String>()
        EngineLibraryLoader(false, true, { error("must not probe") }, libraries::add).load()
        assertEquals(listOf("diary_llama"), libraries)
    }

    @Test fun `unsupported ARM hardware retains baseline`() {
        val libraries = mutableListOf<String>()
        EngineLibraryLoader(true, true, { false }, libraries::add).load()
        assertEquals(listOf("diary_llama"), libraries)
    }

    @Test fun `enabled compatible hardware loads only dotprod after probing`() {
        val events = mutableListOf<String>()
        val loader = EngineLibraryLoader(true, true, { events += "probe"; true }, { events += it })
        loader.load()
        loader.load()
        assertEquals(listOf("probe", "diary_llama_dotprod"), events)
    }

    @Test fun `missing standalone probe safely chooses baseline`() {
        val libraries = mutableListOf<String>()
        EngineLibraryLoader(true, true, { throw UnsatisfiedLinkError("probe missing") }, libraries::add).load()
        assertEquals(listOf("diary_llama"), libraries)
    }

    @Test fun `failed complete engine load never falls back to a second JNI engine`() {
        val libraries = mutableListOf<String>()
        val loader = EngineLibraryLoader(true, true, { true }, {
            libraries += it
            throw UnsatisfiedLinkError("engine load failed")
        })
        assertThrows(UnsatisfiedLinkError::class.java) { loader.load() }
        assertThrows(UnsatisfiedLinkError::class.java) { loader.load() }
        assertEquals(listOf("diary_llama_dotprod", "diary_llama_dotprod"), libraries)
    }
}
