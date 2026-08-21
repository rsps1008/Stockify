package com.rsps1008.stockify

import com.rsps1008.stockify.data.JsonCacheDecodeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonCacheDecodeSupportTest {
    @Test
    fun malformedJsonFallsBackToNullInsteadOfThrowing() {
        assertNull(JsonCacheDecodeSupport.decodeOrNull<Map<String, String>>("{not-json"))
    }

    @Test
    fun validJsonStillDecodes() {
        assertEquals(
            mapOf("2330" to "100.0"),
            JsonCacheDecodeSupport.decodeOrNull<Map<String, String>>("{\"2330\":\"100.0\"}")
        )
    }
}
