package com.davnozdu.vrcompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.davnozdu.vrcompanion.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.modeSwitch.setOnClickListener { toggleMode() }
        binding.devicesButton.setOnClickListener { showDevices() }
        binding.updateButton.setOnClickListener { checkUpdate(manual = true) }

        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Тихая проверка при каждом открытии: сообщаем только когда есть что.
        checkUpdate(manual = false)
    }

    /** targetSdk 35 рисует под системными панелями — возвращаем отступы. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { VrMode.status() }
            if (!st.moduleInstalled) {
                binding.statusText.text = getString(R.string.no_module)
                binding.statusSubtext.text = getString(R.string.no_module_hint)
                binding.modeSwitch.isEnabled = false
                binding.devicesButton.isEnabled = false
                return@launch
            }
            binding.modeSwitch.isEnabled = true
            binding.devicesButton.isEnabled = true

            binding.statusText.text = when (st.mode) {
                VrMode.Mode.HEADSET -> getString(R.string.mode_headset)
                VrMode.Mode.MONITOR -> getString(R.string.mode_monitor)
                VrMode.Mode.UNKNOWN -> getString(R.string.mode_unknown)
            }
            binding.modeSwitch.text = when (st.mode) {
                VrMode.Mode.HEADSET -> getString(R.string.switch_to_monitor)
                else                -> getString(R.string.switch_to_headset)
            }
            binding.statusSubtext.text = buildString {
                append(st.glasses ?: getString(R.string.glasses_none))
                append('\n')
                append(getString(
                    if (st.externalDisplays > 0) R.string.display_visible
                    else R.string.display_hidden
                ))
                append(" · ")
                append(getString(
                    if (st.audioPresent) R.string.audio_ok else R.string.audio_none
                ))
                if (!st.daemonRunning) {
                    append('\n')
                    append(getString(R.string.daemon_down))
                }
            }
        }
    }

    private fun toggleMode() {
        binding.modeSwitch.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val next = if (VrMode.current() == VrMode.Mode.HEADSET) VrMode.Mode.MONITOR
                           else VrMode.Mode.HEADSET
                VrMode.set(next)
            }
            refresh()
        }
    }

    private fun showDevices() {
        lifecycleScope.launch {
            val (saved, connected) = withContext(Dispatchers.IO) {
                VrMode.devices() to VrMode.connectedUsb()
            }
            val savedIds = saved.map { it.substringBefore(' ').trim() }.toSet()
            // Показываем и уже сохранённые, и воткнутые: снять галочку с
            // лежащей в столе гарнитуры иначе было бы нечем.
            val items = (saved + connected.filter {
                it.substringBefore(' ') !in savedIds
            }).distinctBy { it.substringBefore(' ') }

            if (items.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.devices_empty_title)
                    .setMessage(R.string.devices_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val labels = items.map { it }.toTypedArray()
            val checked = items.map { it.substringBefore(' ') in savedIds }.toBooleanArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.devices_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            VrMode.saveDevices(items.filterIndexed { i, _ -> checked[i] })
                        }
                        refresh()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun checkUpdate(manual: Boolean) {
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { UpdateChecker.latest() }
            if (rel == null) {
                if (manual) toast(getString(R.string.update_failed))
                return@launch
            }
            if (!UpdateChecker.isNewer(rel.version, BuildConfig.VERSION_NAME)) {
                if (manual) toast(getString(R.string.update_none))
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.update_title_fmt, rel.version))
                .setMessage(rel.notes?.take(400) ?: getString(R.string.update_available))
                .setPositiveButton(R.string.update_open) { _, _ ->
                    val url = rel.apkUrl ?: "https://github.com/davnozdu/vr-companion/releases/latest"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
}
