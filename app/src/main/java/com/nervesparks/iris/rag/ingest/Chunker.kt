package com.nervesparks.iris.rag.ingest

/**
 * Token-aware chunking needs a tokenizer; to keep things robust,
 * this implementation uses paragraph-first splitting with char budget + overlap.
 * Works well in practice for MiniLM embeddings.
 */
object Chunker {
    data class Chunk(val index: Int, val text: String)

    fun chunkText(
        text: String,
        targetChars: Int = 1400,
        overlapChars: Int = 250
    ): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val paras = text.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val buf = StringBuilder()

        fun flush() {
            val s = buf.toString().trim()
            if (s.isNotBlank()) chunks.add(s)
            buf.setLength(0)
        }

        for (p in paras) {
            if (buf.isEmpty()) {
                buf.append(p)
            } else {
                // try append
                if (buf.length + 2 + p.length <= targetChars) {
                    buf.append("\n\n").append(p)
                } else {
                    flush()
                    buf.append(p)
                }
            }

            if (buf.length >= targetChars) {
                flush()
            }
        }
        flush()

        // add overlap by sliding window over chunk boundaries
        if (chunks.size <= 1 || overlapChars <= 0) {
            return chunks.mapIndexed { i, c -> Chunk(i, c) }
        }

        val overlapped = mutableListOf<String>()
        for (i in chunks.indices) {
            val curr = chunks[i]
            val prevTail = if (i > 0) {
                val prev = chunks[i - 1]
                prev.takeLast(overlapChars)
            } else ""
            val merged = (prevTail + "\n" + curr).trim()
            overlapped.add(merged)
        }

        return overlapped.mapIndexed { i, c -> Chunk(i, c) }
    }
}
