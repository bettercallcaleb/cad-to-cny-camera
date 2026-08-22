package org.ramgpt.cad2cnycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PriceParserTest {
    @Test fun parsesDollarPrice() {
        assertEquals(BigDecimal("7.99"), PriceParser.find("PRICE AT REGISTER $7.99")?.amount)
    }

    @Test fun parsesDecimalWithoutDollarSign() {
        assertEquals(BigDecimal("19.93"), PriceParser.find("19.93")?.amount)
    }

    @Test fun parsesCommaDecimal() {
        assertEquals(BigDecimal("8.49"), PriceParser.find("$8,49")?.amount)
    }

    @Test fun bundlePriceUsesDisplayedTotal() {
        assertEquals(BigDecimal("10"), PriceParser.find("2/$10")?.amount)
    }

    @Test fun ignoresBareIntegerAndUnreasonableValue() {
        assertNull(PriceParser.find("item 1861978"))
        assertNull(PriceParser.find("$0"))
    }
}
