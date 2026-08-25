package com.rsps1008.stockify

import com.rsps1008.stockify.data.finiteOrZero
import com.rsps1008.stockify.data.isFinitePositive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericValidationSupportTest {
    @Test
    fun finitePositiveAcceptsOnlyPositiveFiniteNumbers() {
        assertTrue(1.0.isFinitePositive())
        assertFalse(0.0.isFinitePositive())
        assertFalse((-1.0).isFinitePositive())
        assertFalse(Double.NaN.isFinitePositive())
        assertFalse(Double.POSITIVE_INFINITY.isFinitePositive())
        assertFalse((null as Double?).isFinitePositive())
    }

    @Test
    fun finiteOrZeroKeepsFiniteChangesAndRejectsNonFiniteChanges() {
        assertEquals(-1.5, (-1.5).finiteOrZero(), 0.0)
        assertEquals(0.0, Double.NaN.finiteOrZero(), 0.0)
        assertEquals(0.0, Double.NEGATIVE_INFINITY.finiteOrZero(), 0.0)
        assertEquals(0.0, (null as Double?).finiteOrZero(), 0.0)
    }
}
