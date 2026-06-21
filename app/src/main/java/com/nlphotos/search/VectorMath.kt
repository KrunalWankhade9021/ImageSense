package com.nlphotos.search
import kotlin.math.sqrt
fun l2Normalize(v: FloatArray): FloatArray {
    var s = 0f; for (x in v) s += x * x
    val n = sqrt(s); if (n == 0f) return v
    return FloatArray(v.size) { v[it] / n }
}
fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
}
