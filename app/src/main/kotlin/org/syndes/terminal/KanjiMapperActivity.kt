package org.syndes.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class KanjiMapperActivity : AppCompatActivity() {

    private lateinit var inputEditText: EditText
    private lateinit var encodeButton: Button
    private lateinit var decodeButton: Button

    // Hardcoded list of 95 unique kanji mapped to printable ASCII (32..126).
    // Order corresponds to ASCII 32 (space) .. 126 (~).
    private val kanjiList = listOf(
        "日","本","人","大","小","中","山","川","田","目",
        "耳","口","手","足","力","水","火","土","風","空",
        "海","天","心","愛","学","校","言","語","文","書",
        "話","行","来","見","食","飲","車","電","駅","家",
        "男","女","子","年","時","分","秒","新","古","長",
        "短","高","低","明","暗","赤","青","白","黒","金",
        "銀","銅","王","魚","鳥","犬","猫","虫","花","草",
        "林","森","石","走","立","座","起","泳","歩","歌",
        "泣","笑","喜","怒","怖","旅","宿","室","庭","店",
        "村","町","都","市","県"
    )

    // derived maps
    private val charToKanji: Map<Char, String> by lazy { buildCharToKanji() }
    private val kanjiToChar: Map<String, Char> by lazy { buildKanjiToChar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Protect from screenshots
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // matte background
        window.decorView.setBackgroundColor(Color.parseColor("#0A0A0A"))

        setContentView(R.layout.activity_kanji_mapper)

        inputEditText = findViewById(R.id.inputEditText)
        encodeButton = findViewById(R.id.encodeButton)
        decodeButton = findViewById(R.id.decodeButton)

        applyVisualStyle()

        // Convenience: try paste clipboard if empty
        pasteClipboardIfEmpty()

        encodeButton.setOnClickListener { doEncode() }
        decodeButton.setOnClickListener { doDecode() }
    }

    private fun applyVisualStyle() {
        val mono = Typeface.MONOSPACE
        inputEditText.typeface = mono
        inputEditText.setBackgroundColor(Color.parseColor("#0A0A0A"))
        inputEditText.setTextColor(Color.parseColor("#EEEEEE"))
        inputEditText.setHintTextColor(Color.parseColor("#666666"))
        inputEditText.setPadding(20, 20, 20, 20)
    }

    private fun pasteClipboardIfEmpty() {
        if (inputEditText.text.isNullOrBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    inputEditText.setText(text)
                    Toast.makeText(this, "Clipboard pasted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun doEncode() {
        val input = inputEditText.text.toString()
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter text...", Toast.LENGTH_SHORT).show()
            return
        }

        val unsupported = mutableSetOf<Char>()
        val sb = StringBuilder()

        for (c in input) {
            val mapped = charToKanji[c]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                // unsupported character
                unsupported.add(c)
            }
        }

        if (unsupported.isNotEmpty()) {
            val list = unsupported.joinToString(separator = " ") { it.toString() }
            Toast.makeText(this, "Unsupport simbols: $list", Toast.LENGTH_LONG).show()
            return
        }

        val out = sb.toString()
        inputEditText.setText(out)
        copyToClipboard(out)
        Toast.makeText(this, "Encoded", Toast.LENGTH_SHORT).show()
    }

    private fun doDecode() {
        val input = inputEditText.text.toString()
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter text...", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        val unsupported = mutableSetOf<String>()

        // iterate over Kotlin Chars — kanji are BMP and represented as single Char here
        for (ch in input) {
            val key = ch.toString()
            val mapped = kanjiToChar[key]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                unsupported.add(key)
            }
        }

        if (unsupported.isNotEmpty()) {
            // show small sample for debug
            val sample = unsupported.joinToString(limit = 10, truncated = "…")
            Toast.makeText(this, "Найдены неподдерживаемые иероглифы (декод невозможен): $sample", Toast.LENGTH_LONG).show()
            return
        }

        val out = sb.toString()
        inputEditText.setText(out)
        Toast.makeText(this, "Decoded", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("kanji-mapper", text)
        clipboard.setPrimaryClip(clip)
    }

    // build maps for ASCII 32..126
    private fun buildCharToKanji(): Map<Char, String> {
        val map = LinkedHashMap<Char, String>()
        val asciiRange = 32..126
        if (kanjiList.size < asciiRange.count()) {
            throw IllegalStateException("kanjiList must contain ${asciiRange.count()} items (found ${kanjiList.size})")
        }
        var i = 0
        for (code in asciiRange) {
            val ch = code.toChar()
            map[ch] = kanjiList[i]
            i++
        }
        return map
    }

    private fun buildKanjiToChar(): Map<String, Char> {
        val rev = HashMap<String, Char>()
        for ((k, v) in charToKanji) {
            // v is kanji string
            rev[v] = k
        }
        return rev
    }

    // Optional: expose the hardcoded base64 and bonus lists for reference (not used directly)
    companion object {
        // Base64 alphabet (for reference / documentation)
        const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        // Extra popular symbols we explicitly intended to cover in mapping
        const val BONUS_SYMBOLS = "+-,:/?=._@#%&*()[]{}<>;\"'\\|`~^$"
    }
}
