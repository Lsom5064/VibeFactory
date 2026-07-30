package kr.ac.kangwon.hai.vibefactory

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object TaskChatFileCodec {
    fun read(file: File): String {
        return if (file.extension.equals("gz", ignoreCase = true)) {
            GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            file.readText(Charsets.UTF_8)
        }
    }

    fun writeAtomically(file: File, json: String) {
        val tempFile = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        GZIPOutputStream(tempFile.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json)
        }
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
