package dev.inkstone.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(246, 247, 251))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.home_title)
            textSize = 30f
            setTextColor(Color.rgb(38, 46, 77))
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.home_subtitle)
            textSize = 16f
            setTextColor(Color.rgb(92, 101, 126))
            setPadding(0, dp(8), 0, dp(28))
        })
        root.addView(actionButton(getString(R.string.home_enable)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(actionButton(getString(R.string.home_choose)) {
            val inputMethodManager =
                getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            inputMethodManager.showInputMethodPicker()
        }.apply {
            setBackgroundColor(Color.rgb(226, 230, 243))
            setTextColor(Color.rgb(42, 53, 92))
        }, layoutParams().apply { topMargin = dp(10) })
        root.addView(TextView(this).apply {
            text = getString(R.string.home_instructions)
            textSize = 14f
            setTextColor(Color.rgb(94, 101, 119))
            setPadding(0, dp(26), 0, 0)
        })
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(83, 104, 213))
        setOnClickListener { action() }
    }

    private fun layoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(52)
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
