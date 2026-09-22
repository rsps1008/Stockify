package com.rsps1008.stockify

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdgeToEdgeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun portraitNavigationAndFormStayInsideSystemBars() {
        checkNavigationAndForm(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test
    fun landscapeNavigationAndFormStayInsideSystemBars() {
        checkNavigationAndForm(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    private fun checkNavigationAndForm(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("持股總覽").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("知道了，不再顯示").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithText("知道了，不再顯示").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("知道了，不再顯示").performClick()
        }
        listOf("持股總覽", "交易紀錄", "資料管理", "設定").forEach { label ->
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitForIdle()
            assertInsideSafeArea(compose.onNodeWithContentDescription(label))
        }
        compose.onNodeWithContentDescription("新增交易").performClick()
        compose.onNodeWithText("選擇帳戶").performScrollTo()
        assertInsideSafeArea(compose.onNodeWithText("選擇帳戶"))
        compose.onNodeWithText("新增下一筆").performScrollTo()
        assertInsideSafeArea(compose.onNodeWithText("新增下一筆"))
        assertInsideSafeArea(compose.onNodeWithText("新增交易"))
        saveScreenshot("form-$orientation")
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText("新增下一筆").performScrollTo()
        assertInsideSafeArea(compose.onNodeWithText("新增下一筆"))
        saveScreenshot("ime-$orientation")
        Espresso.closeSoftKeyboard()
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "edge-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertInsideSafeArea(node: SemanticsNodeInteraction) {
        compose.waitForIdle()
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        assertTrue("Node has no visible area: $bounds", bounds.width > 0 && bounds.height > 0)
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            assertTrue("Left overlaps system UI: $bounds / $insets", bounds.left >= insets.left - 1)
            assertTrue("Top overlaps system UI: $bounds / $insets", bounds.top >= insets.top - 1)
            assertTrue("Right overlaps system UI: $bounds / $insets", bounds.right <= decor.width - insets.right + 1)
            assertTrue("Bottom overlaps system UI: $bounds / $insets", bounds.bottom <= decor.height - insets.bottom + 1)
        }
    }
}
