package com.example.lifemanager.data.backup

import java.io.InputStream
import java.io.OutputStream
import com.example.lifemanager.domain.backup.BackupLocation

internal interface BackupStreamAccess {
    fun openRead(location: BackupLocation): InputStream?
    fun openWrite(location: BackupLocation): OutputStream?
}

