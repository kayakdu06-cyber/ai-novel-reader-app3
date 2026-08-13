package app.zhijuan.reader.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.reader.library.LibraryCatalog
import kotlinx.coroutines.flow.catch

@Composable
fun LibraryScreen(
    catalog: LibraryCatalog,
    onOpenBook: (LibraryBookSummary) -> Unit,
    onCreateBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var books by remember(catalog) { mutableStateOf<List<LibraryBookSummary>?>(null) }
    var loadFailed by remember(catalog) { mutableStateOf(false) }
    LaunchedEffect(catalog) {
        catalog.observeShelf()
            .catch { loadFailed = true }
            .collect { updated ->
                books = updated
                loadFailed = false
            }
    }
    LibraryContent(
        books = books,
        loadFailed = loadFailed,
        onOpenBook = onOpenBook,
        onCreateBook = onCreateBook,
        modifier = modifier,
    )
}

@Composable
internal fun LibraryContent(
    books: List<LibraryBookSummary>?,
    loadFailed: Boolean,
    onOpenBook: (LibraryBookSummary) -> Unit,
    onCreateBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                books == null && !loadFailed -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testTag("library-loading"),
                )
                loadFailed -> LibraryEmptyState(
                    title = "书架暂时打不开",
                    detail = "本地内容没有丢失，请稍后再试。",
                    action = "新建一本",
                    onAction = onCreateBook,
                )
                books?.isEmpty() == true -> LibraryEmptyState(
                    title = "还没有书",
                    detail = "写一句故事设想，就能开始第一本。",
                    action = "织一本新书",
                    onAction = onCreateBook,
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 20.dp)
                        .testTag("library-list"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    "书架",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    "完成的章节可以立即阅读",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(
                                onClick = onCreateBook,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("library-create"),
                            ) { Text("新建") }
                        }
                    }
                    items(requireNotNull(books), key = LibraryBookSummary::bookId) { book ->
                        BookCard(book = book, onClick = { onOpenBook(book) })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: LibraryBookSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .testTag("library-book-${book.bookId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 72.dp, height = 100.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("织卷", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "已完成 ${book.completedChapterCount} / ${book.targetChapterCount} 章",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    statusText(book),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (book.generationStatus in ACTIVE_GENERATION_STATUSES) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("library-empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).heightIn(min = 56.dp),
        ) { Text(action) }
    }
}

private fun statusText(book: LibraryBookSummary): String = when (book.generationStatus) {
    GenerationJobStatus.CREATED, GenerationJobStatus.READY, GenerationJobStatus.RUNNING -> "正在生成"
    GenerationJobStatus.PAUSING -> "正在暂停"
    GenerationJobStatus.PAUSED -> "已暂停"
    GenerationJobStatus.STOPPING -> "正在停止"
    GenerationJobStatus.STOPPED -> "已停止"
    GenerationJobStatus.NEEDS_ACTION -> "需要处理"
    GenerationJobStatus.BLOCKED -> "等待条件"
    GenerationJobStatus.COMPLETED -> "本批已完成"
    null -> when (book.status) {
        BookStatus.COMPLETED -> "已完成"
        BookStatus.PAUSED -> "已暂停"
        BookStatus.ERROR -> "需要处理"
        BookStatus.DRAFT -> "尚未开始"
        else -> "可继续阅读"
    }
}

private val ACTIVE_GENERATION_STATUSES = setOf(
    GenerationJobStatus.CREATED,
    GenerationJobStatus.READY,
    GenerationJobStatus.RUNNING,
    GenerationJobStatus.PAUSING,
)
