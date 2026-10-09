package com.example.catcalc

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView
    private lateinit var catOverlay: FrameLayout
    private lateinit var catImage: ImageView

    private var expression = ""
    private var typingAnimator: ValueAnimator? = null
    private var audioTrack: AudioTrack? = null
    private var catShown = false

    companion object {
        private const val SECRET = "16+5"
        private const val ERROR = "Ошибка"
        private const val OPERATORS = "+−×÷%"
        private const val SAMPLE_RATE = 22050
        private val DIGIT_BG = Color.parseColor("#1F1F1F")
        private val OPERATOR_BG = Color.parseColor("#2C2C2C")
        private val EQUALS_BG = Color.parseColor("#1E7A6A")
        private val RED = Color.parseColor("#FF6F61")
        private val TEAL = Color.parseColor("#4DD0B8")
        private val RIPPLE = Color.parseColor("#55FFFFFF")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        expressionView = findViewById(R.id.expression)
        resultView = findViewById(R.id.result)
        catOverlay = findViewById(R.id.catOverlay)
        catImage = findViewById(R.id.catImage)

        // тап по коту закрывает его
        catOverlay.setOnClickListener { hideCat() }

        buildKeys(findViewById(R.id.keys))
        showExpression(animateLast = false)
    }

    override fun onDestroy() {
        typingAnimator?.cancel()
        audioTrack?.release()
        super.onDestroy()
    }

    private fun buildKeys(container: LinearLayout) {
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("", "0", ".", "=")
        )
        val density = resources.displayMetrics.density
        val size = (76 * density).toInt()
        val gap = (12 * density).toInt()

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in row) {
                val cell = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (label.isNotEmpty()) cell.addView(makeKey(label, size))
                rowLayout.addView(cell)
            }
            container.addView(
                rowLayout,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, gap) }
            )
        }
    }

    private fun makeKey(label: String, size: Int): TextView {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundFor(label))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return TextView(this).apply {
            text = label
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(if (label == "C" || label == "⌫") RED else Color.WHITE)
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnTouchListener { v, event -> onKeyTouch(v, event) }
            setOnClickListener { onKey(label) }
        }
    }

    // Анимация нажатия: кнопка сжимается, а при отпускании пружинит обратно
    private fun onKeyTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                v.animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
        }
        return false
    }

    private fun backgroundFor(label: String): Int = when (label) {
        "=" -> EQUALS_BG
        "÷", "×", "−", "+", "%" -> OPERATOR_BG
        else -> DIGIT_BG
    }

    private fun onKey(key: String) {
        val previous = expression
        when (key) {
            "C" -> expression = ""
            "⌫" -> expression = expression.dropLast(1)
            "=" -> expression = evaluate(expression)
            else -> expression = (if (expression == ERROR) "" else expression) + key
        }
        val typedOne = key != "=" && expression.length == previous.length + 1
        showExpression(animateLast = typedOne)
        updateResult()

        if (expression == SECRET) showCat()
    }

    private fun updateResult() {
        val preview = if (expression.isEmpty()) "" else evaluate(expression)
        resultView.text = if (preview == ERROR || preview == expression) "" else groupDigits(preview)
    }

    // "16 000 549" вместо "16000549"
    private fun groupDigits(s: String): String {
        val negative = s.startsWith("-")
        val body = if (negative) s.substring(1) else s
        val intPart = body.substringBefore('.')
        if (intPart.length <= 3 || intPart.any { !it.isDigit() }) return s
        val rest = body.substring(intPart.length)
        val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
        return (if (negative) "-" else "") + grouped + rest
    }

    // Новый символ появляется: сначала крупнее и прозрачнее, потом становится обычным
    private fun showExpression(animateLast: Boolean) {
        typingAnimator?.cancel()
        if (!animateLast || expression.isEmpty()) {
            expressionView.text = styled(expression, 1f)
            return
        }
        typingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                expressionView.text = styled(expression, anim.animatedValue as Float)
            }
            start()
        }
    }

    private fun styled(text: String, progress: Float): CharSequence {
        val sb = SpannableStringBuilder(text)
        for (i in text.indices) {
            val base = if (text[i] in OPERATORS) TEAL else Color.WHITE
            val isLast = i == text.length - 1
            val color = if (isLast) withAlpha(base, progress) else base
            sb.setSpan(ForegroundColorSpan(color), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (isLast && progress < 1f) {
                sb.setSpan(
                    RelativeSizeSpan(1.3f - 0.3f * progress),
                    i, i + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return sb
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // Кот на весь экран: появляется с пружинкой и мяукает
    private fun showCat() {
        if (catShown) return
        catShown = true

        catOverlay.alpha = 0f
        catOverlay.visibility = View.VISIBLE
        catOverlay.animate().alpha(1f).setDuration(250).start()

        catImage.scaleX = 0.2f
        catImage.scaleY = 0.2f
        catImage.alpha = 0f
        catImage.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(80)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()

        playMeow()
    }

    private fun hideCat() {
        if (!catShown) return
        catShown = false
        catOverlay.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                catOverlay.visibility = View.GONE
                expression = ""
                showExpression(animateLast = false)
                updateResult()
            }
            .start()
    }

    private fun evaluate(input: String): String {
        val tokens = Regex("\\d+\\.?\\d*|[+\\-*/]")
            .findAll(
                input.replace("×", "*").replace("÷", "/").replace("−", "-").replace("%", "/100")
            )
            .map { it.value }
            .toMutableList()
        while (tokens.isNotEmpty() && tokens.last() in setOf("+", "-", "*", "/")) {
            tokens.removeAt(tokens.size - 1)
        }
        if (tokens.isEmpty()) return ""
        return try {
            // сначала умножение и деление
            var i = 1
            while (i < tokens.size) {
                if (tokens[i] == "*" || tokens[i] == "/") {
                    val a = tokens[i - 1].toDouble()
                    val b = tokens[i + 1].toDouble()
                    if (tokens[i] == "/" && b == 0.0) throw ArithmeticException()
                    tokens[i - 1] = (if (tokens[i] == "*") a * b else a / b).toString()
                    tokens.removeAt(i)
                    tokens.removeAt(i)
                } else {
                    i += 2
                }
            }
            // потом сложение и вычитание
            var result = tokens[0].toDouble()
            i = 1
            while (i < tokens.size) {
                val b = tokens[i + 1].toDouble()
                result = if (tokens[i] == "+") result + b else result - b
                i += 2
            }
            format(result)
        } catch (e: Exception) {
            ERROR
        }
    }

    private fun format(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun playMeow() {
        val samples = makeMeow()
        audioTrack?.release()
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            samples.size * 2,
            AudioTrack.MODE_STATIC
        )
        track.write(samples, 0, samples.size)
        track.play()
        audioTrack = track
    }

    // Синтез "мяу": сначала голос поднимается ("мя"), потом плавно опускается ("ууу")
    private fun makeMeow(): ShortArray {
        val n = (SAMPLE_RATE * 1.1).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = if (t < 0.3) {
                480 + 380 * (t / 0.3)
            } else {
                860 - 420 * ((t - 0.3) / 0.7)
            }
            phase += 2 * PI * freq / SAMPLE_RATE
            val attack = min(1.0, t / 0.05)
            val release = min(1.0, (1 - t) / 0.25)
            val vibrato = 1 + 0.02 * sin(2 * PI * 7 * i / SAMPLE_RATE)
            val wave = sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)
            out[i] = (wave * attack * release * vibrato * 0.22 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
