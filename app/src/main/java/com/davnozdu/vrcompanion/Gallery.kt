package com.davnozdu.vrcompanion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Перенос снятого в общие хранилища телефона.
 *
 * Съёмку ведёт модуль из-под root и кладёт файл во внутренний каталог
 * приложения. Оттуда его забирает уже приложение и отдаёт MediaStore —
 * иначе файл виден только нам, а пользователь ждёт его в галерее.
 *
 * Каталог один для всех трёх видов записи, чтобы снятое очками не
 * растекалось по телефону и удалялось одним движением.
 */
object Gallery {

    private const val TAG = "VRappGallery"

    /** Подпапка в Pictures/Movies/Music: снятое очками держим вместе. */
    private const val ALBUM = "VR Companion"

    data class Saved(val uri: Uri, val name: String)

    fun savePhoto(ctx: Context, src: File, name: String): Saved? =
        save(ctx, src, name, "image/jpeg",
             MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES)

    fun saveVideo(ctx: Context, src: File, name: String): Saved? =
        save(ctx, src, name, "video/mp4",
             MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES)

    /**
     * Звук кладётся в Music, а не в Recordings: последняя появилась только
     * в Android 12 и на части прошивок не индексируется проигрывателями.
     */
    fun saveAudio(ctx: Context, src: File, name: String): Saved? =
        save(ctx, src, name, "audio/mp4",
             MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC)

    private fun save(
        ctx: Context, src: File, name: String,
        mime: String, collection: Uri, dir: String
    ): Saved? {
        if (!src.exists() || src.length() == 0L) {
            Log.w(TAG, "нечего сохранять: ${src.path}")
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
            // Пока файл копируется, он не должен попадаться в галерее
            // наполовину записанным.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore отказал во вставке")
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("нет потока записи")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            src.delete()
            Saved(uri, name)
        } catch (e: Exception) {
            Log.e(TAG, "не сохранить $name", e)
            // Недописанная запись в MediaStore хуже отсутствующей: она
            // останется битым файлом в галерее.
            resolver.delete(uri, null, null)
            null
        }
    }
}
