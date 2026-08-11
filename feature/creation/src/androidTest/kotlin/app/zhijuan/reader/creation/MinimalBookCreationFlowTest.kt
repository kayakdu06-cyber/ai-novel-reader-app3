package app.zhijuan.reader.creation

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.reader.ui.creation.MinimalBookCreationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinimalBookCreationFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultPathNeedsOnlyOneStoryIdea() {
        var submitted: MinimalBookDraft? = null
        showCreation(onStartBook = { submitted = it })

        composeRule.onNodeWithText("织一本新书").assertIsDisplayed()
        composeRule.onNodeWithText("当前连接 · DeepSeek 写作").assertIsDisplayed()
        composeRule.onAllNodesWithText("成人", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag("advanced-characters").assertCountEquals(0)
        composeRule.onNodeWithTag("story-idea").performTextInput("雨夜重逢后，两个人被困在海边旧旅馆。")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("雨夜重逢后，两个人被困在海边旧旅馆。", submitted?.storyIdea)
            assertNull(submitted?.genreId)
            assertEquals(BookLengthMode.MEDIUM, submitted?.lengthMode)
            assertEquals(300, submitted?.minimumChapterCount)
            assertEquals(300, submitted?.targetChapterCount)
            assertEquals(1, submitted?.lengthPolicySchemaVersion)
            assertEquals(BookPresentationPreset.BALANCED, submitted?.presentationPreset)
            assertEquals(3, submitted?.presentationDirective?.narrativeDetailLevel)
            assertEquals(2, submitted?.presentationDirective?.intimacyDetailLevel)
            assertEquals(FadePolicy.ALLOW, submitted?.presentationDirective?.fadePolicy)
            assertEquals(
                ContentPresentationMappingV1.PRESENTATION_MAPPING_SCHEMA_VERSION,
                submitted?.presentationDirective?.presentationMappingSchemaVersion,
            )
            assertEquals(1, submitted?.optionCatalogSchemaVersion)
            assertEquals(0, submitted?.advancedDetails?.providedFieldCount)
        }
    }

    @Test
    fun longTargetStartsBlankAndMustBeEntered() {
        var submitted: MinimalBookDraft? = null
        showCreation(onStartBook = { submitted = it })

        composeRule.onNodeWithTag("story-idea").performTextInput("一段需要长线推进的故事。")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("length-LONG"))
        composeRule.onNodeWithTag("length-LONG").performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("long-chapter-target"))
        composeRule.onNodeWithText("长篇章数由你决定", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").assertIsNotEnabled()

        composeRule.runOnIdle { assertNull(submitted) }
    }

    @Test
    fun uncommonGenreLongAndDetailedAreSubmittedStructurally() {
        var submitted: MinimalBookDraft? = null
        showCreation(onStartBook = { submitted = it })

        composeRule.onNodeWithTag("story-idea").performTextInput("一群成年人穿越循环世界寻找出口。")
        composeRule.onNodeWithTag("create-book-list").performScrollToIndex(3)
        composeRule.onNodeWithTag("genre-more").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("选择题材").assertIsDisplayed()
        composeRule.onNodeWithTag("genre-dialog-list", useUnmergedTree = true).performScrollToIndex(11)
        composeRule.onNodeWithTag("genre-dialog-infinite-flow")
            .performClick()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("length-LONG"))
        composeRule.onNodeWithTag("length-LONG")
            .performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("long-chapter-target"))
        composeRule.onNodeWithTag("long-chapter-target")
            .performTextClearance()
        composeRule.onNodeWithTag("long-chapter-target").performTextInput("300")
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").assertIsNotEnabled()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("long-chapter-target"))
        composeRule.onNodeWithTag("long-chapter-target")
            .performTextClearance()
        composeRule.onNodeWithTag("long-chapter-target").performTextInput("888")
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("presentation-DETAILED"))
        composeRule.onNodeWithTag("presentation-DETAILED")
            .performClick()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book")
            .performClick()

        composeRule.runOnIdle {
            assertEquals("infinite-flow", submitted?.genreId)
            assertEquals(BookLengthMode.LONG, submitted?.lengthMode)
            assertEquals(301, submitted?.minimumChapterCount)
            assertEquals(888, submitted?.targetChapterCount)
            assertEquals(1, submitted?.lengthPolicySchemaVersion)
            assertEquals(BookPresentationPreset.DETAILED, submitted?.presentationPreset)
        }
    }

    @Test
    fun advancedDetailsStayWhenCollapsedAndSubmitAsSeparateFields() {
        var submitted: MinimalBookDraft? = null
        showCreation(onStartBook = { submitted = it })

        composeRule.onNodeWithTag("story-idea")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("两位旧友在封城中重新建立信任。"))
            }
        composeRule.onNodeWithTag("create-book-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("length-SHORT").performClick()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithTag("advanced-toggle").performClick()

        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithTag("advanced-characters")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("  顾言 29 岁；沈闻 31 岁，两人曾是搭档。  "))
            }
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-world"))
        composeRule.onNodeWithTag("advanced-world")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("近未来沿海城"))
            }
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-narrative"))
        composeRule.onNodeWithTag("advanced-narrative")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("第三人称双视角"))
            }
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-required"))
        composeRule.onNodeWithTag("advanced-required")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("保留共同调查"))
            }
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-excluded"))
        composeRule.onNodeWithTag("advanced-excluded")
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString("不要失忆"))
            }

        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithTag("advanced-toggle").performClick()
        composeRule.onNodeWithText("已填写 5 项 · 展开").assertIsDisplayed()
        composeRule.onAllNodesWithTag("advanced-characters").assertCountEquals(0)

        composeRule.onNodeWithTag("advanced-toggle").performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithText("顾言 29 岁；沈闻 31 岁，两人曾是搭档。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").performClick()

        composeRule.runOnIdle {
            val draft = requireNotNull(submitted)
            val details = draft.advancedDetails
            assertEquals(BookLengthMode.SHORT, draft.lengthMode)
            assertEquals(80, draft.minimumChapterCount)
            assertEquals(80, draft.targetChapterCount)
            assertEquals("  顾言 29 岁；沈闻 31 岁，两人曾是搭档。  ", details.charactersAndRelationships)
            assertEquals("近未来沿海城", details.worldAndBackground)
            assertEquals("第三人称双视角", details.narrativeAndStyle)
            assertEquals("保留共同调查", details.requiredElements)
            assertEquals("不要失忆", details.excludedElements)
        }
    }

    @Test
    fun draftStateSurvivesActivityRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MinimalBookCreationScreen(
                    connectionName = "DeepSeek 写作",
                    modelName = "deepseek-chat",
                    onManageConnections = {},
                    onStartBook = {},
                )
            }
        }

        composeRule.onNodeWithTag("story-idea").performTextInput("重建后仍然保留的故事")
        composeRule.onNodeWithTag("genre-mystery").performClick()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("length-LONG"))
        composeRule.onNodeWithTag("length-LONG").performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("long-chapter-target"))
        composeRule.onNodeWithTag("long-chapter-target").performTextClearance()
        composeRule.onNodeWithTag("long-chapter-target").performTextInput("999")
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("presentation-RESERVED"))
        composeRule.onNodeWithTag("presentation-RESERVED").performClick()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithTag("advanced-toggle").performClick()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithTag("advanced-characters").performTextInput("重建后仍保留的人物设定")

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("story-idea"))
        composeRule.onNodeWithText("重建后仍然保留的故事", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("length-LONG"))
        composeRule.onNodeWithTag("length-LONG").assertIsSelected()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("long-chapter-target"))
        composeRule.onNodeWithText("999", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("presentation-RESERVED"))
        composeRule.onNodeWithTag("presentation-RESERVED").assertIsSelected()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("advanced-toggle"))
        composeRule.onNodeWithText("已填写 1 项 · 收起").assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("advanced-characters"))
        composeRule.onNodeWithText("重建后仍保留的人物设定", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").assertIsEnabled()
    }

    @Test
    fun mainActionsMeetTouchTargetsAndManageConnectionWorks() {
        var manageCalls = 0
        showCreation(onManageConnections = { manageCalls += 1 })

        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book")
            .assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("manage-connections"))
        composeRule.onNodeWithTag("manage-connections")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.runOnIdle { assertEquals(1, manageCalls) }
    }

    @Test
    fun lightScreenCanBeCapturedWithoutCredentialData() {
        showCreation()
        composeRule.onAllNodesWithText("3456", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("length-MEDIUM"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageUri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "zhijuan-task034-create-${System.currentTimeMillis()}.png",
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZhijuanTests")
                },
            ),
        )
        requireNotNull(context.contentResolver.openOutputStream(imageUri)).use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test
    fun darkThemeKeepsTheWholeFlowReachable() {
        showCreation(darkTheme = true)

        composeRule.onNodeWithText("织一本新书").assertIsDisplayed()
        composeRule.onNodeWithTag("story-idea").assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list")
            .performScrollToNode(hasTestTag("presentation-DETAILED"))
        composeRule.onNodeWithText("细写").assertIsDisplayed()
        composeRule.onNodeWithTag("create-book-list").performScrollToNode(hasTestTag("start-book"))
        composeRule.onNodeWithTag("start-book").assertHeightIsAtLeast(56.dp)
    }

    private fun showCreation(
        darkTheme: Boolean = false,
        onManageConnections: () -> Unit = {},
        onStartBook: (MinimalBookDraft) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                MinimalBookCreationScreen(
                    connectionName = "DeepSeek 写作",
                    modelName = "deepseek-chat",
                    onManageConnections = onManageConnections,
                    onStartBook = onStartBook,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
