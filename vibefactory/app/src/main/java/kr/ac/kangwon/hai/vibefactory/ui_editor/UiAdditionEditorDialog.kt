package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

internal class UiAdditionEditorDialog(
    context: Context,
    initial: UiAnnotation,
    original: Bitmap?,
    regionAspectRatio: Float,
    replacementCandidates: List<UiAnnotationTarget>,
    private val onDraftChanged: (UiAnnotation) -> Unit,
    private val onBackToRegion: () -> Unit,
    private val onSave: (UiAnnotation, UiAdditionEditorDialog) -> Unit
) : Dialog(context) {
    private var draft = initial
    private val save: Button
    private val drawing: UiSketchCanvasView
    init {
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
        root.addView(TextView(context).apply { text = "추가할 UI 그리기"; textSize = 21f })
        root.addView(TextView(context).apply { text = "초록 영역 안에 원하는 모습을 그리고, 필요한 동작을 설명해 주세요."; textSize = 13f })
        drawing = UiSketchCanvasView(context).apply {
            id = kr.ac.kangwon.hai.vibefactory.R.id.uiAdditionSketchCanvas
            backgroundColorValue = requireNotNull(initial.addition).backgroundColor
            aspectRatio = regionAspectRatio
            this.original = original
            restore(initial.addition.strokes)
            changed = { update(draft.copy(addition = requireNotNull(draft.addition).copy(strokes = it))) }
        }
        root.addView(drawing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val tools = LinearLayout(context)
        fun tool(text: String, action: () -> Unit): Button = Button(context).apply {
            this.text = text; textSize = 12f; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0); setOnClickListener { action() }
            tools.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val pen = tool("✓ 펜") { drawing.erasing = false }
        val eraser = tool("지우개") { drawing.erasing = true }
        pen.setOnClickListener { drawing.erasing = false; pen.text = "✓ 펜"; eraser.text = "지우개" }
        eraser.setOnClickListener { drawing.erasing = true; eraser.text = "✓ 지우개"; pen.text = "펜" }
        tool("되돌리기") { drawing.undo() }
        tool("다시하기") { drawing.redo() }
        tool("원본 보기") {}.setOnTouchListener { view, event ->
            drawing.showOriginal = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        root.addView(tools)
        val replace = CheckBox(context).apply {
            text = "이 영역의 기존 UI 교체"
            isEnabled = replacementCandidates.isNotEmpty()
            isChecked = initial.addition?.replaceTargets?.isNotEmpty() == true
        }
        root.addView(replace)
        val affected = TextView(context).apply { textSize = 12f; maxLines = 2 }
        fun updateAffected() {
            affected.text = if (replace.isChecked) "교체할 요소: " + replacementCandidates.joinToString(", ") {
                it.text.ifBlank { it.contentDescription }.ifBlank { it.className.substringAfterLast('.') }
            } else "기존 UI를 유지하며 새 요소를 추가합니다."
        }
        replace.setOnCheckedChangeListener { _, checked ->
            update(draft.copy(addition = requireNotNull(draft.addition).copy(replaceTargets = if (checked) replacementCandidates else emptyList())))
            updateAffected()
        }
        updateAffected(); root.addView(affected)
        val input = EditText(context).apply {
            id = kr.ac.kangwon.hai.vibefactory.R.id.uiAdditionInstruction
            hint = "예: 저장 버튼. 누르면 내용을 저장하고 목록으로 돌아가게 해줘."
            minLines = 2; maxLines = 3; textSize = 15f; setText(initial.instruction)
            doAfterTextChanged { update(draft.copy(instruction = it?.toString().orEmpty())) }
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context)
        actions.addView(Button(context).apply {
            text = "위치·크기 조절"; setOnClickListener { dismiss(); onBackToRegion() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        save = Button(context).apply {
            text = "표시 저장"
            setOnClickListener {
                if (draft.instruction.isBlank() && draft.addition?.strokes.isNullOrEmpty()) {
                    input.error = "그림이나 설명을 추가해 주세요."
                } else { isEnabled = false; onSave(draft.copy(instruction = draft.instruction.trim()), this@UiAdditionEditorDialog) }
            }
        }
        actions.addView(save, LinearLayout.LayoutParams(0, dp(52), 1f)); root.addView(actions)
        setContentView(root)
        setOnCancelListener { onBackToRegion() }
        setOnDismissListener { drawing.original = null; original?.recycle() }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    fun allowRetry() { save.isEnabled = true }
    private fun update(value: UiAnnotation) { draft = value; onDraftChanged(value) }
}
