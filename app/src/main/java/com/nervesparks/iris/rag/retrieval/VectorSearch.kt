package com.nervesparks.iris.rag.retrieval

import kotlin.math.min

/**
 * ✅ OPTIMIZED: Vector search with loop unrolling for better CPU performance
 */
object VectorSearch {

    fun dot(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var s = 0.0
        // ✅ Loop unrolling for better CPU utilization
        var i = 0
        while (i + 3 < n) {
            s += (a[i] * b[i]).toDouble()
            s += (a[i + 1] * b[i + 1]).toDouble()
            s += (a[i + 2] * b[i + 2]).toDouble()
            s += (a[i + 3] * b[i + 3]).toDouble()
            i += 4
        }
        while (i < n) {
            s += (a[i] * b[i]).toDouble()
            i++
        }
        return s
    }

    /**
     * ✅ OPTIMIZED: Dot product with packed little-endian bytes
     * Loop unrolled for better CPU cache utilization
     */
    fun dotPackedLE(query: FloatArray, packed: ByteArray, offsetBytes: Int, dim: Int = query.size): Double {
        val n = min(dim, query.size)
        var s = 0.0
        var off = offsetBytes
        
        // ✅ Loop unrolling - process 4 floats at a time
        var i = 0
        while (i + 3 < n) {
            // First float
            var bits = (packed[off].toInt() and 0xFF) or
                    ((packed[off + 1].toInt() and 0xFF) shl 8) or
                    ((packed[off + 2].toInt() and 0xFF) shl 16) or
                    ((packed[off + 3].toInt() and 0xFF) shl 24)
            s += (query[i] * Float.fromBits(bits)).toDouble()
            
            // Second float
            bits = (packed[off + 4].toInt() and 0xFF) or
                    ((packed[off + 5].toInt() and 0xFF) shl 8) or
                    ((packed[off + 6].toInt() and 0xFF) shl 16) or
                    ((packed[off + 7].toInt() and 0xFF) shl 24)
            s += (query[i + 1] * Float.fromBits(bits)).toDouble()
            
            // Third float
            bits = (packed[off + 8].toInt() and 0xFF) or
                    ((packed[off + 9].toInt() and 0xFF) shl 8) or
                    ((packed[off + 10].toInt() and 0xFF) shl 16) or
                    ((packed[off + 11].toInt() and 0xFF) shl 24)
            s += (query[i + 2] * Float.fromBits(bits)).toDouble()
            
            // Fourth float
            bits = (packed[off + 12].toInt() and 0xFF) or
                    ((packed[off + 13].toInt() and 0xFF) shl 8) or
                    ((packed[off + 14].toInt() and 0xFF) shl 16) or
                    ((packed[off + 15].toInt() and 0xFF) shl 24)
            s += (query[i + 3] * Float.fromBits(bits)).toDouble()
            
            off += 16
            i += 4
        }
        
        // Handle remaining elements
        while (i < n) {
            val bits = (packed[off].toInt() and 0xFF) or
                    ((packed[off + 1].toInt() and 0xFF) shl 8) or
                    ((packed[off + 2].toInt() and 0xFF) shl 16) or
                    ((packed[off + 3].toInt() and 0xFF) shl 24)
            s += (query[i] * Float.fromBits(bits)).toDouble()
            off += 4
            i++
        }
        return s
    }
}
