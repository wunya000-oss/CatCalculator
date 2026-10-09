package com.example.catcalc

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var calcContent: LinearLayout
    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView
    private lateinit var catOverlay: FrameLayout
    private lateinit var catImage: ImageView

    private var expression = ""
    private var typingAnimator: ValueAnimator? = null
    private var blurAnimator: ValueAnimator? = null
    private var currentBlur = 0f
    private var audioTrack: AudioTrack? = null
    private var catShown = false

    companion object {
        private const val SECRET = "16+5"
        private const val ERROR = "Ошибка"
        private const val OPERATORS = "+−×÷%"
        private const val SAMPLE_RATE = 22050
        private const val BLUR_MAX = 25f
        private val DIGIT_BG = Color.parseColor("#1F1F1F")
        private val OPERATOR_BG = Color.parseColor("#2C2C2C")
        private val EQUALS_BG = Color.parseColor("#1E7A6A")
        private val RED = Color.parseColor("#FF6F61")
        private val TEAL = Color.parseColor("#4DD0B8")
        private val GREY = Color.parseColor("#8E8E93")
        private val RIPPLE = Color.parseColor("#55FFFFFF")
        private val GLOW = Color.parseColor("#554DD0B8")
        private val GLOW_TEXT = Color.parseColor("#A8F0E0")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        calcContent = findViewById(R.id.calcContent)
        expressionView = findViewById(R.id.expression)
        resultView = findViewById(R.id.result)
        catOverlay = findViewById(R.id.catOverlay)
        catImage = findViewById(R.id.catImage)

        // тап по коту закрывает его
        catOverlay.setOnClickListener { hideCat() }

        buildTopBar(findViewById(R.id.topBar))
        buildKeys(findViewById(R.id.keys))
        showExpression(animateLast = false)
    }

    override fun onDestroy() {
        typingAnimator?.cancel()
        blurAnimator?.cancel()
        audioTrack?.release()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Верхний ряд как на фото: история слева, линейка и корень справа (пока только для вида)
    private fun buildTopBar(bar: LinearLayout) {
        bar.addView(iconView("↺"))
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(iconView("📏"))
        bar.addView(iconView("√"))
    }

    private fun iconView(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 24f
        setTextColor(GREY)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun buildKeys(container: LinearLayout) {
        container.clipChildren = false
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("( )", "0", ",", "=")
        )
        val size = dp(76)
        val gap = dp(12)

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            for (label in row) {
                val cell = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    clipChildren = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                cell.addView(makeKey(label, size))
                rowLayout.addView(cell)
            }
            container.addView(
                rowLayout,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, gap) }
            )
        }
    }

    // Кнопка: круг с ореолом, который появляется при нажатии
    private fun makeKey(label: String, size: Int): FrameLayout {
        val rest = if (label == "C" || label == "⌫") RED else Color.WHITE

        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GLOW)
            }
            alpha = 0f
        }

        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundFor(label))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }

        val key = TextView(this).apply {
            text = label
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(rest)
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask)
        }
        key.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressIn(key, halo)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressOut(key, halo, rest)
            }
            false
        }
        key.setOnClickListener { onKey(label) }

        return FrameLayout(this).apply {
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(halo, FrameLayout.LayoutParams(size, size))
            addView(key, FrameLayout.LayoutParams(size, size))
        }
    }

    // Нажатие: кнопка сжимается, ореол разгорается, текст становится бирюзовым
    private fun pressIn(key: TextView, halo: View) {
        key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        key.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .start()
        halo.animate()
            .alpha(1f)
            .scaleX(1.25f)
            .scaleY(1.25f)
            .setDuration(140)
            .start()
        tintText(key, key.currentTextColor, GLOW_TEXT)
    }

    // Отпускание: кнопка пружинит обратно, ореол гаснет
    private fun pressOut(key: TextView, halo: View, restColor: Int) {
        key.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()
        halo.animate()
            .alpha(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .start()
        tintText(key, key.currentTextColor, restColor)
    }

    private fun tintText(tv: TextView, from: Int, to: Int) {
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 160
            addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
            start()
        }
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
            "( )" -> {
                val base = if (expression == ERROR) "" else expression
                expression = base + parenthesisFor(base)
            }
            else -> expression = (if (expression == ERROR) "" else expression) + key
        }
        val typedOne = key != "=" && expression.length == previous.length + 1
        showExpression(animateLast = typedOne)
        updateResult()

        if (expression == SECRET) showCat()
    }

    // Умная скобка: закрывающая, если открыта и перед ней число, иначе открывающая
    private fun parenthesisFor(base: String): String {
        val open = base.count { it == '(' }
        val close = base.count { it == ')' }
        val last = base.lastOrNull()
        val needClose = open > close && last != null && (last.isDigit() || last == ')' || last == '%')
        return if (needClose) ")" else "("
    }

    private fun updateResult() {
        val preview = if (expression.isEmpty()) "" else evaluate(expression)
        resultView.text = if (preview == ERROR || preview == expression) "" else groupDigits(preview)
    }

    // "6 000 549" вместо "6000549"
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

    // Размытие калькулятора: на Android 12+ настоящий блюр, на старых — приглушение
    private fun animateBlur(to: Float, duration: Long) {
        blurAnimator?.cancel()
        blurAnimator = ValueAnimator.ofFloat(currentBlur, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                currentBlur = anim.animatedValue as Float
                applyBlur(currentBlur)
            }
            start()
        }
    }

    private fun applyBlur(radius: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            calcContent.setRenderEffect(
                if (radius < 0.5f) null
                else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        } else {
            calcContent.alpha = 1f - 0.6f * (radius / BLUR_MAX)
        }
    }

    // Кот поверх размытого калькулятора: появляется с пружинкой и мяукает
    private fun showCat() {
        if (catShown) return
        catShown = true

        catOverlay.visibility = View.VISIBLE
        catImage.animate().cancel()
        catImage.scaleX = 0.2f
        catImage.scaleY = 0.2f
        catImage.alpha = 0f
        catImage.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(650)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()

        animateBlur(BLUR_MAX, 350)
        playMeow()
    }

    // Кот уменьшается, размытие уходит, калькулятор очищается
    private fun hideCat() {
        if (!catShown) return
        catShown = false
        catImage.animate()
            .scaleX(0.2f)
            .scaleY(0.2f)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                catOverlay.visibility = View.GONE
                expression = ""
                showExpression(animateLast = false)
                updateResult()
            }
            .start()
        animateBlur(0f, 250)
    }

    private fun evaluate(input: String): String {
        var s = input
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace(",", ".")
        // убираем хвост, который не к чему применить, и дописываем недостающие скобки
        while (s.isNotEmpty() && s.last() in "+-*/.(") s = s.dropLast(1)
        val missing = s.count { it == '(' } - s.count { it == ')' }
        s += ")".repeat(maxOf(0, missing))
        if (s.isEmpty()) return ""
        return try {
            format(Parser(s).parse())
        } catch (e: Exception) {
            ERROR
        }
    }

    private fun format(d: Double): String {
        if (d.isNaN() || d.isInfinite()) throw ArithmeticException()
        return BigDecimal(d).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    // Разбор выражения: скобки, приоритет умножения, проценты
    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expr()
            if (pos != s.length) throw IllegalArgumentException()
            return value
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        private fun expr(): Double {
            var value = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val right = term()
                value = if (op == '+') value + right else value - right
            }
            return value
        }

        private fun term(): Double {
            var value = factor()
            while (peek() == '*' || peek() == '/') {
                val op = s[pos++]
                val right = factor()
                value = if (op == '*') {
                    value * right
                } else {
                    if (right == 0.0) throw ArithmeticException()
                    value / right
                }
            }
            return value
        }

        private fun factor(): Double {
            if (peek() == '-') {
                pos++
                return -factor()
            }
            var value = primary()
            while (peek() == '%') {
                pos++
                value /= 100
            }
            return value
        }

        private fun primary(): Double {
            if (peek() == '(') {
                pos++
                val value = expr()
                if (peek() != ')') throw IllegalArgumentException()
                pos++
                return value
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
    }

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
