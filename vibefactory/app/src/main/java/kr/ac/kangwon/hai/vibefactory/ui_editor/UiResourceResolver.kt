package kr.ac.kangwon.hai.vibefactory.ui_editor

import kr.ac.kangwon.hai.vibefactory.UiResourceFileDto
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ResolvedUiResources(
    val strings: Map<String, String>,
    val colors: Map<String, String>,
    val dimens: Map<String, String>,
    val styles: Map<String, Map<String, String>>,
    val styleParents: Map<String, String?>,
    val layouts: Map<String, String>,
    val xmlDrawables: Map<String, String>,
    val warnings: List<String>
) {
    fun text(value: String?): String? = resolveValue(value, "@string/", strings)

    fun color(value: String?): String? {
        val raw = value?.trim().orEmpty()
        return when {
            raw.startsWith("@color/") -> resolveValue(raw, "@color/", colors)
            raw.startsWith("#") -> raw
            else -> null
        }
    }

    fun dimen(value: String?): String? = resolveValue(value, "@dimen/", dimens)

    fun layout(value: String?): String? {
        val raw = value?.trim().orEmpty()
        return raw.takeIf { it.startsWith("@layout/") }
            ?.substringAfter("@layout/")
            ?.let(layouts::get)
    }

    fun drawableXml(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith("@drawable/") && !raw.startsWith("@mipmap/")) return null
        return xmlDrawables[raw.substringAfter('/')]
    }

    fun styleValue(styleReference: String?, namespaceUri: String, localName: String): String? {
        val name = styleReference?.trim().orEmpty().removePrefix("@style/")
        if (name.isBlank()) return null
        val merged = mergedStyle(name)
        val keys = when (namespaceUri) {
            ANDROID_NAMESPACE_URI -> listOf("android:$localName", localName)
            APP_NAMESPACE_URI -> listOf("app:$localName", localName)
            else -> listOf(localName)
        }
        return keys.firstNotNullOfOrNull(merged::get)
    }

    private fun mergedStyle(name: String): Map<String, String> {
        val chain = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current: String? = name
        while (!current.isNullOrBlank() && visited.add(current)) {
            chain += current
            current = styleParents[current]
                ?: current.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        }
        return buildMap {
            chain.asReversed().forEach { styleName -> putAll(styles[styleName].orEmpty()) }
        }
    }

    private fun resolveValue(value: String?, prefix: String, values: Map<String, String>): String? {
        var current = value?.trim().orEmpty()
        if (current.isBlank()) return null
        val visited = mutableSetOf<String>()
        repeat(MAX_REFERENCE_DEPTH) {
            if (!current.startsWith(prefix)) return current
            val name = current.substringAfter(prefix)
            if (!visited.add(name)) return current
            current = values[name]?.trim() ?: return current
        }
        return current
    }

    companion object {
        private const val MAX_REFERENCE_DEPTH = 16

        val EMPTY = ResolvedUiResources(
            strings = emptyMap(),
            colors = emptyMap(),
            dimens = emptyMap(),
            styles = emptyMap(),
            styleParents = emptyMap(),
            layouts = emptyMap(),
            xmlDrawables = emptyMap(),
            warnings = emptyList()
        )

        fun from(files: List<UiResourceFileDto>): ResolvedUiResources {
            val strings = linkedMapOf<String, String>()
            val colors = linkedMapOf<String, String>()
            val dimens = linkedMapOf<String, String>()
            val styles = linkedMapOf<String, Map<String, String>>()
            val styleParents = linkedMapOf<String, String?>()
            val layouts = linkedMapOf<String, String>()
            val xmlDrawables = linkedMapOf<String, String>()
            val warnings = mutableListOf<String>()

            files.filter { it.kind == "xml" }.forEach { file ->
                val content = file.content
                if (content.isNullOrBlank()) {
                    warnings += "${file.resource_path}: XML content missing"
                    return@forEach
                }
                when {
                    file.resource_path.substringAfter("res/").startsWith("values") -> {
                        readValuesFile(
                            file = file,
                            content = content,
                            strings = strings,
                            colors = colors,
                            dimens = dimens,
                            styles = styles,
                            styleParents = styleParents,
                            warnings = warnings
                        )
                    }

                    file.resource_path.substringAfter("res/").startsWith("layout") -> {
                        layouts[resourceName(file.resource_path)] = content
                    }

                    file.resource_path.substringAfter("res/").let {
                        it.startsWith("drawable") || it.startsWith("mipmap")
                    } -> {
                        xmlDrawables[resourceName(file.resource_path)] = content
                    }
                }
            }
            return ResolvedUiResources(
                strings = strings,
                colors = colors,
                dimens = dimens,
                styles = styles,
                styleParents = styleParents,
                layouts = layouts,
                xmlDrawables = xmlDrawables,
                warnings = warnings
            )
        }

        private fun readValuesFile(
            file: UiResourceFileDto,
            content: String,
            strings: MutableMap<String, String>,
            colors: MutableMap<String, String>,
            dimens: MutableMap<String, String>,
            styles: MutableMap<String, Map<String, String>>,
            styleParents: MutableMap<String, String?>,
            warnings: MutableList<String>
        ) {
            val document = runCatching { SecureAndroidXml.parse(content) }
                .getOrElse { error ->
                    warnings += "${file.resource_path}: ${error.message.orEmpty()}"
                    return
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
                            styleParents[name] = normalizeStyleName(
                                element.getAttribute("parent").trim().takeIf { it.isNotBlank() }
                            )
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

        private fun normalizeStyleName(value: String?): String? = value
            ?.removePrefix("@style/")
            ?.takeIf { it.isNotBlank() }

        private fun resourceName(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')
    }
}
