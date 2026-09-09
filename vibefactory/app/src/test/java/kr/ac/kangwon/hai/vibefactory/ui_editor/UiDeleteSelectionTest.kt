package kr.ac.kangwon.hai.vibefactory.ui_editor

import org.junit.Assert.*
import org.junit.Test

class UiDeleteSelectionTest {
    private fun target(id: String, path: String) = UiAnnotationTarget(id, "", path, "Button", id, "",
        UiNormalizedRect(.1f, .1f, .2f, .2f), "", "")

    @Test fun repeatedTapTogglesOnlyThatSelection() {
        val a = target("a", "0.1"); val b = target("b", "0.2")
        val selected = UiDeleteSelection.toggle(UiDeleteSelection.toggle(emptyList(), a), b)
        assertEquals(listOf(b), UiDeleteSelection.toggle(selected, a))
    }

    @Test fun parentReplacesSelectedDescendantsWithoutMatchingSiblingPrefixes() {
        val parent = target("parent", "0.1"); val child = target("child", "0.1.0"); val other = target("other", "0.10")
        assertEquals(listOf(other, parent), UiDeleteSelection.toggle(listOf(child, other), parent))
        assertEquals(listOf(parent), UiDeleteSelection.toggle(listOf(parent), child))
    }

    @Test fun applyingBatchIsOneUndoStepAndKeepsOtherTools() {
        val a = target("a", "0.1"); val b = target("b", "0.2")
        val behavior = UiAnnotation(action = UiAnnotationAction.BEHAVIOR, target = a, instruction = "저장")
        val before = listOf(behavior)
        val history = UiAnnotationHistory(before)
        val after = UiDeleteSelection.apply(before, listOf(a, b))
        history.record(after)
        assertEquals(3, after.size)
        assertEquals(before, history.undo())
        assertEquals(after, history.redo())
        assertEquals(3, UiDeleteSelection.apply(after, listOf(a, b)).size)
    }
}
