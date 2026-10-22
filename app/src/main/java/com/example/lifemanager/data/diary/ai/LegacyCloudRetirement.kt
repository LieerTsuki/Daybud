package com.example.lifemanager.data.diary.ai

import java.io.File

/** Deletes only retired cloud state; never reads or decrypts its contents. */
class LegacyCloudRetirement(private val directory: File, private val removeKey: (String) -> Unit) {
    fun retire(): Boolean {
        var complete = true
        for (name in listOf("service.aesgcm", "consent-jobs.json")) {
            for (suffix in listOf("", ".bak", ".new")) {
                // AtomicFile's sidecars are equally private. Never recurse into this directory.
                val removed = runCatching { java.nio.file.Files.deleteIfExists(File(directory, name + suffix).toPath()); true }.getOrDefault(false)
                complete = removed && complete
            }
        }
        val keyRemoved = runCatching { removeKey("lifemanager.diary.ai.v1"); true }.getOrDefault(false)
        return complete && keyRemoved
    }
}
