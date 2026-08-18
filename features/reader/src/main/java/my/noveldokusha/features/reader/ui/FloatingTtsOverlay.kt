package my.noveldokusha.features.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.features.reader.manager.ReaderSession
import my.noveldokusha.reader.R

@Composable
internal fun FloatingTtsOverlayContent(
    session: ReaderSession?,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onClose: () -> Unit,
    onToggleExpand: () -> Unit,
    isExpanded: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    if (isExpanded) {
        FloatingFullReaderView(
            session = session,
            opacity = opacity,
            onOpacityChange = onOpacityChange,
            fontSize = fontSize,
            onFontSizeChange = onFontSizeChange,
            onMinimizeToBubble = onToggleExpand,
            onClose = onClose,
        )
    } else {
        FloatingBubble(
            onClick = onToggleExpand,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
    }
}

@Composable
internal fun FloatingFullReaderView(
    session: ReaderSession?,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onMinimizeToBubble: () -> Unit,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val items = session?.items ?: remember { mutableStateListOf() }

    // Auto-load next chapter when scrolled near bottom
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItemIndex to totalItems
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4) {
                session?.readerChaptersLoader?.tryLoadNext()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = opacity)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = (opacity * 1.15f).coerceAtMost(1f)),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(start = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session?.bookTitle ?: stringResource(R.string.float_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val chapterTitle = session?.currentChapter?.chapterUrl?.let { url ->
                            session.orderedChapters.find { it.url == url }?.name
                        } ?: ""
                        if (chapterTitle.isNotBlank()) {
                            Text(
                                text = chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Opacity cycle button: 100% -> 80% -> 60% -> 40% -> 100%
                    IconButton(
                        onClick = {
                            val next = when {
                                opacity >= 0.95f -> 0.75f
                                opacity >= 0.70f -> 0.50f
                                opacity >= 0.45f -> 0.30f
                                else -> 1.0f
                            }
                            onOpacityChange(next)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Opacity,
                            contentDescription = "Şeffaflık",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Font Size Minus
                    IconButton(
                        onClick = { onFontSizeChange((fontSize - 2f).coerceAtLeast(10f)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Küçült", modifier = Modifier.size(18.dp))
                    }

                    // Font Size Plus
                    IconButton(
                        onClick = { onFontSizeChange((fontSize + 2f).coerceAtMost(36f)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Büyüt", modifier = Modifier.size(18.dp))
                    }

                    // Minimize to Bubble
                    IconButton(
                        onClick = onMinimizeToBubble,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.CloseFullscreen,
                            contentDescription = "Bubble'a Küçült",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Close
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Kapat",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Novel Reader Items
            Box(modifier = Modifier.weight(1f)) {
                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { index, item ->
                                when (item) {
                                    is ReaderItem.Position -> "pos_${item.chapterIndex}_${item.chapterItemPosition}"
                                    is ReaderItem.Divider -> "div_${item.chapterIndex}_$index"
                                    is ReaderItem.Progressbar -> "prog_${item.chapterIndex}_$index"
                                    is ReaderItem.BookStart -> "start_$index"
                                    is ReaderItem.BookEnd -> "end_$index"
                                    else -> "item_$index"
                                }
                            }
                        ) { _, item ->
                            when (item) {
                                is ReaderItem.Title -> {
                                    Text(
                                        text = item.textToDisplay,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 24.dp, bottom = 12.dp)
                                    )
                                }
                                is ReaderItem.Body -> {
                                    Text(
                                        text = item.textToDisplay,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.55f).sp,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    )
                                }
                                is ReaderItem.Divider -> {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 20.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                                is ReaderItem.Progressbar -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    }
                                }
                                is ReaderItem.BookStart -> {
                                    Text(
                                        text = "── Kitap Başı ──",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    )
                                }
                                is ReaderItem.BookEnd -> {
                                    Text(
                                        text = "── Kitap Sonu ──",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp)
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            // Bottom Bar: Chapter Navigation
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = (opacity * 1.15f).coerceAtMost(1f)),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            session?.readerChaptersLoader?.tryLoadPrevious()
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Önceki", style = MaterialTheme.typography.labelMedium)
                    }

                    // Scroll To Top / Bottom quick buttons
                    Row {
                        IconButton(
                            onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Başa Dön", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val count = items.size
                                    if (count > 0) listState.animateScrollToItem(count - 1)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Sona Git", modifier = Modifier.size(20.dp))
                        }
                    }

                    Button(
                        onClick = {
                            session?.readerChaptersLoader?.tryLoadNext()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = "Sonraki", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingBubble(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = onDragEnd
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = stringResource(R.string.float_mode),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}
