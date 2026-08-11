package app.zhijuan.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.zhijuan.reader.connection.SavedConnectionSnapshot
import app.zhijuan.reader.creation.MinimalBookDraft
import app.zhijuan.reader.creation.BookCreationActions
import app.zhijuan.reader.creation.BookCreationConfirmation
import app.zhijuan.reader.creation.BookCreationResult
import app.zhijuan.reader.connection.ConnectionGatewayActions
import app.zhijuan.reader.ui.connection.ConnectionListScreen
import app.zhijuan.reader.ui.creation.MinimalBookCreationScreen
import app.zhijuan.reader.ui.cost.CostConfirmationLoadErrorScreen
import app.zhijuan.reader.ui.cost.CostConfirmationLoadingScreen
import app.zhijuan.reader.ui.cost.CostConfirmationScreen
import app.zhijuan.reader.ui.onboarding.ConnectionWizardScreen
import app.zhijuan.reader.ui.onboarding.FirstLaunchDisclosureScreen
import app.zhijuan.reader.ui.theme.ZhijuanTheme
import kotlinx.coroutines.launch

enum class FirstLaunchDestination {
    LOADING,
    DISCLOSURE,
    CONNECTION_SETUP,
    CONNECTION_LIST,
    CREATE_BOOK,
    COST_CONFIRMATION,
}

