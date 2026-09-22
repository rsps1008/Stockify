package com.rsps1008.stockify

import com.rsps1008.stockify.ui.screens.UPDATE_HIGHLIGHTS_VERSION
import com.rsps1008.stockify.ui.screens.shouldShowUpdateHighlights
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHighlightsDialogTest {
    @Test
    fun currentReleaseIsShownUntilUserDismissesIt() {
        assertTrue(shouldShowUpdateHighlights(UPDATE_HIGHLIGHTS_VERSION, null))
        assertTrue(shouldShowUpdateHighlights(UPDATE_HIGHLIGHTS_VERSION, "1.6.7"))
        assertFalse(
            shouldShowUpdateHighlights(
                UPDATE_HIGHLIGHTS_VERSION,
                UPDATE_HIGHLIGHTS_VERSION
            )
        )
    }

    @Test
    fun highlightsAreNotReusedForAnotherRelease() {
        assertFalse(shouldShowUpdateHighlights("1.6.9", UPDATE_HIGHLIGHTS_VERSION))
    }
}
