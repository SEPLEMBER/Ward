package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.random.Random

/**
 * MatrixActivity — холдер для MatrixView. Управляет lifecycle (pause/resume),
 * чтобы анимация не работала, когда Activity не видна.
 */
class MatrixActivity : AppCompatActivity() {

    private var matrixViewRef: MatrixView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_matrix)

        matrixViewRef = findViewById(R.id.matrixView) // предполагается, что в layout установлен id="@+id/matrixView"

        // close button (optional) — в xml на top-left
        val closeBtn = findViewById<ImageButton?>(R.id.matrixCloseBtn)
        closeBtn?.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        matrixViewRef?.resume()
    }

    override fun onPause() {
        // останавливаем анимацию — существенно экономит CPU/батарею, когда Activity не видна
        matrixViewRef?.pause()
        super.onPause()
    }

    override fun onBackPressed() {
        finish()
    }
}

/**
 * MatrixView — рисует "rain" из 0/1.
 *
 * Оптимизации:
 *  - управление циклом кадров через postDelayed (targetFps)
 *  - при pause() анимация останавливается (нет вызовов invalidate)
 *  - минимальные аллокации в onDraw: переиспользование CharArray(1) и заранее заполненных columnChars
 *  - меньше случайных операций в onDraw: изменяем индекс (offset) для выбора символа в столбце
 */
class MatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rand = Random(System.currentTimeMillis())

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()

    // layout / geometry
    private var columns = 0
    private var charSize = 24f
    private var cw = 12f

    // column state
    private var positions = FloatArray(0)     // current head y pos per column
    private var speeds = FloatArray(0)        // speed per column (pixels/sec)
    private var columnChars: Array<CharArray> = arrayOf() // per-column ring buffer of chars
    private var columnIndex: IntArray = intArrayOf() // current head index into columnChars per column

    // small reusable char buffer to avoid allocations
    private val tmpChar = CharArray(1)

    // control
    @Volatile
    private var running = false
    private var targetFpsActive = 30      // frames per second while active (feel free to tune)
    private var frameDelayMs: Long = (1000L / targetFpsActive)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    // characters used (bias toward 0/1)
    private val extraChars = charArrayOf('0', '1', '0', '1', '1', '0')

    // runnable for frame loop
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            // use postDelayed to control FPS precisely
            postDelayed(this, frameDelayMs)
        }
    }

    init {
        paint.color = Color.parseColor("#00CC33") // default green
        paint.typeface = Typeface.MONOSPACE
        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false

        headPaint.color = Color.WHITE
        headPaint.typeface = Typeface.MONOSPACE
        headPaint.isFakeBoldText = true

        fadePaint.color = Color.argb(60, 0, 0, 0)

        // allow activity to pass "color" extra to pick blue/green
        try {
            val act = context as? Activity
            val col = act?.intent?.getStringExtra("color")
            if (col != null && col.equals("blue", ignoreCase = true)) {
                paint.color = Color.parseColor("#4FC3F7")
                headPaint.color = Color.WHITE
            } else if (col != null && col.equals("green", ignoreCase = true)) {
                paint.color = Color.parseColor("#00FF66")
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Resume animation (call from Activity.onResume)
     */
    fun resume() {
        if (running) return
        running = true
        // ensure a reasonable FPS (can be adjusted)
        frameDelayMs = (1000L / targetFpsActive)
        removeCallbacks(frameRunnable)
        post(frameRunnable)
    }

    /**
     * Pause animation (call from Activity.onPause)
     */
    fun pause() {
        running = false
        removeCallbacks(frameRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // if view attached and Activity visible, start — activity will explicitly call resume() as well
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w <= 0 || h <= 0) return

        // choose char size such that columns ~ 30..80 depending on width
        val density = resources.displayMetrics.density
        val approxColumns = max(20, (w / (10 * density)).toInt())
        charSize = (w.toFloat() / approxColumns).coerceAtLeast(14f * density)
        paint.textSize = charSize
        headPaint.textSize = charSize * 1.05f

        cw = paint.measureText("0")
        columns = max(1, (w / cw).toInt())

        // create buffers
        positions = FloatArray(columns)
        speeds = FloatArray(columns)
        columnIndex = IntArray(columns)
        // rows buffer per column: number of possible rows + some margin
        val rows = (h / charSize + 12).toInt().coerceAtLeast(8)
        columnChars = Array(columns) { CharArray(rows) }

        // init columns
        for (i in 0 until columns) {
            positions[i] = rand.nextFloat() * h.toFloat()
            speeds[i] = (charSize * (0.6f + rand.nextFloat() * 1.6f)) // pixels per frame base scale
            columnIndex[i] = rand.nextInt(0, rows)
            for (r in 0 until rows) {
                columnChars[i][r] = extraChars[rand.nextInt(extraChars.size)]
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // If not running, draw one static frame and return (no scheduling)
        // But usually Activity will stop calling onDraw when paused because we stop frameRunnable
        // Draw translucent rect to produce trailing effect
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)

        val rowsOnScreen = (height / charSize).toInt() + 2

        // speed scaling: use small time step approximation rather than measuring dt to avoid heavy calls
        // We update positions by a per-frame value based on speeds[] (speeds already in px per frame-ish)
        for (i in 0 until columns) {
            val x = i * cw

            // advance head position
            positions[i] += speeds[i] * 0.6f // tuning factor - gives visually pleasing movement
            val resetThreshold = height + rowsOnScreen * charSize
            if (positions[i] > resetThreshold) {
                // reset head above screen by random offset to introduce variation
                positions[i] = -rand.nextInt(0, rowsOnScreen) * charSize.toFloat()
                // refresh speed and refill char column occasionally
                speeds[i] = (charSize * (0.5f + rand.nextFloat() * 1.8f))
                // shuffle column contents a bit without allocating
                val col = columnChars[i]
                for (k in col.indices) {
                    if (rand.nextFloat() < 0.12f) col[k] = extraChars[rand.nextInt(extraChars.size)]
                }
            }

            // compute head row index (approx)
            val headRowFloat = positions[i] / charSize
            val headRow = headRowFloat.toInt()
            // rotate index for column so characters appear to "cycle" — cheaper than calling random each time for every drawn char
            columnIndex[i] = (columnIndex[i] + 1) % columnChars[i].size

            // draw head char (bright)
            val headChar = columnChars[i][(columnIndex[i] + headRow) % columnChars[i].size]
            tmpChar[0] = headChar
            canvas.drawText(tmpChar, 0, 1, x, positions[i], headPaint)

            // draw tail behind head with fading alpha
            var intensityFactor = 0.9f
            var r = 1
            while (true) {
                val yy = positions[i] - r * charSize
                if (yy < -charSize) break
                // pick char from column ring buffer in descending order
                val idx = (columnIndex[i] + headRow - r).let { if (it >= 0) it % columnChars[i].size else (columnChars[i].size + (it % columnChars[i].size)) % columnChars[i].size }
                val ch = columnChars[i][idx]
                tmpChar[0] = ch
                val alpha = (160 * intensityFactor).toInt().coerceIn(10, 255)
                paint.alpha = alpha
                canvas.drawText(tmpChar, 0, 1, x, yy, paint)
                intensityFactor *= 0.80f
                r++
                if (r > rowsOnScreen / 3) break
            }
        }

        // If running is true, next frame is scheduled by frameRunnable via postDelayed.
        // When not running, no further frames will be scheduled (pause()/onPause stops them).
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx < touchSlop && dy < touchSlop) {
                    // simple tap -> finish activity (if context is activity)
                    try {
                        val act = context as? Activity
                        act?.finish()
                    } catch (_: Throwable) {}
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
