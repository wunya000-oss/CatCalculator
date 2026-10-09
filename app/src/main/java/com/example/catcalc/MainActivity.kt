package com.example.catcalc

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private lateinit var catImage: ImageView
    private var expression = ""
    private var audioTrack: AudioTrack? = null

    companion object {
        private const val SECRET = "16+5"
        private const val ERROR = "Ошибка"
        private const val SAMPLE_RATE = 22050
        private val DIGIT_BG = Color.parseColor("#1C1C1C")
        private val OPERATOR_BG = Color.parseColor("#2B2B2B")
        private val EQUALS_BG = Color.parseColor("#1E7A6A")
        private val RED = Color.parseColor("#FF6F61")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        display = findViewById(R.id.display)
        catImage = findViewById(R.id.catImage)
        buildKeys(findViewById(R.id.keys))
    }

    override fun onDestroy() {
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
        val size = (76 * resources.displayMetrics.density).toInt()
        val gap = (12 * resources.displayMetrics.density).toInt()

        for (row in rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            for (label in row) {
                val cell = LinearLayout(this)
                cell.gravity = Gravity.CENTER
                cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (label.isNotEmpty()) {
                    val button = Button(this)
                    button.text = label
                    button.textSize = 26f
                    button.setTextColor(if (label == "C" || label == "⌫") RED else Color.WHITE)
                    button.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(backgroundFor(label))
                    }
                    button.minWidth = 0
                    button.minHeight = 0
                    button.setPadding(0, 0, 0, 0)
                    button.layoutParams = LinearLayout.LayoutParams(size, size)
                    button.setOnClickListener { onKey(label) }
                    cell.addView(button)
                }
                rowLayout.addView(cell)
            }
            container.addView(
                rowLayout,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, gap) }
            )
        }
    }

    private fun backgroundFor(label: String): Int = when (label) {
        "=" -> EQUALS_BG
        "÷", "×", "−", "+", "%" -> OPERATOR_BG
        else -> DIGIT_BG
    }

    private fun onKey(key: String) {
        when (key) {
            "C" -> expression = ""
            "⌫" -> expression = expression.dropLast(1)
            "=" -> expression = evaluate(expression)
            else -> expression = (if (expression == ERROR) "" else expression) + key
        }
        display.text = expression.ifEmpty { "0" }

        if (expression == SECRET) {
            catImage.visibility = View.VISIBLE
            playMeow()
        } else if (key != "=") {
            catImage.visibility = View.GONE
        }
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

    // Синтез "мяу": тон плавно поднимается и опускается, с лёгким вибрато
    private fun makeMeow(): ShortArray {
        val n = (SAMPLE_RATE * 0.9).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = 550 + 350 * sin(PI * t) - 120 * t
            phase += 2 * PI * freq / SAMPLE_RATE
            val envelope = sin(PI * t)
            val vibrato = 1 + 0.02 * sin(2 * PI * 25 * i / SAMPLE_RATE)
            val wave = sin(phase) + 0.4 * sin(2 * phase) + 0.2 * sin(3 * phase)
            out[i] = (wave * envelope * vibrato * 0.25 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
