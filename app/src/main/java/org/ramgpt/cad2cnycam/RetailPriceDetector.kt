package org.ramgpt.cad2cnycam

import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max

data class OcrSourceElement(val text: String, val boundingBox: Rect)
data class RetailPriceDetection(
    val price: PriceCandidate,
    val boundingBox: Rect,
    val sourceElements: List<OcrSourceElement>
)
data class RetailPriceCandidate(
    val price: PriceCandidate,
    val sourceBoundingBox: Rect,
    val score: Double,
    val sourceElements: List<OcrSourceElement>,
    val productText: String = "",
    val mappedPreviewBoundingBox: RectF? = null
)

data class RetailCandidateDebug(
    val sourceBoundingBox: Rect,
    val label: String,
    val accepted: Boolean
)

data class PriceDecision(
    val candidates: List<RetailPriceCandidate>,
    val diagnostics: List<String>,
    val debugCandidates: List<RetailCandidateDebug>
)

object RetailPriceDetector {
    private data class OcrItem(val text: String, val box: Rect, val lineText: String) {
        fun asSource() = OcrSourceElement(text, Rect(box))
    }
    private data class Ranked(val detection: RetailPriceDetection, val score: Double, val reason: String)
    private data class ShelfGroup(val id: Int, val bounds: Rect, val entries: MutableList<Ranked>)

    private val directPrice = Regex("""(?i)(\$\s*)?(\d{1,4})(?:\s*[.,]\s*(\d{2})(?!\d))?""")
    private val bundle = Regex("""(?i)\b(\d{1,2})\s*(?:/|for)\s*\$?\s*(\d{1,4})(?:[.,](\d{2}))?\b""")
    private val digits = Regex("""^\d{1,4}$""")
    private val cents = Regex("""^\d{2}$""")
    private val threeDecimal = Regex("""(?<!\d)\d{1,4}[.,]\d{3}(?!\d)""")
    private val percent = Regex("""\d\s*%|%\s*\d""")
    private val date = Regex("""\b\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?\b""")
    private val unit = Regex("""(?i)/(?:100\s*g|kg|lb)|per\s*(?:100\s*g|kg|lb)""")
    private val save = Regex("""(?i)\bsave\b|\bsavings?\b""")

