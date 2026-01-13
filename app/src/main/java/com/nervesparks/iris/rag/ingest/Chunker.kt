package com.nervesparks.iris.rag.ingest

/**
 * ✅ OPTIMIZED: Better chunking strategy for improved retrieval and comprehensive responses
 *
 * Key improvements:
 * - Smaller chunks (800 chars) for more precise semantic matching
 * - More overlap (350 chars) for better context preservation
 * - Sentence-aware splitting to avoid cutting mid-sentence
 * - Paragraph-aware fallback for documents without clear sentences
 * - Section header detection for better document structure awareness
 */
object Chunker {

    data class Chunk(
        val index: Int,
        val text: String,
        val startOffset: Int = 0,  // ✅ NEW: Track position in original document
        val endOffset: Int = 0
    )

    /**
     * Main chunking function with improved semantic awareness
     */
    fun chunkText(
        text: String,
        targetChars: Int = 800,   // ✅ Reduced from 1000 for better precision
        overlapChars: Int = 350   // ✅ Increased from 300 for better context
    ): List<Chunk> {
        if (text.isBlank()) return emptyList()

        // Normalize whitespace first
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\t", " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

        if (normalized.length <= targetChars) {
            return listOf(Chunk(0, normalized, 0, normalized.length))
        }

        // ✅ Try sentence-based splitting first (best for prose documents)
        val sentenceChunks = chunkBySentences(normalized, targetChars, overlapChars)
        if (sentenceChunks.isNotEmpty()) {
            return sentenceChunks
        }

        // ✅ Fallback to paragraph-based chunking (good for structured documents)
        val paragraphChunks = chunkByParagraphs(normalized, targetChars, overlapChars)
        if (paragraphChunks.isNotEmpty()) {
            return paragraphChunks
        }

        // ✅ Last resort: fixed-size chunking with word boundary awareness
        return chunkBySize(normalized, targetChars, overlapChars)
    }

