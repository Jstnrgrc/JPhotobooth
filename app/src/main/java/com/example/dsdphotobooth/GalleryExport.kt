package com.example.dsdphotobooth

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

internal object GalleryExport {
    fun save(resolver: ContentResolver, session: Long, photos: List<File>, strip: Bitmap): Int {
        val added = mutableListOf<Uri>()
        val timestamp = System.currentTimeMillis()
        fun add(name: String, write: (OutputStream) -> Unit) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "DSD-${session}-${timestamp}-${name}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Daily Sweet Doze")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
            added.add(uri)
            checkNotNull(resolver.openOutputStream(uri,"w")).use(write)
        }
        try {
            photos.forEachIndexed { i, file ->
                add("photo-${i + 1}") { out -> file.inputStream().use { input -> check(input.copyTo(out) > 0L) } }
            }
            add("strip") { out -> check(strip.compress(Bitmap.CompressFormat.JPEG,95,out)) }
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) }
            added.forEach { check(resolver.update(it,published,null,null) == 1) }
            return added.size
        } catch (error: Exception) {
            added.forEach { runCatching { resolver.delete(it,null,null) } }
            throw error
        }
    }
}
