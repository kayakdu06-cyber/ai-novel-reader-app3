package app.zhijuan.reader.m0

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun M0LongChapterReader(
    characterCount: Int,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val paragraphs = remember(characterCount) { createLongChapterParagraphs(characterCount) }
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF5EEDF))
            .testTag("long-chapter-reader"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(
            items = paragraphs,
            key = M0Paragraph::index,
            contentType = { "paragraph" },
        ) { paragraph ->
            Text(
                text = paragraph.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paragraph-${paragraph.index}"),
                color = Color(0xFF2B2721),
                fontSize = 20.sp,
                lineHeight = 34.sp,
            )
        }
    }
}

@Composable
internal fun M0ChapterDirectory(
    chapterCount: Int,
    state: LazyListState,
    selectedIndex: Int?,
    onChapterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("chapter-directory"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(
            count = chapterCount,
            key = { index -> "chapter-$index" },
            contentType = { "chapter-row" },
        ) { index ->
            val chapterNumber = index + 1
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onChapterSelected(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("chapter-row-$index"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = "第${chapterNumber}章",
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedIndex == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = if (selectedIndex == index) {
                            Modifier.semantics { heading() }
                        } else {
                            Modifier
                        },
                    )
                    Text(
                        text = "章节标题 $chapterNumber",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

internal data class M0Paragraph(
    val index: Int,
    val text: String,
)

internal fun createLongChapterParagraphs(characterCount: Int): List<M0Paragraph> {
    require(characterCount > 0)
    val seed = "长安城外风雪渐急，顾南舟沿着旧河道继续前行。他记得灯下那句未说完的话，也记得玄铁剑最后一次出现的位置。"
    val fullText = seed.repeat((characterCount / seed.length) + 1).take(characterCount)
    return fullText.chunked(PARAGRAPH_CHARACTERS).mapIndexed { index, text ->
        M0Paragraph(index, text)
    }
}

internal const val PARAGRAPH_CHARACTERS = 100