    /**
     * Sentence-based chunking - best for prose documents
     */
    private fun chunkBySentences(
        text: String,
        targetChars: Int,
        overlapChars: Int
    ): List<Chunk> {
        // Split by sentence-ending punctuation followed by whitespace
        // This regex handles: periods, exclamation marks, question marks
        // It avoids splitting on abbreviations like "Dr." or "U.S."
        val sentencePattern = Regex("""(?<=[.!?])\s+(?=[A-Z"'\(])""")

        val sentences = text.split(sentencePattern)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sentences.isEmpty() || sentences.size < 2) {
            return emptyList() // Fall back to other methods
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (sentence in sentences) {
            val wouldBeLength = if (currentChunk.isEmpty()) {
                sentence.length
            } else {
                currentChunk.length + 1 + sentence.length
            }

            if (wouldBeLength <= targetChars) {
                // Add sentence to current chunk
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentence)
            } else {
                // Current chunk is full, start new one
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.setLength(0)
                }

                // Handle very long sentences
                if (sentence.length > targetChars) {
                    // Split long sentence at word boundaries
                    val words = sentence.split(Regex("\\s+"))
                    for (word in words) {
                        if (currentChunk.isEmpty()) {
                            currentChunk.append(word)
                        } else if (currentChunk.length + 1 + word.length <= targetChars) {
                            currentChunk.append(" ").append(word)
                        } else {
                            chunks.add(currentChunk.toString().trim())
                            currentChunk.setLength(0)
                            currentChunk.append(word)
                        }
                    }
                } else {
                    currentChunk.append(sentence)
                }
            }
        }

        // Don't forget the last chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        // Apply overlap
        return applyOverlap(chunks, overlapChars)
    }

    /**
     * Paragraph-based chunking - good for structured documents
     */
    private fun chunkByParagraphs(
        text: String,
        targetChars: Int,
        overlapChars: Int
    ): List<Chunk> {
        // Split by double newlines (paragraph boundaries)
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (para in paragraphs) {
            val wouldBeLength = if (currentChunk.isEmpty()) {
                para.length
            } else {
                currentChunk.length + 2 + para.length
            }

            if (wouldBeLength <= targetChars) {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(para)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.setLength(0)
                }

                // Handle very long paragraphs
                if (para.length > targetChars) {
                    // Try to split by sentences within the paragraph
                    val subChunks = chunkBySentences(para, targetChars, 0)
                    if (subChunks.isNotEmpty()) {
                        subChunks.forEach { chunks.add(it.text) }
                    } else {
                        // Fall back to word-based splitting
                        val wordChunks = chunkBySize(para, targetChars, 0)
                        wordChunks.forEach { chunks.add(it.text) }
                    }
                } else {
                    currentChunk.append(para)
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return applyOverlap(chunks, overlapChars)
    }

    /**
     * Fixed-size chunking with word boundary awareness - last resort
     */
    private fun chunkBySize(
        text: String,
        targetChars: Int,
        overlapChars: Int
    ): List<Chunk> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (word in words) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(word)
            } else if (currentChunk.length + 1 + word.length <= targetChars) {
                currentChunk.append(" ").append(word)
            } else {
                chunks.add(currentChunk.toString().trim())
                currentChunk.setLength(0)
                currentChunk.append(word)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return applyOverlap(chunks, overlapChars)
    }

    /**
     * Apply overlap between chunks for better context preservation
     */
    private fun applyOverlap(chunks: List<String>, overlapChars: Int): List<Chunk> {
        if (chunks.size <= 1 || overlapChars <= 0) {
            return chunks.mapIndexed { i, c -> Chunk(i, c) }
        }

        val overlapped = mutableListOf<Chunk>()
        var runningOffset = 0

        for (i in chunks.indices) {
            val curr = chunks[i]

            val prevTail = if (i > 0) {
                val prev = chunks[i - 1]
                // Try to break at word boundary for cleaner overlap
                val breakPoint = findWordBoundary(prev, prev.length - overlapChars)
                if (breakPoint > 0 && breakPoint < prev.length) {
                    prev.substring(breakPoint).trim()
                } else {
                    prev.takeLast(overlapChars.coerceAtMost(prev.length))
                }
            } else ""

            val merged = if (prevTail.isNotBlank()) {
                // Add ellipsis indicator for continuity
                "...$prevTail $curr".trim()
            } else curr

            overlapped.add(Chunk(
                index = i,
                text = merged,
                startOffset = runningOffset,
                endOffset = runningOffset + curr.length
            ))

            runningOffset += curr.length
        }

        return overlapped
    }

    /**
     * Find the nearest word boundary to the given position
     */
    private fun findWordBoundary(text: String, targetPos: Int): Int {
        if (targetPos <= 0) return 0
        if (targetPos >= text.length) return text.length

        // Look for space before and after targetPos
        val spaceBefore = text.lastIndexOf(' ', targetPos)
        val spaceAfter = text.indexOf(' ', targetPos)

        return when {
            spaceBefore == -1 && spaceAfter == -1 -> targetPos
            spaceBefore == -1 -> spaceAfter
            spaceAfter == -1 -> spaceBefore
            else -> {
                // Choose the closer one
                if (targetPos - spaceBefore <= spaceAfter - targetPos) {
                    spaceBefore + 1 // Start after the space
                } else {
                    spaceAfter + 1
                }
            }
        }
    }

    /**
     * ✅ NEW: Detect if text contains section headers
     * Useful for understanding document structure
     */
    fun detectHeaders(text: String): List<String> {
        val headerPatterns = listOf(
            Regex("""^#+\s+(.+)$""", RegexOption.MULTILINE),           // Markdown headers
            Regex("""^([A-Z][A-Z\s]{2,}):?\s*$""", RegexOption.MULTILINE), // ALL CAPS HEADERS
            Regex("""^\d+\.\s+([A-Z].+)$""", RegexOption.MULTILINE),   // Numbered sections
            Regex("""^([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*):$""", RegexOption.MULTILINE) // Title Case:
        )

        val headers = mutableListOf<String>()
        for (pattern in headerPatterns) {
            pattern.findAll(text).forEach { match ->
                val header = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (header.length in 3..100 && header !in headers) {
                    headers.add(header)
                }
            }
        }
        return headers
    }

    /**
     * ✅ NEW: Get document statistics
     */
    fun getDocStats(text: String): DocStats {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val sentences = text.split(Regex("[.!?]+")).count { it.isNotBlank() }
        val paragraphs = text.split(Regex("\n{2,}")).count { it.isNotBlank() }
        val headers = detectHeaders(text)

        return DocStats(
            characterCount = text.length,
            wordCount = words,
            sentenceCount = sentences,
            paragraphCount = paragraphs,
            headerCount = headers.size,
            headers = headers
        )
    }

    data class DocStats(
        val characterCount: Int,
        val wordCount: Int,
        val sentenceCount: Int,
        val paragraphCount: Int,
        val headerCount: Int,
        val headers: List<String>
    )
}