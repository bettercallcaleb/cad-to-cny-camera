package org.ramgpt.cad2cnycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxClassifierTest {
    @Test fun taxableTermsAreMatchedCaseInsensitively() {
        assertEquals(TaxClass.TAXABLE, TaxClassifier.classify("CANDY"))
        assertEquals(TaxClass.TAXABLE, TaxClassifier.classify("Family size soft drinks"))
        assertTrue(TaxClassifier.containsTerm("prepared HOT FOOD special", "hot food"))
    }

    @Test fun basicGroceriesAreZeroRated() {
        assertEquals(TaxClass.ZERO_RATED, TaxClassifier.classify("SALTED BUTTER 454 g"))
    }

    @Test fun arbitraryOcrGarbageCannotCreateInvalidPatterns() {
        val inputs = listOf(
            "((\$\$%%%+++/// dont quote me",
            "product (special) \$12.99 + 13% / lb",
            "1000 4 24 27 30 46 109 122 134 135 136 1000",
            "malformed OCR parentheses and apostrophes",
            ""
        )
        inputs.forEach { assertEquals(TaxClass.UNKNOWN, TaxClassifier.classify(it)) }
        assertFalse(TaxClassifier.containsTerm(inputs.first(), "soft drink"))
    }

    @Test fun phraseMatchingUsesWholeNormalizedTerms() {
        assertTrue(TaxClassifier.containsTerm("SOFT---DRINKS", "soft drinks"))
        assertFalse(TaxClassifier.containsTerm("butterfly cookies", "butter"))
        assertFalse(TaxClassifier.containsTerm("anything", "%%%"))
    }
}
