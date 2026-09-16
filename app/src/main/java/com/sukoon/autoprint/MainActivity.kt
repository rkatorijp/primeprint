package com.sukoon.autoprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sukoon.autoprint.databinding.ActivityMainBinding
import com.sukoon.autoprint.databinding.DialogTriggerBinding
import com.sukoon.autoprint.databinding.ItemTriggerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val notifPermissionRequestCode = 1001
    private val triggers = mutableListOf<Trigger>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        renderTriggers()

        binding.btnAddTrigger.setOnClickListener { editTrigger(null) }
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
        binding.editPrinterIp.setText(prefs.getString(Prefs.KEY_PRINTER_IP, ""))
        binding.editPrinterPort.setText(prefs.getString(Prefs.KEY_PRINTER_PORT, "9100"))
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
            .putString(Prefs.KEY_PRINTER_IP, binding.editPrinterIp.text.toString().trim())
            .putString(Prefs.KEY_PRINTER_PORT, binding.editPrinterPort.text.toString().trim())
            .putInt(Prefs.KEY_INTERVAL_SEC, selectedInterval())
            .putBoolean(Prefs.KEY_BODY_ONLY, binding.switchBodyOnly.isChecked)
            .putInt(Prefs.KEY_COLUMNS, if (binding.paper58.isChecked) 32 else 48)
            .putString(Prefs.KEY_CHARSET, if (binding.charUtf8.isChecked) "UTF-8" else "MS932")
            .apply()
        Prefs.saveTriggers(this, triggers)
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
                if (existing == null) {
                    triggers.add(Trigger(name = name, keyword = keyword, sender = sender, enabled = dlg.switchEnabled.isChecked))
                } else {
                    existing.name = name
                    existing.keyword = keyword
                    existing.sender = sender
                    existing.enabled = dlg.switchEnabled.isChecked
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

    // ── monitoring ────────────────────────────────────────────────────────

    private fun startMonitoring() {
        saveSettings()
        val prefs = Prefs.get(this)
        if (prefs.getString(Prefs.KEY_GMAIL_ADDRESS, "").isNullOrBlank() ||
            prefs.getString(Prefs.KEY_APP_PASSWORD, "").isNullOrBlank() ||
            prefs.getString(Prefs.KEY_PRINTER_IP, "").isNullOrBlank()
        ) {
            toast("Gmailアドレス・アプリパスワード・プリンターIPを入力してください")
            return
        }
        if (triggers.none { it.enabled && it.isUsable }) {
            toast("有効なトリガーが1件もありません")
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
        val ip = binding.editPrinterIp.text.toString().trim()
        val port = binding.editPrinterPort.text.toString().trim().toIntOrNull() ?: 9100
        if (ip.isBlank()) {
            toast("プリンターIPを入力してください")
            return
        }
        saveSettings()
        val charset = Prefs.charset(this)
        val columns = Prefs.columns(this)
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                PrinterHelper.printText(ip, port, PrinterHelper.wrap(PrinterHelper.testPattern(), columns), charset)
            }
            toast(
                if (ok) "文字テストを送信しました（化けていたら文字コードを切り替えて再印刷）"
                else "印刷に失敗しました（IP/ポート/電源を確認）"
            )
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
        binding.textStatus.text = if (enabled) "状態: 監視中 · トリガー${count}件" else "状態: 停止中"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
