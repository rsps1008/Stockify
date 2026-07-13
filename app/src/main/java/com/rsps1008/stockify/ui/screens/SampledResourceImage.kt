package com.rsps1008.stockify.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun SampledResourceImage(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    targetWidth: Dp? = null
) {
    BoxWithConstraints(modifier = modifier) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val measuredWidthPx = with(density) { maxWidth.toPx().roundToInt() }
        val requestedWidthPx = targetWidth?.let { with(density) { it.toPx().roundToInt() } }
        val targetWidthPx = (requestedWidthPx ?: measuredWidthPx).coerceAtLeast(1)
        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            key1 = resId,
            key2 = targetWidthPx
        ) {
            value = withContext(Dispatchers.IO) {
                decodeSampledResource(context.resources, resId, targetWidthPx)
            }
        }

        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth(),
                contentScale = contentScale
            )
        }
    }
}

internal fun decodeSampledResource(
    resources: android.content.res.Resources,
    resId: Int,
    targetWidthPx: Int
): Bitmap? {
    if (targetWidthPx <= 0) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(resources, resId, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidthPx) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeResource(
        resources,
        resId,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}