@Composable
fun ZhijuanApp(
    connectionGateway: ConnectionGatewayActions,
    bookCreationActions: BookCreationActions? = null,
    initialDestination: FirstLaunchDestination? = null,
) {
    var destination by rememberSaveable {
        mutableStateOf(initialDestination ?: FirstLaunchDestination.LOADING)
    }
    var hasSavedConnection by remember { mutableStateOf(false) }
    var currentConnection by remember { mutableStateOf<SavedConnectionSnapshot?>(null) }
    var preparedDraft by remember { mutableStateOf<MinimalBookDraft?>(null) }
    var creationInProgress by remember { mutableStateOf(false) }
    var creationCommitted by rememberSaveable { mutableStateOf(false) }
    var creationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var loadedConfirmation by remember { mutableStateOf<BookCreationConfirmation?>(null) }
    var confirmationLoadFailed by remember { mutableStateOf(false) }
    var usageConfirmationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val saveableStateHolder = rememberSaveableStateHolder()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialDestination) {
        if (initialDestination == null && destination == FirstLaunchDestination.LOADING) {
            val savedConnections = runCatching { connectionGateway.listConnections() }
                .getOrDefault(emptyList())
            currentConnection = savedConnections.firstOrNull { it.isCurrent }
                ?: savedConnections.firstOrNull()
            hasSavedConnection = currentConnection != null
            destination = if (hasSavedConnection) {
                FirstLaunchDestination.CREATE_BOOK
            } else {
                FirstLaunchDestination.DISCLOSURE
            }
        }
    }

    LaunchedEffect(destination) {
        if (destination == FirstLaunchDestination.CREATE_BOOK) {
            val savedConnections = runCatching { connectionGateway.listConnections() }
                .getOrDefault(emptyList())
            currentConnection = savedConnections.firstOrNull { it.isCurrent }
                ?: savedConnections.firstOrNull()
            hasSavedConnection = currentConnection != null
            if (currentConnection == null) {
                destination = FirstLaunchDestination.CONNECTION_LIST
            }
        }
    }

    LaunchedEffect(destination, pendingBookId) {
        if (destination == FirstLaunchDestination.COST_CONFIRMATION) {
            loadedConfirmation = null
            confirmationLoadFailed = false
            val bookId = pendingBookId
            val actions = bookCreationActions
            if (bookId == null || actions == null) {
                confirmationLoadFailed = true
            } else {
                val loaded = runCatching { actions.loadConfirmation(bookId) }.getOrNull()
                loadedConfirmation = loaded
                confirmationLoadFailed = loaded == null
            }
        }
    }

    ZhijuanTheme {
        saveableStateHolder.SaveableStateProvider(destination.name) {
            when (destination) {
            FirstLaunchDestination.LOADING -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            FirstLaunchDestination.DISCLOSURE -> FirstLaunchDisclosureScreen(
                onSkip = { destination = FirstLaunchDestination.CONNECTION_SETUP },
                onContinue = { destination = FirstLaunchDestination.CONNECTION_SETUP },
            )
            FirstLaunchDestination.CONNECTION_SETUP -> ConnectionWizardScreen(
                gateway = connectionGateway,
                onBack = {
                    destination = if (hasSavedConnection) {
                        FirstLaunchDestination.CONNECTION_LIST
                    } else {
                        FirstLaunchDestination.DISCLOSURE
                    }
                },
                onConnectionSaved = {
                    hasSavedConnection = true
                    currentConnection = it
                    destination = FirstLaunchDestination.CREATE_BOOK
                },
            )
            FirstLaunchDestination.CONNECTION_LIST -> ConnectionListScreen(
                gateway = connectionGateway,
                onAddConnection = { destination = FirstLaunchDestination.CONNECTION_SETUP },
                onDone = if (hasSavedConnection) {
                    { destination = FirstLaunchDestination.CREATE_BOOK }
                } else {
                    null
                },
            )
            FirstLaunchDestination.CREATE_BOOK -> currentConnection?.let { connection ->
                MinimalBookCreationScreen(
                    connectionName = connection.displayName,
                    modelName = connection.selectedModelId,
                    onManageConnections = {
                        preparedDraft = null
                        creationMessage = null
                        creationCommitted = false
                        pendingBookId = null
                        loadedConfirmation = null
                        usageConfirmationMessage = null
                        destination = FirstLaunchDestination.CONNECTION_LIST
                    },
                    onStartBook = { draft ->
                        preparedDraft = draft
                        creationMessage = null
                        val actions = bookCreationActions
                        if (actions == null) {
                            creationMessage = "设置已准备好。目前没有发送给模型。"
                        } else if (!creationInProgress) {
                            creationInProgress = true
                            coroutineScope.launch {
                                val result = actions.create(draft, connection)
                                when (result) {
                                    is BookCreationResult.Created -> {
                                        pendingBookId = result.bookId
                                        creationCommitted = true
                                        usageConfirmationMessage = null
                                        destination = FirstLaunchDestination.COST_CONFIRMATION
                                    }
                                    BookCreationResult.Failed -> {
                                        creationMessage = "保存没有完成，请稍后再试。没有发送给模型。"
                                        creationCommitted = false
                                    }
                                }
                                creationInProgress = false
                            }
                        }
                    },
                    isSubmitting = creationInProgress,
                    startEnabled = !creationCommitted,
                    statusMessage = creationMessage,
                )
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            FirstLaunchDestination.COST_CONFIRMATION -> when {
                confirmationLoadFailed -> CostConfirmationLoadErrorScreen(
                    onBack = {
                        creationMessage = "创建内容已经保存在本机，尚未开始生成。"
                        destination = FirstLaunchDestination.CREATE_BOOK
                    },
                )
                loadedConfirmation == null -> CostConfirmationLoadingScreen()
                else -> CostConfirmationScreen(
                    confirmation = requireNotNull(loadedConfirmation),
                    onBack = {
                        creationMessage = "创建内容已经保存在本机，尚未开始生成。"
                        destination = FirstLaunchDestination.CREATE_BOOK
                    },
                    onConfirm = { request ->
                        val loaded = loadedConfirmation
                        usageConfirmationMessage = if (
                            loaded != null &&
                            request.bookId == loaded.bookId &&
                            request.snapshotId == loaded.snapshotId &&
                            request.snapshotContentHash == loaded.contentHash
                        ) {
                            "信息已确认。生成执行器尚未接入，当前没有调用模型。"
                        } else {
                            "确认信息已经变化，请返回创建页后再试。没有调用模型。"
                        }
                    },
                    confirmationMessage = usageConfirmationMessage,
                )
            }
            }
        }
    }
}
