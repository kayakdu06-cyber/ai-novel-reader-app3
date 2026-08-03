package app.zhijuan.reader.ui.onboarding

import androidx.annotation.StringRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.zhijuan.reader.R
import app.zhijuan.reader.ui.theme.ZhijuanTheme

private data class DisclosureItem(
    val sequence: String,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

private val DisclosureItems = listOf(
    DisclosureItem("01", R.string.first_launch_local_title, R.string.first_launch_local_body),
    DisclosureItem("02", R.string.first_launch_remote_title, R.string.first_launch_remote_body),
    DisclosureItem("03", R.string.first_launch_secret_title, R.string.first_launch_secret_body),
)

@Composable
fun FirstLaunchDisclosureScreen(
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("skip-disclosure"),
                    ) {
                        Text(stringResource(R.string.first_launch_skip))
                    }
                }

                Spacer(Modifier.height(40.dp))

                Text(
                    text = stringResource(R.string.first_launch_title),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.first_launch_summary),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(32.dp))

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DisclosureItems.forEach { item ->
                        DisclosureCard(item)
                    }
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("continue-disclosure"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.first_launch_continue))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.first_launch_next_hint),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DisclosureCard(item: DisclosureItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = item.sequence,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(item.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(item.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "First launch light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun FirstLaunchLightPreview() {
    ZhijuanTheme(darkTheme = false) {
        FirstLaunchDisclosureScreen(onSkip = {}, onContinue = {})
    }
}

@Preview(name = "First launch dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun FirstLaunchDarkPreview() {
    ZhijuanTheme(darkTheme = true) {
        FirstLaunchDisclosureScreen(onSkip = {}, onContinue = {})
    }
}