    fun detect(text: Text): PriceDecision {
        val diagnostics = mutableListOf<String>()
        val items = text.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { element ->
                    element.boundingBox?.let { OcrItem(element.text.trim(), it, line.text) }
                }
            }
        }
        val medianHeight = items.map { it.box.height() }.sorted().let {
            if (it.isEmpty()) 1.0 else it[it.size / 2].toDouble().coerceAtLeast(1.0)
        }
        val ranked = mutableListOf<Ranked>()
        val rejectedCandidates = mutableListOf<RetailCandidateDebug>()

        text.textBlocks.flatMap { it.lines }.forEach { line ->
            val lineBox = line.boundingBox ?: return@forEach
            parseText(line.text)?.let { candidate ->
                val sources = priceSources(candidate, items.filter { Rect.intersects(it.box, lineBox) })
                val sourceBox = unionRect(sources)
                if (sourceBox != null) {
                    rank(candidate, sourceBox, line.text, line.text, sourceBox.height(), medianHeight,
                        sourceElements = sources, rejectedCandidates = rejectedCandidates, diagnostics = diagnostics)?.let(ranked::add)
                        ?: diagnostics.add("REJECT '${candidate.source}': retail context")
                }
            }
        }

        items.forEach { item ->
            if (threeDecimal.matches(item.text.trim())) {
                val reason = "REJECT reason=three_decimal_unit_price text=\"" + item.text + "\" rect=" + item.box
                diagnostics.add(reason)
                rejectedCandidates.add(RetailCandidateDebug(Rect(item.box), reason, false))
                return@forEach
            }
            parseText(item.text)?.let { candidate ->
                rank(candidate, item.box, item.text,
                    item.text,
                    item.box.height(), medianHeight,
                    sourceElements = listOf(item.asSource()), rejectedCandidates = rejectedCandidates, diagnostics = diagnostics)?.let(ranked::add)
                    ?: diagnostics.add("REJECT '${candidate.source}': retail context")
            }
        }

        // Retail tags often render whole dollars in large type and cents separately.
        items.filter { digits.matches(it.text) }.forEach { whole ->
            items.filter { it !== whole && cents.matches(it.text) }.forEach { fraction ->
                if (nearbyCents(whole.box, fraction.box)) {
                    val amount = "${whole.text}.${fraction.text}".toBigDecimalOrNull() ?: return@forEach
                    if (reasonable(amount)) {
                        val hasDollar = items.any { it.text.contains('$') && close(it.box, whole.box) }
                        val source = (if (hasDollar) "$" else "") + amount.toPlainString()
                        val combinedBox = Rect(whole.box).apply {
                            union(fraction.box)
                            items.firstOrNull { it.text.contains('$') && close(it.box, whole.box) }
                                ?.let { union(it.box) }
                        }
                        val context = whole.lineText + " " + fraction.lineText
                        val sourceElements = buildList {
                            add(whole.asSource())
                            add(fraction.asSource())
                            items.firstOrNull { it.text.contains('$') && close(it.box, whole.box) }
                                ?.let { add(it.asSource()) }
                        }
                        rank(PriceCandidate(source, amount), combinedBox, source, context,
                            max(whole.box.height(), fraction.box.height()), medianHeight,
                            geometryBonus = 24.0, sourceElements = sourceElements, rejectedCandidates = rejectedCandidates, diagnostics = diagnostics)?.let(ranked::add)
                    }
                }
            }
        }

        if (ranked.isEmpty()) {
            val fallback = PriceParser.find(text.text)
            val fallbackSources = fallback?.let { priceSources(it, items) }.orEmpty()
            if (fallback != null) unionRect(fallbackSources)?.let { box ->
                ranked.add(Ranked(RetailPriceDetection(fallback, box, fallbackSources), 1.0, "text fallback"))
            }
        }

        val debugCandidates = rejectedCandidates.toMutableList()
        val groups = mutableListOf<ShelfGroup>()
        ranked.sortedByDescending { it.detection.boundingBox.height() }.forEach { entry ->
            val box = entry.detection.boundingBox
            val group = groups.firstOrNull { sameShelfLabel(it.bounds, box) }
            if (group == null) {
                groups += ShelfGroup(groups.size + 1, Rect(box), mutableListOf(entry))
            } else {
                group.bounds.union(box)
                group.entries += entry
            }
        }
        val kept = groups.map { group ->
            diagnostics.add("LABEL_GROUP id=" + group.id + " bounds=" + group.bounds)
            items.filter { sameShelfLabel(group.bounds, it.box) }.forEach { item ->
                diagnostics.add("LABEL_GROUP id=" + group.id + " ELEMENT text=\"" + item.text +
                    "\" rect=" + item.box)
                if (threeDecimal.matches(item.text.trim())) {
                    diagnostics.add("LABEL_GROUP id=" + group.id +
                        " REJECT reason=three_decimal_unit_price text=\"" + item.text + "\"")
                }
            }
            val maxHeight = group.entries.maxOf { it.detection.boundingBox.height() }.coerceAtLeast(1)
            val winner = group.entries.maxBy { entry ->
                val box = entry.detection.boundingBox
                val sizeBonus = 45.0 * box.height() / maxHeight
                val lowerBonus = 8.0 * (box.centerY() - group.bounds.top) /
                    group.bounds.height().coerceAtLeast(1)
                entry.score + sizeBonus + lowerBonus
            }
            group.entries.filter { it !== winner }.forEach { rejected ->
                val reason = "REJECT reason=not_main_price_in_label_group groupId=" + group.id +
                    " price=" + rejected.detection.price.amount + " rect=" + rejected.detection.boundingBox
                diagnostics.add(reason)
                debugCandidates.add(RetailCandidateDebug(Rect(rejected.detection.boundingBox), reason, false))
            }
            diagnostics.add("SELECT main_price=" + winner.detection.price.amount +
                " groupId=" + group.id + " rect=" + winner.detection.boundingBox)
            debugCandidates.add(RetailCandidateDebug(Rect(winner.detection.boundingBox),
                "SELECT group=" + group.id + " price=" + winner.detection.price.amount, true))
            winner
        }
        val candidates = kept.map { entry ->
            RetailPriceCandidate(
                price = entry.detection.price,
                sourceBoundingBox = Rect(entry.detection.boundingBox),
                score = entry.score,
                sourceElements = entry.detection.sourceElements,
                productText = items.filter { sameShelfLabel(entry.detection.boundingBox, it.box) }
                    .joinToString(" ") { it.text }.trim()
            )
        }
        return PriceDecision(candidates, diagnostics, debugCandidates)
    }

    private fun parseText(value: String): PriceCandidate? {
        bundle.find(value)?.let {
            val amountText = it.groupValues[2] + it.groupValues[3].takeIf(String::isNotEmpty)?.let { cents -> ".$cents" }.orEmpty()
            return amountText.toBigDecimalOrNull()?.takeIf(::reasonable)?.let { amount ->
                PriceCandidate("${it.groupValues[1]}/\$$amountText", amount)
            }
        }
        return directPrice.findAll(value).mapNotNull { match ->
            val hasDollar = match.groupValues[1].isNotEmpty()
            val fraction = match.groupValues[3]
            if (!hasDollar && fraction.isEmpty()) return@mapNotNull null
            val raw = match.groupValues[2] + fraction.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
            raw.toBigDecimalOrNull()?.takeIf(::reasonable)?.let {
                PriceCandidate((if (hasDollar) "$" else "") + raw, it)
            }
        }.firstOrNull()
    }

    private fun rank(candidate: PriceCandidate, box: Rect, token: String, context: String, height: Int,
                     medianHeight: Double, geometryBonus: Double = 0.0,
                     sourceElements: List<OcrSourceElement>,
                     rejectedCandidates: MutableList<RetailCandidateDebug>,
                     diagnostics: MutableList<String>): Ranked? {
        val normalized = context.replace('\n', ' ')
        var score = geometryBonus + 20.0 * (height / medianHeight).coerceAtMost(4.0)
        val reasons = mutableListOf<String>()
        if (token.contains('$') || candidate.source.contains('$')) {
            score += 80
            reasons += "dollar"
        }
        fun reject(reason: String): Ranked? {
            val candidateLog = "CANDIDATE price=" + candidate.amount + " score=" +
                score.toInt() + " rect=" + box + " raw=\"" + token + "\""
            val message = "FILTER rejected reason=" + reason + " price=" + candidate.amount +
                " rect=" + box
            diagnostics.add(candidateLog)
            diagnostics.add(message)
            rejectedCandidates.add(RetailCandidateDebug(Rect(box), message, false))
            return null
        }
        if (date.containsMatchIn(normalized)) return reject("date")
        if (percent.containsMatchIn(normalized)) return reject("percentage")
        if (candidate.source.filter(Char::isDigit).length >= 7) return reject("SKU/UPC length")
        if (save.containsMatchIn(normalized)) return reject("SAVE amount")
        if (unit.containsMatchIn(normalized)) return reject("unit price")
        if (normalized.replace(Regex("""\D"""), "").length >= 8) return reject("SKU/UPC context")
        if (score <= 0) return reject("score below threshold")
        return Ranked(RetailPriceDetection(candidate, Rect(box), sourceElements), score,
            reasons.joinToString().ifBlank { "visual size" })
    }

    private fun priceSources(candidate: PriceCandidate, available: List<OcrItem>): List<OcrSourceElement> {
        available.firstOrNull { item ->
            PriceParser.find(item.text)?.amount?.compareTo(candidate.amount) == 0
        }?.let { return listOf(it.asSource()) }

        val matchingLine = available.firstOrNull { item ->
            PriceParser.find(item.lineText)?.amount?.compareTo(candidate.amount) == 0
        }?.lineText ?: return emptyList()
        return available.filter { it.lineText == matchingLine }.filter { item ->
            val numericPart = item.text.filter(Char::isDigit)
            item.text.contains('$') || item.text == "/" ||
                item.text.equals("for", ignoreCase = true) ||
                (numericPart.isNotEmpty() && candidate.source.contains(numericPart))
        }.map(OcrItem::asSource)
    }

    private fun unionRect(elements: List<OcrSourceElement>): Rect? =
        elements.map { it.boundingBox }.reduceOrNull { current, box ->
            Rect(current).apply { union(box) }
        }

    private fun nearbyCents(whole: Rect, fraction: Rect): Boolean {
        val horizontalGap = fraction.left - whole.right
        val beside = horizontalGap in (-whole.width() / 5)..(whole.height() * 2) &&
            fraction.centerY() in (whole.top - whole.height())..(whole.bottom + whole.height() / 2)
        val aboveRight = fraction.centerX() >= whole.centerX() &&
            abs(fraction.bottom - whole.top) <= whole.height()
        return beside || aboveRight
    }

    private fun close(a: Rect, b: Rect): Boolean =
        abs(a.centerX() - b.centerX()) < max(a.height(), b.height()) * 2 &&
            abs(a.centerY() - b.centerY()) < max(a.height(), b.height()) * 2

    private fun sameShelfLabel(a: Rect, b: Rect): Boolean {
        if (Rect.intersects(a, b)) return true
        val horizontalGap = maxOf(a.left, b.left) - minOf(a.right, b.right)
        val verticalGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        val height = max(a.height(), b.height()).coerceAtLeast(1)
        val width = max(a.width(), b.width()).coerceAtLeast(1)
        return verticalGap <= height * 3 && horizontalGap <= max(width, height * 4)
    }

    private fun overlapRatio(a: Rect, b: Rect): Double {
        val intersection = Rect()
        if (!intersection.setIntersect(a, b)) return 0.0
        val intersectionArea = intersection.width().toDouble() * intersection.height()
        val smallerArea = minOf(
            a.width().toDouble() * a.height(),
            b.width().toDouble() * b.height()
        ).coerceAtLeast(1.0)
        return intersectionArea / smallerArea
    }

    private fun reasonable(value: BigDecimal) = value > BigDecimal.ZERO && value <= BigDecimal("9999.99")
}
