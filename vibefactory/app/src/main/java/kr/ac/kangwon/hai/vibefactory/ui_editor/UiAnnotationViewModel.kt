package kr.ac.kangwon.hai.vibefactory.ui_editor

import androidx.lifecycle.ViewModel
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kr.ac.kangwon.hai.vibefactory.UiPreviewChildDto
import kotlinx.coroutines.sync.Mutex

data class UiAnnotationSession(
    val taskId: String,
    val revisionLabel: String,
    val layout: UiLayoutSummaryDto,
    val originalXml: String,
    val baseXmlSha256: String,
    val document: AndroidXmlDocument,
    val resources: ResolvedUiResources,
    val unresolvedResourceCount: Int,
    val previewChildren: List<UiPreviewChildDto>,
    val previewDynamicTextViewIds: Set<String>,
    val previewHiddenViewIds: Set<String>,
    val annotations: MutableList<UiAnnotation>,
    val images: MutableList<UiEditorImage>,
    val history: UiAnnotationHistory,
    var serverDraftId: String?,
    var serverDraftVersion: Int?
) {
    fun replaceAnnotations(value: List<UiAnnotation>) {
        annotations.clear()
        annotations.addAll(value)
    }
}

class UiAnnotationViewModel : ViewModel() {
    private val sessions = object : LinkedHashMap<String, UiAnnotationSession>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, UiAnnotationSession>?
        ): Boolean = size > MAX_CACHED_LAYOUT_SESSIONS
    }
    var session: UiAnnotationSession? = null
    var layouts: List<UiLayoutSummaryDto> = emptyList()
        private set
    var layoutMenuGroups: List<UiLayoutMenuGroup> = emptyList()
        private set
    var loadGeneration: Int = 0
    val serverDraftMutex = Mutex()
    val localDraftMutex = Mutex()
    var nextLocalSaveSequence: Long = 0
    var savedLocalSequence: Long = 0
    var remoteSaveSequence: Long = 0

    fun setLayouts(value: List<UiLayoutSummaryDto>) {
        layouts = value
        layoutMenuGroups = UiLayoutPresentation.groups(value)
    }

    fun restoreCachedSession(
        taskId: String,
        revisionLabel: String,
        layout: UiLayoutSummaryDto
    ): UiAnnotationSession? = sessions[sessionKey(taskId, revisionLabel, layout)]?.also {
        session = it
    }

    fun initialize(
        taskId: String,
        revisionLabel: String,
        layout: UiLayoutSummaryDto,
        document: AndroidXmlDocument,
        resources: ResolvedUiResources,
        unresolvedResourceCount: Int,
        previewChildren: List<UiPreviewChildDto>,
        previewDynamicTextViewIds: Set<String>,
        previewHiddenViewIds: Set<String>,
        draft: UiAnnotationDraftRecord?
    ): UiAnnotationSession {
        val applicable = draft?.takeIf {
            it.baseXmlSha256.equals(document.originalSha256, ignoreCase = true) &&
                it.originalXml == document.originalXml
        }
        val annotations = applicable?.annotations.orEmpty().toMutableList()
        return UiAnnotationSession(
            taskId = taskId,
            revisionLabel = revisionLabel,
            layout = layout,
            originalXml = document.originalXml,
            baseXmlSha256 = document.originalSha256,
            document = document,
            resources = resources,
            unresolvedResourceCount = unresolvedResourceCount,
            previewChildren = previewChildren,
            previewDynamicTextViewIds = previewDynamicTextViewIds,
            previewHiddenViewIds = previewHiddenViewIds,
            annotations = annotations,
            images = applicable?.images.orEmpty().toMutableList(),
            history = UiAnnotationHistory(annotations),
            serverDraftId = applicable?.serverDraftId,
            serverDraftVersion = applicable?.serverDraftVersion
        ).also {
            session = it
            sessions[sessionKey(taskId, revisionLabel, layout)] = it
        }
    }

    private fun sessionKey(
        taskId: String,
        revisionLabel: String,
        layout: UiLayoutSummaryDto
    ): String = "$taskId:$revisionLabel:${layout.configuration}:${layout.layout_name}"

    companion object {
        private const val MAX_CACHED_LAYOUT_SESSIONS = 4
    }
}
