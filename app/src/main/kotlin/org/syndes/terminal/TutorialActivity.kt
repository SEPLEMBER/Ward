package org.syndes.terminal

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        val tv: TextView = findViewById(R.id.tutorial_text)
        tv.setText(buildHighlightedCommands(), TextView.BufferType.SPANNABLE)
    }

    private fun buildHighlightedCommands(): SpannableStringBuilder {
        // Цвета
        val colorCommand = Color.parseColor("#4FC3F7") // blue-ish
        val colorArg = Color.parseColor("#80E27E")     // green-ish
        val colorWarning = Color.parseColor("#FF5252") // red
        val colorNeonCyan = Color.parseColor("#00FFF0") // neon cyan
        val colorDefault = Color.parseColor("#E0E0E0")

        val raw = """
Available commands:
  about           - app info/version
  echo <text>     - print text (supports > file for redirect)
  open <app|file> - launch app by visible name (e.g. open Minecraft) or open file in associated app (e.g. open file.txt)
  launch <app>    - explicitly launch app (alias to open-app)
  history         - show input history
  clear           - clear terminal history (internal)
  settings|console - open settings screen
  ls|dir          - list files in current directory
  cd <dir>        - change directory (supports .., relative paths, 'home')
  pwd             - print working directory path
  cp <src> <dst>  - copy file/dir (supports relative paths)
  mv <src> <dst>  - move file/dir (supports relative paths)
  rm [-r] <file>  - remove file or dir (recursive with -r, supports paths)
  mkdir <name>    - create directory (supports paths)
  touch <name>    - create empty file (supports paths)
  cat <file>      - display file contents (supports paths)
  ln <src> <link> - create pseudo-link (copy) of file (supports paths)
  wc <file>       - word count: lines, words, chars (supports paths)
  head <file> [n] - show first n lines (default 10, supports paths)
  tail <file> [n] - show last n lines (default 10, supports paths)
  du <file|dir>   - show size in bytes (recursive for dirs, supports paths)
  stat <path>     - show file/dir stats (size, type, modified)
  find <name>     - find files by name in current dir
  cmp <f1> <f2>   - compare two text files (line-by-line)
  diff [-u] [-c <lines>] [-i] <f1> <f2> - show differences with line numbers (supports unified format, context lines, ignore case)
  replace <old> <new> <path> - replace text in file or recursively in directory
  rename <old> <new> <path> - rename filenames in dir (substring replace; use {} in new for counter)
  encrypt <password> <path> - encrypt text files (recursively if dir)
  decrypt <password> <path> - decrypt text files (recursively if dir)
  
  !!! WARNING (ENCRYPT): encrypting large folders is CPU-intensive.
  Please encrypt small folders or specific directories one-by-one.
  Iterations = 1000 (LOW). This provides only limited KDF hardness.
  Use a strong password — recommendation: minimum **8 different characters** (no enforcement).
   (Encryption here is a convenience/bonus — responsibility for secure passwords is on the user.)
  
  wait <seconds>  - block commands for given seconds (persist until timeout)
  pm list [user|system] - list installed packages (all or filtered)
  pm install <apk> - install APK from work dir (supports paths)
  pm uninstall <pkg|appname> - uninstall package or app by name
  pm launch <pkg|appname> - launch package or app by name
  date            - show current date/time
  whoami          - show user info
  uname           - show system info
  uptime          - show system uptime
  which <cmd>     - check if command exists
  alias <name>=<command> - set alias
  alias list      - list aliases
  alias run <name> - run alias
  unalias <name>  - remove alias
  env             - show environment vars
  sms [number] [text] - open SMS app (with number and text)
  call <number>   - dial number
  email [address] [subject] [body] - open email app (with address, subject, body)
  browser [url]   - open browser (with URL)
  search <query>  - web search query
  contacts        - open contacts app
  alarm           - open alarm app
  calc            - open calculator app
  vpns            - open VPN settings
  btss            - open battery saver settings
  wifi            - open Wi-Fi settings
  bts             - open Bluetooth settings
  data            - open mobile data settings
  apm             - open airplane mode settings
  snd             - open sound settings
  dsp             - open display settings
  apps            - open app settings
  stg             - open storage settings
  sec             - open security/app details
  loc             - open location settings
  nfc             - open NFC settings
  cam             - open camera
  clk             - open date/time settings
  notif           - open notification settings
  acc             - open account settings
  dev             - open developer settings
  syd list        - list scripts in work_dir/scripts
  syd run <name>  - run script (name or name.syd)
  syd stop <script-id> - stop running script (id returned on start)
  syd edit <name> - open script for editing (if editor available)
  syd validate <name> - validate syntax using ScriptHandler
  run <name>      - alias for 'syd run <name>'
  matrix fall [color] - start Matrix effect (color: blue or green)
  tutorial        - open tutorial screen
  exit            - shut down the application
  backup|snapshot <path> - create backup of file/dir in SydBack
  batchren <dir> <newPattern> - batch rename files in directory
  trash <path>    - move file/dir to trash (.syndes_trash)
  cleartrash      - clear trash directory
  sha256|md5 <path> - compute file hash (SHA-256 or MD5)
  pminfo|pkginfo <package|appname> - show package information
  type <path>     - show file/dir type and metadata
  pkgof|findpkg <appname> - find package name by app name
  batchedit <old> <new> <dir> [--dry] - batch replace text in files
  ps|top          - show running processes
  sysclipboard get|set <text> - get/set system clipboard content
  preview <path> [lines] - preview file content (text or image metadata)
  filekey|apkkey <apk_path> - show APK signatures/certificates
  ping <host> [count] - ping a host
  dns <name>      - perform DNS lookup
  curl|fetch <url> - perform HTTP GET request
  grep [-r] [-i] [-l] [-n] <pattern> <path> - search text in files (recursive, ignore case, names only, line numbers)
  zip <source> <archive> - create ZIP archive
  unzip <archive> [dest] - extract ZIP archive
  logcat [-t tag] [-l level] [-n lines] - view system logs
  notify -t <title> -m <message> - send system notification
  split <file> <lines_per_file> [prefix] - split file into parts by lines
  dedup-files <dir> [--delete] - find/delete duplicate files
  checksum <file> [md5|sha256] - compute file hash
  rev <file> [--inplace] - reverse line order in file
  cut -d<delim> -f<fields> <file> - extract fields from file by delimiter
  join|merge <file1> <file2> [sep] - join files line-by-line
  sort-lines <file> [--unique] [--reverse] [--inplace] - sort lines in file
  dup-lines|duplicate-lines|dup-lines-in-file <file> [--count] [--show-only-duplicates] - show/count duplicate lines
  mem [pkg]       - show memory info (system or package)
  device          - show device information
""".trimIndent()

        val sb = SpannableStringBuilder(raw)

        // 1) highlight the "!!! WARNING (ENCRYPT):" header in red
        val warningHeader = "!!! WARNING (ENCRYPT):"
        val whIndex = raw.indexOf(warningHeader)
        if (whIndex >= 0) {
            val start = whIndex
            val end = whIndex + warningHeader.length
            sb.setSpan(ForegroundColorSpan(colorWarning), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 2) highlight the essence of the warning (look for sentence containing "encrypting large folders")
        val essenceTargets = listOf(
            "encrypting large folders is CPU-intensive.",
            "Please encrypt small folders or specific directories one-by-one.",
            "Iterations = 1000 (LOW). This provides only limited KDF hardness."
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

            // If line starts with two spaces and then text like "about", parse command tokens until spacing cluster
            // we'll color the left-side (command part) tokens blue
            if (line.trim().isNotEmpty() && line.trimStart().length != 0) {
                // color command part (left of " - ") in blue (only if it's not description/warning block)
                if (commandPartEnd > lineStart) {
                    // color token characters that look like command names / aliases (letters, numbers, |, |)
                    // we'll simply color the whole left part blue, then override args/flags inside it as green
                    sb.setSpan(ForegroundColorSpan(colorCommand), lineStart, commandPartEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    // inside left part, highlight <...> and -flags as green
                    // find all occurrences of <...>
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
                    // flags like -r or [-r]
                    val flagRegex = Regex("""\B-[-\w\[\]]+""")
                    flagRegex.findAll(leftPart).forEach { m ->
                        val absStart = lineStart + m.range.first
                        val absEnd = lineStart + m.range.last + 1
                        sb.setSpan(ForegroundColorSpan(colorArg), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    // for lines without " - " we can still highlight commands that start after indentation
                    // find first non-space in line
                    val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }
                    if (firstNonSpace >= 0) {
                        // color the first token up to space in blue
                        val tokenEndInLine = line.indexOfFirst { it.isWhitespace() }.let { if (it < 0) line.length else it }
                        val absStart = lineStart + firstNonSpace
                        val absEnd = lineStart + tokenEndInLine
                        if (absEnd > absStart) {
                            sb.setSpan(ForegroundColorSpan(colorCommand), absStart, absEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // ALSO: in the description (right of " - "), highlight <...> and flags in green as well
            if (sepIndexInLine >= 0) {
                val descStart = lineStart + sepIndexInLine + 3 // after " - "
                val descText = line.substring(sepIndexInLine + 3)
                // args in <>
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
                // flags -r etc.
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

        // final: make sure the default text color is applied to non-colored parts (TextView default already does),
        // but we can optionally set entire area to default before overlays (we already used spans only where needed).

        return sb
    }
}
