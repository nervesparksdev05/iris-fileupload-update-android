package com.nervesparks.iris.rag

data class RetrievalHit(
    val docId: String,
    val docName: String,
    val chunkId: String,
    val chunkIndex: Int,
    val text: String,
    val score: Double
)

enum class DocStatus { INDEXING, READY, FAILED }
