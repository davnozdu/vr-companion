package com.davnozdu.vrcompanion

/**
 * Режим очков. Вся работа идёт через файлы модуля VR Headset Mode, а не
 * через собственную логику: единственным владельцем DisplayPort должен
 * оставаться модуль, иначе приложение и его демон дёргали бы один узел.
 */
object VrMode {

    const val MODE_FILE    = "/data/adb/vr_mode"
    const val DEVICES_FILE = "/data/adb/vr_headset/devices.conf"
    const val VR_MODE_CMD  = "/data/adb/bin/vr-mode"

    enum class Mode { HEADSET, MONITOR, UNKNOWN }

    /** Почему переключать нечем — причины разные и лечатся по-разному. */
    enum class Problem { NONE, NO_ROOT, NO_MODULE }

    data class Status(
        val problem: Problem = Problem.NONE,
        val mode: Mode = Mode.UNKNOWN,
        val glasses: String? = null,
        val externalDisplays: Int = 0,
        val audioPresent: Boolean = false,
        val daemonRunning: Boolean = false,
        val moduleInstalled: Boolean = false,
    )

    fun moduleInstalled(): Boolean =
        RootShell.ok("test -f $VR_MODE_CMD")

    fun current(): Mode = when (RootShell.out("cat $MODE_FILE 2>/dev/null").trim()) {
        "headset" -> Mode.HEADSET
        "monitor" -> Mode.MONITOR
        else      -> Mode.UNKNOWN
    }

    /**
     * Переключение через команду модуля, а не прямой записью в sysfs:
     * модуль сам решает, какие узлы DisplayPort трогать, и пишет это в свой
     * журнал. Дублировать его логику здесь — значит однажды разойтись с ней.
     */
    fun set(mode: Mode): Boolean {
        val arg = if (mode == Mode.HEADSET) "headset" else "monitor"
        return RootShell.ok("sh $VR_MODE_CMD $arg")
    }

    fun status(): Status {
        // Без root проверка наличия модуля всегда провалится, и отсутствие
        // прав выглядело бы как отсутствие модуля. Разделяем причины.
        if (!RootShell.isAvailable()) return Status(problem = Problem.NO_ROOT)
        if (!moduleInstalled()) return Status(problem = Problem.NO_MODULE)
        val ext = RootShell.out(
            "dumpsys display 2>/dev/null | grep -c 'DisplayViewport{type=EXTERNAL'"
        ).trim().toIntOrNull() ?: 0
        val glasses = RootShell.out("sh $VR_MODE_CMD status 2>/dev/null")
            .lineSequence()
            .firstOrNull { it.startsWith("очки:") }
            ?.removePrefix("очки:")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "не найдены" }
        return Status(
            mode = current(),
            glasses = glasses,
            externalDisplays = ext,
            audioPresent = RootShell.out("grep -c USB-Audio /proc/asound/cards")
                .trim().toIntOrNull()?.let { it > 0 } ?: false,
            daemonRunning = RootShell.ok("pgrep -f vrheadsetd.sh"),
            moduleInstalled = true,
            problem = Problem.NONE,
        )
    }

    /**
     * Список своих очков — как его видит модуль: "vid:pid" либо
     * "vid:pid имя", если имя сохранено в комментарии строки.
     */
    fun devices(): List<String> =
        RootShell.out("cat $DEVICES_FILE 2>/dev/null")
            .lineSequence()
            .mapNotNull { raw ->
                val id = raw.substringBefore('#').trim().substringBefore(' ').trim()
                if (id.isEmpty()) return@mapNotNull null
                val name = raw.substringAfter('#', "").trim()
                if (name.isEmpty()) id else "$id $name"
            }
            .toList()

    /** Что воткнуто в USB прямо сейчас: пары VID:PID и название. */
    fun connectedUsb(): List<String> =
        RootShell.out(
            "for d in /sys/bus/usb/devices/*/; do " +
            "[ -f \"\$d/idVendor\" ] && " +
            "echo \"\$(cat \$d/idVendor):\$(cat \$d/idProduct) \$(cat \$d/product 2>/dev/null)\"; done"
        ).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Пара VID:PID, как её пишет sysfs и ждёт модуль: четыре шестнадцатеричные цифры. */
    private val ID_RE = Regex("^[0-9a-f]{4}:[0-9a-f]{4}$")

    /**
     * Замена списка устройств. Пишем через su в файл модуля: приложение
     * не имеет прав на /data/adb, а держать вторую копию списка у себя
     * означало бы разъезжание настроек.
     *
     * Формат строки обязателен: сначала идентификатор, имя — только после '#'.
     * Модуль сравнивает с vid:pid первое слово строки, и имя, попавшее в неё
     * без комментария, делало очки неопознаваемыми: "3318:0436 XREAL One Pro"
     * не совпадало с "3318:0436" никогда, и режим гарнитуры молча умирал
     * после первого же сохранения списка из приложения.
     *
     * Строки, не похожие на идентификатор, отбрасываем: это заодно
     * единственный путь, которым в команду su могло бы попасть чужое.
     */
    fun saveDevices(lines: List<String>): Boolean {
        val body = lines.mapNotNull { entry ->
            val id = entry.substringBefore(' ').trim().lowercase()
            if (!ID_RE.matches(id)) return@mapNotNull null
            val name = entry.substringAfter(' ', "").lineSequence().first().trim()
            if (name.isEmpty()) id else "$id  # $name"
        }.joinToString("\n").replace("'", "")
        return RootShell.ok("printf '%s\\n' '$body' > $DEVICES_FILE")
    }
}
