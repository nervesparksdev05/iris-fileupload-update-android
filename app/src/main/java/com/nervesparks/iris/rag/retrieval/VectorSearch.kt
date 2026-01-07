package com.nervesparks.iris.rag.retrieval

import kotlin.math.min

object VectorSearch {
    fun dot(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var s = 0.0
        for (i in 0 until n) s += (a[i] * b[i]).toDouble()
        return s
    }
}
