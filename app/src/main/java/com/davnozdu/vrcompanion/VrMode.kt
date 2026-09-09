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

    data class Status(
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
        if (!moduleInstalled()) return Status(moduleInstalled = false)
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
        )
    }

    /** Список своих очков — как его видит модуль. */
    fun devices(): List<String> =
        RootShell.out("cat $DEVICES_FILE 2>/dev/null")
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** Что воткнуто в USB прямо сейчас: пары VID:PID и название. */
    fun connectedUsb(): List<String> =
        RootShell.out(
            "for d in /sys/bus/usb/devices/*/; do " +
            "[ -f \"\$d/idVendor\" ] && " +
            "echo \"\$(cat \$d/idVendor):\$(cat \$d/idProduct) \$(cat \$d/product 2>/dev/null)\"; done"
        ).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * Замена списка устройств. Пишем через su в файл модуля: приложение
     * не имеет прав на /data/adb, а держать вторую копию списка у себя
     * означало бы разъезжание настроек.
     */
    fun saveDevices(lines: List<String>): Boolean {
        val body = lines.joinToString("\n") { it.trim() }.replace("'", "")
        return RootShell.ok("printf '%s\\n' '$body' > $DEVICES_FILE")
    }
}
