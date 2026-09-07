package lol.alphaliu01.runningmusic.cadence

/**
 * How far from unity playback speed a track may be stretched to be accepted.
 *
 * The two bounds are independent because speeding up adds energy that suits
 * running while slowing down tends to drag, so a band like +12%/-8% may feel
 * better than a symmetric +/-10%. That is a hypothesis to validate on real runs,
 * so the default is [symmetric] and the asymmetry is available without a change
 * of shape here.
 *
 * @property maxSpeedUp the fastest accepted speed, at least 1.0.
 * @property maxSlowDown the reciprocal of the slowest accepted speed, at least 1.0.
 */
data class ToleranceBand(
    val maxSpeedUp: Double,
    val maxSlowDown: Double,
) {
    init {
        require(maxSpeedUp >= 1.0 && maxSpeedUp.isFinite()) {
            "maxSpeedUp must be at least 1.0 and finite, was $maxSpeedUp"
        }
        require(maxSlowDown >= 1.0 && maxSlowDown.isFinite()) {
            "maxSlowDown must be at least 1.0 and finite, was $maxSlowDown"
        }
    }

    /** The slowest accepted speed. */
    val minSpeed: Double get() = 1.0 / maxSlowDown

    /** The widest stretch this band permits in either direction. */
    val widest: Double get() = maxOf(maxSpeedUp, maxSlowDown)

    fun accepts(speed: Double): Boolean = speed in minSpeed..maxSpeedUp

    fun accepts(fold: Fold): Boolean = accepts(fold.speed)

    /**
     * The track tempos this band accepts for [targetCadence], one window per
     * reachable steps-per-beat exponent.
     *
     * Inverting `speed = cadence / (bpm * 2^k)` gives, for each k:
     *
     * ```
     * [ cadence / (2^k * maxSpeedUp) , cadence * maxSlowDown / 2^k ]
     * ```
     *
     * This union of windows, rather than one narrow window, is what separates
     * cadence matching from naive tempo filtering. At 170 spm with a symmetric
     * 1.2 band it is 141.7-204.0 bpm and 70.8-102.0 bpm.
     *
     * The windows meet when `maxSpeedUp * maxSlowDown == 2`, at which point the
     * band tiles the whole tempo range and accepts every track. For a symmetric
     * band that is [MAX_RESIDUAL].
     */
    fun bandsFor(targetCadence: Double): List<ClosedFloatingPointRange<Double>> =
        REACHABLE_EXPONENTS.map { k ->
            val octave = (1 shl k).toDouble()
            (targetCadence / (octave * maxSpeedUp))..(targetCadence * maxSlowDown / octave)
        }

    companion object {
        fun symmetric(r: Double) = ToleranceBand(r, r)

        /**
         * The widest band worth allowing, whatever the user asks for.
         *
         * A thin library should degrade by producing a shorter queue, not by
         * producing unlistenable audio.
         */
        val CEILING = symmetric(1.15)

        /** Wide enough that the windows tile, so every track is accepted. */
        val EVERYTHING = symmetric(MAX_RESIDUAL)

        /**
         * The asymmetric shape Goal 3 puts forward as worth testing: happy to run
         * a track 12% fast, reluctant to drag it 8% slow.
         */
        val ASYMMETRIC_HYPOTHESIS = ToleranceBand(maxSpeedUp = 1.12, maxSlowDown = 1.08)
    }
}
