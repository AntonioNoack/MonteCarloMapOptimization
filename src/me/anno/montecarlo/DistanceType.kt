package me.anno.montecarlo

enum class DistanceType(val maxFunction: (a: Float, b: Float) -> Float) {
    EUCLIDIAN({ a, b -> a * a + b * b }),
    MANHATTEN({ a, b -> Studio.sq(a + b) }),
    MAX_NORM({ a, b -> Studio.sq(kotlin.math.max(a, b)) }),
    POWER_3({ a, b -> a * a * a + b * b * b }),
    POWER_4({ a, b -> a * a * a * a + b * b * b * b });

    val displayName = name
}