package app.zhijuan.reader.ui.onboarding

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.reader.connection.ConnectionServiceChoice
import app.zhijuan.reader.connection.ConnectionCommitResult
import app.zhijuan.reader.connection.ConnectionWizardActions
import app.zhijuan.reader.connection.ConnectionWizardCheckResult
import app.zhijuan.reader.connection.ConnectionWizardInput
import app.zhijuan.reader.connection.ConnectionWizardInputError
import app.zhijuan.reader.connection.FullConnectionCheckResult
import app.zhijuan.reader.connection.PendingConnectionSnapshot
import app.zhijuan.reader.connection.SavedConnectionSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class ConnectionSourceChoice {
    OFFICIAL,
    RELAY,
}

private sealed interface WizardStatus {
    data object Idle : WizardStatus
    data object Checking : WizardStatus
    data class Failed(val message: String) : WizardStatus
    data class Ready(val detail: String) : WizardStatus
    data class Manual(val detail: String) : WizardStatus
    data class CommitFailed(val detail: String) : WizardStatus
}

@Composable
fun ConnectionWizardScreen(
    gateway: ConnectionWizardActions,
    onBack: () -> Unit,
    onConnectionSaved: (SavedConnectionSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(ConnectionSourceChoice.OFFICIAL) }
    var service by rememberSaveable { mutableStateOf(ConnectionServiceChoice.DEEPSEEK) }
    var relayBaseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasStoredCredential by rememberSaveable { mutableStateOf(false) }
    var secretTail by rememberSaveable { mutableStateOf("") }
    var status: WizardStatus by remember { mutableStateOf(WizardStatus.Idle) }
    var pending by remember { mutableStateOf<PendingConnectionSnapshot?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showFullCheckConfirmation by remember { mutableStateOf(false) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var isCommitting by remember { mutableStateOf(false) }

    fun currentInput() = ConnectionWizardInput(
        service = if (source == ConnectionSourceChoice.RELAY) ConnectionServiceChoice.RELAY else service,
        relayBaseUrl = relayBaseUrl,
    )

    fun clearResult() {
        status = WizardStatus.Idle
        pending = null
        availableModels = emptyList()
        selectedModel = ""
        hasStoredCredential = false
        secretTail = ""
    }

    fun abandonAndThen(action: () -> Unit) {
        runningJob?.cancel()
        runningJob = scope.launch {
            gateway.discardPending()
            clearResult()
            action()
        }
    }

    fun applyResult(result: ConnectionWizardCheckResult) {
        when (result) {
            is ConnectionWizardCheckResult.Ready -> {
                pending = result.pending
                availableModels = result.models
                selectedModel = result.recommendedModel
                hasStoredCredential = true
                secretTail = result.pending.secretLastFour
                status = WizardStatus.Ready("已找到 ${result.models.size} 个模型，并替你选好了一个。")
            }
            is ConnectionWizardCheckResult.ManualModelAllowed -> {
                pending = result.pending
                availableModels = emptyList()
                selectedModel = ""
                hasStoredCredential = true
                secretTail = result.pending.secretLastFour
                status = WizardStatus.Manual("服务端未提供模型列表，可手动填写模型名称后继续。")
            }
            is ConnectionWizardCheckResult.Failed -> {
                pending = result.pendingCredential
                hasStoredCredential = result.pendingCredential != null
                secretTail = result.pendingCredential?.secretLastFour.orEmpty()
                status = WizardStatus.Failed(errorMessage(result.error))
            }
            is ConnectionWizardCheckResult.TimedOut -> {
                pending = result.pendingCredential
                hasStoredCredential = result.pendingCredential != null
                secretTail = result.pendingCredential?.secretLastFour.orEmpty()
                status = WizardStatus.Failed("连接检查超过 60 秒。地址和密钥尾号已保留，可以直接重试。")
            }
            is ConnectionWizardCheckResult.InvalidInput -> {
                status = WizardStatus.Failed(inputErrorMessage(result.reason))
            }
        }
    }

    fun startCheck() {
        if (status is WizardStatus.Checking) return
        status = WizardStatus.Checking
        val keyChars = apiKey.takeIf { !hasStoredCredential }?.toCharArray()
        apiKey = ""
        runningJob = scope.launch {
            val result = gateway.check(currentInput(), keyChars)
            applyResult(result)
        }
    }

    BackHandler(enabled = !isCommitting) { abandonAndThen(onBack) }

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
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                TextButton(
                    onClick = { abandonAndThen(onBack) },
                    enabled = !isCommitting,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("返回")
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = "连接一个模型服务",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "选服务、粘贴密钥，剩下的交给织卷。默认检查不会生成内容，也不会产生生成费用。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))
                SectionLabel("1  选择来源")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = source == ConnectionSourceChoice.OFFICIAL,
                        onClick = {
                            if (source != ConnectionSourceChoice.OFFICIAL) {
                                abandonAndThen { source = ConnectionSourceChoice.OFFICIAL }
                            }
                        },
                        label = { Text("官方服务") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = source == ConnectionSourceChoice.RELAY,
                        onClick = {
                            if (source != ConnectionSourceChoice.RELAY) {
                                abandonAndThen { source = ConnectionSourceChoice.RELAY }
                            }
                        },
                        label = { Text("兼容中转") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (source == ConnectionSourceChoice.OFFICIAL) {
                    OfficialServiceChooser(
                        selected = service,
                        onSelected = { choice ->
                            if (choice != service) abandonAndThen { service = choice }
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = relayBaseUrl,
                        onValueChange = { value ->
                            relayBaseUrl = value
                            if (pending != null) abandonAndThen {}
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("relay-base-url"),
                        label = { Text("中转站基础地址") },
                        placeholder = { Text("https://example.com/v1") },
                        supportingText = { Text("只允许 HTTPS；地址中不要放密钥。") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }

                Spacer(Modifier.height(28.dp))
                SectionLabel("2  填写密钥")
                if (hasStoredCredential) {
                    StoredCredentialCard(
                        tail = secretTail,
                        onReplace = {
                            abandonAndThen {
                                apiKey = ""
                            }
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api-key"),
                        label = { Text("API 密钥") },
                        supportingText = { Text("密钥会立即转入 Android 安全存储，不写入普通设置或日志。") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = ::startCheck,
                    enabled = status !is WizardStatus.Checking &&
                        !isCommitting &&
                        (hasStoredCredential || apiKey.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("check-connection"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (status is WizardStatus.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("正在检查…")
                    } else {
                        Text(if (hasStoredCredential) "重新检查" else "检查并获取模型")
                    }
                }

                Spacer(Modifier.height(20.dp))
                when (val currentStatus = status) {
                    WizardStatus.Idle,
                    WizardStatus.Checking,
                    -> Unit
                    is WizardStatus.Failed -> StatusCard(
                        title = "还没有连上",
                        detail = currentStatus.message,
                        isError = true,
                    )
                    is WizardStatus.CommitFailed -> StatusCard(
                        title = "连接没有保存",
                        detail = currentStatus.detail,
                        isError = true,
                    )
                    is WizardStatus.Ready -> {
                        StatusCard(
                            title = "基础检查已通过",
                            detail = currentStatus.detail,
                            isError = false,
                        )
                        Spacer(Modifier.height(20.dp))
                        ModelSelectionCard(
                            selectedModel = selectedModel,
                            modelCount = availableModels.size,
                            onChange = { showModelPicker = true },
                        )
                    }
                    is WizardStatus.Manual -> {
                        StatusCard(
                            title = "可以继续，但模型尚未验证",
                            detail = currentStatus.detail,
                            isError = false,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = { selectedModel = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual-model"),
                            label = { Text("模型名称") },
                            supportingText = { Text("请按中转站说明填写，例如它提供的模型 ID。") },
                            singleLine = true,
                        )
                    }
                }

                if (pending != null && selectedModel.isNotBlank() && status !is WizardStatus.Failed) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { showFullCheckConfirmation = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("高级：做一次更完整的验证")
                    }
                    Text(
                        text = "会发送固定测试语句，输出最多 16 tokens，可能产生极少费用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (isCommitting) return@Button
                            isCommitting = true
                            runningJob = scope.launch {
                                when (val result = gateway.commitPending(selectedModel.trim())) {
                                    is ConnectionCommitResult.Saved -> onConnectionSaved(result.connection)
                                    is ConnectionCommitResult.InvalidInput -> {
                                        isCommitting = false
                                        status = WizardStatus.CommitFailed(inputErrorMessage(result.reason))
                                    }
                                    ConnectionCommitResult.Failed -> {
                                        isCommitting = false
                                        status = WizardStatus.CommitFailed(
                                            "安全密钥仍保留在本次向导中，可以直接重试。",
                                        )
                                    }
                                }
                            }
                        },
                        enabled = !isCommitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(if (isCommitting) "正在保存…" else "使用此连接")
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = availableModels,
            selectedModel = selectedModel,
            onSelect = {
                selectedModel = it
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showFullCheckConfirmation) {
        AlertDialog(
            onDismissRequest = { showFullCheckConfirmation = false },
            title = { Text("确认完整验证") },
            text = { Text("织卷会向当前服务发送固定测试语句，并把输出严格限制为最多 16 tokens。服务商可能收取极少费用。不会发送小说或人物资料。") },
            confirmButton = {
                Button(
                    onClick = {
                        showFullCheckConfirmation = false
                        status = WizardStatus.Checking
                        runningJob = scope.launch {
                            status = when (val result = gateway.runFullCheck(selectedModel)) {
                                is FullConnectionCheckResult.Verified -> WizardStatus.Ready(
                                    if (result.usageObserved) {
                                        "完整验证已通过，并收到了用量信息。"
                                    } else {
                                        "完整验证已通过；服务端没有返回用量信息。"
                                    },
                                )
                                is FullConnectionCheckResult.Failed -> WizardStatus.Failed(
                                    errorMessage(result.error),
                                )
                                FullConnectionCheckResult.TimedOut -> WizardStatus.Failed(
                                    "完整验证超过 60 秒，未自动重发。",
                                )
                                is FullConnectionCheckResult.InvalidInput -> WizardStatus.Failed(
                                    inputErrorMessage(result.reason),
                                )
                            }
                        }
                    },
                ) {
                    Text("确认并验证")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullCheckConfirmation = false }) {
                    Text("暂不验证")
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun OfficialServiceChooser(
    selected: ConnectionServiceChoice,
    onSelected: (ConnectionServiceChoice) -> Unit,
) {
    val choices = listOf(
        ConnectionServiceChoice.DEEPSEEK to "DeepSeek",
        ConnectionServiceChoice.OPENAI to "OpenAI",
        ConnectionServiceChoice.ANTHROPIC to "Anthropic",
        ConnectionServiceChoice.GEMINI to "Gemini",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.chunked(2).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowChoices.forEach { (choice, label) ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clickable { onSelected(choice) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected == choice) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            width = if (selected == choice) 2.dp else 1.dp,
                            color = if (selected == choice) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == choice, onClick = null)
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredCredentialCard(
    tail: String,
    onReplace: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("密钥已安全保存", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "••••$tail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReplace, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("更换")
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    isError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelSelectionCard(
    selectedModel: String,
    modelCount: Int,
    onChange: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("推荐模型", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = selectedModel,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "共找到 $modelCount 个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("更换")
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(models, filter) {
        models.filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column {
                if (models.size > 8) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索模型") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it }) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onSelect(model) }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = model == selectedModel,
                                onClick = { onSelect(model) },
                            )
                            Text(
                                text = model,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun errorMessage(error: StandardErrorCode): String = when (error) {
    StandardErrorCode.NETWORK_OFFLINE -> "当前没有网络。地址和密钥尾号已保留，联网后可直接重试。"
    StandardErrorCode.DNS_FAILED -> "找不到这个服务地址，请检查地址或网络后重试。"
    StandardErrorCode.TLS_FAILED -> "安全连接没有建立。为保护密钥，织卷不会忽略证书问题。"
    StandardErrorCode.AUTH_FAILED -> "密钥没有通过验证，请更换密钥后重试。"
    StandardErrorCode.MODEL_NOT_FOUND -> "没有找到可用模型，请确认所选服务是否正确。"
    StandardErrorCode.PROTOCOL_MISMATCH -> "这个地址的接口格式与当前选择不一致，请检查服务类型或基础地址。"
    StandardErrorCode.RATE_LIMITED -> "服务请求过于频繁，请稍后直接重试。"
    StandardErrorCode.QUOTA_EXHAUSTED -> "服务商提示额度不足，请先在服务商处处理额度。"
    StandardErrorCode.SERVER_OVERLOADED -> "服务暂时繁忙，请稍后直接重试。"
    StandardErrorCode.POLICY_REFUSAL -> "服务商拒绝了完整验证请求；基础连接结果不受影响。"
    StandardErrorCode.CREDENTIAL_UNAVAILABLE -> "系统暂时无法读取安全存储中的密钥，请更换密钥。"
    StandardErrorCode.CONTEXT_TOO_LARGE,
    StandardErrorCode.OUTPUT_TRUNCATED,
    StandardErrorCode.FORMAT_INVALID,
    StandardErrorCode.STREAM_INTERRUPTED,
    StandardErrorCode.BUDGET_EXCEEDED,
    StandardErrorCode.UNKNOWN_RESULT,
    -> "没有拿到可靠结果。织卷不会自动重复发送，请直接重试一次。"
}

private fun inputErrorMessage(error: ConnectionWizardInputError): String = when (error) {
    ConnectionWizardInputError.API_KEY_REQUIRED -> "请先粘贴 API 密钥。"
    ConnectionWizardInputError.API_KEY_INVALID -> "密钥格式不正确；请粘贴完整密钥，不要包含空格。"
    ConnectionWizardInputError.BASE_URL_REQUIRED -> "请填写中转站提供的基础地址。"
    ConnectionWizardInputError.BASE_URL_INVALID -> "基础地址格式不正确。请使用完整 HTTPS 地址，且不要带密钥、查询参数或锚点。"
    ConnectionWizardInputError.NO_MODELS_RETURNED -> "服务返回了空模型列表，暂时无法自动选择。"
    ConnectionWizardInputError.MODEL_ID_INVALID -> "模型名称为空或格式不正确。"
    ConnectionWizardInputError.NO_PENDING_CONNECTION -> "临时连接已失效，请重新检查。"
}
