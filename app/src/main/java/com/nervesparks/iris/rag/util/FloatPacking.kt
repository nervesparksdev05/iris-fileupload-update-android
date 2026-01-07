package com.nervesparks.iris.rag.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FloatPacking {
    fun floatsToBytes(arr: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in arr) bb.putFloat(f)
        return bb.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = bb.getFloat()
        return out
    }
}
