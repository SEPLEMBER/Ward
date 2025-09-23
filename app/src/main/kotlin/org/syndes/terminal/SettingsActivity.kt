package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var workFolderUriView: TextView
    private lateinit var chooseFolderBtn: Button
    private lateinit var themeGroup: RadioGroup
    private lateinit var themeDark: RadioButton
    private lateinit var themeLight: RadioButton
    private lateinit var themeAuto: RadioButton
    private lateinit var autoScrollSwitch: SwitchCompat
    private lateinit var aliasesField: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button

    private val PREFS_NAME = "terminal_prefs"
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        workFolderUriView = findViewById(R.id.workFolderUri)
        chooseFolderBtn = findViewById(R.id.chooseFolderBtn)
        themeGroup = findViewById(R.id.themeGroup)
        themeDark = findViewById(R.id.themeDark)
        themeLight = findViewById(R.id.themeLight)
        themeAuto = findViewById(R.id.themeAuto)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        aliasesField = findViewById(R.id.aliasesField)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)

        // Регистрируем launcher для выбора директории (SAF)
        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val treeUri: Uri? = result.data?.data
                if (treeUri != null) {
                    // сохраняем persistable permission
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString("work_dir_uri", treeUri.toString()).apply()
                    workFolderUriView.text = treeUri.toString()
                }
            }
        }

        loadValues()

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
            finish()
        }

        resetButton.setOnClickListener {
            resetValues()
        }
    }

    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uri = prefs.getString("work_dir_uri", null)
        workFolderUriView.text = uri ?: "(not set)"

        when (prefs.getString("theme", "dark")) {
            "dark" -> themeDark.isChecked = true
            "light" -> themeLight.isChecked = true
            else -> themeAuto.isChecked = true
        }

        autoScrollSwitch.isChecked = prefs.getBoolean("scroll_behavior", true)
        aliasesField.setText(prefs.getString("aliases", ""))
    }

    private fun saveValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()

        // theme
        val theme = when (themeGroup.checkedRadioButtonId) {
            R.id.themeDark -> "dark"
            R.id.themeLight -> "light"
            else -> "auto"
        }
        prefs.putString("theme", theme)

        prefs.putBoolean("scroll_behavior", autoScrollSwitch.isChecked)
        prefs.putString("aliases", aliasesField.text.toString().trim())
        prefs.apply()
    }

    private fun resetValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.clear()
        prefs.apply()
        loadValues()
        Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
    }
}
