package app.zhijuan.reader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.reader.reading.ReaderChapterState
import app.zhijuan.reader.reading.ReaderSessionCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    coordinator: ReaderSessionCoordinator,
    book: LibraryBookSummary,
    rootJobId: String? = book.generationJobId,
    onBack: () -> Unit,
    onShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chapters by remember(book.bookId) { mutableStateOf<List<LibraryChapterSummary>>(emptyList()) }
    var selectedOrdinal by rememberSaveable(book.bookId) { mutableStateOf(1) }
    var chapterState by remember(book.bookId) { mutableStateOf<ReaderChapterState?>(null) }
    var generationStatus by remember(rootJobId) { mutableStateOf(book.generationStatus) }
    var actionPending by remember { mutableStateOf(false) }

    LaunchedEffect(book.bookId, selectedOrdinal, rootJobId) {
        while (isActive) {
            val updated = runCatching { coordinator.contents(book.bookId) }.getOrDefault(chapters)
            chapters = updated
            val selected = updated.firstOrNull { it.ordinal == selectedOrdinal }
                ?: updated.firstOrNull { it.hasReadableContent }
                ?: updated.firstOrNull()
            if (selected != null) {
                selectedOrdinal = selected.ordinal
                chapterState = runCatching { coordinator.openChapter(selected) }.getOrNull()
            }
            if (rootJobId != null) {
                generationStatus = runCatching { coordinator.generationStatus(rootJobId) }
                    .getOrNull() ?: generationStatus
            }
            delay(REFRESH_MILLIS)
        }
    }

    val scope = rememberCoroutineScope()
    ReaderContent(
        book = book,
        chapters = chapters,
        selectedOrdinal = selectedOrdinal,
        chapterState = chapterState,
        generationStatus = generationStatus,
        actionPending = actionPending,
        onBack = onBack,
        onShelf = onShelf,
        onSelectChapter = { selectedOrdinal = it.ordinal },
        onPause = rootJobId?.let { jobId ->
            {
                if (!actionPending) scope.launch {
                    actionPending = true
                    generationStatus = runCatching {
                        coordinator.pauseGeneration(jobId, now())
                    }.getOrDefault(generationStatus)
                    actionPending = false
                }
            }
        },
        onResume = rootJobId?.let { jobId ->
            {
                if (!actionPending) scope.launch {
                    actionPending = true
                    generationStatus = runCatching {
                        coordinator.resumeGeneration(jobId, now())
                    }.getOrDefault(generationStatus)
                    actionPending = false
                }
            }
        },
        onStop = rootJobId?.let { jobId ->
            {
                if (!actionPending) scope.launch {
                    actionPending = true
                    generationStatus = runCatching {
                        coordinator.stopGeneration(jobId, now())
                    }.getOrDefault(generationStatus)
                    actionPending = false
                }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderContent(
    book: LibraryBookSummary,
    chapters: List<LibraryChapterSummary>,
    selectedOrdinal: Int,
    chapterState: ReaderChapterState?,
    generationStatus: GenerationJobStatus?,
    actionPending: Boolean,
    onBack: () -> Unit,
    onShelf: () -> Unit,
    onSelectChapter: (LibraryChapterSummary) -> Unit,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var toolsVisible by rememberSaveable { mutableStateOf(true) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val selectedIndex = chapters.indexOfFirst { it.ordinal == selectedOrdinal }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.86f).testTag("reader-directory")) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(24.dp).semantics { heading() },
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(chapters, key = { _, chapter -> "${chapter.ordinal}:${chapter.chapterId}" }) {
                            _, chapter ->
                        val current = chapter.ordinal == selectedOrdinal
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.Button) {
                                    onSelectChapter(chapter)
                                    scope.launch { drawerState.close() }
                                }
                                .semantics { selected = current }
                                .testTag("directory-chapter-${chapter.ordinal}"),
                            color = if (current) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("${chapter.ordinal}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    chapter.title,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(if (current) "当前" else chapterStateLabel(chapter))
                            }
                        }
                    }
                }
            }
        },
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ChapterBody(
                    state = chapterState,
                    onToggleTools = { toolsVisible = !toolsVisible },
                    modifier = Modifier.fillMaxSize(),
                )
                if (toolsVisible) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                            Text(
                                book.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            TextButton(onClick = onShelf, modifier = Modifier.heightIn(min = 48.dp)) { Text("书架") }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolButton("目录", "reader-open-directory") { scope.launch { drawerState.open() } }
                            ToolButton("上一章", "reader-previous", enabled = selectedIndex > 0) {
                                onSelectChapter(chapters[selectedIndex - 1])
                            }
                            ToolButton("下一章", "reader-next", enabled = selectedIndex in 0 until chapters.lastIndex) {
                                onSelectChapter(chapters[selectedIndex + 1])
                            }
                            when (generationStatus) {
                                GenerationJobStatus.CREATED, GenerationJobStatus.READY,
                                GenerationJobStatus.RUNNING, GenerationJobStatus.PAUSING ->
                                    onPause?.let { ToolButton("暂停生成", "reader-pause", !actionPending, it) }
                                GenerationJobStatus.PAUSED, GenerationJobStatus.NEEDS_ACTION,
                                GenerationJobStatus.BLOCKED ->
                                    onResume?.let { ToolButton("继续生成", "reader-resume", !actionPending, it) }
                                else -> Unit
                            }
                            if (generationStatus !in setOf(null, GenerationJobStatus.COMPLETED, GenerationJobStatus.STOPPED)) {
                                onStop?.let {
                                    ToolButton("停止", "reader-stop", !actionPending) { confirmStop = true }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止生成？") },
            text = { Text("已经完成的章节会保留，尚未完成的内容不会成为正式章节。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop?.invoke()
                }) { Text("停止生成") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续写") } },
        )
    }
}

@Composable
private fun ChapterBody(
    state: ReaderChapterState?,
    onToggleTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = when (state) {
        is ReaderChapterState.Ready -> state.content
        is ReaderChapterState.Generating -> state.content
        else -> null
    }
    if (content == null) {
        Column(
            modifier = modifier.clickable(onClick = onToggleTools).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state?.chapter?.title ?: "正在准备第一章",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "下一段故事正在织，完成后会自动出现在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.testTag("reader-waiting"),
            )
        }
        return
    }
    val loadedState = requireNotNull(state)
    val paragraphs = remember(content) {
        content.split(PARAGRAPH_BOUNDARY).map(String::trim).filter(String::isNotEmpty)
    }
    LazyColumn(
        modifier = modifier.clickable(onClick = onToggleTools).testTag("reader-body"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 88.dp,
            bottom = 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                loadedState.chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.widthIn(max = 560.dp).semantics { heading() },
            )
            if (loadedState is ReaderChapterState.Generating) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "生成中正文 · 尚未定稿",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("reader-draft-label"),
                )
            }
        }
        itemsIndexed(paragraphs, key = { index, paragraph -> "$index:${paragraph.hashCode()}" }) { _, paragraph ->
            Text(
                paragraph,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp,
                lineHeight = 30.sp,
            )
        }
    }
}

@Composable
private fun ToolButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text) }
}

private fun chapterStateLabel(chapter: LibraryChapterSummary): String = when {
    chapter.hasReadableContent -> "可读"
    chapter.chapterId != null -> "生成中"
    else -> "待生成"
}

private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0L)

private val PARAGRAPH_BOUNDARY = Regex("\\n\\s*\\n|\\n")
private const val REFRESH_MILLIS = 1_000L
