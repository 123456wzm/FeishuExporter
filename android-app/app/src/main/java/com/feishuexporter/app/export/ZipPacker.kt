package com.feishuexporter.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 把导出目录打成 zip 并保存到手机 Downloads，返回可访问的 Uri */
class ZipPacker(private val context: Context) {

    fun pack(sourceDir: File, zipName: String): Uri? {
        // 先打到应用内部临时文件
        val tmp = File(context.cacheDir, zipName)
        try {
            ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                val base = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.absolutePath.removePrefix(base)
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            return saveToDownloads(zipName, tmp)
        } catch (e: Exception) {
            return null
        }
    }

    private fun saveToDownloads(displayName: String, source: File): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FeishuExporter")
                dir.mkdirs()
                val dest = File(dir, displayName)
                source.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) { null }
    }
}
