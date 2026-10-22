package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.config.ThinkingConfig
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Saves completed thinking blocks to a local JSONL file, one file per request.
 * Writing failures never break the proxied response — they are logged and the
 * request proceeds without persistence.
 */
class ThinkingLogger(config: ThinkingConfig) {

    private val logger = LoggerFactory.getLogger(ThinkingLogger::class.java)
    private val dir: Path? = config.saveDirectory?.let { Path.of(it) }

    /** Creates the output file lazily; null when saving is disabled or setup failed. */
    private fun fileFor(requestId: String): Path? {
        val base = dir ?: return null
        return try {
            Files.createDirectories(base)
            val name = "thinking-${Instant.now().toString().replace(":", "-")}-${
                requestId.takeLast(8).replace(Regex("[^a-zA-Z0-9]"), "")
            }.jsonl"
            base.resolve(name)
        } catch (e: Exception) {
            logger.warn("Thinking blocks will not be saved (cannot create {}): {}", base, e.message)
            null
        }
    }

    /**
     * Appends one JSON object per thinking block. Returns the number of blocks saved.
     * Safe to call with an empty list.
     */
    fun save(requestId: String, blocks: List<JsonObject>): Int {
        if (blocks.isEmpty()) return 0
        val file = fileFor(requestId) ?: return 0
        return try {
            Files.newBufferedWriter(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            ).use { writer ->
                for (block in blocks) {
                    writer.write(block.toString())
                    writer.write("\n")
                }
            }
            blocks.size
        } catch (e: Exception) {
            logger.warn("Failed to save thinking blocks to {}: {}", file, e.message)
            0
        }
    }
}
