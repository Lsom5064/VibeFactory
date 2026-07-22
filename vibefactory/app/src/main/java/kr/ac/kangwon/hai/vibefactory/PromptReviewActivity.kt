package kr.ac.kangwon.hai.vibefactory

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class PromptReviewActivity : AppCompatActivity() {
    private lateinit var promptEditor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPrompt = savedInstanceState?.getString(STATE_PROMPT)
            ?: intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.bg_app))
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
            setColorFilter(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_primary))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = getString(R.string.navigate_back)
            setOnClickListener { finish() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(56), dp(58)))
        val title = TextView(this).apply {
            text = getString(R.string.prompt_review_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_primary))
            setPadding(dp(14), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        promptEditor = EditText(this).apply {
            setText(initialPrompt)
            setSelection(text?.length ?: 0)
            gravity = Gravity.TOP or Gravity.START
            minLines = 14
            setTextColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_secondary))
            hint = getString(R.string.prompt_review_empty)
            textSize = 16f
            setLineSpacing(dp(5).toFloat(), 1.0f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = ContextCompat.getDrawable(this@PromptReviewActivity, R.drawable.bg_surface_card)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(
            promptEditor,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(dp(16), dp(4), dp(16), dp(12))
            }
        )

        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        val back = Button(this).apply {
            text = getString(R.string.prompt_review_back)
            setTextColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_primary))
            background = ContextCompat.getDrawable(this@PromptReviewActivity, R.drawable.bg_button_secondary)
            setOnClickListener { finish() }
        }
        footer.addView(back, LinearLayout.LayoutParams(0, dp(48), 1f))
        val send = Button(this).apply {
            text = getString(R.string.prompt_review_send)
            setTextColor(ContextCompat.getColor(this@PromptReviewActivity, R.color.text_inverse))
            background = ContextCompat.getDrawable(this@PromptReviewActivity, R.drawable.bg_button_primary)
            setOnClickListener {
                val editedPrompt = promptEditor.text?.toString()?.trim().orEmpty()
                if (editedPrompt.isBlank()) {
                    Toast.makeText(this@PromptReviewActivity, R.string.prompt_review_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                hideKeyboard()
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_TASK_ID, taskId)
                        .putExtra(EXTRA_PROMPT, editedPrompt)
                        .putExtra(EXTRA_MESSAGE_ID, messageId)
                )
                finish()
            }
        }
        footer.addView(
            send,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(10)
            }
        )
        root.addView(footer)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = systemBars.top,
                bottom = if (imeVisible) ime.bottom else systemBars.bottom
            )
            insets
        }
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROMPT, promptEditor.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(promptEditor.windowToken, 0)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        private const val STATE_PROMPT = "state_prompt"
    }
}
