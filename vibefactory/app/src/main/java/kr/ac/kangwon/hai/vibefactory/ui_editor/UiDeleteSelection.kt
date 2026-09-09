package kr.ac.kangwon.hai.vibefactory.ui_editor

/** A parent represents its entire subtree. Selection itself never changes the draft. */
internal object UiDeleteSelection {
    fun toggle(selected: List<UiAnnotationTarget>, target: UiAnnotationTarget): List<UiAnnotationTarget> {
        if (selected.any { it.stableId == target.stableId }) return selected.filterNot { it.stableId == target.stableId }
        if (selected.any { contains(it, target) }) return selected
        return selected.filterNot { contains(target, it) } + target
    }

    fun contains(parent: UiAnnotationTarget, child: UiAnnotationTarget): Boolean =
        parent.stableId == child.stableId || (parent.hierarchyPath.isNotBlank() &&
            child.hierarchyPath.removePrefix("rendered.").startsWith(parent.hierarchyPath.removePrefix("rendered.") + "."))

    fun apply(existing: List<UiAnnotation>, selected: List<UiAnnotationTarget>): List<UiAnnotation> {
        val remaining = existing.filterNot { annotation ->
            annotation.action == UiAnnotationAction.DELETE && selected.any { contains(it, annotation.target) }
        }
        val additions = selected.filterNot { target ->
            remaining.any { it.action == UiAnnotationAction.DELETE && contains(it.target, target) }
        }.map { UiAnnotation(action = UiAnnotationAction.DELETE, target = it) }
        return remaining + additions
    }
}
