package org.syndes.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class RuTutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val tv: TextView = findViewById(R.id.tutorial_text)
        // monospace for terminal feel
        tv.typeface = Typeface.MONOSPACE
        // subtle neon glow
        val neonCyan = Color.parseColor("#00FFF0")
        applyNeon(tv, neonCyan, radius = 4f)

        tv.setText(buildHighlightedCommands(), TextView.BufferType.SPANNABLE)
    }

    private fun buildHighlightedCommands(): SpannableStringBuilder {
        // Цвета
        val colorCommand = Color.parseColor("#4FC3F7") // blue-ish
        val colorArg = Color.parseColor("#80E27E")     // green-ish
        val colorWarning = Color.parseColor("#FF5252") // red
        val colorNeonCyan = Color.parseColor("#00FFF0") // neon cyan
        val colorDefault = Color.parseColor("#E0E0E0")

        // Организованные категории (A..E). Команды в каждой категории отсортированы по алфавиту.
        val raw = """
Доступные команды:

==A: Apps & Package manager==
  about                - показать информацию о приложении и версию
  alias <name>=<cmd>   - определить псевдоним (ярлык). Имена псевдонимов не могут содержать пробелы
  alias list           - показать список определённых псевдонимов
  alias run <name>     - выполнить псевдоним по имени
  apps                 - открыть экран настроек приложения
  launch <app>         - запустить приложение по видимому имени (синоним open для приложений)
  pm install <apk>     - установить APK из текущего рабочего каталога (поддерживаются SAF-пути)
  pm launch <pkg|app>  - запустить пакет или приложение по имени пакета или видимому имени
  pm list [user|system]- перечислить установленные пакеты; необязательный фильтр 'user' или 'system'
  pm uninstall <pkg>   - удалить пакет (запускает системный диалог удаления)
  pminfo|pkginfo <pkg> - показать информацию о пакете (имя пакета или видимое имя приложения)
  pkgof|findpkg <app>  - найти имя(а) пакета по видимому имени приложения
  resetup              - открыть утилиту пакетного удаления (итерирует список пакетов для удаления)
  runsyd <name>        - загрузить и вставить скрипт из папки 'scripts' в SAF root (пытается .syd, .sh, .txt)

==B: Files & File system operations==
  apm                  - открыть настройки режима полёта (ярлык)
  cat <file>           - показать содержимое файла (поддерживаются SAF/относительные пути)
  checksum <file> [md5|sha256] - вычислить хеш файла (по умолчанию sha256)
  cmp <f1> <f2>        - сравнить два текстовых файла (построчно)
  cp <src> <dst>       - копировать файл или директорию (поддерживаются относительные пути)
  cd                   - сменить рабочую директорию
  cut -d<delim> -f<fields> <file> - извлечь поля из файла по разделителю
  du <file|dir>        - показать размер в байтах (рекурсивно для директорий)
  encrypt <password> <path> - зашифровать файлы (рекурсивно для директорий)
  decrypt <password> <path> - расшифровать файлы (рекурсивно для директорий)
  filekey|apkkey <apk> - показать подписи/сертификаты APK
  find <name>          - найти файлы по имени в текущей директории (по умолчанию не рекурсивно)
  head <file> [n]      - показать первые n строк (по умолчанию 10)
  join|merge <file1> <file2> [sep] - объединить файлы построчно
  ln <src> <link>      - создать псевдоссылку (копию) файла
  ls|dir               - перечислить файлы в текущей директории
  mkdir <name>         - создать директорию (поддерживаются пути)
  mv <src> <dst>       - переместить файл или директорию (поддерживаются относительные пути)
  preview <path> [lines] - предварительный просмотр содержимого файла (текст или метаданные изображения)
  replace <old> <new> <path> - заменить текст в файлах или рекурсивно в директориях
  rename <old> <new> <path> - переименовать файлы в директории (замена подстроки; поддерживается счётчик { })
  rev <file> [--inplace] - обратить порядок строк в файле
  rm [-r] <path>       - удалить файл или директорию (-r для рекурсивного удаления)
  rmdir (use rm)       - (примечание) предпочтительнее rm для рекурсивных удалений
  split <file> <lines_per_file> [prefix] - разбить файл на части по количеству строк
  stat <path>          - показать статистику файла/директории (размер, тип, время модификации)
  stash/trash <path>   - переместить файл/папку в корзину (.syndes_trash)
  cleartrash           - очистить корзину
  touch <name>         - создать пустой файл (поддерживаются пути)
  trash <path>         - переместить файл/директорию в корзину (.syndes_trash)
  unzip <archive> [dest] - распаковать ZIP-архив
  zip <source> <archive> - создать ZIP-архив

==C: System & Information==
  date                 - показать текущие дату и время
  device               - показать информацию об устройстве (модель, бренд и т.д.)
  mem [pkg]            - показать использование памяти (система или конкретный пакет)
  ps|top               - показать запущенные процессы (ps) или краткую сводку (top-подобно)
  sha256|md5 <path>    - вычислить хеш файла (ярлык)
  sysclipboard get|set <text> - получить или установить содержимое системного буфера обмена
  uname                - показать системное имя/версию
  uptime               - показать время работы системы
  sleep <min>/<ms>/<sec> - ждать перед выполнением команды
  wait <sec> - блокировать исполнение команд на указанное число секунд (до тайм-аута)
  whoami               - показать текущего пользователя (или идентификатор окружения)

==D: Utilities & Text tools==
  backup|snapshot <path> - создать резервную копию файла/папки с SydBack (если доступно)
  batchren <dir> <pattern> - массовое переименование файлов в директории
  batchedit <old> <new> <dir> [--dry] - массовая замена текста в файлах
  calc                 - открыть приложение калькулятора
  cat                  - (см. Files) показать содержимое файла
  cmp                  - (см. Files) сравнить файлы
  diff [-u] [-c <n>] [-i] <f1> <f2> - показать текстовые отличия с опциями (unified/context/ignore-case)
  grep [-r] [-i] [-l] [-n] <pattern> <path> - поиск текста в файлах (рекурсивно, без учёта регистра, только имена, показать номера строк)
  head                 - (см. Files)
  hash utilities       - использовать checksum/sha256/md5 для хеширования
  rev                  - обращение строк файла
  sort-lines <file> [--unique] [--reverse] [--inplace] - сортировка строк в файле
  split                - (см. Files)
  tail <file> [n]      - показать последние n строк (по умолчанию 10)
  wc <file>            - подсчитать строки, слова, символы

==E: Settings, Shortcuts & Apps shortcuts==
act                  - запустить Activity указанного приложения (если exported=true)
shortc               - создать ярлык на рабочем столе, запускающий указанную команду терминала
bootshell            - открыть BootShell (UI для автозагрузки / редактирования автокоманд)
kbd                  - открыть настройки клавиатуры
aband                - открыть «О телефоне» / информация об устройстве
accs                 - открыть настройки доступности (Accessibility)
priv                 - открыть настройки приватности
lang                 - открыть языковые настройки
home                 - открыть настройки лаунчера / рабочего стола
zen                  - открыть настройки режима «Не беспокоить»
night                - открыть настройки ночного режима / фильтра синего света
apse <app|pkg>       - открыть настройки указанного приложения (application details)
wifi                 - открыть настройки Wi-Fi
bts                  - открыть настройки Bluetooth
data                 - открыть настройки мобильных данных
apm                  - открыть настройки режима полёта
snd                  - открыть настройки звука
dsp                  - открыть настройки дисплея / экрана
apps                 - открыть общий раздел настроек приложений
stg                  - открыть настройки хранилища / внутренней памяти
sec                  - открыть безопасность / детали приложения (app details)
loc                  - открыть настройки определения местоположения
nfc                  - открыть настройки NFC
cam                  - открыть камеру (команда запускает ACTION_IMAGE_CAPTURE)
clk                  - открыть настройки даты/времени
notif                - открыть настройки уведомлений приложения
acc                  - открыть настройки аккаунтов / синхронизации
dev                  - открыть настройки разработчика
alarm                - открыть приложение будильника
btss                 - открыть настройки энергосбережения / батареи
contacts             - открыть приложение контактов
vpns                 - открыть настройки VPN
browser [url]        - открыть браузер (опционально с URL)
call <number>        - открыть набор номера в Dialer
email [addr] [subj] [body]
                     - открыть компоновщик письма (адрес/тема/содержание опциональны)
notify -t <title> -m <message>
                     - отправить системное уведомление с заголовком и сообщением
search <query>       - выполнить поиск в интернете (открывает браузер)
sms [number] [text]  - открыть SMS-приложение с необязательным номером и текстом

==F: Shell control, aliases & app flow==
  clear                - очистить вывод терминала (внутренняя команда)
  exit                 - завершить работу приложения
  history              - показать историю ввода
  help                 - показать эту справку / руководство
  alias                - (см. раздел A: alias)
  unalias <name>       - удалить псевдоним

==G: Misc / Notes & Warnings==
  !!! ВНИМАНИЕ (ENCRYPT): шифрование больших папок требует большой CPU-нагрузки.
  Пожалуйста, шифруйте небольшие папки или отдельные директории по очереди.
  Итерации = 1000 (НИЗКО). Это даёт ограниченную стойкость KDF.
  Используйте сильный пароль — рекомендуется минимум 8 различных символов.
  (Шифрование — удобная функция; ответственность за безопасность паролей лежит на пользователе.)

Примечания:
  - runsyd читает скрипты из корня SAF → директории 'scripts' (пытается name.syd, name.sh, name.txt). Поддерживает как указание расширения, так и его отсутствие — например: "runsyd scriptname" или "runsyd scriptname.syd".
  - pm uninstall запускает системный поток удаления (пользователю нужно подтвердить каждый диалог удаления).
  - resetup открывает UI, который итерирует список пакетов и вызывает системные диалоги удаления по очереди.
  - многие файловые операции поддерживают SAF-пути или относительные пути от настроенной рабочей директории.
  - псевдонимы (aliases) локальны для приложения и не влияют на системный shell.
  - Поддерживаются последовательности команд: команды вида 'cmd1; cmd2; cmd3' выполняются по очереди. Группы с префиксом 'parallel:' такие как 'parallel: cmd1; cmd2; cmd3' выполняются параллельно. Команды с '&' (например 'cmd1 & cmd2; cmd3') ведут себя как фоновые/неблокирующие — полезно, например, чтобы открыть Activity, не останавливая остальные команды.
  - Поддерживается команда 'button': 'button (Текст вопроса - Вариант1=cmd1 - Вариант2=cmd2 - ...)', разделитель частей — '-'. Если 'button(...)' встречается в цепочке команд (например 'button(...); othercommand'), последующие команды будут приостановлены до тех пор, пока пользователь не выберет один из вариантов. После выбора цепочка возобновится — к ней будет добавлена команда, связанная с выбранным вариантом (выполнится как будто пользователь ввёл её вручную).
  - Поддерживается команда 'random {cmd1-cmd2-cmd3}'. Она запускает случайно выбранную команду из предоставленного списка.
  - SyPL Compiler дополняет функции терминала следующими командами:
  - `if <left> = <right> then <command>` с поддержкой `else <command>`. `then` выполняет указанную команду как если бы пользователь ввёл её вручную (вплоть до ожиданий/блокировок). `else` относится ко всей последовательности подряд идущих `if` (если между ними нет других команд) и выполняется только если ни одно из предыдущих `if` в цепочке не сработало. Примеры с `echo`: `if 1 = 1 then echo ok` — сработает, выведет `ok` (литеральное сравнение). 
  -`echo hello` `if echo hello = whatever then echo prev_cmd_matched` — сработает, потому что сравнивается последняя выполненная команда. `echo hi` `if hi = hi then echo result_matched` — сработает, потому что сравнивается последний результат команды. Цепочка:
  - `if cmdA = x then echo A`
  - `if cmdB = y then echo B`
  `else echo fallback`
  — `else` выполнится только если ни `A`, ни `B` не сработали.
  - существует поддержка `cycle`. Поддерживаемые формы:
  - `cycle <N>t <interval>=<cmd>` — выполнить `<cmd>` N раз с паузой `<interval>` между запусками. Примеры: `cycle 10t 3ms=echo hi`, `cycle 5t 2s=echo tick`. Поддерживаются суффиксы времени `ms`, `s`, `m`.
  - `cycle next <Mi>i <N>t=<cmd>` — выполнять `<cmd>` N раз, каждый раз после того как будет обработано `Mi` команд (т.е. через указанное количество обработанных команд). Пример: `cycle next 3i 7t=echo every3` — команда `echo every3` будет инжектирована и выполнена 7 раз, каждый раз после обработки 3 команд.
  - циклы планируются как фоновые задачи и добавляют команды в очередь исполнения согласно расписанию/триггерам.

""".trimIndent()

        val sb = SpannableStringBuilder(raw)

        // 1) highlight the warning header in red (match Russian header)
        val warningHeader = "!!! ВНИМАНИЕ (ENCRYPT):"
        val whIndex = raw.indexOf(warningHeader)
        if (whIndex >= 0) {
            val start = whIndex
            val end = whIndex + warningHeader.length
            sb.setSpan(ForegroundColorSpan(colorWarning), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 2) highlight the essence of the warning (Russian phrases)
        val essenceTargets = listOf(
            "шифрование больших папок требует большой CPU-нагрузки.",
            "Пожалуйста, шифруйте небольшие папки или отдельные директории по очереди.",
            "Итерации = 1000 (НИЗКО). Это даёт ограниченную стойкость KDF."
        )
        essenceTargets.forEach { t ->
            val idx = raw.indexOf(t, ignoreCase = true)
            if (idx >= 0) {
                sb.setSpan(ForegroundColorSpan(colorNeonCyan), idx, idx + t.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 3) per-line parsing: command part (left of " - ") -> blue; args <...> and flags -x -> green
        val lines = raw.split("\n")
        var offset = 0
        for (line in lines) {
            // position of this line in sb
            val lineStart = offset
            val lineEnd = offset + line.length

            // find separator " - "
            val sepIndexInLine = line.indexOf(" - ")
            val commandPartEnd = if (sepIndexInLine >= 0) lineStart + sepIndexInLine else lineEnd

            if (line.trim().isNotEmpty()) {
                // color command part (left of " - ") in blue (only if it's not description/warning block)
                if (commandPartEnd > lineStart) {
                    sb.setSpan(ForegroundColorSpan(colorCommand), lineStart, commandPartEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    // inside left part, highlight <...> and -flags as green
                    val leftPart = line.substring(0, if (sepIndexInLine >= 0) sepIndexInLine else line.length)
                    var relIndex = 0
                    while (true) {
                        val lt = leftPart.indexOf('<', relIndex)
                        if (lt < 0) break
                        val gt = leftPart.indexOf('>', lt + 1)
                        if (gt < 0) break
                        val absStart = lineStart + lt
                        val absEnd = lineStart + gt + 1
                        sb.setSpan(ForegroundColorSpan(colorArg), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        relIndex = gt + 1
                    }
                    val flagRegex = Regex("""\B-[-\w\[\]]+""")
                    flagRegex.findAll(leftPart).forEach { m ->
                        val absStart = lineStart + m.range.first
                        val absEnd = lineStart + m.range.last + 1
                        sb.setSpan(ForegroundColorSpan(colorArg), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }
                    if (firstNonSpace >= 0) {
                        val tokenEndInLine = line.indexOfFirst { it.isWhitespace() }.let { if (it < 0) line.length else it }
                        val absStart = lineStart + firstNonSpace
                        val absEnd = lineStart + tokenEndInLine
                        if (absEnd > absStart) {
                            sb.setSpan(ForegroundColorSpan(colorCommand), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // description part highlight (<...> and flags)
            if (sepIndexInLine >= 0) {
                val descStart = lineStart + sepIndexInLine + 3 // after " - "
                val descText = line.substring(sepIndexInLine + 3)
                var relIndex = 0
                while (true) {
                    val lt = descText.indexOf('<', relIndex)
                    if (lt < 0) break
                    val gt = descText.indexOf('>', lt + 1)
                    if (gt < 0) break
                    val absStart = descStart + lt
                    val absEnd = descStart + gt + 1
                    sb.setSpan(ForegroundColorSpan(colorArg), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    relIndex = gt + 1
                }
                val flagRegex = Regex("""\B-[-\w\[\]]+""")
                flagRegex.findAll(descText).forEach { m ->
                    val absStart = descStart + m.range.first
                    val absEnd = descStart + m.range.last + 1
                    sb.setSpan(ForegroundColorSpan(colorArg), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // advance offset (+1 for newline)
            offset += line.length + 1
        }

        return sb
    }

    // subtle neon glow helper
    private fun applyNeon(tv: TextView, color: Int, radius: Float = 4f, dx: Float = 0f, dy: Float = 0f) {
        try {
            tv.setShadowLayer(radius, dx, dy, color)
        } catch (_: Throwable) {
            // defensive: some devices could behave differently; ignore failure
        }
    }
}
