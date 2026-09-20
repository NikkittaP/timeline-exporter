package io.github.nikkittap.timelineexporter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.ui.theme.TimelineExporterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plaque that takes the slot under a finished export.
 *
 * Every state here is fed in by hand rather than obtained from Play: a debug
 * package has no tip products configured against it, so a test that waited for
 * real prices would verify nothing on any machine it ran on. What is worth
 * pinning down is what the plaque does with each answer Play can give, and
 * that is exactly what [PostExportAsk] hands it.
 *
 * Visible text is looked up from the app's own resources rather than written
 * out in English, the way ScreenshotTest does it. These emulators are left in
 * whatever locale the last store-screenshot run used - zh-CN, as it happens -
 * and a test that hard-codes "Not now" passes or fails on that rather than on
 * the code.
 */
@RunWith(AndroidJUnit4::class)
class TipPromptTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val appCtx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int): String = appCtx.getString(id)

    private fun products() = listOf(
        TipProduct(id = "tip_small", name = "Small Tip", formattedPrice = "$1.00", emoji = "A"),
        TipProduct(id = "tip_medium", name = "Medium Tip", formattedPrice = "$3.00", emoji = "B"),
        TipProduct(id = "tip_large", name = "Large Tip", formattedPrice = "$5.00", emoji = "C"),
    )

    private fun setPlaque(
        state: TipJarState,
        onPick: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            TimelineExporterTheme {
                PostExportPrompt(
                    ask = PostExportAsk.Tip(state = state, onPick = onPick, onDismiss = onDismiss),
                    onFeedbackClick = {},
                )
            }
        }
    }

    @Test
    fun everyAmountPlayReturnedIsOffered() {
        setPlaque(TipJarState.Loaded(products()))

        // Found by content description, which is the product's own name and
        // price: the visible label is an emoji next to a number, and reads as
        // neither to a screen reader.
        composeRule.onNodeWithContentDescription("Small Tip — $1.00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Medium Tip — $3.00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Large Tip — $5.00").assertIsDisplayed()
    }

    @Test
    fun pickingAnAmountNamesTheProductItBelongsTo() {
        val picked = mutableListOf<String>()
        setPlaque(TipJarState.Loaded(products()), onPick = { picked += it })

        composeRule.onNodeWithContentDescription("Medium Tip — $3.00").performClick()

        assertEquals(listOf("tip_medium"), picked)
    }

    @Test
    fun theAskCanAlwaysBeDeclined() {
        var declined = false
        setPlaque(TipJarState.Loaded(products()), onDismiss = { declined = true })

        composeRule.onNodeWithText(str(R.string.tip_prompt_dismiss)).performClick()

        assertTrue(declined)
    }

    @Test
    fun anAnswerWithNoAmountsInItIsNotAnAsk() {
        // Play answers OK with an empty list when the products are not
        // configured for this package, or not sold in the user's country.
        // Putting the plaque up anyway would be a question on screen with
        // nothing underneath it to answer.
        setPlaque(TipJarState.Loaded(emptyList()))

        composeRule.onNodeWithText(str(R.string.tip_prompt_title)).assertDoesNotExist()
    }

    @Test
    fun aFinishedPurchaseIsThanked() {
        setPlaque(TipJarState.ThankYou)

        composeRule.onNodeWithText(str(R.string.tipjar_thank_you)).assertIsDisplayed()
        // Nothing left to buy, so nothing left to decline.
        composeRule.onNodeWithText(str(R.string.tip_prompt_dismiss)).assertDoesNotExist()
    }
}
