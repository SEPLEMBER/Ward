package org.syndes.terminal

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

class MatrixActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity_matrix.xml содержит наш MatrixView
        setContentView(R.layout.activity_matrix)

        // close button (optional) — в xml на top-left
        val closeBtn = findViewById<ImageButton?>(R.id.matrixCloseBtn)
        closeBtn?.setOnClickListener {
            finish()
        }
    }

    override fun onBackPressed() {
        // просто закрываем
        finish()
    }
}

/**
 * Простая view, рисующая падающие символы (0/1) с эффектом "Matrix rain".
 * Параметры:
 * - размер шрифта определяется экраном (примерно 14-28sp).
 * - цвет можно выбрать через Intent extra "color" == "blue" для синего.
 */
class MatrixView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rand = Random(System.currentTimeMillis())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private var columns = 0
    private var charSize = 0f
    private var positions = FloatArray(0)
    private var speeds = FloatArray(0)
    private var columnChars: Array<CharArray> = arrayOf()
    private var lastTime = System.nanoTime()
    private val chars = charArrayOf('0', '1') // base characters
    private val extraChars = charArrayOf('0','1','0','1','1','0') // bias to 0/1
    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        paint.color = Color.parseColor("#00CC33") // default green
        paint.typeface = Typeface.MONOSPACE
        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false

        headPaint.color = Color.WHITE // head highlighted (may look bright)
        headPaint.typeface = Typeface.MONOSPACE
        headPaint.isFakeBoldText = true

        // fade paint used to draw translucent rect to create tail effect
        fadePaint.color = Color.argb(60, 0, 0, 0) // semi-transparent black

        // allow color override by Intent (activity that creates view can pass color via getIntent)
        // but since this View is created from XML, we can check context if it's Activity and extras exist.
        try {
            val act = context as? android.app.Activity
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // determine char size based on width: choose a readable monospace size
        // pick charSize so columns count ~ w/charSize is reasonable (30-80)
        val targetColumns = max(20, (w / resources.displayMetrics.density / 10).toInt())
        charSize = (w.toFloat() / targetColumns).coerceAtLeast(14f * resources.displayMetrics.density / 1f)
        paint.textSize = charSize
        headPaint.textSize = charSize * 1.05f

        val fm = paint.fontMetricsInt
        // columns count
        val cw = paint.measureText("0")
        columns = max(1, (w / cw).toInt())

        positions = FloatArray(columns)
        speeds = FloatArray(columns)
        columnChars = Array(columns) { CharArray((h / charSize + 10).toInt()) }

        for (i in 0 until columns) {
            positions[i] = rand.nextFloat() * h
            speeds[i] = (charSize * (0.5f + rand.nextFloat() * 2.0f))
            val rows = columnChars[i].size
            for (r in 0 until rows) {
                columnChars[i][r] = extraChars[rand.nextInt(extraChars.size)]
            }
        }

        lastTime = System.nanoTime()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = (now - lastTime) / 1_000_000_000f
        lastTime = now

        // Draw translucent rect to produce trailing effect
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)

        // draw each column: iterate columns -> draw sequence of chars downward
        val cw = paint.measureText("0")
        val rowsOnScreen = (height / charSize).toInt() + 2

        for (i in 0 until columns) {
            val x = i * cw
            // update column head position
            positions[i] += speeds[i] * dt * 60f // scaled to feel consistent
            if (positions[i] > height + rowsOnScreen * charSize) {
                // reset with a random offset above top
                positions[i] = -rand.nextInt(0, rowsOnScreen) * charSize.toFloat()
                speeds[i] = (charSize * (0.6f + rand.nextFloat() * 2.2f))
            }

            // draw chars from head upwards for a few rows
            var y = positions[i]
            // head (bright) draw one
            val headChar = extraChars[rand.nextInt(extraChars.size)]
            canvas.drawText(headChar.toString(), x, y, headPaint)

            // draw tail behind head
            var intensityFactor = 0.9f
            var r = 1
            while (true) {
                val yy = y - r * charSize
                if (yy < -charSize) break
                val ch = extraChars[(rand.nextInt(extraChars.size))]
                val alpha = (120 * intensityFactor).toInt().coerceIn(10, 255)
                paint.alpha = alpha
                canvas.drawText(ch.toString(), x, yy, paint)
                intensityFactor *= 0.85f
                r++
                // limit tail length
                if (r > rowsOnScreen / 3) break
            }
        }

        // schedule next frame
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (dx < touchSlop && dy < touchSlop) {
                    // simple tap -> finish activity (if context is activity)
                    try {
                        val act = context as? android.app.Activity
                        act?.finish()
                    } catch (_: Throwable) {}
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
