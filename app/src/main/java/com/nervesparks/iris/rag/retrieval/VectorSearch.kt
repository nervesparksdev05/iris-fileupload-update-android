package com.nervesparks.iris.rag.retrieval

import kotlin.math.min

object VectorSearch {

    fun dot(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var s = 0.0
        for (i in 0 until n) s += (a[i] * b[i]).toDouble()
        return s
    }

    fun dotPackedLE(query: FloatArray, packed: ByteArray, offsetBytes: Int, dim: Int = query.size): Double {
        val n = min(dim, query.size)
        var s = 0.0
        var off = offsetBytes
        for (i in 0 until n) {
            val bits =
                (packed[off].toInt() and 0xFF) or
                        ((packed[off + 1].toInt() and 0xFF) shl 8) or
                        ((packed[off + 2].toInt() and 0xFF) shl 16) or
                        ((packed[off + 3].toInt() and 0xFF) shl 24)
            val v = Float.fromBits(bits)
            s += (query[i] * v).toDouble()
            off += 4
        }
        return s
    }
}
