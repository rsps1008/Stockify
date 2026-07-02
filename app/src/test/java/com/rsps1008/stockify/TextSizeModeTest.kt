package com.rsps1008.stockify

import com.rsps1008.stockify.data.TextSizeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSizeModeTest {

    @Test
    fun normalizeFallsBackToDefault() {
        assertEquals(TextSizeMode.DEFAULT, TextSizeMode.normalize(null))
        assertEquals(TextSizeMode.DEFAULT, TextSizeMode.normalize(""))
        assertEquals(TextSizeMode.DEFAULT, TextSizeMode.normalize("unknown"))
    }

    @Test
    fun scaleMatchesMode() {
        assertEquals(0.9f, TextSizeMode.scale(TextSizeMode.SMALL), 0.0001f)
        assertEquals(1.0f, TextSizeMode.scale(TextSizeMode.DEFAULT), 0.0001f)
        assertEquals(1.15f, TextSizeMode.scale(TextSizeMode.LARGE), 0.0001f)
        assertEquals(1.3f, TextSizeMode.scale(TextSizeMode.EXTRA_LARGE), 0.0001f)
    }
}
