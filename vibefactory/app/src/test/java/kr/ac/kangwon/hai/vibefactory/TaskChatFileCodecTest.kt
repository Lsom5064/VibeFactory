package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TaskChatFileCodecTest {
    @Test
    fun writesAndReadsCompressedJsonAtomically() {
        val directory = Files.createTempDirectory("task-chat-codec").toFile()
        try {
            val file = directory.resolve("task.json.gz")
            val json = """[{"body":"반복되는 채팅 내용 ${"가".repeat(2_000)}"}]"""

            TaskChatFileCodec.writeAtomically(file, json)

            assertEquals(json, TaskChatFileCodec.read(file))
            assertTrue(file.length() < json.toByteArray(Charsets.UTF_8).size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
