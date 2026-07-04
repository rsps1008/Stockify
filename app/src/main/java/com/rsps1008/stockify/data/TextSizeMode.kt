package com.rsps1008.stockify.data

object TextSizeMode {
    const val EXTRA_SMALL = "EXTRA_SMALL"
    const val SMALL = "SMALL"
    const val DEFAULT = "DEFAULT"
    const val LARGE = "LARGE"
    const val EXTRA_LARGE = "EXTRA_LARGE"

    private val scaleMap = mapOf(
        EXTRA_SMALL to 0.82f,
        SMALL to 0.9f,
        DEFAULT to 1.0f,
        LARGE to 1.15f,
        EXTRA_LARGE to 1.3f
    )

    fun normalize(value: String?): String {
        return when (value?.trim()?.uppercase()) {
            EXTRA_SMALL -> EXTRA_SMALL
            SMALL -> SMALL
            LARGE -> LARGE
            EXTRA_LARGE -> EXTRA_LARGE
            DEFAULT -> DEFAULT
            else -> DEFAULT
        }
    }

    fun label(value: String?): String {
        return when (normalize(value)) {
            EXTRA_SMALL -> "特小"
            SMALL -> "小"
            DEFAULT -> "標準"
            LARGE -> "大"
            EXTRA_LARGE -> "特大"
            else -> "標準"
        }
    }

    fun scale(value: String?): Float {
        return scaleMap[normalize(value)] ?: 1.0f
    }
}
