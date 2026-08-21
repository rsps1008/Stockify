package com.rsps1008.stockify.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object JsonCacheDecodeSupport {
    inline fun <reified T> decodeOrNull(raw: String): T? =
        runCatching { Json.decodeFromString<T>(raw) }.getOrNull()
}
