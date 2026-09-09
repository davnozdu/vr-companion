package com.davnozdu.vrcompanion

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка в шторке: одно касание переключает очки между гарнитурой и монитором.
 *
 * Ради этого приложение и писалось — лезть в терминал перед каждым фильмом
 * неудобно.
 */
class ModeTileService : TileService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        // Область пересоздаём: между показами шторки прежняя уже отменена.
        if (!scope.isActive()) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refresh()
    }

    override fun onStopListening() {
        super.onStopListening()
        scope.cancel()
    }

    override fun onClick() {
        super.onClick()
        // Пока идёт переключение, плитка неактивна: повторное касание
        // запустило бы вторую команду поверх первой.
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            label = getString(R.string.tile_switching)
            updateTile()
        }
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                val now = VrMode.current()
                val next = if (now == VrMode.Mode.HEADSET) VrMode.Mode.MONITOR
                           else VrMode.Mode.HEADSET
                VrMode.set(next)
                VrMode.current()
            }
            apply(target)
        }
    }

    private fun refresh() {
        scope.launch {
            val mode = withContext(Dispatchers.IO) {
                if (VrMode.moduleInstalled()) VrMode.current() else null
            }
            if (mode == null) {
                qsTile?.apply {
                    state = Tile.STATE_UNAVAILABLE
                    label = getString(R.string.tile_no_module)
                    updateTile()
                }
            } else apply(mode)
        }
    }

    private fun apply(mode: VrMode.Mode) {
        val tile = qsTile ?: return
        when (mode) {
            VrMode.Mode.HEADSET -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_headset)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_headset)
            }
            VrMode.Mode.MONITOR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_monitor)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_monitor)
            }
            VrMode.Mode.UNKNOWN -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.tile_unknown)
            }
        }
        tile.updateTile()
    }

    private fun CoroutineScope.isActive(): Boolean =
        coroutineContext[kotlinx.coroutines.Job]?.isActive == true
}
