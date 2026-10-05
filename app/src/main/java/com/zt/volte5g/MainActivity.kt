package com.zt.volte5g

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zt.volte5g.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuStatus() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateShizukuStatus() }
    }
    private val permResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateShizukuStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        requestReadPhoneStateIfNeeded()
        loadSwitches()
        bindListeners()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permResultListener)

        updateShizukuStatus()
        updateStatusInfo()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        updateStatusInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permResultListener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_PHONE_STATE) updateStatusInfo()
    }

    private fun requestReadPhoneStateIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_READ_PHONE_STATE
            )
        }
    }

    private fun loadSwitches() {
        binding.swVolte.isChecked = prefs.getBoolean(Prefs.VOLTE, true)
        binding.swVonr.isChecked = prefs.getBoolean(Prefs.VONR, true)
        binding.sw5gNr.isChecked = prefs.getBoolean(Prefs.NR5G, true)
        binding.swVowifi.isChecked = prefs.getBoolean(Prefs.VOWIFI, true)
        binding.swVt.isChecked = prefs.getBoolean(Prefs.VT, true)
        binding.swCrossSim.isChecked = prefs.getBoolean(Prefs.CROSS_SIM, true)
        binding.swUt.isChecked = prefs.getBoolean(Prefs.UT, true)
    }

    private fun bindListeners() {
        listOf(
            binding.swVolte to Prefs.VOLTE,
            binding.swVonr to Prefs.VONR,
            binding.sw5gNr to Prefs.NR5G,
            binding.swVowifi to Prefs.VOWIFI,
            binding.swVt to Prefs.VT,
            binding.swCrossSim to Prefs.CROSS_SIM,
            binding.swUt to Prefs.UT,
        ).forEach { (switch, key) ->
            switch.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }

        binding.btnApply.setOnClickListener { applyConfiguration() }
        binding.btnSelectSim.setOnClickListener { showSimSelection() }
        binding.btnOpenShizuku.setOnClickListener { openShizukuApp() }
        binding.btnNrMode.setOnClickListener { showNrModeSelection() }
        updateNrModeLabel()
    }

    private fun updateNrModeLabel() {
        binding.btnNrMode.text = when (prefs.getInt(Prefs.NR_MODE, Prefs.NR_MODE_BOTH)) {
            Prefs.NR_MODE_SA -> getString(R.string.nr_mode_sa)
            Prefs.NR_MODE_NSA -> getString(R.string.nr_mode_nsa)
            else -> getString(R.string.nr_mode_both)
        }
    }

    private fun showNrModeSelection() {
        val items = arrayOf(
            getString(R.string.nr_mode_both),
            getString(R.string.nr_mode_sa),
            getString(R.string.nr_mode_nsa)
        )
        val checked = when (prefs.getInt(Prefs.NR_MODE, Prefs.NR_MODE_BOTH)) {
            Prefs.NR_MODE_SA -> 1
            Prefs.NR_MODE_NSA -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nr_mode_dialog_title)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                prefs.edit().putInt(
                    Prefs.NR_MODE,
                    when (which) {
                        1 -> Prefs.NR_MODE_SA
                        2 -> Prefs.NR_MODE_NSA
                        else -> Prefs.NR_MODE_BOTH
                    }
                ).apply()
                updateNrModeLabel()
                dialog.dismiss()
            }
            .setMessage(getString(R.string.nr_mode_sa_hint))
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shizukuAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

    private fun updateShizukuStatus() {
        val (textRes, color, ready) = when {
            !shizukuAlive() -> Triple(R.string.shizuku_not_running, 0xFFF44336.toInt(), false)
            !shizukuGranted() -> Triple(R.string.shizuku_no_permission, 0xFFFF9800.toInt(), false)
            else -> Triple(R.string.shizuku_ready, 0xFF4CAF50.toInt(), true)
        }
        binding.tvShizukuStatus.text = getString(R.string.shizuku_status_fmt, getString(textRes))
        binding.tvShizukuStatus.setTextColor(color)
        binding.btnApply.isEnabled = ready

        if (shizukuAlive() && !shizukuGranted()) requestShizukuPermission()
    }

    private fun requestShizukuPermission() {
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            Toast.makeText(this, R.string.update_shizuku, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { Shizuku.requestPermission(REQ_SHIZUKU) }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.shizuku_not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatusInfo() {
        val persistent = runCatching { ShizukuProvider.canPersistent(this) }.getOrDefault(false)
        binding.tvPersistentMode.text = getString(
            R.string.persistent_mode_fmt,
            getString(if (persistent) R.string.yes else R.string.no)
        )
        binding.tvPersistentMode.setTextColor(if (persistent) COLOR_OK else COLOR_WARN)

        val applied = !runCatching { ShizukuProvider.needOverride(this) }.getOrDefault(true)
        binding.tvConfigStatus.text = getString(
            R.string.config_status_fmt,
            getString(if (applied) R.string.applied else R.string.not_applied)
        )
        binding.tvConfigStatus.setTextColor(if (applied) COLOR_OK else COLOR_IDLE)
    }

    private fun applyConfiguration() {
        if (!shizukuAlive()) {
            Toast.makeText(this, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
            return
        }
        if (!shizukuGranted()) {
            requestShizukuPermission()
            Toast.makeText(this, R.string.shizuku_no_permission_msg, Toast.LENGTH_LONG).show()
            return
        }

        ShizukuProvider.applyNow(this)
        Toast.makeText(this, R.string.apply_started, Toast.LENGTH_SHORT).show()

        // Instrumentation 会让进程短暂切到后台，3 秒后拉回界面并刷新状态
        Thread {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
            }
            runOnUiThread {
                updateStatusInfo()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.apply_success_title)
                    .setMessage(R.string.apply_success_msg)
                    .setPositiveButton(R.string.goto_network_settings) { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                    }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }.start()
    }

    private fun showSimSelection() {
        val items = arrayOf(
            getString(R.string.sim_1),
            getString(R.string.sim_2),
            getString(R.string.all_sims)
        )
        val checked = when (prefs.getInt(Prefs.KEY_SELECTED_SUB, -1)) {
            1 -> 0
            2 -> 1
            else -> 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.select_sim)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                prefs.edit().putInt(
                    Prefs.KEY_SELECTED_SUB,
                    when (which) {
                        0 -> 1
                        1 -> 2
                        else -> -1
                    }
                ).apply()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_READ_PHONE_STATE = 2001
        private const val COLOR_OK = 0xFF4CAF50.toInt()
        private const val COLOR_WARN = 0xFFF57C00.toInt()
        private const val COLOR_IDLE = 0xFF9E9E9E.toInt()
    }
}
