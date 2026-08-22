package org.ramgpt.cad2cnycam

import java.math.BigDecimal

data class PriceCandidate(val source: String, val amount: BigDecimal)

object PriceParser {
    // Bundle prices are interpreted as the total shown (e.g. 2/$10 means CAD 10 total).
    private val bundlePattern = Regex("""(?i)\b(\d{1,2})\s*[/\\]\s*\$?\s*(\d{1,4}(?:[.,]\d{1,2})?)\b""")
    private val dollarPattern = Regex("""(?<![\d.])\$\s*(\d{1,4}(?:[.,]\d{1,2})?)(?!\d)""")
    private val decimalPattern = Regex("""(?<![\d.])(\d{1,4}[.,]\d{2})(?!\d)""")

    fun find(text: String): PriceCandidate? {
        bundlePattern.find(text)?.let { match ->
            val quantity = match.groupValues[1]
            val rawPrice = match.groupValues[2].replace(',', '.')
            return rawPrice.toBigDecimalOrNull()?.takeIf(::reasonable)?.let {
                PriceCandidate("$quantity/\$$rawPrice", it)
            }
        }
        dollarPattern.find(text)?.let { match ->
            val rawPrice = match.groupValues[1].replace(',', '.')
            return rawPrice.toBigDecimalOrNull()?.takeIf(::reasonable)?.let {
                PriceCandidate("\$$rawPrice", it)
            }
        }
        decimalPattern.find(text)?.let { match ->
            val rawPrice = match.groupValues[1].replace(',', '.')
            return rawPrice.toBigDecimalOrNull()?.takeIf(::reasonable)?.let {
                PriceCandidate(rawPrice, it)
            }
        }
        return null
    }

    private fun reasonable(value: BigDecimal) = value > BigDecimal.ZERO && value <= BigDecimal("9999.99")
}

enum class TaxClass { ZERO_RATED, TAXABLE, UNKNOWN }
enum class TaxOverrideMode { AUTO, ZERO_PERCENT, THIRTEEN_PERCENT }

object TaxClassifier {
    private val zeroRated = listOf(
        "butter", "milk", "egg", "eggs", "bread", "meat", "beef", "pork", "chicken",
        "vegetable", "vegetables", "fruit", "cheese", "rice", "flour", "fish", "yogurt"
    )
    private val taxable = listOf(
        "candy", "confectionery", "soft drink", "soft drinks", "soda", "chips", "snack",
        "prepared hot", "hot food", "heated", "pop", "chocolate bar", "merchandise"
    )

    fun classify(productText: String): TaxClass {
        val normalized = normalize(productText)
        if (normalized.isEmpty()) return TaxClass.UNKNOWN
        if (taxable.any { containsTerm(normalized, it) }) return TaxClass.TAXABLE
        if (zeroRated.any { containsTerm(normalized, it) }) return TaxClass.ZERO_RATED
        return TaxClass.UNKNOWN
    }

    internal fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    internal fun containsTerm(text: String, term: String): Boolean {
        val normalizedText = normalize(text)
        val normalizedTerm = normalize(term)
        if (normalizedTerm.isEmpty()) return false
        return (" " + normalizedText + " ").contains(" " + normalizedTerm + " ")
    }
}
