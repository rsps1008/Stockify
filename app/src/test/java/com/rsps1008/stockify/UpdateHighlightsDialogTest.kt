package com.rsps1008.stockify

import com.rsps1008.stockify.ui.screens.UPDATE_HIGHLIGHTS_VERSION
import com.rsps1008.stockify.ui.screens.shouldShowUpdateHighlights
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHighlightsDialogTest {
    @Test
    fun highlightIsShownUntilUserDismissesIt() {
        assertTrue(shouldShowUpdateHighlights(UPDATE_HIGHLIGHTS_VERSION, null))
        assertTrue(shouldShowUpdateHighlights(UPDATE_HIGHLIGHTS_VERSION, "1.6.9"))
        assertFalse(
            shouldShowUpdateHighlights(
                UPDATE_HIGHLIGHTS_VERSION,
                UPDATE_HIGHLIGHTS_VERSION
            )
        )
    }

    @Test
    fun laterReleaseShowsMissedHighlightButNotDismissedHighlight() {
        assertTrue(shouldShowUpdateHighlights("1.7.3", null))
        assertTrue(shouldShowUpdateHighlights("1.7.3", "1.7.1"))
        assertFalse(shouldShowUpdateHighlights("1.7.3", UPDATE_HIGHLIGHTS_VERSION))
        assertFalse(shouldShowUpdateHighlights("1.7.1", null))
    }
}
