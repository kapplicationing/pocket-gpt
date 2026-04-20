package com.pocketagent.android.ui

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun copyContentUriToLocal(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val mimeType = context.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase()
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: "jpg"
            val imagesDir = java.io.File(context.cacheDir, "attached_images").apply { mkdirs() }
            cleanupStaleAttachedImages(imagesDir)
            val target = java.io.File(imagesDir, "img_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            target.absolutePath
        }.getOrNull()
    }
}

private fun cleanupStaleAttachedImages(imagesDir: java.io.File) {
    val staleThresholdMs = 60 * 60 * 1000L
    imagesDir.listFiles()?.forEach { file ->
        if (System.currentTimeMillis() - file.lastModified() > staleThresholdMs) {
            file.delete()
        }
    }
}
