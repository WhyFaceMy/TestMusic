package com.karaoke.lyrics

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Экран настройки: разрешения, переключатели и предпросмотр караоке. */
class MainActivity : ComponentActivity() {

    private lateinit var listenerStatus: TextView
    private lateinit var listenerButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notifyStatus: TextView
    private lateinit var notifyButton: Button
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi(): View {
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        text("Караоке-строка", 26f, bold = true)
        text("Показывает текст песни, которая играет в любом плеере, с подсветкой как в караоке — " +
            "в приложении, поверх других приложений и на заблокированном экране.", 14f, color = 0xB3FFFFFF.toInt())

        // Предпросмотр
        val preview = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(20), dp(12), dp(20))
            addView(KaraokePanel(this@MainActivity, compact = false))
        }
        col.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        button("Открыть на весь экран") { startActivity(Intent(this, LyricsActivity::class.java)) }

        header("1. Доступ к плеерам (обязательно)")
        text("Android показывает, что играет в других приложениях, только приложениям с доступом к уведомлениям. " +
            "Уведомления не читаются и никуда не отправляются.", 13f, color = 0x99FFFFFF.toInt())
        listenerStatus = text("", 15f)
        listenerButton = button("Выдать доступ к уведомлениям") { openListenerSettings() }

        header("2. Показ поверх экрана блокировки")
        text("Разрешение «Поверх других приложений» нужно, чтобы открыть караоке, когда телефон заблокирован, " +
            "и для плавающей строки.", 13f, color = 0x99FFFFFF.toInt())
        overlayStatus = text("", 15f)
        overlayButton = button("Разрешить показ поверх приложений") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifyStatus = text("", 15f)
        notifyButton = button("Разрешить уведомления") { requestNotifications() }

        header("Настройки")
        val s = Prefs.settings.value
        switch("Караоке на экране блокировки", s.lockScreen) { v -> Prefs.update { it.copy(lockScreen = v) } }
        switch("Плавающая строка поверх приложений", s.overlay) { v -> Prefs.update { it.copy(overlay = v) } }
        switch("Не гасить экран, пока играет музыка", s.keepScreenOn) { v -> Prefs.update { it.copy(keepScreenOn = v) } }
        switch("Закрывать караоке после разблокировки", s.closeOnUnlock) { v -> Prefs.update { it.copy(closeOnUnlock = v) } }

        val sizeLabel = text("Размер текста: ${s.textSizeSp}", 15f)
        col.addView(SeekBar(this).apply {
            max = 48 - 18
            progress = s.textSizeSp - 18
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    val sp = value + 18
                    sizeLabel.text = "Размер текста: $sp"
                    if (fromUser) Prefs.update { it.copy(textSizeSp = sp) }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        button("Сбросить сдвиг синхронизации") { Prefs.update { it.copy(syncOffsetMs = 0) } }
        button("Повторить поиск текста") { LyricsController.retry() }

        header("Если не работает на блокировке")
        text("• Xiaomi / Redmi / POCO: Настройки → Приложения → Караоке-строка → Другие разрешения → " +
            "включите «Экран блокировки» и «Отображать всплывающие окна в фоне».\n" +
            "• Включите автозапуск и отключите оптимизацию батареи для приложения (Samsung, Huawei, Honor, Xiaomi).\n" +
            "• Тексты берутся из открытой базы lrclib.net; для редких песен текста может не быть.",
            13f, color = 0x99FFFFFF.toInt())

        return scroll
    }

    private fun refreshStatus() {
        val listenerOk = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        listenerStatus.text = if (listenerOk) "✅ Доступ выдан" else "❌ Доступа нет — текст не появится"
        listenerButton.visibility = if (listenerOk) View.GONE else View.VISIBLE

        val overlayOk = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayOk) "✅ Показ поверх приложений разрешён" else "⚠️ Показ поверх приложений не разрешён"
        overlayButton.visibility = if (overlayOk) View.GONE else View.VISIBLE

        // Уведомления нужны только как запасной путь, если нет разрешения на наложение.
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val fullScreenOk = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val showNotify = !overlayOk && !(notifyOk && fullScreenOk)
        notifyStatus.visibility = if (showNotify) View.VISIBLE else View.GONE
        notifyButton.visibility = notifyStatus.visibility
        notifyStatus.text = "⚠️ Запасной способ: уведомления с показом на весь экран"
    }

    private fun openListenerSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                android.content.ComponentName(this, MediaListenerService::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (Build.VERSION.SDK_INT >= 34) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // --- простые конструкторы UI ---

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
            col.addView(this)
        }

    private fun header(value: String) {
        text(value, 18f, bold = true, color = 0xFFFFD54F.toInt()).apply { setPadding(0, dp(24), 0, dp(4)) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        col.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
        col.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
