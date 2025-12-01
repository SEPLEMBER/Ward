package org.syndes.terminal

import android.graphics.Color
import android.graphics.Typeface
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

        // Organized categories (A..E). Commands in each category are alphabetically sorted.
        val raw = """
Available commands:

==A: Apps & Package manager==
  about                - show app information and version
  alias <name>=<cmd>   - define alias (alias names cannot contain spaces)
  alias list           - list defined aliases
  alias run <name>     - run alias by name
  apps                 - open application settings screen
  launch <app>         - launch an app by name (alias of open for apps)
  pm install <apk>     - install APK from current work directory (SAF paths supported)
  pm launch <pkg|app>  - launch package or app by package name or visible app name
  pm list [user|system]- list installed packages; optional filter 'user' or 'system'
  pm uninstall <pkg>   - uninstall package (starts system uninstall flow)
  pminfo|pkginfo <pkg> - show package information (package or app visible name)
  pkgof|findpkg <app>  - find package name(s) by visible app name
  resetup              - open batch uninstall utility (batch remove apps)
  runsyd <name>        - load and inject script from SAF root 'scripts' folder (tries .syd, .sh, .txt)

==B: Files & File system operations==
  apm                  - open airplane mode settings (shortcut)
  cat <file>           - display file contents (supports SAF/relative paths)
  checksum <file> [md5|sha256] - compute file hash (default sha256)
  cmp <f1> <f2>        - compare two text files (line-by-line)
  cp <src> <dst>       - copy file or directory (supports relative paths)
  cd - change directory
  cut -d<delim> -f<fields> <file> - extract fields from file by delimiter
  du <file|dir>        - show size in bytes (recursive for directories)
  encrypt <password> <path> - encrypt files (recursively if directory)
  decrypt <password> <path> - decrypt files (recursively if directory)
  filekey|apkkey <apk> - show APK signatures/certificates
  find <name>          - find files by name in current directory (non-recursive by default)
  head <file> [n]      - show first n lines (default 10)
  join|merge <file1> <file2> [sep] - join files line-by-line
  ln <src> <link>      - create pseudo-link (copy) of file
  ls|dir               - list files in current directory
  mkdir <name>         - create directory (supports paths)
  mv <src> <dst>       - move file or directory (supports relative paths)
  preview <path> [lines] - preview file content (text or image metadata)
  replace <old> <new> <path> - replace text in files or recursively in dirs
  rename <old> <new> <path> - rename filenames in directory (substring replace; supports { } counter)
  rev <file> [--inplace] - reverse line order in file
  rm [-r] <path>       - remove file or directory (-r for recursive)
  rmdir (use rm)       - (note) prefer rm for recursive removes
  split <file> <lines_per_file> [prefix] - split file into parts by lines
  stat <path>          - show file/directory stats (size, type, modified)
  stash/trash <path>   - move file/dir to trash (.syndes_trash)
  cleartrash           - clear trash directory
  touch <name>         - create empty file (supports paths)
  trash <path>         - move file/dir to trash (.syndes_trash)
  unzip <archive> [dest] - extract ZIP archive
  zip <source> <archive> - create ZIP archive

==C: System & Information==
  date                 - show current date and time
  device               - show device information (model, brand, etc.)
  mem [pkg]            - show memory usage info (system or specific package)
  ps|top               - show running processes (ps) or top-like summary
  sha256|md5 <path>    - compute file hash (shortcut)
  sysclipboard get|set <text> - get or set system clipboard contents
  uname                - show system name/version information
  uptime               - show system uptime
  sleep <min>/<ms>/<sec> - wait before run command
  wait <sec> - block commands for given seconds (persist until timeout)
  whoami               - show current user (or environment identity)

==D: Utilities & Text tools==
  backup|snapshot <path> - create backup of file/dir with SydBack (if available)
  batchren <dir> <pattern> - batch rename files in directory
  batchedit <old> <new> <dir> [--dry] - batch replace text in files
  calc                 - open calculator app
  cat                  - (see Files) display file contents
  cmp                  - (see Files) compare files
  diff [-u] [-c <n>] [-i] <f1> <f2> - show textual differences with options (unified/context/ignore-case)
  grep [-r] [-i] [-l] [-n] <pattern> <path> - search text in files (recursive, ignore case, names only, show line numbers)
  head                 - (see Files) 
  hash utilities       - use checksum/sha256/md5 commands for hashing
  rev                  - reverse file lines
  sort-lines <file> [--unique] [--reverse] [--inplace] - sort lines in file
  split                - (see Files)
  tail <file> [n]      - show last n lines (default 10)
  wc <file>            - word count: lines, words, chars

==E: Settings, Shortcuts & Apps shortcuts==
  acc                  - open account settings
  alarm                - open alarm app
  apm                  - open airplane mode settings
  btss                 - open battery saver settings
  bts                  - open Bluetooth settings
  cam                  - open camera app
  clk                  - open date/time settings
  contacts             - open contacts app
  data                 - open mobile data settings
  dev                  - open developer settings
  dsp                  - open display settings
  loc                  - open location settings
  nfc                  - open NFC settings
  notif                - open notification settings
  sec                  - open security/app details
  snd                  - open sound settings
  stg                  - open storage settings
  vpns                 - open VPN settings
  wifi                 - open Wi-Fi settings
  browser [url]        - open browser with optional URL
  call <number>        - open dialer with number
  email [addr] [subj] [body] - open email composer
  notify -t <title> -m <message> - send system notification
  search <query>       - perform web search (opens browser)
  sms [number] [text]  - open SMS app with optional number/text

==F: Shell control, aliases & app flow==
  clear                - clear terminal output (internal)
  exit                 - shut down the application
  history              - show input history
  help                 - show this help / tutorial
  alias                - (see A: alias commands)
  unalias <name>       - remove alias

==G: Misc / Notes & Warnings==
  !!! WARNING (ENCRYPT): encrypting large folders is CPU-intensive.
  Please encrypt small folders or specific directories one-by-one.
  Iterations = 1000 (LOW). This provides only limited KDF hardness.
  Use a strong password — recommendation: minimum 8 different characters.
  (Encryption is a convenience feature; responsibility for secure passwords lies with the user.)

Notes:
  - runsyd reads scripts from SAF root → 'scripts' directory (tries name.syd, name.sh, name.txt). Supports both specifying the file extension and omitting it — e.g. you can run "runsyd scriptname" or "runsyd scriptname.syd".
  - pm uninstall starts system uninstall flow (user must confirm each uninstall dialog).
  - resetup opens UI that iterates package list and launches system uninstall dialogs one-by-one.
  - many file operations support SAF paths or relative paths from the configured work directory.
  - aliases are local to the app and do not affect the system shell.
  - Command sequences are supported: commands of the form 'cmd1; cmd2; cmd3' run sequentially. Groups prefixed with 'parallel:' such as 'parallel: cmd1; cmd2; cmd3' run concurrently. Commands that include '&' (for example 'cmd1 & cmd2; cmd3') behave as backgrounded or non-blocking tasks — useful when, for example, you need to start an Activity without stopping the rest of the chain.
  - Supports the 'button' command: 'button (Question text - Option1=cmd1 - Option2=cmd2 - ...)', using '-' as the separator between parts. If a 'button(...)' appears in a command chain (for example 'button(...); othercommand'), the following commands will be paused until the user selects one of the options. After a choice is made the chain resumes, and the command associated with the chosen option is appended to the chain (executed as if the user had entered it by pressing the button).
  - Supports the 'random {cmd1-cmd2-cmd3}' command. This runs a randomly selected command from the provided list.

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
