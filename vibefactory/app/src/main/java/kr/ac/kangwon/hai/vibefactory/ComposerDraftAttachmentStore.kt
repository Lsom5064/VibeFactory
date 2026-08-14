package kr.ac.kangwon.hai.vibefactory

import java.io.File
import java.util.Base64
import java.util.UUID

internal data class PersistedComposerAttachment(
    val kind: String,
    val displayName: String,
    val mimeType: String,
    val filePath: String
)

internal class ComposerDraftAttachmentStore(
    private val rootDirectory: File
) {
    fun persist(attachment: SelectedAttachment): SelectedAttachment? {
        val bytes = runCatching { Base64.getDecoder().decode(attachment.base64) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        rootDirectory.mkdirs()
        val target = File(rootDirectory, "${UUID.randomUUID()}.bin")
        return runCatching {
            target.writeBytes(bytes)
            attachment.copy(draftFilePath = target.absolutePath)
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun describe(attachment: SelectedAttachment): PersistedComposerAttachment? {
        val file = attachment.draftFilePath?.let(::File) ?: return null
        if (!isManagedFile(file) || !file.isFile) return null
        return PersistedComposerAttachment(
            kind = attachment.kind.name,
            displayName = attachment.displayName,
            mimeType = attachment.mimeType,
            filePath = file.absolutePath
        )
    }

    fun restore(descriptor: PersistedComposerAttachment): SelectedAttachment? {
        val kind = runCatching { SelectedAttachmentKind.valueOf(descriptor.kind) }.getOrNull()
            ?: return null
        val file = File(descriptor.filePath)
        if (!isManagedFile(file) || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        return SelectedAttachment(
            kind = kind,
            displayName = descriptor.displayName,
            mimeType = descriptor.mimeType,
            base64 = Base64.getEncoder().encodeToString(bytes),
            draftFilePath = file.absolutePath
        )
    }

    fun delete(attachment: SelectedAttachment) {
        attachment.draftFilePath
            ?.let(::File)
            ?.takeIf(::isManagedFile)
            ?.delete()
    }

    fun clear(attachments: Collection<SelectedAttachment>) {
        attachments.forEach(::delete)
    }

    fun clearStaleFiles() {
        rootDirectory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun isManagedFile(file: File): Boolean {
        val rootPath = runCatching { rootDirectory.canonicalFile.toPath() }.getOrNull() ?: return false
        val filePath = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return false
        return filePath.parent == rootPath
    }
}
