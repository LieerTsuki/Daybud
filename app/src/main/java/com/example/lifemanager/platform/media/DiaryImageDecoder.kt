package com.example.lifemanager.platform.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Bounds decoded image memory by the requested presentation size. */
internal fun decodeDiaryImage(file: File, maxSide: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > maxSide || options.outHeight / sample > maxSide) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
