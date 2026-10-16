package kr.ac.kangwon.hai.vibefactory.ui_editor

import androidx.lifecycle.ViewModel
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kr.ac.kangwon.hai.vibefactory.UiPreviewChildDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

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
    var serverDraftVersion: Int?,
    var pendingAddition: UiAnnotation? = null,
    var pendingAdditionEditing: Boolean = false,
    var pendingModification: UiAnnotation? = null,
    var selectedDeleteTargets: List<UiAnnotationTarget> = emptyList(),
    var referenceCanvasWidthDp: Float? = null,
    var referenceCanvasHeightDp: Float? = null,
    var previewCanvasHeightDp: Float? = null
) {
    fun replaceAnnotations(value: List<UiAnnotation>) {
        annotations.clear()
        annotations.addAll(value)
    }
}

class UiAnnotationViewModel : ViewModel() {
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCleared() {
        persistenceScope.cancel()
        super.onCleared()
    }
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
    val nextLocalSaveSequence = java.util.concurrent.atomic.AtomicLong()
    private val savedLocalSequences = mutableMapOf<String, Long>()
    val localSaveJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val annotationCounts = mutableMapOf<String, Int>()
    var remoteSaveSequence: Long = 0

    fun setLayouts(value: List<UiLayoutSummaryDto>) {
        layouts = value
        layoutMenuGroups = UiLayoutPresentation.groups(value)
    }

    fun rememberDrafts(records: List<UiAnnotationDraftRecord>) {
        records.forEach { annotationCounts["${it.configuration}:${it.layoutName}"] = it.annotations.size }
    }

    fun annotationCounts(): Map<String, Int> {
        sessions.values.forEach { annotationCounts["${it.layout.configuration}:${it.layout.layout_name}"] = it.annotations.size }
        session?.let { annotationCounts["${it.layout.configuration}:${it.layout.layout_name}"] = it.annotations.size }
        return annotationCounts.toMap()
    }

    fun cachedSessions(): List<UiAnnotationSession> = (sessions.values + listOfNotNull(session)).distinctBy {
        sessionKey(it.taskId, it.revisionLabel, it.layout)
    }

    fun cachedSession(taskId: String, revisionLabel: String, layout: UiLayoutSummaryDto): UiAnnotationSession? =
        sessions[sessionKey(taskId, revisionLabel, layout)]

    suspend fun persistRecord(record: UiAnnotationDraftRecord, sequence: Long, write: (UiAnnotationDraftRecord) -> Unit) {
        localDraftMutex.withLock {
            val key = "${record.taskId}:${record.revisionLabel}:${record.configuration}:${record.layoutName}"
            if (sequence <= (savedLocalSequences[key] ?: 0L)) return
            write(record)
            savedLocalSequences[key] = sequence
        }
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
            serverDraftVersion = applicable?.serverDraftVersion,
            pendingAddition = applicable?.pendingAddition,
            pendingAdditionEditing = applicable?.pendingAdditionEditing == true,
            pendingModification = applicable?.pendingModification
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
