package app.zhijuan.reader.ui.creation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.reader.creation.AdvancedCreationDetails
import app.zhijuan.reader.creation.CreationOptionCatalog
import app.zhijuan.reader.creation.DefaultCreationOptionCatalog
import app.zhijuan.reader.creation.GenreOption
import app.zhijuan.reader.creation.MinimalBookDraft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MinimalBookCreationScreen(
    connectionName: String,
    modelName: String,
    onManageConnections: () -> Unit,
    onStartBook: (MinimalBookDraft) -> Unit,
    modifier: Modifier = Modifier,
    catalog: CreationOptionCatalog = DefaultCreationOptionCatalog.value,
    isSubmitting: Boolean = false,
    startEnabled: Boolean = true,
    statusMessage: String? = null,
) {
    var storyIdea by rememberSaveable { mutableStateOf("") }
    var selectedGenreId by rememberSaveable { mutableStateOf<String?>(null) }
    var lengthName by rememberSaveable { mutableStateOf(BookLengthMode.MEDIUM.name) }
    var customLongChapterTarget by rememberSaveable { mutableStateOf("") }
    var presentationName by rememberSaveable { mutableStateOf(BookPresentationPreset.BALANCED.name) }
    var showAllGenres by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var charactersAndRelationships by rememberSaveable { mutableStateOf("") }
    var worldAndBackground by rememberSaveable { mutableStateOf("") }
    var narrativeAndStyle by rememberSaveable { mutableStateOf("") }
    var requiredElements by rememberSaveable { mutableStateOf("") }
    var excludedElements by rememberSaveable { mutableStateOf("") }

    val lengthMode = runCatching { BookLengthMode.valueOf(lengthName) }
        .getOrDefault(BookLengthMode.MEDIUM)
    val presentation = runCatching { BookPresentationPreset.valueOf(presentationName) }
        .getOrDefault(BookPresentationPreset.BALANCED)
    val parsedLongChapterTarget = customLongChapterTarget.toIntOrNull()
    val targetChapterCount = BookLengthPolicy.targetChapterCount(
        mode = lengthMode,
        customLongTarget = parsedLongChapterTarget,
    )
    val quickGenres = remember(catalog) {
        catalog.genres.filter { it.id in catalog.quickGenreIds }
    }
    val selectedGenreLabel = catalog.genres.firstOrNull { it.id == selectedGenreId }?.label
    val advancedDetails = AdvancedCreationDetails(
        charactersAndRelationships = charactersAndRelationships,
        worldAndBackground = worldAndBackground,
        narrativeAndStyle = narrativeAndStyle,
        requiredElements = requiredElements,
        excludedElements = excludedElements,
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 24.dp)
                    .testTag("create-book-list"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "织一本新书",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "先写一句你想读的故事，其余可以交给织卷。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    CurrentConnectionCard(
                        connectionName = connectionName,
                        modelName = modelName,
                        onManageConnections = onManageConnections,
                    )
                }

                item {
                    SectionTitle("故事设想", required = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = storyIdea,
                        onValueChange = { if (it.length <= MAX_STORY_IDEA_LENGTH) storyIdea = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 156.dp)
                            .testTag("story-idea"),
                        label = { Text("你想读一个什么故事？") },
                        placeholder = { Text("例如：两个多年未见的人，在一座暴雨封城的海边小镇重逢。") },
                        supportingText = {
                            Text("写一句就能开始 · ${storyIdea.length}/$MAX_STORY_IDEA_LENGTH")
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        minLines = 4,
                        maxLines = 8,
                    )
                }

                item {
                    SectionTitle("题材", helper = "可跳过，织卷会根据设想判断")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GenreChip(
                            label = "不限定",
                            selected = selectedGenreId == null,
                            onClick = { selectedGenreId = null },
                            tag = "genre-auto",
                        )
                        quickGenres.forEach { genre ->
                            GenreChip(
                                label = genre.label,
                                selected = selectedGenreId == genre.id,
                                onClick = { selectedGenreId = genre.id },
                                tag = "genre-${genre.id}",
                            )
                        }
                        GenreChip(
                            label = selectedGenreLabel
                                ?.takeIf { selectedGenreId !in catalog.quickGenreIds }
                                ?.let { "更多 · $it" }
                                ?: "更多题材",
                            selected = selectedGenreId != null && selectedGenreId !in catalog.quickGenreIds,
                            onClick = { showAllGenres = true },
                            tag = "genre-more",
                        )
                    }
                }

                item {
                    SectionTitle("篇幅", helper = "默认中篇，后续会按章动态调整")
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LengthChoice.values().forEach { choice ->
                            RadioChoiceCard(
                                title = choice.label,
                                description = choice.description,
                                selected = lengthMode == choice.mode,
                                onClick = { lengthName = choice.mode.name },
                                tag = "length-${choice.mode.name}",
                            )
                        }
                    }
                }

                if (lengthMode == BookLengthMode.LONG) {
                    item {
                        val longTargetValid = targetChapterCount != null
                        val longTargetBlank = customLongChapterTarget.isBlank()
                        OutlinedTextField(
                            value = customLongChapterTarget,
                            onValueChange = { updated ->
                                if (updated.length <= MAX_CHAPTER_INPUT_LENGTH && updated.all(Char::isDigit)) {
                                    customLongChapterTarget = updated
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("long-chapter-target"),
                            label = { Text("长篇目标章数") },
                            supportingText = {
                                Text(
                                    when {
                                        longTargetValid -> "已按 $targetChapterCount 章规划，可填写 301–10,000 章"
                                        longTargetBlank -> "长篇章数由你决定，请填写 301–10,000 之间的数字"
                                        else -> "请输入 301–10,000 之间的章数"
                                    },
                                )
                            },
                            isError = !longTargetBlank && !longTargetValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

                item {
                    SectionTitle("呈现", helper = "用克制的名称控制故事展开程度")
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PresentationChoice.values().forEach { choice ->
                            RadioChoiceCard(
                                title = choice.label,
                                description = choice.description,
                                selected = presentation == choice.preset,
                                onClick = { presentationName = choice.preset.name },
                                tag = "presentation-${choice.preset.name}",
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .semantics {
                                stateDescription = if (advancedExpanded) "已展开" else "已收起"
                            }
                            .testTag("advanced-toggle"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "再补充一点（可选）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = buildString {
                                    if (advancedDetails.providedFieldCount > 0) {
                                        append("已填写 ${advancedDetails.providedFieldCount} 项 · ")
                                    }
                                    append(if (advancedExpanded) "收起" else "展开")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (advancedExpanded) {
                    item {
                        AdvancedTextField(
                            title = "人物与关系",
                            helper = "可写姓名、年龄、身份、性格和关系。若包含亲密关系，请写明相关人物均为 18 岁以上。",
                            placeholder = "例如：顾言，29 岁，克制谨慎；沈闻，31 岁，表面冷淡。两人曾是搭档。",
                            value = charactersAndRelationships,
                            maxLength = MAX_CHARACTERS_LENGTH,
                            minLines = 4,
                            tag = "advanced-characters",
                            onValueChange = { charactersAndRelationships = it },
                        )
                    }
                    item {
                        AdvancedTextField(
                            title = "世界与背景",
                            helper = "时代、地点和规则都可以写在一起。",
                            placeholder = "例如：近未来沿海城，持续暴雨让城区与外界失联。",
                            value = worldAndBackground,
                            maxLength = MAX_WORLD_LENGTH,
                            minLines = 3,
                            tag = "advanced-world",
                            onValueChange = { worldAndBackground = it },
                        )
                    }
                    item {
                        AdvancedTextField(
                            title = "叙事与文风",
                            helper = "可写视角、人称、节奏或你喜欢的文字感觉。",
                            placeholder = "例如：第三人称双视角，节奏舒缓，语言克制。",
                            value = narrativeAndStyle,
                            maxLength = MAX_NARRATIVE_LENGTH,
                            minLines = 3,
                            tag = "advanced-narrative",
                            onValueChange = { narrativeAndStyle = it },
                        )
                    }
                    item {
                        AdvancedTextField(
                            title = "希望保留",
                            helper = "写下故事中一定要有的情节、氛围或关系变化。",
                            placeholder = "例如：保留雨夜重逢、共同调查和开放式结局。",
                            value = requiredElements,
                            maxLength = MAX_ELEMENT_LENGTH,
                            minLines = 2,
                            tag = "advanced-required",
                            onValueChange = { requiredElements = it },
                        )
                    }
                    item {
                        AdvancedTextField(
                            title = "不想出现",
                            helper = "写下不希望出现的设定、情节或表达方式。",
                            placeholder = "例如：不要失忆，不要突然出现超自然力量。",
                            value = excludedElements,
                            maxLength = MAX_ELEMENT_LENGTH,
                            minLines = 2,
                            tag = "advanced-excluded",
                            onValueChange = { excludedElements = it },
                        )
                    }
                }

                statusMessage?.let { message ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            onStartBook(
                                MinimalBookDraft(
                                    storyIdea = storyIdea,
                                    genreId = selectedGenreId,
                                    lengthMode = lengthMode,
                                    minimumChapterCount = BookLengthPolicy.minimumChapterCount(lengthMode),
                                    targetChapterCount = requireNotNull(targetChapterCount),
                                    lengthPolicySchemaVersion = BookLengthPolicy.SCHEMA_VERSION,
                                    presentationDirective =
                                        ContentPresentationMappingV1.directiveFor(presentation),
                                    optionCatalogSchemaVersion = catalog.schemaVersion,
                                    advancedDetails = advancedDetails,
                                ),
                            )
                        },
                        enabled = storyIdea.isNotBlank() && targetChapterCount != null &&
                            !isSubmitting && startEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("start-book"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(10.dp))
                            Text("正在准备…")
                        } else {
                            Text("开始织书")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "下一步会先显示预计用量；确认前不会调用模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    if (showAllGenres) {
        GenrePickerDialog(
            genres = catalog.genres,
            selectedGenreId = selectedGenreId,
            onSelect = {
                selectedGenreId = it
                showAllGenres = false
            },
            onDismiss = { showAllGenres = false },
        )
    }

    BackHandler(enabled = showAllGenres) { showAllGenres = false }
}

@Composable
private fun AdvancedTextField(
    title: String,
    helper: String,
    placeholder: String,
    value: String,
    maxLength: Int,
    minLines: Int,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, helper = helper)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .testTag(tag),
            label = { Text(title) },
            placeholder = { Text(placeholder) },
            supportingText = { Text("可留空 · ${value.length}/$maxLength") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            minLines = minLines,
            maxLines = 8,
        )
    }
}

@Composable
private fun CurrentConnectionCard(
    connectionName: String,
    modelName: String,
    onManageConnections: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("current-connection-summary"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前连接 · $connectionName",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            TextButton(
                onClick = onManageConnections,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("manage-connections"),
            ) {
                Text("管理")
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    required: Boolean = false,
    helper: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (required) "$title · 必填" else title,
            style = MaterialTheme.typography.titleLarge,
        )
        helper?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag),
    )
}

@Composable
private fun RadioChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .testTag(tag),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenrePickerDialog(
    genres: List<GenreOption>,
    selectedGenreId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择题材") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .selectableGroup()
                    .testTag("genre-dialog-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    RadioChoiceCard(
                        title = "不限定",
                        description = "根据故事设想自动判断",
                        selected = selectedGenreId == null,
                        onClick = { onSelect(null) },
                        tag = "genre-dialog-auto",
                    )
                }
                items(genres, key = { it.id }) { genre ->
                    RadioChoiceCard(
                        title = genre.label,
                        description = "设为主要题材",
                        selected = selectedGenreId == genre.id,
                        onClick = { onSelect(genre.id) },
                        tag = "genre-dialog-${genre.id}",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private enum class LengthChoice(
    val mode: BookLengthMode,
    val label: String,
    val description: String,
) {
    SHORT(BookLengthMode.SHORT, "短篇", "最低 80 章 · 自动分卷推进"),
    MEDIUM(BookLengthMode.MEDIUM, "中篇", "最低 300 章 · 默认选择"),
    LONG(BookLengthMode.LONG, "长篇", "自定目标章数 · 最低 301 章"),
}

private enum class PresentationChoice(
    val preset: BookPresentationPreset,
    val label: String,
    val description: String,
) {
    RESERVED(BookPresentationPreset.RESERVED, "留白", "更多依靠转场、暗示与情绪余韵"),
    BALANCED(BookPresentationPreset.BALANCED, "均衡", "情节、心理与感官保持平衡"),
    DETAILED(BookPresentationPreset.DETAILED, "细写", "完整展开过程、动作与感官变化"),
}

private const val MAX_STORY_IDEA_LENGTH = 2_000
private const val MAX_CHARACTERS_LENGTH = 3_000
private const val MAX_WORLD_LENGTH = 2_000
private const val MAX_NARRATIVE_LENGTH = 1_000
private const val MAX_ELEMENT_LENGTH = 1_000
private const val MAX_CHAPTER_INPUT_LENGTH = 5
