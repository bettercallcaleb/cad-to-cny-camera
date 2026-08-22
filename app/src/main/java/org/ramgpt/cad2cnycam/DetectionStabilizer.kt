package org.ramgpt.cad2cnycam

import java.math.RoundingMode
import java.util.ArrayDeque

class DetectionStabilizer(
    private val requiredMatches: Int = 3,
    private val windowSize: Int = 5
) {
    private data class FrameObservation(val priceKey: String?)

    private val recent = ArrayDeque<FrameObservation>()

    init {
        require(requiredMatches > 0) { "requiredMatches must be positive" }
        require(windowSize >= requiredMatches) { "windowSize must fit requiredMatches" }
    }

    fun add(candidate: PriceCandidate?): PriceCandidate? {
        val key = candidate?.amount?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
        recent.addLast(FrameObservation(key))
        while (recent.size > windowSize) recent.removeFirst()
        return if (key != null && recent.count { it.priceKey == key } >= requiredMatches) candidate else null
    }
}
