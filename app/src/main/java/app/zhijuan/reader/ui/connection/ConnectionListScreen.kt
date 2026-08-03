package app.zhijuan.reader.ui.connection

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhijuan.reader.connection.ConnectionManagementActions
import app.zhijuan.reader.connection.ConnectionModelVerification
import app.zhijuan.reader.connection.ConnectionMutationResult
import app.zhijuan.reader.connection.SavedConnectionSnapshot
import kotlinx.coroutines.launch

@Composable
fun ConnectionListScreen(
    gateway: ConnectionManagementActions,
    onAddConnection: () -> Unit,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<SavedConnectionSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SavedConnectionSnapshot?>(null) }
    var deleting by remember { mutableStateOf<SavedConnectionSnapshot?>(null) }

    suspend fun reload() {
        loading = true
        connections = runCatching { gateway.listConnections() }
            .onFailure { message = "连接暂时无法读取，请稍后重试。" }
            .getOrDefault(emptyList())
        loading = false
    }

    fun runMutation(action: suspend () -> ConnectionMutationResult) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { action() }.getOrDefault(ConnectionMutationResult.Failed)
            message = when (result) {
                ConnectionMutationResult.Success -> null
                ConnectionMutationResult.NotFound -> "这个连接已经不存在，列表已刷新。"
                ConnectionMutationResult.InvalidInput -> "名称或模型不正确，请检查后重试。"
                ConnectionMutationResult.Failed -> "操作没有完成，请稍后重试。"
            }
            reload()
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }
    BackHandler(enabled = editing != null || deleting != null) {
        editing = null
        deleting = null
    }

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
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .testTag("connection-list"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "模型连接",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "织卷默认使用“当前”连接，平时不需要再选择。",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        onDone?.takeIf { connections.isNotEmpty() }?.let {
                            TextButton(
                                onClick = it,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("done-connections"),
                            ) {
                                Text("返回创作")
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onAddConnection,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("add-connection"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("添加新连接")
                    }
                }

                message?.let { detail ->
                    item {
                        InlineMessage(detail, onDismiss = { message = null })
                    }
                }

                when {
                    loading -> item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text("正在读取本地连接…")
                        }
                    }
                    connections.isEmpty() -> item {
                        EmptyConnectionsCard()
                    }
                    else -> items(connections, key = { it.connectionId }) { connection ->
                        ConnectionCard(
                            connection = connection,
                            enabled = !busy,
                            onSelectCurrent = {
                                runMutation { gateway.selectCurrent(connection.connectionId) }
                            },
                            onEdit = { editing = connection },
                        )
                    }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }

    editing?.let { connection ->
        EditConnectionDialog(
            connection = connection,
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                deleting = connection
            },
            onSave = { name, model ->
                editing = null
                runMutation {
                    gateway.editConnection(connection.connectionId, name, model)
                }
            },
        )
    }

    deleting?.let { connection ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个连接？") },
            text = {
                Text(
                    if (connection.isCurrent && connections.size > 1) {
                        "将删除“${connection.displayName}”及它保存的密钥，并自动把另一个连接设为当前。小说和历史章节不会删除。"
                    } else {
                        "将删除“${connection.displayName}”及它保存的密钥。小说和历史章节不会删除。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = null
                        runMutation { gateway.deleteConnection(connection.connectionId) }
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: SavedConnectionSnapshot,
    enabled: Boolean,
    onSelectCurrent: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connection-${connection.connectionId}"),
        shape = RoundedCornerShape(20.dp),
        color = if (connection.isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            if (connection.isCurrent) 2.dp else 1.dp,
            if (connection.isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = connection.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connection.isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Text(
                text = connection.selectedModelId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${connection.endpointHost}  ·  密钥 ••••${connection.secretLastFour}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = verificationLabel(connection.modelVerification),
                style = MaterialTheme.typography.labelLarge,
                color = if (connection.modelVerification == ConnectionModelVerification.MANUAL_UNVERIFIED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("edit-${connection.connectionId}"),
                ) {
                    Text("编辑")
                }
                if (!connection.isCurrent) {
                    Button(
                        onClick = onSelectCurrent,
                        enabled = enabled,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("select-${connection.connectionId}"),
                    ) {
                        Text("设为当前")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditConnectionDialog(
    connection: SavedConnectionSnapshot,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var displayName by rememberSaveable(connection.connectionId) { mutableStateOf(connection.displayName) }
    var selectedModel by rememberSaveable(connection.connectionId) { mutableStateOf(connection.selectedModelId) }
    var choosingModel by remember { mutableStateOf(false) }

    if (choosingModel) {
        ConnectionModelPickerDialog(
            models = connection.availableModels,
            selectedModel = selectedModel,
            onSelect = {
                selectedModel = it
                choosingModel = false
            },
            onDismiss = { choosingModel = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑连接") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 80) displayName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connection-name"),
                    label = { Text("连接名称") },
                    singleLine = true,
                )
                if (connection.availableModels.isEmpty()) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit-model"),
                        label = { Text("模型名称") },
                        supportingText = { Text("该服务没有提供模型列表，修改后会保持“未验证”状态。") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                } else {
                    Text("使用模型", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(
                        onClick = { choosingModel = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("edit-model-picker"),
                    ) {
                        Text(selectedModel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    text = "${connection.endpointHost}\n密钥 ••••${connection.secretLastFour}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "更换服务地址或密钥时，请先添加一个新连接并验证成功，再删除旧连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("delete-connection"),
                ) {
                    Text("删除此连接", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(displayName.trim(), selectedModel.trim()) },
                enabled = displayName.isNotBlank() && selectedModel.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ConnectionModelPickerDialog(
    models: List<String>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val filtered = remember(models, filter) {
        models.filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索") },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it }) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onSelect(model) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model == selectedModel, onClick = null)
                            Text(model, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun EmptyConnectionsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有连接", style = MaterialTheme.typography.titleMedium)
            Text(
                "添加并检查一次，之后织卷会自动使用它。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InlineMessage(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("知道了") }
        }
    }
}

private fun verificationLabel(verification: ConnectionModelVerification): String = when (verification) {
    ConnectionModelVerification.DISCOVERED -> "基础检查已通过"
    ConnectionModelVerification.MANUAL_UNVERIFIED -> "手填模型 · 尚未验证"
    ConnectionModelVerification.MINIMAL_GENERATION -> "完整验证已通过"
}
