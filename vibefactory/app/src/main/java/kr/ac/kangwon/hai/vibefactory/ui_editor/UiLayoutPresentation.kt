package kr.ac.kangwon.hai.vibefactory.ui_editor

import kr.ac.kangwon.hai.vibefactory.UiLayoutSummaryDto

data class UiLayoutMenuGroup(
    val kind: String,
    val label: String,
    val layouts: List<UiLayoutSummaryDto>
)

object UiLayoutPresentation {
    private val kindOrder = listOf("screen", "dialog", "component", "item")
    private val prefixes = setOf(
        "activity", "fragment", "screen", "dialog", "sheet", "bottom", "item", "row", "cell"
    )
    private val knownWords = mapOf(
        "main" to "메인",
        "home" to "홈",
        "settings" to "설정",
        "setting" to "설정",
        "history" to "기록",
        "detail" to "상세",
        "list" to "목록",
        "todo" to "할 일",
        "task" to "작업",
        "profile" to "프로필",
        "calendar" to "캘린더",
        "login" to "로그인",
        "search" to "검색",
        "result" to "결과",
        "edit" to "편집",
        "editor" to "편집",
        "create" to "등록"
    )

    fun displayName(layout: UiLayoutSummaryDto): String {
        val base = layout.display_name.trim().ifBlank {
            val kind = normalizedKind(layout)
            val words = layout.layout_name
                .split('_')
                .dropWhile { it in prefixes }
                .dropLastWhile { it in prefixes }
                .filter(String::isNotBlank)
                .map { knownWords[it.lowercase()] ?: it.replaceFirstChar(Char::uppercase) }
            val stem = words.joinToString(" ").ifBlank { "기본" }
            "$stem ${kindSuffix(kind)}"
        }
        val variant = configurationLabel(layout.configuration)
        return if (variant == null) base else "$base · $variant"
    }

    fun groups(layouts: List<UiLayoutSummaryDto>): List<UiLayoutMenuGroup> =
        layouts
            .groupBy(::normalizedKind)
            .entries
            .sortedWith(compareBy({ kindOrder.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.key }))
            .map { (kind, entries) ->
                UiLayoutMenuGroup(
                    kind = kind,
                    label = kindGroupLabel(kind),
                    layouts = entries.sortedWith(
                        compareBy<UiLayoutSummaryDto> { displayName(it) }
                            .thenBy { it.configuration }
                            .thenBy { it.layout_name }
                    )
                )
            }

    fun normalizedKind(layout: UiLayoutSummaryDto): String =
        layout.layout_kind.trim().takeIf { it in kindOrder } ?: when {
            layout.layout_name.startsWith("activity_") ||
                layout.layout_name.startsWith("fragment_") ||
                layout.layout_name.startsWith("screen_") ||
                layout.layout_name.endsWith("_activity") ||
                layout.layout_name.endsWith("_fragment") ||
                layout.layout_name.endsWith("_screen") -> "screen"
            layout.layout_name.startsWith("dialog_") ||
                layout.layout_name.startsWith("sheet_") ||
                layout.layout_name.startsWith("bottom_sheet_") ||
                layout.layout_name.endsWith("_dialog") ||
                layout.layout_name.endsWith("_sheet") -> "dialog"
            layout.layout_name.startsWith("item_") ||
                layout.layout_name.startsWith("row_") ||
                layout.layout_name.startsWith("cell_") ||
                layout.layout_name.endsWith("_item") ||
                layout.layout_name.endsWith("_row") ||
                layout.layout_name.endsWith("_cell") -> "item"
            else -> "component"
        }

    private fun kindSuffix(kind: String): String = when (kind) {
        "screen" -> "화면"
        "dialog" -> "팝업"
        "item" -> "항목"
        else -> "구성요소"
    }

    private fun kindGroupLabel(kind: String): String = when (kind) {
        "screen" -> "화면"
        "dialog" -> "팝업"
        "item" -> "반복 항목"
        "component" -> "구성요소"
        else -> "기타"
    }

    private fun configurationLabel(configuration: String): String? {
        if (configuration == "layout") return null
        val qualifiers = configuration.removePrefix("layout-").split('-').filter(String::isNotBlank)
        val labels = qualifiers.map { qualifier ->
            when {
                qualifier == "land" -> "가로 화면"
                qualifier == "port" -> "세로 화면"
                qualifier == "night" -> "다크 모드"
                qualifier.startsWith("sw") && qualifier.endsWith("dp") -> "큰 화면"
                qualifier.startsWith("w") && qualifier.endsWith("dp") -> "넓은 화면"
                qualifier.startsWith("h") && qualifier.endsWith("dp") -> "긴 화면"
                else -> "대체 화면"
            }
        }.distinct()
        return labels.joinToString(" · ").ifBlank { "대체 화면" }
    }
}
