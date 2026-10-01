package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import kr.ac.kangwon.hai.vibefactory.R
import kotlinx.coroutines.*

/** Shared editor for an addition or an existing element's appearance/behavior. */
internal class UiVisualChangeDialog(
    context: Context,
    initial: UiAnnotation,
    private val original: Bitmap?,
    regionAspectRatio: Float,
    replacementCandidates: List<UiAnnotationTarget>,
    private val onDraftChanged: (UiAnnotation) -> Unit,
    private val onBack: () -> Unit,
    private val onPickImages: () -> Unit,
    private val onSave: (UiAnnotation, UiVisualChangeDialog) -> Unit
) : Dialog(context) {
    private var draft = initial
    private val adding = initial.action == UiAnnotationAction.ADD
    private val save: Button
    private val drawing: UiSketchCanvasView
    private val imageCount: TextView
    private val imageButton: ImageButton
    private val penButton: ImageButton
    private val eraserButton: ImageButton
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var imageJob: Job? = null
    private var imageGeneration = 0
    private val imageBitmaps = mutableMapOf<String, Bitmap>()
    private var requestedBusy = false
    private var loadingImages = false
    private var imageLoadFailed = false
    private val busy get() = requestedBusy || loadingImages
    private val preferences = context.getSharedPreferences("ui_sketch_tools", Context.MODE_PRIVATE)
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        root.addView(TextView(context).apply {
            text = if (adding) "추가할 UI 그리기" else "모양·기능 변경"; textSize = 21f
        })
        root.addView(TextView(context).apply {
            text = if (adding) "추가할 모습과 동작을 설명해 주세요. 그림·이미지는 선택이에요."
                else "원본 위에 원하는 모양을 표시하고, 변경할 모습이나 동작을 설명해 주세요."
            textSize = 13f
        })
        drawing = UiSketchCanvasView(context).apply {
            id = R.id.uiAdditionSketchCanvas
            backgroundColorValue = initial.addition?.backgroundColor ?: requireNotNull(initial.sketch).backgroundColor
            aspectRatio = regionAspectRatio
            this.original = this@UiVisualChangeDialog.original
            drawOnOriginal = !adding
            if (!adding) borderColor = android.graphics.Color.rgb(123, 31, 162)
            penWidth = preferences.getFloat("pen_width", .009f).coerceIn(.0045f, .032f)
            restore(initial.addition?.strokes ?: initial.sketch?.strokes.orEmpty(), initial.canvasImages)
            contentChanged = { strokes, layers ->
                val next = draft.copy(imageLayers = layers,
                    imageIds = layers.map { it.imageId } + listOfNotNull(draft.sketchImageId))
                update(if (adding) next.copy(addition = requireNotNull(next.addition).copy(strokes = strokes))
                    else next.copy(sketch = requireNotNull(next.sketch).copy(strokes = strokes)))
                updateImageHint()
            }
            selectionChanged = { updateToolSelection(); updateImageHint() }
        }
        root.addView(drawing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(UiSketchZoomControls(context, drawing))
        val tools = LinearLayout(context)
        fun tool(id: Int, icon: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
            this.id = id; contentDescription = label; tooltipText = label
            setImageResource(icon); setPadding(dp(12), dp(12), dp(12), dp(12))
            val background = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setOnClickListener { if (!busy) action() }
            tools.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        penButton = tool(R.id.uiSketchPen, R.drawable.ic_sketch_pen, "펜 · 다시 누르면 굵기 변경") {
            if (!drawing.erasing && drawing.selectedImageId == null) showPenWidths()
            else drawing.selectDrawingTool(false)
        }
        eraserButton = tool(R.id.uiSketchEraser, R.drawable.ic_sketch_eraser, "지우개") { drawing.selectDrawingTool(true) }
        tool(R.id.uiSketchUndo, R.drawable.ic_sketch_undo, "되돌리기") { drawing.undo() }
        tool(R.id.uiSketchRedo, R.drawable.ic_sketch_redo, "다시하기") { drawing.redo() }
        imageButton = tool(R.id.uiSketchAddImage, R.drawable.ic_sketch_image, "이미지 추가") { onPickImages() }
        tool(R.id.uiSketchOriginal, R.drawable.ic_sketch_original, "원본 보기 · 누르고 있기") {}.setOnTouchListener { view, event ->
            drawing.showOriginal = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        root.addView(tools)
        if (adding) {
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
                update(draft.copy(addition = requireNotNull(draft.addition).copy(
                    replaceTargets = if (checked) replacementCandidates else emptyList())))
                updateAffected()
            }
            updateAffected(); root.addView(affected)
        }
        imageCount = TextView(context).apply { textSize = 12f; minLines = 2 }
        root.addView(imageCount)
        val input = EditText(context).apply {
            id = R.id.uiAdditionInstruction
            hint = if (adding) "부연 설명 (필수): 추가할 모습과 동작을 적어 주세요."
                else "부연 설명 (필수): 바꿀 색·모양·크기나 동작을 적어 주세요."
            minLines = 2; maxLines = 3; textSize = 15f; setText(initial.instruction)
            doAfterTextChanged {
                if (!it.isNullOrBlank()) error = null
                update(draft.copy(instruction = it?.toString().orEmpty()))
            }
        }
        root.addView(input)
        val actions = LinearLayout(context)
        actions.addView(Button(context).apply {
            text = if (adding) "위치·크기 조절" else "닫기"
            setOnClickListener { if (!busy) { dismiss(); onBack() } }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        save = Button(context).apply {
            text = "표시 저장"
            setOnClickListener {
                if (draft.instruction.isBlank()) input.error = "설명을 입력해 주세요."
                else { setBusy(true); onSave(draft.copy(instruction = draft.instruction.trim()), this@UiVisualChangeDialog) }
            }
        }
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f)); root.addView(actions)
        setContentView(root)
        setOnCancelListener { onBack() }
        setOnDismissListener {
            uiScope.cancel(); drawing.original = null; drawing.imageBitmaps = emptyMap()
            imageBitmaps.values.forEach { it.recycle() }; imageBitmaps.clear(); original?.recycle()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        updateToolSelection(); updateImageHint()
    }

    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun setBusy(value: Boolean) {
        requestedBusy = value; applyBusyState()
    }

    private fun applyBusyState() {
        save.isEnabled = !busy && !imageLoadFailed; imageButton.isEnabled = !busy
        findViewById<EditText>(R.id.uiAdditionInstruction).isEnabled = !busy
        drawing.isEnabled = !busy; setCancelable(!busy)
    }

    fun allowRetry() = setBusy(false)
    fun copyOriginal(): Bitmap? = original?.copy(Bitmap.Config.ARGB_8888, false)

    fun showReferenceImages(images: List<UiEditorImage>) {
        val generation = ++imageGeneration
        imageJob?.cancel(); loadingImages = true; imageLoadFailed = false; applyBusyState()
        imageJob = uiScope.launch {
            val decoded = mutableMapOf<String, Bitmap?>()
            try {
                val missing = images.filter { it.imageId !in imageBitmaps }
                withContext(Dispatchers.IO) {
                    missing.forEach { image ->
                        ensureActive()
                        decoded[image.imageId] = UiSketchImages.decode(image.localPath)
                    }
                }
                if (generation != imageGeneration) return@launch
                if (decoded.values.any { it == null }) {
                    imageLoadFailed = true
                    Toast.makeText(context, "이미지를 불러오지 못했어요. 편집 화면을 다시 열어 주세요.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                decoded.forEach { (id, bitmap) -> imageBitmaps[id] = requireNotNull(bitmap) }
                // Ownership transfers to the dialog only after every image loaded successfully.
                decoded.clear()
                drawing.imageBitmaps = imageBitmaps.toMap()
                val added = images.filter { image -> drawing.imageLayers.none { it.imageId == image.imageId } }
                    .map { image ->
                        val bitmap = requireNotNull(imageBitmaps[image.imageId])
                        UiSketchImageLayer(image.imageId, UiSketchImages.fit(bitmap.width.toFloat()/bitmap.height, drawing.aspectRatio))
                    }
                drawing.insertImages(added)
                updateImageHint()
            } finally {
                decoded.values.forEach { it?.recycle() }
                if (generation == imageGeneration) { loadingImages = false; applyBusyState() }
            }
        }
    }

    private fun updateImageHint() {
        imageCount.text = if (drawing.selectedImageId != null)
            "이미지 ${drawing.imageLayers.size}장 / 최대 5장 · 끌어서 이동, 모서리로 크기 조절\n×: 선택 이미지 삭제 · 펜을 누르면 이미지 위에 그리기"
        else "이미지 ${drawing.imageLayers.size}장 / 최대 5장 · 길게 눌러 이미지 선택\n선택된 펜을 다시 누르면 굵기를 바꿀 수 있어요."
    }

    private fun updateToolSelection() {
        val selectedColor = context.getColor(R.color.accent_primary)
        val normalColor = context.getColor(R.color.text_secondary)
        listOf(penButton to (!drawing.erasing && drawing.selectedImageId == null),
            eraserButton to (drawing.erasing && drawing.selectedImageId == null)).forEach { (button, selected) ->
            button.isSelected = selected
            button.imageTintList = android.content.res.ColorStateList.valueOf(if (selected) selectedColor else normalColor)
            button.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) androidx.core.graphics.ColorUtils.setAlphaComponent(selectedColor, 32) else android.graphics.Color.TRANSPARENT)
            }
        }
    }

    private fun showPenWidths() {
        val widths = floatArrayOf(.0045f, .009f, .018f, .032f)
        val labels = arrayOf("가는 선", "보통", "굵은 선", "아주 굵은 선")
        val selected = widths.indices.minBy { kotlin.math.abs(widths[it]-drawing.penWidth) }
        AlertDialog.Builder(context).setTitle("펜 굵기")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                drawing.penWidth = widths[which]
                preferences.edit().putFloat("pen_width", widths[which]).apply()
                penButton.contentDescription = "펜 · ${labels[which]} · 다시 누르면 굵기 변경"
                dialog.dismiss()
            }.setNegativeButton("닫기", null).show()
    }

    private fun update(value: UiAnnotation) { draft = value; onDraftChanged(value) }
    fun replaceDraft(value: UiAnnotation) { draft = value }
}
