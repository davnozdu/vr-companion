package com.davnozdu.vrcompanion

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Один долгоживущий root-шелл на всё приложение.
 *
 * Раньше каждая команда порождала свой процесс `su`: это десятки-сотни
 * миллисекунд на спавн, отдельный проход через менеджер прав KernelSU/Magisk,
 * и — главное — ни stdout, ни stderr не вычитывались. При выводе крупнее
 * буфера pipe (~64 КБ) процесс блокировался на записи, а waitFor() вис навсегда.
 *
 * Здесь процесс один, stderr дренируется отдельным демон-потоком, а каждая
 * команда ограничена таймаутом: по истечении шелл убивается и пересоздаётся
 * при следующем вызове.
 */
object RootShell {

    data class Result(val exitCode: Int, val out: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private val FAILED = Result(-1, "")

    private const val TAG = "VRappRoot"

    private const val MARKER = "__VRAPP_DONE__"
    private const val DEFAULT_TIMEOUT_MS = 10_000L

    /** Первый вызов может ждать, пока пользователь нажмёт "разрешить" в KernelSU. */
    private const val HANDSHAKE_TIMEOUT_MS = 30_000L

    private val SU_CANDIDATES = listOf("/system/bin/su", "/sbin/su", "su")

    private val lock = Any()

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "root-shell-watchdog").apply { isDaemon = true }
    }

    @Volatile private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    /** null — ещё не проверяли. Сбрасывается в null при потере шелла. */
    @Volatile private var available: Boolean? = null

    // ── Публичный API ────────────────────────────────────────────────────────

    fun isAvailable(): Boolean = synchronized(lock) {
        available ?: (openLocked() != null).also { available = it }
    }

    /** Забыть результат проверки — например, после того как root выдали вручную. */
    fun forgetAvailability() = synchronized(lock) { available = null }

    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        synchronized(lock) { execLocked(command, timeoutMs) }

    /** Выполнить несколько команд в одном шелле, не отпуская его между ними. */
    fun execAll(commands: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<Result> =
        synchronized(lock) { commands.map { execLocked(it, timeoutMs) } }

    fun out(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        exec(command, timeoutMs).out

    fun ok(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        exec(command, timeoutMs).ok

    fun close() = synchronized(lock) { closeLocked() }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    private fun execLocked(command: String, timeoutMs: Long): Result {
        openLocked() ?: return FAILED
        val w = writer ?: return FAILED
        val r = reader ?: return FAILED

        // Сторож убивает процесс, если команда зависла: это разблокирует
        // readLine() ниже (вернёт null по EOF), вместо вечного ожидания.
        // Убиваем процесс напрямую, не через close(): lock здесь уже удерживается
        // текущим потоком, и попытка сторожа войти в synchronized была бы вечной.
        val doomed = process
        val kill = watchdog.schedule({ doomed?.destroyForcibly() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            w.write(command)
            w.write("\n")
            w.write("echo $MARKER \$?\n")
            w.flush()
            readUntilMarker(r)
        } catch (_: Exception) {
            closeLocked()
            FAILED
        } finally {
            kill.cancel(false)
        }
    }

    private fun readUntilMarker(r: BufferedReader): Result {
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine()
            if (line == null) {          // шелл умер или был убит по таймауту
                closeLocked()
                return FAILED
            }
            if (line.startsWith(MARKER)) {
                val code = line.removePrefix(MARKER).trim().toIntOrNull() ?: -1
                return Result(code, sb.toString().trim())
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
    }

    private fun openLocked(): Process? {
        process?.let { if (it.isAlive) return it }
        closeLocked()

        for (su in SU_CANDIDATES) {
            val p = try {
                ProcessBuilder(su).start()
            } catch (e: Exception) {
                Log.w(TAG, "spawn '$su' failed: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            Log.i(TAG, "spawned '$su', waiting for handshake")

            // stderr вычитывается и выбрасывается — иначе полный pipe остановит шелл
            Thread {
                try {
                    p.errorStream.bufferedReader().forEachLine { }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; name = "root-shell-stderr" }.start()

            val w = OutputStreamWriter(p.outputStream)
            val r = BufferedReader(InputStreamReader(p.inputStream))

            process = p
            writer = w
            reader = r

            val kill = watchdog.schedule({ p.destroyForcibly() }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val handshake = try {
                w.write("echo $MARKER 0\n")
                w.flush()
                readUntilMarker(r).ok
            } catch (_: Exception) {
                false
            } finally {
                kill.cancel(false)
            }

            if (handshake) {
                Log.i(TAG, "root shell ready via '$su'")
                available = true
                return p
            }
            Log.w(TAG, "handshake failed for '$su' (alive=${p.isAlive})")
            closeLocked()
        }
        Log.w(TAG, "no usable su found among $SU_CANDIDATES")
        available = false
        return null
    }

    private fun closeLocked() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        writer = null
        reader = null
        process = null
    }
}
