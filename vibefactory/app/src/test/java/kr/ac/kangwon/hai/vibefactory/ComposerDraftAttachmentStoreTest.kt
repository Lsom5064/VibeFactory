package kr.ac.kangwon.hai.vibefactory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class ComposerDraftAttachmentStoreTest {
    private lateinit var root: File
    private lateinit var store: ComposerDraftAttachmentStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("composer-draft-store").toFile()
        store = ComposerDraftAttachmentStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun persistsAndRestoresAttachmentWithoutPuttingPayloadInDescriptor() {
        val payload = "native-pdf-payload".toByteArray()
        val attachment = SelectedAttachment(
            kind = SelectedAttachmentKind.PDF,
            displayName = "reference.pdf",
            mimeType = "application/pdf",
            base64 = Base64.getEncoder().encodeToString(payload)
        )

        val persisted = store.persist(attachment)
        assertNotNull(persisted)
        val descriptor = store.describe(persisted!!)
        assertNotNull(descriptor)

        val restored = store.restore(descriptor!!)
        assertEquals(attachment.kind, restored?.kind)
        assertEquals(attachment.displayName, restored?.displayName)
        assertEquals(attachment.mimeType, restored?.mimeType)
        assertEquals(attachment.base64, restored?.base64)
    }

    @Test
    fun rejectsDescriptorOutsideManagedDirectory() {
        val outside = Files.createTempFile("outside-draft", ".bin").toFile().apply {
            writeText("outside")
        }
        try {
            val descriptor = PersistedComposerAttachment(
                kind = SelectedAttachmentKind.TEXT.name,
                displayName = "outside.txt",
                mimeType = "text/plain",
                filePath = outside.absolutePath
            )

            assertNull(store.restore(descriptor))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun clearDeletesPersistedPayload() {
        val persisted = store.persist(
            SelectedAttachment(
                kind = SelectedAttachmentKind.TEXT,
                displayName = "note.txt",
                mimeType = "text/plain",
                base64 = Base64.getEncoder().encodeToString("note".toByteArray())
            )
        )!!
        val file = File(persisted.draftFilePath!!)
        assertTrue(file.isFile)

        store.clear(listOf(persisted))

        assertFalse(file.exists())
    }
}
