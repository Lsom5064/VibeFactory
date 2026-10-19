package kr.ac.kangwon.hai.vibefactory

import android.app.Activity
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Empty log Activity and synthetic prompts only: never submits a real generation request. */
@RunWith(AndroidJUnit4::class)
class PromptReviewFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val selection = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
    private fun review(prompt: String) = ChatMessage("synthetic-review", MessageKind.CONFIRMATION, "확인", prompt,
        confirmAction = "submit_initial_prompt", promptReviewTaskId = "synthetic-review-task", promptReviewText = prompt)
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()

    @Test fun onlyOneReviewActionIsShownForBothShortAndLongDrafts() {
        var opened = 0
        ActivityScenario.launch<TaskLogDetailActivity>(Intent(context, TaskLogDetailActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val frame = FrameLayout(activity); activity.setContentView(frame)
                val adapter = ChatMessageAdapter(selection, { it }, { false }, {}, {}, { opened++ }, {}, {}, {})
                val holder = adapter.onCreateViewHolder(frame, 0); frame.addView(holder.itemView)
                for (prompt in listOf("짧은 요청", "긴 앱 요청 내용\n".repeat(100))) {
                    holder.bind(review(prompt))
                    val action = holder.itemView.findViewById<TextView>(R.id.messageExpandToggle)
                    assertEquals(View.VISIBLE, action.visibility)
                    assertEquals("내용 확인·수정", action.text.toString())
                    assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.messageConfirmationActions).visibility)
                    action.performClick()
                }
                assertEquals(2, opened)
            }
        }
    }

    @Test fun sentContentOpensReadOnlyAndCannotStartAnotherBuild() {
        val prompt = "사용자가 수정한 최종 요청\n버튼과 글씨를 크게 만들어 주세요."
        var editingOpened = false
        val monitor = instrumentation.addMonitor(FullMessageActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<TaskLogDetailActivity>(Intent(context, TaskLogDetailActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val frame = FrameLayout(activity); activity.setContentView(frame)
                    val adapter = ChatMessageAdapter(selection, { it }, { false }, {}, {}, { editingOpened = true }, {}, {}, {})
                    val holder = adapter.onCreateViewHolder(frame, 0); frame.addView(holder.itemView)
                    holder.bind(PromptReviewMessagePolicy.withSubmittedPrompt(review("초안"), prompt, "전송한 내용"))
                    val action = holder.itemView.findViewById<TextView>(R.id.messageExpandToggle)
                    assertEquals("전송한 내용 보기", action.text.toString())
                    assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.messageConfirmationActions).visibility)
                    action.performClick()
                }
                val full = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Sent content must open the read-only viewer", full)
                instrumentation.runOnMainSync {
                    assertFalse(editingOpened)
                    val views = nodes(full.window.decorView)
                    assertFalse(views.any { it is EditText })
                    assertFalse(views.filterIsInstance<Button>().any { it.text.toString() == "이 내용으로 앱 만들기" })
                    assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "전송한 앱 요청 내용" })
                    assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("사용자가 수정한 최종 요청") })
                    full.finish()
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun editAndBuildActionReturnsExactlyTheEditedTextAndOriginalCardIdentity() {
        val intent = Intent(context, PromptReviewActivity::class.java)
            .putExtra(PromptReviewActivity.EXTRA_TASK_ID, "synthetic-review-task")
            .putExtra(PromptReviewActivity.EXTRA_MESSAGE_ID, "original-card")
            .putExtra(PromptReviewActivity.EXTRA_PROMPT, "원래 요청")
        val finalPrompt = "최종 앱 요청\n줄바꿈과 수정한 내용을 그대로 유지해 주세요."
        ActivityScenario.launchActivityForResult<PromptReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val views = nodes(activity.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "앱 요청 내용 확인" })
                views.filterIsInstance<EditText>().single().setText(finalPrompt)
                val send = views.filterIsInstance<Button>().single { it.text.toString() == "이 내용으로 앱 만들기" }
                assertTrue(send.performClick())
            }
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(finalPrompt, result.resultData.getStringExtra(PromptReviewActivity.EXTRA_PROMPT))
            assertEquals("original-card", result.resultData.getStringExtra(PromptReviewActivity.EXTRA_MESSAGE_ID))
            assertEquals("synthetic-review-task", result.resultData.getStringExtra(PromptReviewActivity.EXTRA_TASK_ID))
        }
    }
}
