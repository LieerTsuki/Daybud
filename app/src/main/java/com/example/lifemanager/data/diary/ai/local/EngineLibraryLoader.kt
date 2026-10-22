package com.example.lifemanager.data.diary.ai.local

/** Chooses the complete engine before the VM resolves any inference JNI symbol. */
internal class EngineLibraryLoader(
    private val isArm64: Boolean,
    private val candidateEnabled: Boolean,
    private val hasDotProduct: () -> Boolean,
    private val loadLibrary: (String) -> Unit,
) {
    private var loaded = false
    // Resolve once even when the selected engine fails to load. Trying the other
    // complete library afterward could leave two identical JNI exports loaded.
    private val selectedLibrary: String by lazy {
        val supported = if (isArm64 && candidateEnabled) {
            try { hasDotProduct() } catch (_: LinkageError) { false } catch (_: SecurityException) { false }
        } else false
        if (supported) "diary_llama_dotprod" else "diary_llama"
    }

    @Synchronized fun load() {
        if (loaded) return
        loadLibrary(selectedLibrary)
        loaded = true
    }
}
