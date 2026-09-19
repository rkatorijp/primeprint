package com.sukoon.autoprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.sukoon.autoprint.databinding.ActivityMainBinding
import com.sukoon.autoprint.databinding.DialogPrinterBinding
import com.sukoon.autoprint.databinding.DialogTriggerBinding
import com.sukoon.autoprint.databinding.ItemPrinterBinding
import com.sukoon.autoprint.databinding.ItemTriggerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val notifPermissionRequestCode = 1001
    private val triggers = mutableListOf<Trigger>()
    private val printers = mutableListOf<Printer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        renderTriggers()
        renderPrinters()

        binding.btnAddTrigger.setOnClickListener { editTrigger(null) }
        binding.btnAddPrinter.setOnClickListener { editPrinter(null) }
        binding.btnSave.setOnClickListener { saveSettings(); toast("設定を保存しました") }
        binding.btnStart.setOnClickListener { startMonitoring() }
        binding.btnStop.setOnClickListener { stopMonitoring() }
        binding.btnTestPrint.setOnClickListener { testPrint() }
        binding.btnDiagnose.setOnClickListener { runDiagnose() }

        updateStatusLabel()
    }

    // ── settings ──────────────────────────────────────────────────────────

    private fun loadSettings() {
        val prefs = Prefs.get(this)
        binding.editGmailAddress.setText(prefs.getString(Prefs.KEY_GMAIL_ADDRESS, ""))
        binding.editAppPassword.setText(prefs.getString(Prefs.KEY_APP_PASSWORD, ""))
        binding.switchBodyOnly.isChecked = Prefs.bodyOnly(this)
        if (Prefs.columns(this) == 32) binding.paper58.isChecked = true else binding.paper80.isChecked = true
        if (Prefs.charset(this).uppercase().contains("UTF")) binding.charUtf8.isChecked = true else binding.charSjis.isChecked = true
        when (Prefs.intervalSec(this)) {
            5 -> binding.interval5.isChecked = true
            30 -> binding.interval30.isChecked = true
            else -> binding.interval10.isChecked = true
        }
        triggers.clear()
        triggers.addAll(Prefs.loadTriggers(this))
        printers.clear()
        printers.addAll(Prefs.loadPrinters(this))
    }

    private fun selectedInterval(): Int = when (binding.groupInterval.checkedRadioButtonId) {
        R.id.interval5 -> 5
        R.id.interval30 -> 30
        else -> 10
    }

    private fun saveSettings() {
        Prefs.get(this).edit()
            .putString(Prefs.KEY_GMAIL_ADDRESS, binding.editGmailAddress.text.toString().trim())
            .putString(Prefs.KEY_APP_PASSWORD, binding.editAppPassword.text.toString().trim())
            .putInt(Prefs.KEY_INTERVAL_SEC, selectedInterval())
            .putBoolean(Prefs.KEY_BODY_ONLY, binding.switchBodyOnly.isChecked)
            .putInt(Prefs.KEY_COLUMNS, if (binding.paper58.isChecked) 32 else 48)
            .putString(Prefs.KEY_CHARSET, if (binding.charUtf8.isChecked) "UTF-8" else "MS932")
            .apply()
        Prefs.saveTriggers(this, triggers)
        Prefs.savePrinters(this, printers)
    }

    // ── triggers ──────────────────────────────────────────────────────────

    private fun renderTriggers() {
        binding.triggerContainer.removeAllViews()
        if (triggers.isEmpty()) {
            val row = ItemTriggerBinding.inflate(layoutInflater, binding.triggerContainer, false)
            row.textTriggerName.text = "トリガーがありません"
            row.textTriggerRule.text = "「＋ 追加」で、印刷したいメールの条件を登録します"
            row.switchTrigger.visibility = android.view.View.GONE
            row.rowBody.setOnClickListener { editTrigger(null) }
            binding.triggerContainer.addView(row.root)
            return
        }
        for (trigger in triggers.toList()) {
            val row = ItemTriggerBinding.inflate(layoutInflater, binding.triggerContainer, false)
            row.textTriggerName.text = if (trigger.name.isBlank()) "(名前なし)" else trigger.name
            row.textTriggerRule.text = trigger.describe()
            row.switchTrigger.isChecked = trigger.enabled
            row.switchTrigger.setOnCheckedChangeListener { _, checked ->
                trigger.enabled = checked
                Prefs.saveTriggers(this, triggers)
                toast(if (checked) "「${trigger.name}」をオンにしました" else "「${trigger.name}」をオフにしました")
            }
            row.rowBody.setOnClickListener { editTrigger(trigger) }
            binding.triggerContainer.addView(row.root)
        }
    }

    /** null = add a new trigger. */
    private fun editTrigger(existing: Trigger?) {
        val dlg = DialogTriggerBinding.inflate(layoutInflater)
        dlg.editName.setText(existing?.name ?: "")
        dlg.editKeyword.setText(existing?.keyword ?: "")
        dlg.editSender.setText(existing?.sender ?: "")
        dlg.editMaxLines.setText(if ((existing?.maxLines ?: 0) > 0) existing?.maxLines.toString() else "")
        dlg.switchEnabled.isChecked = existing?.enabled ?: true

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "トリガーを追加" else "トリガーを編集")
            .setView(dlg.root)
            .setPositiveButton("保存") { _, _ ->
                val keyword = dlg.editKeyword.text.toString().trim()
                val sender = dlg.editSender.text.toString().trim()
                if (keyword.isBlank() && sender.isBlank()) {
                    toast("キーワードか送信元のどちらかは必要です")
                    return@setPositiveButton
                }
                val name = dlg.editName.text.toString().trim().ifBlank { keyword.ifBlank { sender } }
                val maxLines = dlg.editMaxLines.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (existing == null) {
                    triggers.add(Trigger(name = name, keyword = keyword, sender = sender, enabled = dlg.switchEnabled.isChecked, maxLines = maxLines))
                } else {
                    existing.name = name
                    existing.keyword = keyword
                    existing.sender = sender
                    existing.enabled = dlg.switchEnabled.isChecked
                    existing.maxLines = maxLines
                }
                Prefs.saveTriggers(this, triggers)
                renderTriggers()
            }
            .setNegativeButton("やめる", null)

        if (existing != null) {
            builder.setNeutralButton("削除") { _, _ ->
                triggers.remove(existing)
                Prefs.saveTriggers(this, triggers)
                renderTriggers()
            }
        }
        builder.show()
    }

    // ── printers ──────────────────────────────────────────────────────────

    private fun renderPrinters() {
        binding.printerContainer.removeAllViews()
        if (printers.isEmpty()) {
            val row = ItemPrinterBinding.inflate(layoutInflater, binding.printerContainer, false)
            row.textPrinterName.text = "プリンターがありません"
            row.textPrinterRule.text = "「＋ 追加」でプリンターのIPアドレスを登録します"
            row.switchPrinterItem.visibility = android.view.View.GONE
            row.rowPrinterBody.setOnClickListener { editPrinter(null) }
            binding.printerContainer.addView(row.root)
            return
        }
        for (printer in printers.toList()) {
            val row = ItemPrinterBinding.inflate(layoutInflater, binding.printerContainer, false)
            row.textPrinterName.text = if (printer.name.isBlank()) "(名前なし)" else printer.name
            row.textPrinterRule.text = printer.describe()
            row.switchPrinterItem.isChecked = printer.enabled
            row.switchPrinterItem.setOnCheckedChangeListener { _, checked ->
                printer.enabled = checked
                Prefs.savePrinters(this, printers)
                toast(if (checked) "「${printer.name}」をオンにしました" else "「${printer.name}」をオフにしました")
            }
            row.rowPrinterBody.setOnClickListener { editPrinter(printer) }
            binding.printerContainer.addView(row.root)
        }
    }

    /** null = add a new printer. */
    private fun editPrinter(existing: Printer?) {
        val dlg = DialogPrinterBinding.inflate(layoutInflater)
        dlg.editPrinterName.setText(existing?.name ?: "")
        dlg.editPrinterIp.setText(existing?.ip ?: "")
        dlg.editPrinterPort.setText((existing?.port ?: 9100).toString())
        dlg.switchPrinterEnabled.isChecked = existing?.enabled ?: true

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "プリンターを追加" else "プリンターを編集")
            .setView(dlg.root)
            .setPositiveButton("保存") { _, _ ->
                val ip = dlg.editPrinterIp.text.toString().trim()
                if (ip.isBlank()) {
                    toast("プリンターIPアドレスは必須です")
                    return@setPositiveButton
                }
                val port = dlg.editPrinterPort.text.toString().trim().toIntOrNull() ?: 9100
                val name = dlg.editPrinterName.text.toString().trim().ifBlank { "プリンター${printers.size + 1}" }
                if (existing == null) {
                    printers.add(Printer(name = name, ip = ip, port = port, enabled = dlg.switchPrinterEnabled.isChecked))
                } else {
                    existing.name = name
                    existing.ip = ip
                    existing.port = port
                    existing.enabled = dlg.switchPrinterEnabled.isChecked
                }
                Prefs.savePrinters(this, printers)
                renderPrinters()
            }
            .setNegativeButton("やめる", null)

        if (existing != null) {
            builder.setNeutralButton("削除") { _, _ ->
                printers.remove(existing)
                Prefs.savePrinters(this, printers)
                renderPrinters()
            }
        }
        builder.show()
    }

    // ── monitoring ────────────────────────────────────────────────────────

    private fun startMonitoring() {
        saveSettings()
        val prefs = Prefs.get(this)
        if (prefs.getString(Prefs.KEY_GMAIL_ADDRESS, "").isNullOrBlank() ||
            prefs.getString(Prefs.KEY_APP_PASSWORD, "").isNullOrBlank()
        ) {
            toast("Gmailアドレスとアプリパスワードを入力してください")
            return
        }
        if (triggers.none { it.enabled && it.isUsable }) {
            toast("有効なトリガーが1件もありません")
            return
        }
        if (printers.none { it.enabled && it.isUsable }) {
            toast("有効なプリンターが1台もありません")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifPermissionRequestCode)
        }
        prefs.edit().putBoolean(Prefs.KEY_SERVICE_ENABLED, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, EmailCheckService::class.java))
        updateStatusLabel()
        toast("監視を開始しました")
    }

    private fun stopMonitoring() {
        Prefs.get(this).edit().putBoolean(Prefs.KEY_SERVICE_ENABLED, false).apply()
        stopService(Intent(this, EmailCheckService::class.java))
        updateStatusLabel()
        toast("監視を停止しました")
    }

    private fun testPrint() {
        val targets = printers.filter { it.enabled && it.isUsable }
        if (targets.isEmpty()) {
            toast("有効なプリンターがありません（IPを入力し、スイッチをオンにしてください）")
            return
        }
        saveSettings()
        val charset = Prefs.charset(this)
        val columns = Prefs.columns(this)
        CoroutineScope(Dispatchers.Main).launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { printer ->
                    printer.name to PrinterHelper.printText(printer.ip, printer.port, PrinterHelper.wrap(PrinterHelper.testPattern(), columns), charset)
                }
            }
            val okCount = results.count { it.second }
            val summary = if (okCount == results.size) {
                "文字テストを${okCount}台に送信しました（化けていたら文字コードを切り替えて再印刷）"
            } else {
                val failed = results.filter { !it.second }.joinToString("、") { it.first }
                "${okCount}/${results.size}台に送信しました（送れなかった台: $failed）"
            }
            toast(summary)
        }
    }

    /** Runs the same IMAP code the service uses and prints the report on screen. */
    private fun runDiagnose() {
        saveSettings()
        binding.textDiag.text = "確認中…"
        binding.btnDiagnose.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    MailChecker.diagnose(this@MainActivity)
                } catch (e: Exception) {
                    "NG 予期しないエラー: ${e.message}"
                }
            }
            binding.textDiag.text = report
            binding.btnDiagnose.isEnabled = true
        }
    }

    private fun updateStatusLabel() {
        val enabled = Prefs.get(this).getBoolean(Prefs.KEY_SERVICE_ENABLED, false)
        val count = triggers.count { it.enabled && it.isUsable }
        val printerCount = printers.count { it.enabled && it.isUsable }
        binding.textStatus.text = if (enabled) "状態: 監視中 · トリガー${count}件 · プリンター${printerCount}台" else "状態: 停止中"
        val dotColor = if (enabled) "#43A047" else "#E53935" // green = monitoring, red = stopped
        ImageViewCompat.setImageTintList(binding.statusDot, ColorStateList.valueOf(android.graphics.Color.parseColor(dotColor)))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
