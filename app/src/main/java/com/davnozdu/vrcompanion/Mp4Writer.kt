package com.davnozdu.vrcompanion

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Сборка MP4 из того, что сняли модуль и приложение.
 *
 * Модуль отдаёт поток HEVC и индекс кадров, приложение — сырой PCM с
 * микрофона. Здесь они сводятся в один файл: видео перекладывается как
 * есть, звук сжимается в AAC, метки времени приводятся к общей шкале.
 *
 * Перекодирования картинки нет намеренно. HEVC и выбран потому, что
 * MediaMuxer принимает его напрямую: MJPEG пришлось бы разжимать и
 * сжимать заново на каждом кадре.
 */
object Mp4Writer {

    private const val TAG = "VRappMux"

    private const val AUDIO_MIME = "audio/mp4a-latm"
    private const val AUDIO_BITRATE = 64_000
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_HEVC

    private const val WIDTH = 1920
    private const val HEIGHT = 1080

    private data class Frame(val offset: Long, val size: Int, val timeUs: Long, val key: Boolean)

    /**
     * Индекс, который пишет vrcam: по строке на кадр — смещение, размер,
     * метка времени в микросекундах по CLOCK_MONOTONIC и признак опорного
     * кадра. Опорные помечать обязательно: по ним плеер перематывает, и
     * без них перемотка упирается в начало файла.
     */
    private fun readIndex(idx: File): List<Frame> =
        idx.readLines().mapNotNull { line ->
            val p = line.trim().split(" ")
            if (p.size < 3) return@mapNotNull null
            val off = p[0].toLongOrNull() ?: return@mapNotNull null
            val size = p[1].toIntOrNull() ?: return@mapNotNull null
            val us = p[2].toLongOrNull() ?: return@mapNotNull null
            val key = p.getOrNull(3) == "1"
            Frame(off, size, us, key)
        }

    /**
     * Вытащить наборы параметров (VPS, SPS, PPS) из первого кадра.
     *
     * MediaMuxer требует их отдельно, в csd-0. Отдать весь первый кадр
     * целиком нельзя: вместе с параметрами там идёт и картинка, а
     * мультиплексор ждёт только заголовки.
     */
    private fun parameterSets(firstFrame: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        var found = false
        while (i + 4 < firstFrame.size) {
            // Стартовый код Annex-B: три или четыре байта.
            val long4 = firstFrame[i] == 0.toByte() && firstFrame[i + 1] == 0.toByte() &&
                    firstFrame[i + 2] == 0.toByte() && firstFrame[i + 3] == 1.toByte()
            val short3 = firstFrame[i] == 0.toByte() && firstFrame[i + 1] == 0.toByte() &&
                    firstFrame[i + 2] == 1.toByte()
            if (!long4 && !short3) { i++; continue }

            val startLen = if (long4) 4 else 3
            val nalStart = i + startLen
            if (nalStart >= firstFrame.size) break
            val type = (firstFrame[nalStart].toInt() shr 1) and 0x3F

            // Ищем конец этой NAL-единицы — начало следующей.
            var j = nalStart + 2
            var end = firstFrame.size
            while (j + 3 < firstFrame.size) {
                val n4 = firstFrame[j] == 0.toByte() && firstFrame[j + 1] == 0.toByte() &&
                        firstFrame[j + 2] == 0.toByte() && firstFrame[j + 3] == 1.toByte()
                val n3 = firstFrame[j] == 0.toByte() && firstFrame[j + 1] == 0.toByte() &&
                        firstFrame[j + 2] == 1.toByte()
                if (n4 || n3) { end = j; break }
                j++
            }
            // 32 — VPS, 33 — SPS, 34 — PPS.
            if (type == 32 || type == 33 || type == 34) {
                out.write(firstFrame, i, end - i)
                found = true
            } else if (found) {
                // Заголовки идут подряд перед картинкой: встретили не
                // заголовок после них — дальше уже кадр.
                break
            }
            i = end
        }
        return if (found) out.toByteArray() else null
    }

