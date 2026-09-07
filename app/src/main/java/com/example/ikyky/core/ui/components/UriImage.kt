package com.example.ikyky.core.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes and displays a small local (`file://` / content) [Uri] — the
 * representative-person crops, which are a handful of app-private JPEGs, not
 * network images. Decoding runs off the main thread; the decoded bitmap
 * crossfades in over a neutral surface, and a failure just leaves the neutral
 * surface (never a crash, never a stale previous image). Unchanged decode
 * behaviour from Phase 7 — only the presentation crossfade is new.
 */
@Composable
fun UriImage(
    uri: Uri?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val bitmap = bitmapState.value
        AnimatedVisibility(
            visible = bitmap != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
