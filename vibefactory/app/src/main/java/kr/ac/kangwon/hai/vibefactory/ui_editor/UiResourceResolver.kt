package kr.ac.kangwon.hai.vibefactory.ui_editor

import kr.ac.kangwon.hai.vibefactory.UiResourceFileDto
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ResolvedUiResources(
    val strings: Map<String, String>,
    val colors: Map<String, String>,
    val dimens: Map<String, String>,
    val styles: Map<String, Map<String, String>>,
    val warnings: List<String>
) {
    fun text(value: String?): String? {
        val raw = value?.trim().orEmpty()
        return when {
            raw.startsWith("@string/") -> strings[raw.substringAfter("@string/")] ?: raw
            raw.isNotBlank() -> raw
            else -> null
        }
    }

    fun color(value: String?): String? {
        val raw = value?.trim().orEmpty()
        return when {
            raw.startsWith("@color/") -> colors[raw.substringAfter("@color/")] ?: raw
            raw.startsWith("#") -> raw
            else -> null
        }
    }

    fun dimen(value: String?): String? {
        val raw = value?.trim().orEmpty()
        return when {
            raw.startsWith("@dimen/") -> dimens[raw.substringAfter("@dimen/")] ?: raw
            raw.isNotBlank() -> raw
            else -> null
        }
    }

    companion object {
        val EMPTY = ResolvedUiResources(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())

        fun from(files: List<UiResourceFileDto>): ResolvedUiResources {
            val strings = linkedMapOf<String, String>()
            val colors = linkedMapOf<String, String>()
            val dimens = linkedMapOf<String, String>()
            val styles = linkedMapOf<String, Map<String, String>>()
            val warnings = mutableListOf<String>()

            files.filter { it.kind == "xml" && it.resource_path.contains("/values") }
                .forEach { file ->
                    val content = file.content
                    if (content.isNullOrBlank()) {
                        warnings += "${file.resource_path}: XML content missing"
                        return@forEach
                    }
                    val document = runCatching { SecureAndroidXml.parse(content) }
                        .getOrElse { error ->
                            warnings += "${file.resource_path}: ${error.message.orEmpty()}"
                            return@forEach
                        }
                    var child = document.documentElement.firstChild
                    while (child != null) {
                        if (child.nodeType == Node.ELEMENT_NODE) {
                            val element = child as Element
                            val name = element.getAttribute("name").trim()
                            when (element.tagName.substringAfterLast('.')) {
                                "string" -> if (name.isNotBlank()) strings[name] = element.textContent.orEmpty()
                                "color" -> if (name.isNotBlank()) colors[name] = element.textContent.trim()
                                "dimen" -> if (name.isNotBlank()) dimens[name] = element.textContent.trim()
                                "style" -> if (name.isNotBlank()) {
                                    styles[name] = styleItems(element)
                                }
                                "item" -> when (element.getAttribute("type")) {
                                    "string" -> if (name.isNotBlank()) strings[name] = element.textContent.orEmpty()
                                    "color" -> if (name.isNotBlank()) colors[name] = element.textContent.trim()
                                    "dimen" -> if (name.isNotBlank()) dimens[name] = element.textContent.trim()
                                }
                            }
                        }
                        child = child.nextSibling
                    }
                }
            return ResolvedUiResources(strings, colors, dimens, styles, warnings)
        }

        private fun styleItems(style: Element): Map<String, String> = buildMap {
            var child = style.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == "item") {
                    val name = child.getAttribute("name").trim()
                    if (name.isNotBlank()) put(name, child.textContent.trim())
                }
                child = child.nextSibling
            }
        }
    }
}
