package org.ramgpt.cad2cnycam

import android.graphics.Rect
import android.graphics.RectF
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.hypot
import kotlin.math.max

data class MappedPriceObservation(
    val candidate: RetailPriceCandidate,
    val previewBoundingBox: RectF,
    val isLightBackground: Boolean
)

data class StablePriceTrack(
    val trackId: Long,
    val cadPrice: BigDecimal,
    val sourceBoundingBox: Rect,
    val previewBoundingBox: RectF,
    val score: Double,
    val sourceElements: List<OcrSourceElement>,
    val productText: String,
    val isLightBackground: Boolean,
    val missedFrames: Int
)

data class PriceTrackSnapshot(
    val trackId: Long,
    val cadPrice: BigDecimal,
    val previewBoundingBox: RectF,
    val score: Double,
    val hits: Int,
    val misses: Int,
    val isStable: Boolean
)

class MultiPriceTracker(
    private val requiredHits: Int = 3,
    private val maxMissedFrames: Int = 20,
    private val maxVisible: Int = 5
) {
    private data class Track(
        val id: Long,
        var price: BigDecimal,
        var sourceBox: Rect,
        var previewBox: RectF,
        var score: Double,
        var sourceElements: List<OcrSourceElement>,
        var productText: String,
        var isLight: Boolean,
        var hits: Int,
        var misses: Int
    )

    private val tracks = mutableListOf<Track>()
    private val lastEvents = mutableListOf<String>()
    private var nextId = 1L
    private var activeRequiredHits = requiredHits

    fun update(observations: List<MappedPriceObservation>, requiredHitsOverride: Int? = null): List<StablePriceTrack> {
        lastEvents.clear()
        activeRequiredHits = requiredHitsOverride ?: requiredHits
        val unmatchedTracks = tracks.toMutableSet()
        observations.sortedByDescending { it.candidate.score }.forEach { observation ->
            val match = unmatchedTracks
                .filter { priceSimilar(it.price, observation.candidate.price.amount) }
                .map { it to normalizedDistance(it.previewBox, observation.previewBoundingBox) }
                .filter { it.second <= 1.5 }
                .minByOrNull { it.second }
                ?.first

            if (match == null) {
                val created = Track(
                    id = nextId++,
                    price = observation.candidate.price.amount,
                    sourceBox = Rect(observation.candidate.sourceBoundingBox),
                    previewBox = RectF(observation.previewBoundingBox),
                    score = observation.candidate.score,
                    sourceElements = observation.candidate.sourceElements,
                    productText = observation.candidate.productText,
                    isLight = observation.isLightBackground,
                    hits = 1,
                    misses = 0
                )
                tracks.add(created)
                lastEvents += "TRACK created trackId=" + created.id + " price=" + created.price + " score=" + created.score.toInt() + " rect=" + created.previewBox
            } else {
                unmatchedTracks.remove(match)
                match.price = observation.candidate.price.amount
                match.sourceBox = Rect(observation.candidate.sourceBoundingBox)
                match.previewBox = smooth(match.previewBox, observation.previewBoundingBox)
                match.score = observation.candidate.score
                match.sourceElements = observation.candidate.sourceElements
                match.productText = observation.candidate.productText
                match.isLight = observation.isLightBackground
                match.hits++
                match.misses = 0
                lastEvents += "TRACK matched trackId=" + match.id + " price=" + match.price + " score=" + match.score.toInt() + " rect=" + match.previewBox
            }
        }
        unmatchedTracks.forEach { it.misses++ }
        mergeDuplicateTracks()
        tracks.filter { it.misses > maxMissedFrames }.forEach {
            lastEvents += "TRACK dropped trackId=" + it.id + " price=" + it.price + " reason=timeout"
        }
        tracks.removeAll { it.misses > maxMissedFrames }

        return tracks.asSequence()
            .filter { it.hits >= activeRequiredHits && it.misses <= maxMissedFrames }
            .sortedByDescending { it.score }
            .take(maxVisible)
            .map { it.toStable() }
            .toList()
    }

    private fun mergeDuplicateTracks() {
        var index = 0
        while (index < tracks.size) {
            var otherIndex = index + 1
            while (otherIndex < tracks.size) {
                val first = tracks[index]
                val second = tracks[otherIndex]
                if (priceSimilar(first.price, second.price) &&
                    normalizedDistance(first.previewBox, second.previewBox) <= 0.9) {
                    val keep = if (first.hits > second.hits ||
                        (first.hits == second.hits && first.score >= second.score)) first else second
                    val drop = if (keep === first) second else first
                    keep.previewBox = smooth(drop.previewBox, keep.previewBox)
                    if (keep.productText.isBlank() && drop.productText.isNotBlank()) keep.productText = drop.productText
                    keep.hits = max(keep.hits, drop.hits)
                    keep.misses = minOf(keep.misses, drop.misses)
                    lastEvents += "TRACK merged kept=" + keep.id + " dropped=" + drop.id +
                        " price=" + keep.price
                    tracks.remove(drop)
                    if (drop === first) { index--; break }
                    continue
                }
                otherIndex++
            }
            index++
        }
    }

    fun snapshots(): List<PriceTrackSnapshot> = tracks.map {
        PriceTrackSnapshot(
            trackId = it.id,
            cadPrice = it.price,
            previewBoundingBox = RectF(it.previewBox),
            score = it.score,
            hits = it.hits,
            misses = it.misses,
            isStable = it.hits >= activeRequiredHits
        )
    }

    fun events(): List<String> = lastEvents.toList()

    private fun Track.toStable() = StablePriceTrack(
        trackId = id,
        cadPrice = price,
        sourceBoundingBox = Rect(sourceBox),
        previewBoundingBox = RectF(previewBox),
        score = score,
        sourceElements = sourceElements,
        productText = productText,
        isLightBackground = isLight,
        missedFrames = misses
    )

    private fun priceSimilar(a: BigDecimal, b: BigDecimal): Boolean =
        a.setScale(2, RoundingMode.HALF_UP).compareTo(b.setScale(2, RoundingMode.HALF_UP)) == 0

    private fun normalizedDistance(a: RectF, b: RectF): Double {
        val distance = hypot((a.centerX() - b.centerX()).toDouble(),
            (a.centerY() - b.centerY()).toDouble())
        val scale = max(max(a.width(), a.height()), max(b.width(), b.height())).coerceAtLeast(1f)
        return distance / scale
    }

    private fun smooth(previous: RectF, current: RectF) = RectF(
        previous.left * 0.45f + current.left * 0.55f,
        previous.top * 0.45f + current.top * 0.55f,
        previous.right * 0.45f + current.right * 0.55f,
        previous.bottom * 0.45f + current.bottom * 0.55f
    )
}
