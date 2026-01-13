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

    fun readFloatLE(bytes: ByteArray, offset: Int): Float {
        val bits =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }
}
