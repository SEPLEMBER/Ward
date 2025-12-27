package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile

/**
 * SettingsActivity — улучшенная версия:
 *  - выбор work-папки через SAF (ACTION_OPEN_DOCUMENT_TREE)
 *  - сохраняет persistable permission (read + write)
 *  - создаёт внутри work-папки базовые папки: "scripts" и "logs" (если отсутствуют)
 *  - сохраняет work_dir_uri и current_dir_uri в SharedPreferences("terminal_prefs")
 *
 * Добавлена опция "secure screenshots" — ставит/убирает FLAG_SECURE у окна приложения.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var workFolderUriView: TextView
    private lateinit var chooseFolderBtn: Button
    private lateinit var autoScrollSwitch: SwitchCompat
    private lateinit var aliasesField: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var secureScreenshotsSwitch: SwitchCompat

    private val PREFS_NAME = "terminal_prefs"
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        workFolderUriView = findViewById(R.id.workFolderUri)
        chooseFolderBtn = findViewById(R.id.chooseFolderBtn)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        aliasesField = findViewById(R.id.aliasesField)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)
        secureScreenshotsSwitch = findViewById(R.id.secureScreenshotsSwitch)

        // Launcher для выбора директории (SAF)
        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val treeUri: Uri? = result.data?.data
                if (treeUri != null) {
                    handlePickedTreeUri(treeUri)
                } else {
                    Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Обработчики кнопок
        chooseFolderBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        }

        saveButton.setOnClickListener {
            saveValues()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        resetButton.setOnClickListener {
            resetValues()
        }

        // Слушатель переключателя защиты от скриншотов — применяем и сохраняем сразу
        secureScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            applySecureFlag(isChecked)
            // сохраняем сразу, чтобы изменение действовало глобально даже если пользователь не нажмёт Save
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("secure_screenshots", isChecked)
                .apply()
        }

        // Загрузить текущие значения (и проверить права на work dir)
        loadValues()
    }

    // Применить/убрать FLAG_SECURE для текущего окна
    private fun applySecureFlag(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Обработка выбора папки — присваиваем persistable permission, сохраняем prefs, создаём поддиры
    private fun handlePickedTreeUri(treeUri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            prefs.putString("work_dir_uri", treeUri.toString())
            prefs.putString("current_dir_uri", treeUri.toString())
            prefs.apply()

            ensureWorkSubfolders(treeUri)

            workFolderUriView.text = buildFriendlyName(treeUri) ?: treeUri.toString()
            Toast.makeText(this, "Work folder set", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Failed to set work folder", Toast.LENGTH_SHORT).show()
        }
    }

    // Проверяем сохранённые prefs и отображаем friendly name или "(not set)"
    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString("work_dir_uri", null)
        if (uriStr != null) {
            val uri = try { Uri.parse(uriStr) } catch (e: Exception) { null }
            if (uri != null && hasPersistedPermission(uri)) {
                workFolderUriView.text = buildFriendlyName(uri) ?: uri.toString()
            } else {
                workFolderUriView.text = "(not set)"
            }
        } else {
            workFolderUriView.text = "(not set)"
        }

        autoScrollSwitch.isChecked = prefs.getBoolean("scroll_behavior", true)
        aliasesField.setText(prefs.getString("aliases", ""))

        // Загружаем состояние защиты от скриншотов и применяем флаг окна
        val secure = prefs.getBoolean("secure_screenshots", false)
        secureScreenshotsSwitch.isChecked = secure
        applySecureFlag(secure)
    }

    // Save other prefs (scroll, aliases, secure_screenshots)
    private fun saveValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putBoolean("scroll_behavior", autoScrollSwitch.isChecked)
        prefs.putString("aliases", aliasesField.text.toString().trim())
        prefs.putBoolean("secure_screenshots", secureScreenshotsSwitch.isChecked)
        prefs.apply()
    }

    // сброс всех настроек и work dir (не отзывает persistable permission)
    private fun resetValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove("work_dir_uri")
        prefs.remove("current_dir_uri")
        prefs.remove("aliases")
        prefs.remove("scroll_behavior")
        prefs.remove("secure_screenshots")
        prefs.apply()
        // Убираем флаг у текущего окна тоже
        applySecureFlag(false)
        loadValues()
        Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
    }

    // Проверка: есть ли persistable permission на URI
    private fun hasPersistedPermission(uri: Uri): Boolean {
        val perms = contentResolver.persistedUriPermissions
        for (p in perms) {
            if (p.uri == uri && p.isReadPermission && p.isWritePermission) return true
        }
        return false
    }

    // Пытаемся создать внутри выбранной work папки подпапки scripts и logs (если не существуют).
    private fun ensureWorkSubfolders(treeUri: Uri) {
        try {
            val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return
            val scripts = tree.findFile("scripts") ?: tree.createDirectory("scripts")
            val logs = tree.findFile("logs") ?: tree.createDirectory("logs")
            val meta = tree.findFile(".syd_meta") ?: tree.createDirectory(".syd_meta")
            val trash = tree.findFile(".syndes_trash") ?: tree.createDirectory(".syndes_trash")
        } catch (t: Throwable) {
            // молча игнорируем ошибки создания — не критично
        }
    }

    // Пытаемся получить удобочитаемое имя для UI (name of DocumentFile), иначе null
    private fun buildFriendlyName(uri: Uri): String? {
        return try {
            val df = DocumentFile.fromTreeUri(this, uri)
            df?.name
        } catch (t: Throwable) {
            null
        }
    }
}
