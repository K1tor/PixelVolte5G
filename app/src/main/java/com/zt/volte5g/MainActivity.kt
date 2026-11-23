package com.zt.volte5g

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.zt.volte5g.databinding.ActivityMainBinding
import com.zt.volte5g.databinding.RowChoiceBinding
import com.zt.volte5g.databinding.RowSwitchBinding
import rikka.shizuku.Shizuku

/**
 * 主界面（v1.3）。
 *
 * 设计目标：像系统设置一样“一眼看清状态、一步完成操作”。
 *  - 状态胶囊 + 上次应用的真实回执（写入成功/失败、是否持久化、清理是否正常）
 *  - 设备卡片展示系统构建号与兼容判定，可直接确认是否已适配当前 OTA
 *  - 主操作固定在底部，长列表滚动时也始终可达
 *  - 高级功能折叠，减少首屏噪音
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var profile: DeviceProfile

    private lateinit var rowVolte: MaterialSwitch
    private lateinit var rowVonr: MaterialSwitch
    private lateinit var row5g: MaterialSwitch
    private lateinit var rowVowifi: MaterialSwitch
    private lateinit var rowVt: MaterialSwitch
    private lateinit var rowCrossSim: MaterialSwitch
    private lateinit var rowUt: MaterialSwitch

    private val handler = Handler(Looper.getMainLooper())
    private var applyStartedAt = 0L
    private var applying = false

    private lateinit var update: UpdateController
    private var pendingRelease: Updater.Release? = null
    private var checkingOnResume = false

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
        profile = DeviceSupport.detect(this)

        bindRows()
        requestReadPhoneStateIfNeeded()
        bindDeviceCard()
        bindAdvancedSection()
        bindUpdateCard(savedInstanceState)
        loadSwitches()
        bindListeners()
        updateSimRows()

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
        updateSimRows()
        // 若应用流程仍在等待回执，继续轮询
        if (applying) pollApplyResult()
        // 从“安装未知应用”授权页返回时，自动继续安装
        update.installIfPermitted()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        update.shutdown()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permResultListener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_PHONE_STATE) {
            updateStatusInfo()
            updateSimRows()
        }
    }

    // ---------------------------------------------------------------------
    // 绑定
    // ---------------------------------------------------------------------

    private fun bindRows() {
        val b = binding
        rowVolte = b.rowVolte.sw
        rowVonr = b.rowVonr.sw
        row5g = b.row5g.sw
        rowVowifi = b.rowVowifi.sw
        rowVt = b.rowVt.sw
        rowCrossSim = b.rowCrossSim.sw
        rowUt = b.rowUt.sw

        bindSwitchRow(b.rowVolte, R.string.volte, R.string.volte_desc)
        bindSwitchRow(b.rowVonr, R.string.vonr, R.string.vonr_desc)
        bindSwitchRow(b.row5g, R.string.nr_5g, R.string.nr_5g_desc)
        bindSwitchRow(b.rowVowifi, R.string.vowifi, R.string.vowifi_desc)
        bindSwitchRow(b.rowVt, R.string.vt, R.string.vt_desc)
        bindSwitchRow(b.rowCrossSim, R.string.cross_sim, R.string.cross_sim_desc)
        bindSwitchRow(b.rowUt, R.string.ut, R.string.ut_desc)

        // 整行可点击（点击文字也能切换），与系统设置一致
        bindSwitchRowClick(b.rowVolte)
        bindSwitchRowClick(b.rowVonr)
        bindSwitchRowClick(b.row5g)
        bindSwitchRowClick(b.rowVowifi)
        bindSwitchRowClick(b.rowVt)
        bindSwitchRowClick(b.rowCrossSim)
        bindSwitchRowClick(b.rowUt)

        bindChoiceRow(b.rowNrMode, R.string.nr_mode_label, null)
        bindChoiceRow(b.rowSim, R.string.btn_select_sim, null)
    }

    private fun bindSwitchRow(row: RowSwitchBinding, titleRes: Int, descRes: Int) {
        row.tvTitle.setText(titleRes)
        row.tvDesc.setText(descRes)
    }

    private fun bindSwitchRowClick(row: RowSwitchBinding) {
        row.root.setOnClickListener {
            if (row.sw.isEnabled) row.sw.isChecked = !row.sw.isChecked
        }
    }

    private fun bindChoiceRow(row: RowChoiceBinding, titleRes: Int, descRes: Int?) {
        row.tvTitle.setText(titleRes)
        if (descRes != null) row.tvDesc.setText(descRes) else row.tvDesc.isVisible = false
    }

    private fun bindAdvancedSection() {
        binding.headerAdvanced.setOnClickListener { toggleAdvanced() }
        val expanded = prefs.getBoolean(Prefs.UI_ADVANCED_OPEN, false)
        binding.groupAdvanced.isVisible = expanded
        binding.icAdvanced.rotation = if (expanded) 180f else 0f
    }

    private fun toggleAdvanced() {
        val open = !binding.groupAdvanced.isVisible
        binding.groupAdvanced.isVisible = open
        binding.icAdvanced.animate().rotation(if (open) 180f else 0f).setDuration(160).start()
        prefs.edit().putBoolean(Prefs.UI_ADVANCED_OPEN, open).apply()
    }

    // ---------------------------------------------------------------------
    // 应用内更新
    // ---------------------------------------------------------------------

    private fun bindUpdateCard(savedInstanceState: Bundle?) {
        update = UpdateController(this, object : UpdateController.Listener {
            override fun onUpdateState(state: UpdateController.State) = renderUpdateState(state)
        })

        binding.tvUpdateVersion.text =
            getString(R.string.update_current_fmt, BuildConfig.VERSION_NAME)

        binding.btnCheckUpdate.setOnClickListener { update.check() }
        binding.btnUpdateAction.setOnClickListener {
            val hasDownload = binding.btnUpdateAction.tag == TAG_ACTION_INSTALL
            when {
                hasDownload -> update.install()
                else -> pendingRelease?.let { showUpdateDialog(it) } ?: update.check()
            }
        }
        // 首次进入静默检查：有更新才提示，不打扰
        if (savedInstanceState == null) {
            checkingOnResume = true
            update.check(silent = true)
        }
    }

    private fun showUpdateDialog(release: Updater.Release) {
        pendingRelease = release
        val sizeText = if (release.size > 0) {
            " · " + getString(R.string.update_size_fmt, readableSize(release.size))
        } else ""
        val notes = MarkdownLite.toPlain(release.notes, maxLines = 26)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_dialog_title, release.tag))
            .setMessage(
                if (notes.isBlank()) getString(R.string.update_available_fmt, release.tag)
                else notes + "\n\n" + release.title + sizeText
            )
            .setPositiveButton(R.string.update_dialog_download) { _, _ -> update.download() }
            .setNeutralButton(R.string.update_dialog_open_release) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)))
                }
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .show()
    }

    private fun renderUpdateState(state: UpdateController.State) {
        val b = binding
        when (state) {
            is UpdateController.State.Idle -> {
                b.tvUpdateStatus.setText(R.string.update_idle)
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = true
                b.btnCheckUpdate.setText(R.string.btn_check_update)
                b.btnUpdateAction.isVisible = false
                b.tvUpdateNotes.isVisible = false
            }

            is UpdateController.State.Checking -> {
                b.tvUpdateStatus.setText(R.string.update_checking)
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = false
                b.btnUpdateAction.isVisible = false
                b.tvUpdateNotes.isVisible = false
            }

            is UpdateController.State.UpToDate -> {
                b.tvUpdateStatus.text = getString(R.string.update_up_to_date, state.version)
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = true
                b.btnCheckUpdate.setText(R.string.btn_check_update)
                b.btnUpdateAction.isVisible = false
                b.tvUpdateNotes.isVisible = false
            }

            is UpdateController.State.Available -> {
                pendingRelease = state.release
                b.tvUpdateStatus.text = getString(R.string.update_available_fmt, state.release.tag)
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = true
                b.btnUpdateAction.isVisible = true
                b.btnUpdateAction.tag = null
                b.btnUpdateAction.setText(R.string.btn_download_update)
                val notes = MarkdownLite.toPlain(state.release.notes, maxLines = 6)
                b.tvUpdateNotes.isVisible = notes.isNotBlank()
                b.tvUpdateNotes.text = notes
                // 静默检查发现新版本时，主动弹一次
                if (checkingOnResume) {
                    checkingOnResume = false
                    showUpdateDialog(state.release)
                }
            }

            is UpdateController.State.Downloading -> {
                b.tvUpdateStatus.text =
                    getString(R.string.update_downloading_fmt, state.release.tag, state.percent)
                b.progressDownload.isVisible = true
                b.progressDownload.isIndeterminate = false
                b.progressDownload.setProgressCompat(state.percent, true)
                b.btnCheckUpdate.isEnabled = false
                b.btnUpdateAction.isVisible = true
                b.btnUpdateAction.tag = null
                b.btnUpdateAction.setText(R.string.btn_cancel_download)
                b.btnUpdateAction.setOnClickListener { update.cancelDownload() }
            }

            is UpdateController.State.Downloaded -> {
                b.tvUpdateStatus.text = getString(R.string.update_downloaded_fmt, state.release.tag)
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = true
                b.btnUpdateAction.isVisible = true
                b.btnUpdateAction.tag = TAG_ACTION_INSTALL
                b.btnUpdateAction.setText(R.string.btn_install_update)
                // 恢复默认的点击行为（下载态被改成了“取消”）
                b.btnUpdateAction.setOnClickListener { update.install() }
            }

            is UpdateController.State.NeedsPermission -> {
                b.tvUpdateStatus.setText(R.string.update_need_permission)
                Toast.makeText(this, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                Updater.openInstallPermission(this)
            }

            is UpdateController.State.Failed -> {
                b.tvUpdateStatus.text = state.message
                b.progressDownload.isVisible = false
                b.btnCheckUpdate.isEnabled = true
                b.btnCheckUpdate.setText(R.string.btn_check_update)
                // 下载失败时可重试下载；检查失败时收起操作按钮
                val retryDownload = state.release != null && update.latest != null
                b.btnUpdateAction.isVisible = retryDownload
                if (retryDownload) {
                    b.btnUpdateAction.tag = null
                    b.btnUpdateAction.setText(R.string.btn_download_update)
                    b.btnUpdateAction.setOnClickListener { update.download() }
                }
            }
        }
    }

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------------------------------------------------------------------
    // 设备与系统
    // ---------------------------------------------------------------------

    private fun requestReadPhoneStateIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_READ_PHONE_STATE
            )
        }
    }

    private fun bindDeviceCard() {
        binding.tvDeviceName.text = if (profile.isPixel) {
            getString(R.string.device_name_fmt, profile.name, profile.device)
        } else {
            profile.name
        }
        binding.tvDeviceAndroid.text = getString(
            R.string.android_version_fmt, Build.VERSION.RELEASE, Build.VERSION.SDK_INT
        )

        val sys = SystemCompat.describe(this)
        binding.tvBuildId.text = getString(
            R.string.build_id_fmt,
            sys.buildId.ifBlank { getString(R.string.build_unknown) }
        )
        binding.tvSecurityPatch.text = getString(
            R.string.security_patch_fmt,
            sys.securityPatch.ifBlank { getString(R.string.unknown) }
        )
        binding.tvDelegate.text = getString(R.string.delegate_fmt, sys.delegateVariant.label)
        binding.tvCompatNote.text = sys.note

        val (compatTextRes, tone) = when (sys.tier) {
            SystemCompat.Tier.VERIFIED -> R.string.badge_compat_verified to Tone.OK
            SystemCompat.Tier.EXPECTED -> R.string.badge_compat_expected to Tone.WARN
            SystemCompat.Tier.LEGACY -> R.string.badge_compat_legacy to Tone.IDLE
        }
        binding.chipCompat.setText(compatTextRes)
        paintChip(binding.chipCompat, tone)

        binding.chipDeviceBadge.setText(
            if (profile.isPixel) R.string.badge_pixel else R.string.badge_not_pixel
        )
        paintChip(binding.chipDeviceBadge, Tone.IDLE)

        binding.chip5gBadge.setText(
            when {
                !profile.telephony -> R.string.badge_no_telephony
                !profile.has5G -> R.string.badge_no_5g
                profile.hasSa -> R.string.badge_5g_nsa_sa
                else -> R.string.badge_5g_nsa
            }
        )
        paintChip(binding.chip5gBadge, if (profile.has5G) Tone.OK else Tone.IDLE)

        val note = when {
            !profile.telephony -> R.string.unsupported_no_telephony
            profile.isPixel && !profile.has5G -> R.string.unsupported_no_5g
            !profile.isPixel -> R.string.untested_device
            else -> null
        }
        binding.tvDeviceNote.isVisible = note != null
        note?.let { binding.tvDeviceNote.setText(it) }

        // 无 5G 能力的机型禁用 5G / VoNR
        row5g.isEnabled = profile.has5G
        rowVonr.isEnabled = profile.hasVoNR
        binding.row5g.root.isEnabled = profile.has5G
        binding.rowVonr.root.isEnabled = profile.hasVoNR
        updateNrModeRowVisibility()
    }

    // ---------------------------------------------------------------------
    // 开关
    // ---------------------------------------------------------------------

    private fun loadSwitches() {
        rowVolte.isChecked = prefs.getBoolean(Prefs.VOLTE, true)
        rowVonr.isChecked = prefs.getBoolean(Prefs.VONR, true) && profile.hasVoNR
        row5g.isChecked = prefs.getBoolean(Prefs.NR5G, true) && profile.has5G
        rowVowifi.isChecked = prefs.getBoolean(Prefs.VOWIFI, true)
        rowVt.isChecked = prefs.getBoolean(Prefs.VT, true)
        rowCrossSim.isChecked = prefs.getBoolean(Prefs.CROSS_SIM, true)
        rowUt.isChecked = prefs.getBoolean(Prefs.UT, true)
        updateNrModeRowVisibility()
    }

    private fun bindListeners() {
        listOf(
            rowVolte to Prefs.VOLTE,
            rowVonr to Prefs.VONR,
            row5g to Prefs.NR5G,
            rowVowifi to Prefs.VOWIFI,
            rowVt to Prefs.VT,
            rowCrossSim to Prefs.CROSS_SIM,
            rowUt to Prefs.UT,
        ).forEach { (sw, key) ->
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (sw === row5g) updateNrModeRowVisibility()
            }
        }

        binding.btnApply.setOnClickListener { applyConfiguration(clear = false) }
        binding.rowSim.root.setOnClickListener { showSimSelection() }
        binding.rowNrMode.root.setOnClickListener { showNrModeSelection() }
        binding.btnOpenShizuku.setOnClickListener { openShizukuApp() }
        binding.btnRefresh.setOnClickListener {
            updateShizukuStatus()
            updateStatusInfo()
            updateSimRows()
            Toast.makeText(this, R.string.btn_refresh_status, Toast.LENGTH_SHORT).show()
        }
        binding.btnClear.setOnClickListener { confirmClear() }
    }

    private fun updateNrModeRowVisibility() {
        binding.rowNrMode.root.isVisible = profile.has5G && row5g.isChecked
    }

    private fun updateNrModeRowLabel() {
        binding.rowNrMode.tvValue.text = when (prefs.getInt(Prefs.NR_MODE, Prefs.NR_MODE_BOTH)) {
            Prefs.NR_MODE_SA -> getString(R.string.nr_mode_sa)
            Prefs.NR_MODE_NSA -> getString(R.string.nr_mode_nsa)
            else -> getString(R.string.nr_mode_both)
        }
    }

    // ---------------------------------------------------------------------
    // SIM 选择（系统设置同款双行单选列表：运营商名 + 卡槽标识，只列实际插卡的卡槽）
    // ---------------------------------------------------------------------

    private fun updateSimRows() {
        binding.rowSim.tvValue.text = currentSimLabel()
        binding.rowSim.tvDesc.text = getString(R.string.sim_target_fmt, currentSimLabel())
        updateNrModeRowLabel()
    }

    private fun currentSimLabel(): String = when (prefs.getInt(Prefs.KEY_SELECTED_SUB, -1)) {
        1 -> slotLabel(1, carrierNameForSlot(0))
        2 -> slotLabel(2, carrierNameForSlot(1))
        else -> getString(R.string.all_sims)
    }

    private fun slotLabel(slotNumber: Int, carrier: String?): String =
        if (carrier.isNullOrBlank()) {
            getString(R.string.sim_slot_plain_fmt, slotNumber)
        } else {
            getString(R.string.sim_slot_fmt, slotNumber, carrier)
        }

    /** 读取各卡槽（槽序号 0 起）的运营商名；无权限或无 SIM 时返回 null */
    private fun activeSimInfos(): List<Pair<Int, String?>>? = runCatching {
        val sm = getSystemService(SubscriptionManager::class.java) ?: return null
        sm.activeSubscriptionInfoList?.map { info ->
            info.simSlotIndex to
                (info.displayName ?: info.carrierName)?.toString()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun carrierNameForSlot(slotIndex: Int): String? =
        activeSimInfos()?.firstOrNull { it.first == slotIndex }?.second

    private fun buildSimOptions(): List<ChoiceItem> {
        val infos = activeSimInfos()
        val options = mutableListOf<ChoiceItem>()
        val slot1 = infos?.firstOrNull { it.first == 0 }
        val slot2 = infos?.firstOrNull { it.first == 1 }
        if (infos == null || slot1 != null) {
            options += ChoiceItem(
                title = slot1?.second ?: getString(R.string.sim_slot_plain_fmt, 1),
                subtitle = if (slot1?.second != null) getString(R.string.sim_slot_plain_fmt, 1) else null,
                value = 1,
            )
        }
        if (infos == null || slot2 != null) {
            options += ChoiceItem(
                title = slot2?.second ?: getString(R.string.sim_slot_plain_fmt, 2),
                subtitle = if (slot2?.second != null) getString(R.string.sim_slot_plain_fmt, 2) else null,
                value = 2,
            )
        }
        options += ChoiceItem(
            title = getString(R.string.all_sims),
            subtitle = getString(R.string.all_sims_desc),
            value = -1,
        )
        return options
    }

    private fun showSimSelection() {
        val items = buildSimOptions()
        val current = prefs.getInt(Prefs.KEY_SELECTED_SUB, -1)
        val checked = items.indexOfFirst { it.value == current }.coerceAtLeast(0)
        val adapter = ChoiceAdapter(this, items, checked)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_sim)
            .setSingleChoiceItems(adapter, checked) { dialog, which ->
                prefs.edit().putInt(Prefs.KEY_SELECTED_SUB, items[which].value).apply()
                updateSimRows()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNrModeSelection() {
        val current = prefs.getInt(Prefs.NR_MODE, Prefs.NR_MODE_BOTH)
        val items = listOf(
            ChoiceItem(
                getString(R.string.nr_mode_both),
                getString(R.string.nr_mode_both_desc),
                Prefs.NR_MODE_BOTH,
            ),
            ChoiceItem(
                getString(R.string.nr_mode_sa),
                getString(R.string.nr_mode_sa_desc),
                Prefs.NR_MODE_SA,
            ),
            ChoiceItem(
                getString(R.string.nr_mode_nsa),
                getString(R.string.nr_mode_nsa_desc),
                Prefs.NR_MODE_NSA,
            ),
        )
        val checked = items.indexOfFirst { it.value == current }.coerceAtLeast(0)
        val adapter = ChoiceAdapter(this, items, checked)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nr_mode_dialog_title)
            .setSingleChoiceItems(adapter, checked) { dialog, which ->
                prefs.edit().putInt(Prefs.NR_MODE, items[which].value).apply()
                updateNrModeRowLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Shizuku 状态
    // ---------------------------------------------------------------------

    private fun shizukuAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

    private fun updateShizukuStatus() {
        val (textRes, tone, ready) = when {
            !shizukuAlive() -> Triple(R.string.shizuku_not_running, Tone.ERROR, false)
            !shizukuGranted() -> Triple(R.string.shizuku_no_permission, Tone.WARN, false)
            else -> Triple(R.string.shizuku_ready, Tone.OK, true)
        }
        binding.chipShizuku.text = getString(R.string.shizuku_status_fmt, getString(textRes))
        paintChip(binding.chipShizuku, tone)
        binding.btnApply.isEnabled = ready && profile.telephony && !applying

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
        binding.chipPersistent.text = getString(
            R.string.persistent_mode_fmt,
            getString(if (persistent) R.string.yes else R.string.no)
        )
        paintChip(binding.chipPersistent, if (persistent) Tone.OK else Tone.WARN)

        val applied = !runCatching { ShizukuProvider.needOverride(this) }.getOrDefault(true)
        binding.chipConfig.text = getString(
            R.string.config_status_fmt,
            getString(if (applied) R.string.applied else R.string.not_applied)
        )
        paintChip(binding.chipConfig, if (applied) Tone.OK else Tone.IDLE)

        updateLastApply()
    }

    /** 展示上一次应用的真实回执，而不是“点了就说成功” */
    private fun updateLastApply() {
        val at = prefs.getLong(Prefs.LAST_APPLY_AT, 0L)
        if (at <= 0L) {
            binding.tvLastApply.setText(R.string.last_apply_none)
            return
        }
        val whenText = DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
        val ok = prefs.getBoolean(Prefs.LAST_APPLY_OK, false)
        val cleared = prefs.getBoolean(Prefs.LAST_APPLY_CLEAR, false)
        val succeeded = prefs.getInt(Prefs.LAST_APPLY_SUCCEEDED, 0)
        val total = prefs.getInt(Prefs.LAST_APPLY_TOTAL, 0)
        val error = prefs.getString(Prefs.LAST_APPLY_ERROR, null)
        val cleanupOk = prefs.getBoolean(Prefs.LAST_CLEANUP_OK, true)

        val text = when {
            cleared -> getString(R.string.last_apply_cleared_fmt, whenText)
            ok -> getString(R.string.last_apply_ok_fmt, whenText, succeeded, total)
            else -> getString(R.string.last_apply_fail_fmt, error ?: getString(R.string.unknown))
        }
        binding.tvLastApply.text = if (ok && !cleanupOk) {
            text + "\n" + getString(R.string.cleanup_warn)
        } else {
            text
        }
    }

    // ---------------------------------------------------------------------
    // 应用 / 恢复
    // ---------------------------------------------------------------------

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_clear)
            .setMessage(R.string.btn_clear_desc)
            .setPositiveButton(R.string.btn_clear) { _, _ -> applyConfiguration(clear = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyConfiguration(clear: Boolean) {
        if (!profile.telephony) {
            Toast.makeText(this, R.string.unsupported_no_telephony, Toast.LENGTH_LONG).show()
            return
        }
        if (!shizukuAlive()) {
            Toast.makeText(this, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
            return
        }
        if (!shizukuGranted()) {
            requestShizukuPermission()
            Toast.makeText(this, R.string.shizuku_no_permission_msg, Toast.LENGTH_LONG).show()
            return
        }

        // 先清掉上一次回执，避免轮询读到旧结果
        prefs.edit()
            .putLong(Prefs.LAST_APPLY_AT, 0L)
            .putString(Prefs.LAST_APPLY_ERROR, null)
            .apply()

        if (clear) ShizukuProvider.clearNow(this) else ShizukuProvider.applyNow(this)

        applying = true
        applyStartedAt = System.currentTimeMillis()
        setApplyingUi(true)
        pollApplyResult()
    }

    private fun setApplyingUi(running: Boolean) {
        applying = running
        binding.progressApply.isVisible = running
        binding.tvApplyHint.isVisible = running
        binding.btnApply.isEnabled = !running && shizukuAlive() && shizukuGranted() && profile.telephony
        binding.btnApply.text = getString(if (running) R.string.applying else R.string.btn_apply)
        binding.btnClear.isEnabled = !running
    }

    /**
     * Instrumentation 会让进程短暂切到后台，结果由 PrivilegedProcess 写入 SharedPreferences。
     * 这里轮询等待真实回执（最多 12 秒），超时按“已提交但无回执”处理，不再谎报成功。
     */
    private fun pollApplyResult() {
        handler.removeCallbacksAndMessages(null)
        val startedAt = applyStartedAt
        handler.postDelayed(object : Runnable {
            override fun run() {
                val at = prefs.getLong(Prefs.LAST_APPLY_AT, 0L)
                val hasResult = at >= startedAt && startedAt > 0L
                val elapsed = System.currentTimeMillis() - startedAt
                if (hasResult) {
                    finishApply()
                } else if (elapsed < APPLY_TIMEOUT_MS) {
                    handler.postDelayed(this, 400)
                } else {
                    finishApply()
                }
            }
        }, 400)
    }

    private fun finishApply() {
        handler.removeCallbacksAndMessages(null)
        applying = false
        setApplyingUi(false)
        updateShizukuStatus()
        updateStatusInfo()
        updateSimRows()

        val at = prefs.getLong(Prefs.LAST_APPLY_AT, 0L)
        val hasResult = at >= applyStartedAt && applyStartedAt > 0L
        if (!hasResult) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_unknown_title)
                .setMessage(R.string.apply_unknown_msg)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val ok = prefs.getBoolean(Prefs.LAST_APPLY_OK, false)
        val cleared = prefs.getBoolean(Prefs.LAST_APPLY_CLEAR, false)
        val succeeded = prefs.getInt(Prefs.LAST_APPLY_SUCCEEDED, 0)
        val total = prefs.getInt(Prefs.LAST_APPLY_TOTAL, 0)
        val error = prefs.getString(Prefs.LAST_APPLY_ERROR, null)
        val persistent = prefs.getBoolean(Prefs.LAST_APPLY_PERSISTENT, false)
        val cleanupOk = prefs.getBoolean(Prefs.LAST_CLEANUP_OK, true)

        if (cleared && ok) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_cleared_title)
                .setMessage(R.string.apply_cleared_msg)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        if (ok) {
            val detail = getString(
                R.string.apply_result_detail,
                succeeded, total,
                getString(if (persistent) R.string.apply_persistent_yes else R.string.apply_persistent_no),
                if (cleanupOk) getString(R.string.ok) else getString(R.string.cleanup_warn)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_success_title)
                .setMessage(getString(R.string.apply_success_msg) + "\n\n" + detail)
                .setPositiveButton(R.string.goto_network_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
                .setNegativeButton(R.string.later, null)
                .show()
        } else if (succeeded > 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_partial_title)
                .setMessage(
                    getString(
                        R.string.apply_partial_msg,
                        succeeded, total, error ?: getString(R.string.unknown)
                    )
                )
                .setPositiveButton(R.string.goto_network_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
                .setNegativeButton(R.string.later, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_failed_title)
                .setMessage(getString(R.string.apply_failed_msg, error ?: getString(R.string.unknown)))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    // ---------------------------------------------------------------------
    // 状态胶囊着色
    // ---------------------------------------------------------------------

    private enum class Tone { OK, WARN, ERROR, IDLE }

    private fun paintChip(chip: com.google.android.material.chip.Chip, tone: Tone) {
        val (fg, bg) = when (tone) {
            Tone.OK -> R.color.status_ok to R.color.status_ok_bg
            Tone.WARN -> R.color.status_warn to R.color.status_warn_bg
            Tone.ERROR -> R.color.status_error to R.color.status_error_bg
            Tone.IDLE -> R.color.status_idle to R.color.status_idle_bg
        }
        chip.setTextColor(ContextCompat.getColor(this, fg))
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_READ_PHONE_STATE = 2001
        private const val APPLY_TIMEOUT_MS = 12_000L
        private const val TAG_ACTION_INSTALL = "action_install"
        private const val RELEASE_PAGE = "https://github.com/K1tor/PixelVolte5G/releases/latest"
    }
}
