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

    /** Потолок записи в модуле — час; шелл должен ждать не меньше. */
    private const val MAX_VIDEO_MS = 3_600_000L

    sealed interface Result {
        data class Ok(val name: String, val details: String) : Result
        data class Fail(val reason: String) : Result
    }

    fun available(): Boolean = RootShell.ok("test -x $VR_CAM_CMD")

    /**
     * Что сейчас пишется. Нужно, чтобы кнопка «Остановить» знала, кого
     * останавливать: съёмку останавливает файл-стоп, который видит модуль,
     * а запись звука — сам объект записи.
     */
    @Volatile private var activeRecorder: AudioRecorder? = null
    @Volatile private var activeStopFile: File? = null

    val isRecording: Boolean get() = activeRecorder != null || activeStopFile != null

    /**
     * Остановить то, что идёт.
     *
     * Вызывается из потока интерфейса и намеренно не трогает RootShell: он
     * занят самой съёмкой, и вторая команда в него не пройдёт. Файл-стоп
     * приложение создаёт в своём каталоге, root для этого не нужен.
     */
    fun stop() {
        activeStopFile?.let { runCatching { it.createNewFile() } }
        activeRecorder?.stop()
    }

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
    fun video(ctx: Context): Result {
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

        val stopFile = File(dir, "clip.h265.stop")
        stopFile.delete()
        activeStopFile = stopFile
        activeRecorder = if (withSound) recorder else null

        val pool = Executors.newSingleThreadExecutor()
        val audioTask = if (withSound) pool.submit<Boolean> { recorder.record(pcm) } else null

        // Длительность модулю не передаём: он пишет, пока не увидит
        // файл-стоп. Таймаут шелла — по его же потолку в час плюс запас.
        val r = try {
            RootShell.exec("$VR_CAM_CMD video ${hevc.path}", MAX_VIDEO_MS + EXTRA_TIMEOUT_MS)
        } finally {
            activeStopFile = null
            activeRecorder = null
            stopFile.delete()
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
        if (!ok) {
            // Исходники оставляем: без них потом не разобраться, на чём
            // именно споткнулась сборка.
            return Result.Fail("снято, но не собралось в MP4 — исходники в ${hevc.parent}")
        }
        listOf(hevc, idx, pcm).forEach { it.delete() }

        val name = "VR-${stamp()}.mp4"
        val mb = mp4.length() / (1024.0 * 1024.0)
        val secs = parseSeconds(r.out)
        val saved = Gallery.saveVideo(ctx, mp4, name)
            ?: return Result.Fail("снято, но не сохранилось в галерею")

        val sound = when {
            !withSound -> "без звука: микрофон очков не найден"
            silenced   -> "без звука: микрофон занят другим приложением"
            else       -> "со звуком"
        }
        return Result.Ok(saved.name, "%.1f с, %.1f МБ, %s".format(secs, mb, sound))
    }

    /** Только звук: камера не трогается вовсе, root не нужен. */
    fun audio(ctx: Context): Result {
        val dir = workDir(ctx)
        val pcm = File(dir, "voice.pcm")
        val m4a = File(dir, "voice.m4a")
        pcm.delete(); m4a.delete()

        val recorder = AudioRecorder(ctx)
        if (!recorder.glassesMicAvailable()) {
            Log.w(TAG, "микрофон очков не найден — пишем тем, что дала система")
        }
        activeRecorder = recorder
        val pool = Executors.newSingleThreadExecutor()
        val task = pool.submit<Boolean> { recorder.record(pcm) }
        // Запись идёт, пока кто-нибудь не позовёт stop(): задача завершится
        // сама, ждать по таймеру больше нечего.
        val ok = runCatching { task.get() }.getOrDefault(false)
        activeRecorder = null
        pool.shutdownNow()

        if (!ok || !pcm.exists() || pcm.length() == 0L) {
            return Result.Fail("записать звук не удалось")
        }
        // Длительность считаем по объёму PCM: 16 бит моно, два байта на
        // отсчёт. После удаления файла узнать её будет неоткуда.
        val pcm0 = pcm.length()
        if (!Mp4Writer.audioOnly(pcm, m4a)) {
            pcm.delete()
            return Result.Fail("записано, но не собралось в файл")
        }
        pcm.delete()

        val name = "VR-${stamp()}.m4a"
        val kb = m4a.length() / 1024
        val secs = pcm0 / (AudioRecorder.SAMPLE_RATE * 2.0)
        val saved = Gallery.saveAudio(ctx, m4a, name)
            ?: return Result.Fail("записано, но не сохранилось")

        val note = if (recorder.wasSilenced) ", микрофон отбирали" else ""
        return Result.Ok(saved.name, "%.1f с, %d КБ%s".format(secs, kb, note))
    }

    /**
     * Длительность ролика — из отчёта модуля: путь, байты, кадры, частота,
     * метка первого кадра. Считать по числу кадров и частоте вернее, чем
     * по часам приложения: запись начинается не в момент вызова.
     */
    private fun parseSeconds(out: String): Double {
        val p = out.lineSequence().map { it.trim() }
            .lastOrNull { it.isNotEmpty() }?.split(" ") ?: return 0.0
        val frames = p.getOrNull(2)?.toIntOrNull() ?: return 0.0
        val fps = p.getOrNull(3)?.toDoubleOrNull() ?: return 0.0
        return if (fps > 0) frames / fps else 0.0
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
