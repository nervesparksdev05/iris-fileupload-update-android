package com.nervesparks.iris.rag.ingest

object TextNormalize {
    fun normalize(text: String): String {
        return text
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
