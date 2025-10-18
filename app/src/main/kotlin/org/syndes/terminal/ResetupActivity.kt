package org.syndes.terminal

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.ClipboardManager // deprecated fallback only for very old devices; we'll use getSystemService below
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity 

class ResetupActivity : AppCompatActivity() {

    private lateinit var inputEditText: EditText
    private lateinit var resetupButton: Button
    private lateinit var progressText: TextView

    // очередь пакетов для последовательной обработки
    private val packageQueue = mutableListOf<String>()
    private var currentIndex = 0

    // Лаунчер для запуска системного Activity (удаления) и получения результата
    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        // После закрытия системного диалога (удалено или отменено), продолжаем следующий пакет
        // Здесь мы не используем result.resultCode для логики — система не всегда возвращает детальное состояние,
        // поэтому просто идем дальше по очереди.
        currentIndex++
        processNextInQueue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resetup)

        inputEditText = findViewById(R.id.inputEditText)
        resetupButton = findViewById(R.id.resetupButton)
        progressText = findViewById(R.id.progressText)

        // Попробуем вставить содержимое буфера обмена (если есть) — это просто удобная подсказка, не обязательная
        pasteClipboardIfEmpty()

        resetupButton.setOnClickListener {
            startResetupProcess()
        }
    }

    private fun pasteClipboardIfEmpty() {
        if (inputEditText.text.isNullOrBlank()) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    inputEditText.setText(text)
                    Toast.makeText(this, "Вставлено содержимое буфера обмена", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startResetupProcess() {
        // Сброс предыдущей очереди
        packageQueue.clear()
        currentIndex = 0

        // Разбор входа по строкам
        val raw = inputEditText.text.toString()
        val lines = raw.lines()

        for (ln in lines) {
            val pkg = ln.trim().trimEnd('.') // иногда копирование даёт точки в конце
            if (pkg.isEmpty()) continue
            if (!isLikelyValidPackageName(pkg)) continue
            // Не добавляем собственный пакет или системные пакеты
            if (isProtectedPackage(pkg)) continue

            packageQueue.add(pkg)
        }

        if (packageQueue.isEmpty()) {
            Toast.makeText(this, "Нет валидных package name'ов для обработки", Toast.LENGTH_LONG).show()
            progressText.text = "Готово: 0 пакетов"
            return
        }

        progressText.text = "0 / ${packageQueue.size}"
        // Начинаем последовательную обработку
        currentIndex = 0
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (currentIndex >= packageQueue.size) {
            Toast.makeText(this, "Обработка завершена", Toast.LENGTH_SHORT).show()
            progressText.text = "Готово: ${packageQueue.size} / ${packageQueue.size}"
            return
        }

        val pkg = packageQueue[currentIndex]
        progressText.text = "${currentIndex + 1} / ${packageQueue.size} — $pkg"

        // Проверяем установлен ли пакет
        if (!isPackageInstalled(pkg)) {
            Toast.makeText(this, "Пакет не установлен: $pkg — пропускаем", Toast.LENGTH_SHORT).show()
            currentIndex++
            processNextInQueue()
            return
        }

        // Создаём Intent для удаления пакета — системный диалог, пользователь подтверждает
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true) // попытка получить результат; поведение зависит от системы

            // Запускаем системный Activity и ждём возврата (через uninstallLauncher callback)
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось запустить удаление для $pkg: ${e.message}", Toast.LENGTH_LONG).show()
            // переходим к следующему
            currentIndex++
            processNextInQueue()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        // Не даём удалять саму утилиту или системные контейнеры
        val myPackage = applicationContext.packageName
        if (packageName == myPackage) return true

        // Простая проверка на system-like пакеты (не исчерпывающая)
        val lower = packageName.lowercase()
        if (lower.startsWith("android") || lower.startsWith("com.google.android") || lower.startsWith("com.samsung") || lower.startsWith("com.huawei")) {
            // Предполагаем системные/важные пакеты — блокируем
            Toast.makeText(this, "Пакет пропущен как защищённый: $packageName", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun isLikelyValidPackageName(pkg: String): Boolean {
        // Простая валидация package name (не жёсткая)
        // Разрешаем латиницу, цифры, точки и подчеркивания; первая часть должна начинаться с буквы
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        return regex.matches(pkg)
    }
}
