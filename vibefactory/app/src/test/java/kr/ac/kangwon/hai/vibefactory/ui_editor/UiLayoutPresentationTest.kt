package kr.ac.kangwon.hai.vibefactory.ui_editor

import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiLayoutPresentationTest {
    @Test
    fun catalogNameIsShownWithoutInternalFileName() {
        val layout = UiLayoutSummaryDto(
            layout_name = "activity_secret_internal",
            configuration = "layout-land",
            display_name = "혜택 달력",
            layout_kind = "screen",
            guide_available = true
        )

        val label = UiLayoutPresentation.displayName(layout)

        assertEquals("혜택 달력 · 가로 화면", label)
        assertFalse(label.contains("activity_secret_internal"))
        assertFalse(label.contains("layout-land"))
    }

    @Test
    fun legacyLayoutGetsSafeFallbackNameAndKind() {
        val layout = UiLayoutSummaryDto(layout_name = "item_todo_result")

        assertEquals("할 일 결과 항목", UiLayoutPresentation.displayName(layout))
        assertEquals("item", UiLayoutPresentation.normalizedKind(layout))
    }

    @Test
    fun groupsUseStableUserFacingOrder() {
        val groups = UiLayoutPresentation.groups(
            listOf(
                UiLayoutSummaryDto(layout_name = "item_result", layout_kind = "item"),
                UiLayoutSummaryDto(layout_name = "dialog_help", layout_kind = "dialog"),
                UiLayoutSummaryDto(layout_name = "activity_main", layout_kind = "screen"),
                UiLayoutSummaryDto(layout_name = "toolbar", layout_kind = "component")
            )
        )

        assertEquals(listOf("화면", "팝업", "구성요소", "반복 항목"), groups.map { it.label })
    }
}
