package kr.ac.kangwon.hai.vibefactory

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class FullMessageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val titleText = intent.getStringExtra(EXTRA_TITLE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.message_full_view_title)
        val bodyText = intent.getStringExtra(EXTRA_BODY).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@FullMessageActivity, R.color.bg_app))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(20), dp(12))
        }

        val backButton = ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_top_chip)
            setImageResource(R.drawable.ic_arrow_back_settings)
            setColorFilter(ContextCompat.getColor(this@FullMessageActivity, R.color.text_primary))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = getString(R.string.navigate_back)
            setOnClickListener { finish() }
        }
        header.addView(
            backButton,
            LinearLayout.LayoutParams(dp(56), dp(58))
        )
        val title = TextView(this).apply {
            text = titleText
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@FullMessageActivity, R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(dp(14), 0, 0, 0)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val body = TextView(this).apply {
            text = ChatMarkdownRenderer.render(this@FullMessageActivity, bodyText)
            textSize = 17f
            setLineSpacing(dp(8).toFloat(), 1.0f)
            setTextColor(ContextCompat.getColor(this@FullMessageActivity, R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(dp(26), dp(22), dp(26), dp(40))
        }
        val scrollView = ScrollView(this).apply {
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
    }
}
