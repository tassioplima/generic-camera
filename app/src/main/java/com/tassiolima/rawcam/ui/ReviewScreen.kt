package com.tassiolima.rawcam.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.tassiolima.rawcam.camera.CapturedMedia
import com.tassiolima.rawcam.camera.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(media: CapturedMedia, onBack: () -> Unit, onDelete: (CapturedMedia) -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var items by remember { mutableStateOf(listOf(media)) }

    // The bubble that opens this screen always shows the newest capture, so it's already at
    // index 0 of this query - no jump once the fuller list arrives a moment later.
    LaunchedEffect(media.uri) {
        val recent = withContext(Dispatchers.IO) { MediaStoreSaver.listRecentCaptures(context) }
        if (recent.isNotEmpty()) {
            items = if (recent.any { it.uri == media.uri }) recent else listOf(media) + recent
        }
    }

    if (items.isEmpty()) return

    val initialIndex = remember { items.indexOfFirst { it.uri == media.uri }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    val current = items.getOrNull(pagerState.currentPage) ?: media
    val mimeType = if (current.isVideo) "video/mp4" else "image/jpeg"

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            CaptureContent(items[page])
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 8.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
        }

        IconButton(
            onClick = {
                onDelete(current)
                val remaining = items.filterNot { it.uri == current.uri }
                if (remaining.isEmpty()) {
                    onBack()
                } else {
                    items = remaining
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val installedTargets = remember(current.uri) {
                    ShareTarget.entries.filter { ShareUtil.isInstalled(context, it.packageName) }
                }
                installedTargets.forEach { target ->
                    ShareButton(label = target.label, circleColor = target.brandColor) {
                        ShareUtil.shareToApp(context, current.uri, mimeType, target)
                    }
                }
                ShareButton(label = "Mais", icon = Icons.Filled.MoreHoriz, circleColor = Color.DarkGray) {
                    ShareUtil.shareGeneric(context, current.uri, mimeType)
                }
            }
        }
    }
}

@Composable
private fun CaptureContent(media: CapturedMedia) {
    if (media.isVideo) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setVideoURI(media.uri)
                    setOnPreparedListener { it.isLooping = true }
                    start()
                }
            },
        )
    } else {
        AsyncImage(
            model = media.uri,
            contentDescription = "Foto capturada",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ShareButton(
    label: String,
    circleColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Box(
                modifier = Modifier
                    .background(circleColor, shape = androidx.compose.foundation.shape.CircleShape)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = label, tint = Color.White)
                } else {
                    Text(label.first().toString(), color = Color.White)
                }
            }
        }
        Text(label, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}
