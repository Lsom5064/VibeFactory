package kr.ac.kangwon.hai.vibefactory.ui_editor

import androidx.lifecycle.ViewModel
import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import kotlinx.coroutines.sync.Mutex

data class UiEditorSession(
    val taskId: String,
    val revisionLabel: String,
    val layout: UiLayoutSummaryDto,
    val baseXml: String,
    val baseXmlSha256: String,
    var document: AndroidXmlDocument,
    val resources: ResolvedUiResources,
    val unresolvedResourceCount: Int,
    val isNewLayout: Boolean,
    val descriptions: MutableMap<String, String>,
    val images: MutableList<UiEditorImage>,
    var selectedElementId: String?,
    val history: UiEditorHistory,
    var serverDraftId: String?,
    var serverDraftVersion: Int?
) {
    fun snapshot(): UiEditorSnapshot = UiEditorSnapshot(
        xml = document.xml(),
        descriptions = descriptions.toMap(),
        images = images.toList()
    )

    fun recordChange() {
        history.record(snapshot())
    }

    fun restore(snapshot: UiEditorSnapshot) {
        document = AndroidXmlDocument.parse(snapshot.xml)
        descriptions.clear()
        descriptions.putAll(snapshot.descriptions)
        images.clear()
        images.addAll(snapshot.images)
        selectedElementId = selectedElementId?.takeIf { selected ->
            document.root.descendantsAndSelf().any { it.stableId == selected }
        }
    }
}

class UiEditorViewModel : ViewModel() {
    var session: UiEditorSession? = null
    var layouts: List<UiLayoutSummaryDto> = emptyList()
    var loadGeneration: Int = 0
    val serverDraftMutex = Mutex()

    fun initialize(
        taskId: String,
        revisionLabel: String,
        layout: UiLayoutSummaryDto,
        baseDocument: AndroidXmlDocument,
        resources: ResolvedUiResources,
        draft: UiEditorDraftRecord?,
        unresolvedResourceCount: Int = 0,
        isNewLayout: Boolean = false
    ): UiEditorSession {
        val initialSnapshot = UiEditorSnapshot(baseDocument.xml(), emptyMap(), emptyList())
        val history = UiEditorHistory(initialSnapshot)
        val draftApplies = draft?.baseXmlSha256.equals(baseDocument.originalSha256, ignoreCase = true)
        val document = if (draftApplies) AndroidXmlDocument.parse(draft!!.editedXml) else baseDocument
        val descriptions = if (draftApplies) draft!!.descriptions.toMutableMap() else mutableMapOf()
        val images = if (draftApplies) draft!!.images.toMutableList() else mutableListOf()
        val created = UiEditorSession(
            taskId = taskId,
            revisionLabel = revisionLabel,
            layout = layout,
            baseXml = baseDocument.originalXml,
            baseXmlSha256 = baseDocument.originalSha256,
            document = document,
            resources = resources,
            unresolvedResourceCount = unresolvedResourceCount,
            isNewLayout = isNewLayout,
            descriptions = descriptions,
            images = images,
            selectedElementId = draft?.selectedElementId?.takeIf { draftApplies },
            history = history,
            serverDraftId = draft?.serverDraftId?.takeIf { draftApplies },
            serverDraftVersion = draft?.serverDraftVersion?.takeIf { draftApplies }
        )
        if (draftApplies && created.snapshot() != initialSnapshot) history.record(created.snapshot())
        session = created
        return created
    }
}