    /**
     * Видео со звуком.
     *
     * @param audioStartNanos момент первого отсчёта звука по CLOCK_MONOTONIC;
     *        по нему звук и подрезается, иначе он уехал бы вперёд на время
     *        прогрева автоэкспозиции камеры.
     * @return true, если файл собран.
     */
    fun videoWithAudio(
        hevc: File, idx: File, pcm: File?, audioStartNanos: Long, out: File
    ): Boolean {
        val frames = readIndex(idx)
        if (frames.isEmpty()) {
            Log.e(TAG, "индекс пуст — собирать нечего")
            return false
        }
        val raf = try { RandomAccessFile(hevc, "r") } catch (e: Exception) {
            Log.e(TAG, "не открыть поток", e); return false
        }

        return raf.use { file ->
            val first = ByteArray(frames[0].size)
            file.seek(frames[0].offset)
            file.readFully(first)
            val csd = parameterSets(first)
            if (csd == null) {
                Log.e(TAG, "в потоке не нашлись VPS/SPS/PPS")
                return@use false
            }

            // Начало отсчёта — метка первого кадра: от неё считаются и
            // метки видео, и подрезка звука.
            val base = frames[0].timeUs

            // Звук сжимается до того, как создан мультиплексор: формат
            // дорожки кодировщик отдаёт только вместе с первыми данными, а
            // объявить её надо до старта записи. Десять секунд AAC — это
            // около восьмидесяти килобайт, держать их в памяти не жалко.
            val audio = if (pcm != null && pcm.exists() && pcm.length() > 0) {
                val audioStartUs = audioStartNanos / 1000
                runCatching { AacEncoder.encodeAll(pcm, base - audioStartUs) }
                    .onFailure { Log.e(TAG, "звук не сжался — соберём без него", it) }
                    .getOrNull()
            } else null

            val muxer = try { MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            catch (e: Exception) { Log.e(TAG, "не создать MP4", e); return@use false }

            var ok = false
            try {
                val vFmt = MediaFormat.createVideoFormat(VIDEO_MIME, WIDTH, HEIGHT).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
                val vTrack = muxer.addTrack(vFmt)
                val aTrack = audio?.let { muxer.addTrack(it.format) } ?: -1

                muxer.start()

                val buf = ByteBuffer.allocate(frames.maxOf { it.size })
                val info = MediaCodec.BufferInfo()
                for ((n, f) in frames.withIndex()) {
                    val data = ByteArray(f.size)
                    file.seek(f.offset)
                    file.readFully(data)
                    buf.clear()
                    buf.put(data)
                    buf.position(0)
                    buf.limit(f.size)
                    // Первый кадр опорный по построению: vrcam начинает
                    // запись только с кадра, несущего наборы параметров.
                    info.set(0, f.size, f.timeUs - base,
                        if (f.key || n == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(vTrack, buf, info)
                }

                if (audio != null && aTrack >= 0) {
                    for (s in audio.samples) {
                        muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), s.info)
                    }
                }
                muxer.stop()
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "сборка MP4 не удалась", e)
            } finally {
                try { muxer.release() } catch (_: Exception) {}
            }
            ok
        }
    }

    /** Только звук: тот же кодировщик, но дорожка одна. */
    fun audioOnly(pcm: File, out: File): Boolean {
        if (!pcm.exists() || pcm.length() == 0L) return false
        val audio = try { AacEncoder.encodeAll(pcm, 0) } catch (e: Exception) {
            Log.e(TAG, "звук не сжался", e); return false
        }
        val muxer = try { MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
        catch (e: Exception) { Log.e(TAG, "не создать m4a", e); return false }
        return try {
            val track = muxer.addTrack(audio.format)
            muxer.start()
            for (s in audio.samples) {
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), s.info)
            }
            muxer.stop()
            true
        } catch (e: Exception) {
            Log.e(TAG, "не собрать m4a", e)
            false
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Сжатие PCM в AAC целиком, в память.
     *
     * Кодировщик отдаёт формат дорожки только вместе с первыми выходными
     * данными, а объявить дорожку мультиплексору надо до начала записи.
     * Поэтому сжимаем всё заранее и лишь потом создаём файл: иначе
     * приходилось бы гадать, успел ли кодировщик, и при неудаче на выходе
     * оставался пустой MP4.
     */
    private class AacEncoder private constructor(
        val format: MediaFormat,
        val samples: List<Sample>
    ) {
        class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)

        companion object {
            /**
             * @param skipUs сколько микросекунд отбросить с начала, чтобы
             *        звук совпал с первым кадром видео. Отрицательное
             *        значение означает, что звук начался позже картинки, и
             *        его надо сдвинуть вперёд.
             */
            fun encodeAll(pcm: File, skipUs: Long): AacEncoder {
                val sampleRate = AudioRecorder.SAMPLE_RATE
                val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
                val fmt = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                val out = ArrayList<Sample>()
                var outFormat: MediaFormat? = null
                val info = MediaCodec.BufferInfo()

                // Два байта на отсчёт: PCM 16 бит моно.
                val skipBytes = if (skipUs > 0)
                    ((skipUs * sampleRate / 1_000_000L) * 2) else 0L
                val shiftUs = if (skipUs < 0) -skipUs else 0L

                try {
                    pcm.inputStream().use { ins ->
                        if (skipBytes > 0) ins.skip(skipBytes)
                        val chunk = ByteArray(2048)
                        var written = 0L
                        var eosSent = false
                        var eosSeen = false

                        while (!eosSeen) {
                            if (!eosSent) {
                                val i = codec.dequeueInputBuffer(10_000)
                                if (i >= 0) {
                                    val n = ins.read(chunk)
                                    val ptsUs = shiftUs + (written / 2) * 1_000_000L / sampleRate
                                    if (n <= 0) {
                                        codec.queueInputBuffer(i, 0, 0, ptsUs,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        eosSent = true
                                    } else {
                                        val buf = codec.getInputBuffer(i)!!
                                        buf.clear()
                                        buf.put(chunk, 0, n)
                                        codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                                        written += n
                                    }
                                }
                            }
                            val o = codec.dequeueOutputBuffer(info, 10_000)
                            when {
                                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                    outFormat = codec.outputFormat
                                o >= 0 -> {
                                    val buf = codec.getOutputBuffer(o)
                                    if (buf != null && info.size > 0 &&
                                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                    ) {
                                        val data = ByteArray(info.size)
                                        buf.position(info.offset)
                                        buf.get(data)
                                        val copy = MediaCodec.BufferInfo()
                                        copy.set(0, info.size, info.presentationTimeUs, info.flags)
                                        out.add(Sample(data, copy))
                                    }
                                    codec.releaseOutputBuffer(o, false)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        eosSeen = true
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                }

                val f = outFormat
                    ?: throw IllegalStateException("кодировщик не отдал формат дорожки")
                if (out.isEmpty()) throw IllegalStateException("кодировщик не дал ни одного пакета")
                return AacEncoder(f, out)
            }
        }
    }
}
