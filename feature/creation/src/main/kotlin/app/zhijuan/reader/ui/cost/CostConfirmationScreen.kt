package app.zhijuan.reader.ui.cost

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.reader.creation.BookCreationConfirmation

enum class CostEstimateState {
    PRICE_CATALOG_UNAVAILABLE,
}

data class UsageConfirmationRequest(
    val bookId: String,
    val snapshotId: String,
    val snapshotContentHash: String,
    val priceState: CostEstimateState,
    val requestTokenHardLimit: Long,
    val bookTokenHardLimit: Long,
    val dailyTokenHardLimit: Long,
    val dailyZoneId: String,
)

@Composable
fun CostConfirmationScreen(
    confirmation: BookCreationConfirmation,
    connectionName: String? = null,
    normalizedDestination: String? = null,
    onBack: () -> Unit,
    onConfirm: (UsageConfirmationRequest) -> Unit,
    modifier: Modifier = Modifier,
    isConfirming: Boolean = false,
    confirmationMessage: String? = null,
) {
    BackHandler(onBack = onBack)
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
                    .testTag("cost-confirmation-list"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("cost-confirmation-back"),
                    ) {
                        Text("返回创建页")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "开始前确认",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "《${confirmation.title}》已经保存在本机。先核对规模和模型，再进入后续生成准备。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cost-confirmation-summary"),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SummaryRow("篇幅", confirmation.lengthMode.displayName())
                            SummaryRow(
                                "章节规模",
                                "${confirmation.targetChapterCount} 章（最低 ${confirmation.minimumChapterCount} 章）",
                            )
                            SummaryRow("当前模型", confirmation.modelId)
                            connectionName?.let { SummaryRow("当前连接", it) }
                            normalizedDestination?.let { SummaryRow("数据发送到", it) }
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cost-estimate-unavailable"),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "费用暂时无法可靠估算",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "当前价格目录尚未接入。织卷不会把未知费用显示成 0 元，也不会根据模型名称猜价格。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                confirmationMessage?.let { message ->
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cost-confirmation-message"),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            onConfirm(
                                UsageConfirmationRequest(
                                    bookId = confirmation.bookId,
                                    snapshotId = confirmation.snapshotId,
                                    snapshotContentHash = confirmation.contentHash,
                                    priceState = CostEstimateState.PRICE_CATALOG_UNAVAILABLE,
                                    requestTokenHardLimit = DEFAULT_REQUEST_TOKEN_LIMIT,
                                    bookTokenHardLimit = DEFAULT_BOOK_TOKEN_LIMIT,
                                    dailyTokenHardLimit = DEFAULT_DAILY_TOKEN_LIMIT,
                                    dailyZoneId = "Asia/Shanghai",
                                ),
                            )
                        },
                        enabled = !isConfirming,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("confirm-usage"),
                    ) {
                        if (isConfirming) {
                            CircularProgressIndicator(
                                modifier = Modifier.testTag("confirm-usage-progress"),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("确认以上信息")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "确认后开始生成。单次请求最多 16000 token；全书和当日各最多 2000000 token。价格未知，达到上限会停止。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.66f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun BookLengthMode.displayName(): String = when (this) {
    BookLengthMode.SHORT -> "短篇"
    BookLengthMode.MEDIUM -> "中篇"
    BookLengthMode.LONG -> "长篇"
}

private const val DEFAULT_REQUEST_TOKEN_LIMIT = 16_000L
private const val DEFAULT_BOOK_TOKEN_LIMIT = 2_000_000L
private const val DEFAULT_DAILY_TOKEN_LIMIT = 2_000_000L

@Composable
fun CostConfirmationLoadingScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag("cost-confirmation-loading"))
        }
    }
}

@Composable
fun CostConfirmationLoadErrorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .testTag("cost-confirmation-load-error"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "暂时无法读取确认信息",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "没有开始生成，也没有调用模型。请返回创建页后再试。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cost-confirmation-error-back"),
                ) {
                    Text("返回创建页")
                }
            }
        }
    }
}
