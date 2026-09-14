package com.davnozdu.vrcompanion

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Запись с микрофонов очков штатным путём Android.
 *
 * Микрофон очков доступен через обычный AudioRecord — root не нужен, и
 * это не прихоть, а требование. Прямой захват ALSA идёт мимо политики
 * звука: карта очков открывается ровно один раз, и фоновая запись
 * отняла бы микрофон у входящего звонка — вас бы попросту не слышали.
 * Здесь же приоритеты разруливает система, а мы лишь узнаём, что нас
 * заглушили, и честно это показываем.
 */
class AudioRecorder(private val ctx: Context) {

    /**
     * 16 кГц моно. Выше 8 кГц у очков ровный ноль — тракт микрофона
     * работает на 16 кГц и растягивается до 48 только в USB. Писать
     * больше значило бы хранить пустую полосу.
     */
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "VRappAudio"
    }

    /** Метка первого отсчёта по CLOCK_MONOTONIC — якорь для сведения с видео. */
    @Volatile var startNanos: Long = 0L
        private set

    /** Заглушили ли нас чужим приоритетом (звонок, другое приложение). */
    @Volatile var wasSilenced = false
        private set

    @Volatile private var running = false
    private var record: AudioRecord? = null

    private val audioManager by lazy {
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val silenceWatch = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == record?.audioSessionId }
            if (mine != null && mine.isClientSilenced) {
                if (!wasSilenced) Log.w(TAG, "микрофон отняли — пишем тишину")
                wasSilenced = true
            }
        }
    }

    /** Микрофон очков, если очки подключены. null — писать нечем или их нет. */
    private fun glassesMic(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

    fun glassesMicAvailable(): Boolean = glassesMic() != null

    /**
     * Пишет сырой PCM в файл, пока не позовут stop().
     *
     * Вызывать из фонового потока: метод блокирующий.
     */
    @SuppressLint("MissingPermission")
    fun record(pcm: File): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord не принял 16 кГц моно")
            return false
        }
        // Запас вчетверо: при коротких буферах отставание планировщика
        // даёт разрывы, которые потом слышно щелчками.
        val bufSize = minBuf * 4

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "не создать AudioRecord", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord не инициализировался")
            rec.release()
            return false
        }
        // Просим именно очки. Система может не послушаться — тогда пишем
        // тем, что дала, но об этом хотя бы будет известно из лога.
        glassesMic()?.let {
            if (!rec.setPreferredDevice(it)) Log.w(TAG, "система не отдала микрофон очков")
        }

        record = rec
        wasSilenced = false
        startNanos = 0L
        audioManager.registerAudioRecordingCallback(silenceWatch, null)

        return try {
            rec.startRecording()
            running = true
            val buf = ByteArray(bufSize)
            FileOutputStream(pcm).use { out ->
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                        continue
                    }
                    if (startNanos == 0L) startNanos = firstSampleNanos(rec)
                    out.write(buf, 0, n)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "запись оборвалась", e)
            false
        } finally {
            running = false
            audioManager.unregisterAudioRecordingCallback(silenceWatch)
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            record = null
        }
    }

    /**
     * Момент первого отсчёта в шкале CLOCK_MONOTONIC.
     *
     * getTimestamp даёт пару «позиция кадра — время», а нам нужно начало
     * записи, поэтому вычитаем уже пройденные кадры. Та же шкала, в
     * которой V4L2 метит кадры камеры, — по ней звук и сводится с видео.
     */
    private fun firstSampleNanos(rec: AudioRecord): Long {
        val ts = AudioTimestamp()
        if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            val elapsedNs = ts.framePosition * 1_000_000_000L / SAMPLE_RATE
            return ts.nanoTime - elapsedNs
        }
        // Без метки сведение будет грубым, но запись не теряем.
        Log.w(TAG, "метка времени недоступна — синхронизация будет приблизительной")
        return System.nanoTime()
    }

    fun stop() { running = false }
}
