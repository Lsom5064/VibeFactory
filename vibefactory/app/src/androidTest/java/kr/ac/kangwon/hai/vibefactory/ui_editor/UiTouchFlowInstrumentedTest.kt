package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import kr.ac.kangwon.hai.vibefactory.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in E2E against an isolated API, DB and separately installed host package. */
@RunWith(AndroidJUnit4::class)
class UiTouchFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val taskId get() = InstrumentationRegistry.getArguments().getString("touchTaskId")
    private lateinit var activity: UiAnnotationEditorActivity

    private fun guard() {
        assumeTrue(!taskId.isNullOrBlank())
        check(context.packageName.endsWith(".touchhelpverify"))
        check(HostAppConfig.BASE_URL.startsWith("http://127.0.0.1:18090"))
        HostPreferencesStore(context, Gson(), "touch-test").savePhoneNumber("01000000000")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    @Test fun tapToolsBatchDeleteMoveReflowBehaviorAndSketchSaveThroughApi() {
        guard()
        ActivityScenario.launch<UiAnnotationEditorActivity>(Intent(context, UiAnnotationEditorActivity::class.java)
            .putExtra(UiAnnotationEditorActivity.EXTRA_TASK_ID, taskId)
            .putExtra(UiAnnotationEditorActivity.EXTRA_REVISION_LABEL, "rev_0001")
            .putExtra(UiAnnotationEditorActivity.EXTRA_APP_NAME, "터치 편집 검증")).use { scenario ->
            scenario.onActivity { activity = it }
            await { main { activity.findViewById<View>(R.id.uiAnnotationStateOverlay).visibility == View.GONE } }
            screenshot("01_original")
            tapId(R.id.btnUiAnnotationDeleteTool)
            tapTag("id:deleteA"); tapTag("id:deleteB")
            assertEquals(2, main { session().selectedDeleteTargets.size })
            tapTag("id:deleteB")
            assertEquals(1, main { session().selectedDeleteTargets.size })
            tapTag("id:deleteB")
            scenario.recreate(); scenario.onActivity { activity = it }
            await { main { session().selectedDeleteTargets.size == 2 } }
            screenshot("02_batch_selection")
            tapId(R.id.btnUiAnnotationDeleteSelected)
            await { main { session().annotations.count { it.action == UiAnnotationAction.DELETE } == 2 } }
            tapId(R.id.btnUiAnnotationUndo)
            assertTrue(main { session().annotations.isEmpty() })
            tapId(R.id.btnUiAnnotationRedo)
            assertEquals(2, main { session().annotations.size })
            screenshot("03_batch_applied")

            val sourceBefore = tagBounds("id:sourceAction")
            val targetBefore = tagBounds("id:destinationRow")
            tapId(R.id.btnUiAnnotationMoveTool); tapTag("id:sourceAction")
            drag(sourceBefore.centerX().toFloat(), sourceBefore.centerY().toFloat(),
                targetBefore.centerX().toFloat(), targetBefore.centerY().toFloat()) {
                assertEquals(sourceBefore, tagBounds("id:sourceAction"))
                assertTrue(tagBounds("id:destinationRow").top > targetBefore.top)
                screenshot("04_move_drag_reflow")
            }
            await { textNode("이동 요청 설명") != null }
            tapText("표시 추가")
            await { main { session().annotations.any { it.action == UiAnnotationAction.MOVE } } }
            assertEquals(sourceBefore, tagBounds("id:sourceAction"))
            assertTrue(main { session().annotations.first { it.action == UiAnnotationAction.MOVE }.instruction.isBlank() })
            assertEquals("id:destinationRow", main { session().annotations.first { it.action == UiAnnotationAction.MOVE }.destination?.stableId })
            screenshot("05_move_saved")

            tapId(R.id.btnUiAnnotationBehaviorTool); tapTag("id:behaviorAction")
            await { textNode("기능 변경 설명") != null }
            setText("버튼을 누르면 저장한 메모를 보여줘.")
            tapText("표시 추가")
            await { main { session().annotations.any { it.action == UiAnnotationAction.BEHAVIOR } } }

            tapId(R.id.btnUiAnnotationAddTool)
            val canvas = idBounds(R.id.uiAnnotationCanvas)
            tap(canvas.left + canvas.width() * .65f, canvas.top + 370f * context.resources.displayMetrics.density)
            await { main { session().pendingAddition != null } }
            val before = main { session().pendingAddition!!.addition!!.bounds }
            tap(canvas.left + canvas.width() * before.left, canvas.top + 640f * context.resources.displayMetrics.density * before.top)
            tap(canvas.left + canvas.width() * (before.left + .04f), canvas.top + 640f * context.resources.displayMetrics.density * (before.top + .04f))
            assertNotEquals(before, main { session().pendingAddition!!.addition!!.bounds })
            tapId(R.id.btnUiAnnotationDraw)
            await { textNode("표시 저장") != null }
            val sketch = awaitNode { nodes().firstOrNull { it.viewIdResourceName?.endsWith("/uiAdditionSketchCanvas") == true } }
            val sketchBounds = Rect().also(sketch::getBoundsInScreen)
            drag(sketchBounds.left + sketchBounds.width() * .15f, sketchBounds.top + sketchBounds.height() * .35f,
                sketchBounds.left + sketchBounds.width() * .85f, sketchBounds.top + sketchBounds.height() * .35f)
            val input = awaitNode { nodes().firstOrNull { it.viewIdResourceName?.endsWith("/uiAdditionInstruction") == true } }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "메모 입력칸과 저장 버튼을 추가해줘.")
            })
            screenshot("06_addition_sketch")
            tapText("표시 저장")
            await { main { session().annotations.any { it.action == UiAnnotationAction.ADD } } }
            screenshot("07_all_tools_saved")
            tapId(R.id.btnUiAnnotationSave)
            await { main { activity.isFinishing || activity.isDestroyed } }
        }
    }

    @Test fun helpRestoresFromLogAfterHidingAndColdRestart() {
        guard()
        val generatedPackage = "kr.ac.kangwon.hai.generated.baseproject.debug"
        val launch = context.packageManager.getLaunchIntentForPackage(generatedPackage)
        requireNotNull(launch) { "Install the generated template debug APK first" }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { textNode("도움말 버튼 숨기기") != null || nodes().any { it.contentDescription == "사용법 다시 보기" } }
        if (textNode("도움말 버튼 숨기기") == null) {
            val help = nodes().first { it.contentDescription == "사용법 다시 보기" }
            tapNode(help)
        }
        tapText("도움말 버튼 숨기기")
        SystemClock.sleep(500)
        automation.executeShellCommand("am force-stop $generatedPackage").close()
        SystemClock.sleep(300)
        context.startActivity(launch)
        await { nodes().any { it.packageName == generatedPackage } }
        SystemClock.sleep(500)
        assertFalse(nodes().any { it.contentDescription == "사용법 다시 보기" && it.isVisibleToUser })
        screenshot("08_help_hidden_after_restart")
        val payload = TaskLogDetailPayload("터치 편집 검증 · 작업 로그", "터치 편집 검증", taskId!!,
            "완료", "success", "UI 검증", emptyList(), emptyList(),
            TaskLogApkAction(taskId!!, "검증 앱 APK", "설치된 검증 앱", null, null, null), generatedPackage)
        ActivityScenario.launch<TaskLogDetailActivity>(Intent(context, TaskLogDetailActivity::class.java)
            .putExtra(TaskLogDetailActivity.EXTRA_PAYLOAD, Gson().toJson(payload))).use {
            await { textNode("도움말 다시 표시") != null }
            screenshot("09_log_restore_button")
            tapText("도움말 다시 표시")
            await { nodes().any { it.contentDescription == "사용법 다시 보기" && it.isVisibleToUser } }
            screenshot("10_help_restored")
        }
    }

    private fun session() = ViewModelProvider(activity)[UiAnnotationViewModel::class.java].session!!
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun idBounds(id: Int) = main { Rect().also { activity.findViewById<View>(id).getGlobalVisibleRect(it) } }
    private fun tagBounds(tag: String) = main { Rect().also { activity.findViewById<View>(R.id.uiAnnotationCanvas).findViewWithTag<View>(tag).getGlobalVisibleRect(it) } }
    private fun tapId(id: Int) = idBounds(id).let { tap(it.exactCenterX(), it.exactCenterY()) }
    private fun tapTag(tag: String) = tagBounds(tag).let { tap(it.exactCenterX(), it.exactCenterY()) }
    private fun tap(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also {
                assertTrue("Touch injection failed", automation.injectInputEvent(it, true)); it.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(30)
        }
        SystemClock.sleep(250)
    }
    private fun drag(x: Float, y: Float, endX: Float, endY: Float, beforeUp: () -> Unit = {}) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0).also {
                assertTrue("Touch injection failed", automation.injectInputEvent(it, true)); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..8) { send(MotionEvent.ACTION_MOVE, x + (endX - x) * i / 8, y + (endY - y) * i / 8); SystemClock.sleep(25) }
        SystemClock.sleep(100); beforeUp(); send(MotionEvent.ACTION_UP, endX, endY); SystemClock.sleep(150)
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out waiting for UI" }; SystemClock.sleep(100) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; add(node); for (i in 0 until node.childCount) visit(node.getChild(i)) }
        visit(automation.rootInActiveWindow)
    }
    private fun textNode(text: String) = nodes().firstOrNull { it.text?.toString() == text && it.isVisibleToUser }
    private fun awaitNode(find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo { await { find() != null }; return find()!! }
    private fun tapNode(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).let { tap(it.exactCenterX(), it.exactCenterY()) }
    private fun tapText(text: String) = tapNode(awaitNode { textNode(text) })
    private fun setText(text: String) {
        val input = awaitNode { nodes().firstOrNull { it.className == EditText::class.java.name && it.isEditable } }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "touch_validation/$name.png")
        file.parentFile!!.mkdirs()
        automation.takeScreenshot().useBitmap { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}
