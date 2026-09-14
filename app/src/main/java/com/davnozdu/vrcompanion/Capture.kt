package com.davnozdu.vrcompanion

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Съёмка: фото, видео со звуком и отдельная запись звука.
 *
 * Работа разделена по тому, где она вообще возможна. Картинку снимает
 * модуль: камера очков — это UVC-устройство, которое поднимается
 * вендорской HID-командой и доступно лишь из-под root. Звук пишет само
 * приложение обычным AudioRecord — так его слышит политика звука
 * Android, и входящий звонок отбирает микрофон штатно, а не дерётся за
 * него с нами.
 *
 * Сборка контейнера и перенос в галерею — тоже здесь: у модуля нет
 * доступа ни к MediaMuxer, ни к MediaStore.
 */
object Capture {

    private const val TAG = "VRappCapture"

    const val VR_CAM_CMD = "/data/adb/bin/vr-cam"

    /** Съёмка длиннее таймаута по умолчанию, поэтому считаем с запасом. */
    private const val EXTRA_TIMEOUT_MS = 30_000L

    sealed interface Result {
        data class Ok(val name: String, val details: String) : Result
        data class Fail(val reason: String) : Result
    }

    fun available(): Boolean = RootShell.ok("test -x $VR_CAM_CMD")

    private fun workDir(ctx: Context): File =
        File(ctx.filesDir, "capture").apply { mkdirs() }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Фото: модуль отдаёт готовый JPEG, приложение только перекладывает. */
    fun photo(ctx: Context): Result {
        if (!available()) return Result.Fail("модуль не умеет снимать — обновите VR Headset Mode")
        val tmp = File(workDir(ctx), "shot.jpg")
        tmp.delete()

        val r = RootShell.exec("$VR_CAM_CMD photo ${tmp.path}", EXTRA_TIMEOUT_MS)
        if (!r.ok || !tmp.exists()) return Result.Fail(reason(r.out, "снять кадр не удалось"))

        val name = "VR-${stamp()}.jpg"
        val kb = tmp.length() / 1024
        val saved = Gallery.savePhoto(ctx, tmp, name)
            ?: return Result.Fail("снято, но не сохранилось в галерею")
        return Result.Ok(saved.name, "1920×1080, $kb КБ")
    }

    /**
     * Видео со звуком.
     *
     * Микрофон включается первым и намеренно: модуль ещё греет
     * автоэкспозицию камеры, и начало записи всё равно обрежется по
     * меткам времени. Начать звук позже картинки было бы нечем исправить.
     */
    fun video(ctx: Context, seconds: Int): Result {
        if (!available()) return Result.Fail("модуль не умеет снимать — обновите VR Headset Mode")
        val dir = workDir(ctx)
        val hevc = File(dir, "clip.h265")
        val idx = File(dir, "clip.h265.idx")
        val pcm = File(dir, "clip.pcm")
        val mp4 = File(dir, "clip.mp4")
        listOf(hevc, idx, pcm, mp4).forEach { it.delete() }

        val recorder = AudioRecorder(ctx)
        val withSound = recorder.glassesMicAvailable()
        if (!withSound) Log.w(TAG, "микрофон очков не найден — пишем без звука")

        val pool = Executors.newSingleThreadExecutor()
        val audioTask = if (withSound) pool.submit<Boolean> { recorder.record(pcm) } else null

        val r = try {
            RootShell.exec(
                "$VR_CAM_CMD video ${hevc.path} $seconds",
                seconds * 1000L + EXTRA_TIMEOUT_MS
            )
        } finally {
            recorder.stop()
            audioTask?.let { runCatching { it.get(5, TimeUnit.SECONDS) } }
            pool.shutdownNow()
        }

        if (!r.ok || !hevc.exists() || !idx.exists()) {
            return Result.Fail(reason(r.out, "записать видео не удалось"))
        }

        val silenced = withSound && recorder.wasSilenced
        val ok = Mp4Writer.videoWithAudio(
            hevc, idx,
            if (withSound && !silenced) pcm else null,
            recorder.startNanos, mp4
        )
        listOf(hevc, idx, pcm).forEach { it.delete() }
        if (!ok) return Result.Fail("снято, но не собралось в MP4")

        val name = "VR-${stamp()}.mp4"
        val mb = mp4.length() / (1024.0 * 1024.0)
        val saved = Gallery.saveVideo(ctx, mp4, name)
            ?: return Result.Fail("снято, но не сохранилось в галерею")

        val sound = when {
            !withSound -> "без звука: микрофон очков не найден"
            silenced   -> "без звука: микрофон занят другим приложением"
            else       -> "со звуком"
        }
        return Result.Ok(saved.name, "%d с, %.1f МБ, %s".format(seconds, mb, sound))
    }

    /** Только звук: камера не трогается вовсе, root не нужен. */
    fun audio(ctx: Context, seconds: Int): Result {
        val dir = workDir(ctx)
        val pcm = File(dir, "voice.pcm")
        val m4a = File(dir, "voice.m4a")
        pcm.delete(); m4a.delete()

        val recorder = AudioRecorder(ctx)
        if (!recorder.glassesMicAvailable()) {
            Log.w(TAG, "микрофон очков не найден — пишем тем, что дала система")
        }
        val pool = Executors.newSingleThreadExecutor()
        val task = pool.submit<Boolean> { recorder.record(pcm) }
        try {
            Thread.sleep(seconds * 1000L)
        } catch (_: InterruptedException) {
        } finally {
            recorder.stop()
        }
        val ok = runCatching { task.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
        pool.shutdownNow()

        if (!ok || !pcm.exists() || pcm.length() == 0L) {
            return Result.Fail("записать звук не удалось")
        }
        if (!Mp4Writer.audioOnly(pcm, m4a)) {
            pcm.delete()
            return Result.Fail("записано, но не собралось в файл")
        }
        pcm.delete()

        val name = "VR-${stamp()}.m4a"
        val kb = m4a.length() / 1024
        val saved = Gallery.saveAudio(ctx, m4a, name)
            ?: return Result.Fail("записано, но не сохранилось")

        val note = if (recorder.wasSilenced) ", микрофон отбирали" else ""
        return Result.Ok(saved.name, "$seconds с, $kb КБ$note")
    }

    /**
     * Сообщение модуля полезнее общей фразы: оно объясняет, например, что
     * поток не взведён и нужен перетык очков.
     */
    private fun reason(out: String, fallback: String): String {
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        return if (line.isNullOrBlank()) fallback else line
    }
}
