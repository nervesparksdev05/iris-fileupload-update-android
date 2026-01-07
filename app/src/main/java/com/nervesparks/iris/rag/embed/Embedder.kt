package com.nervesparks.iris.rag.embed

interface Embedder {
    fun embed(text: String): FloatArray
}
