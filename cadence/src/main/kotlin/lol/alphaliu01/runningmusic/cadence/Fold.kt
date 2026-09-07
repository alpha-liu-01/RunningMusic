package lol.alphaliu01.runningmusic.cadence

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.round

/**
 * Steps-per-beat exponents a runner can actually use.
 *
 * A target cadence of 150-190 spm is reachable at one step per beat (k = 0, so
 * 150-190 bpm) and at two (k = 1, so 75-95 bpm). Four steps per beat would need
 * 37-48 bpm tracks, which barely exist and are too sparse to entrain to; one step
 * per two beats would need 300-380 bpm, which do not exist at all.
 *
 * The range is a hard clamp rather than a hint. Trusting [round] alone would let
 * a mis-detected 40 bpm ambient track report a perfect match at four steps per
 * beat.
 */
val REACHABLE_EXPONENTS = 0..1

/**
 * The result of folding one track's tempo onto a target cadence.
 *
 * @property exponent k, clamped to [REACHABLE_EXPONENTS].
 * @property rawExponent k as rounding produced it, before clamping.
 * @property speed the playback rate needed to reach the target: `cadence / (bpm * 2^k)`.
 */
data class Fold(
    val exponent: Int,
    val rawExponent: Int,
    val speed: Double,
) {
    /** 1, 2, 4... steps per beat. */
    val stepsPerBeat: Int get() = 1 shl exponent

    /** Signed distance from unity in octaves. This is the quantity to rank by. */
    val logDeviation: Double get() = log2(speed)

    /** Distance from unity regardless of direction. */
    val absLogDeviation: Double get() = abs(logDeviation)

    /**
     * True when the track's nearest octave was out of reach, which means [speed]
     * is not bounded by [MAX_RESIDUAL] and the track should be rejected.
     */
    val wasClamped: Boolean get() = exponent != rawExponent
}

/**
 * The widest residual an unclamped fold can produce, `2^0.5`.
 *
 * Because k is rounded, a track is never more than half an octave from its
 * nearest reachable multiple, so the speed of an unclamped fold always lies in
 * `[1 / MAX_RESIDUAL, MAX_RESIDUAL]`. That bound is unconditional, and 41% is
 * still far too much to listen to, which is why [ToleranceBand] exists.
 *
 * It does not survive clamping. See [Fold.wasClamped].
 */
const val MAX_RESIDUAL = 1.4142135623730951 // sqrt(2)

/**
 * Folds [bpm] onto [targetCadence] by powers of two, returning the steps-per-beat
 * exponent and the residual playback speed.
 *
 * Runners step on beats or between them, so the quantity to match is not the
 * track's tempo but its tempo times some steps-per-beat factor. Working in log
 * space:
 *
 * ```
 * k     = round(log2(cadence / bpm))
 * speed = cadence / (bpm * 2^k)
 * ```
 *
 * @throws IllegalArgumentException if either argument is not positive.
 */
fun fold(bpm: Double, targetCadence: Double): Fold {
    require(bpm > 0.0 && bpm.isFinite()) { "bpm must be positive and finite, was $bpm" }
    require(targetCadence > 0.0 && targetCadence.isFinite()) {
        "targetCadence must be positive and finite, was $targetCadence"
    }

    // kotlin.math.round is half-to-even, not the half-away-from-zero of
    // Math.round. It makes no difference here: log2(sqrt(2)) lands one ulp above
    // 0.5, so a worst-case track folds to the higher exponent either way. The
    // clamp below, not the rounding rule, is what keeps k usable.
    val rawExponent = round(log2(targetCadence / bpm)).toInt()
    val exponent = rawExponent.coerceIn(REACHABLE_EXPONENTS)
    val folded = bpm * (1 shl exponent)

    return Fold(
        exponent = exponent,
        rawExponent = rawExponent,
        speed = targetCadence / folded,
    )
}

/**
 * The speed [bpm] needs to reach [targetCadence] at a steps-per-beat [exponent]
 * chosen elsewhere.
 *
 * [fold] re-picks the exponent every time it is called, which is exactly wrong
 * once a track is playing: a target that drifts across a fold boundary would
 * flip k mid-song, and applying that is a 2x speed jump. So k is decided once at
 * the track transition and only the residual is allowed to move afterwards,
 * which is what this computes.
 *
 * The result is not bounded by [MAX_RESIDUAL], because holding k fixed is the
 * whole point. Callers are expected to clamp it with [ToleranceBand.clamp].
 *
 * @throws IllegalArgumentException if the tempo or cadence is not positive, or
 * the exponent is outside [REACHABLE_EXPONENTS].
 */
fun foldAt(bpm: Double, targetCadence: Double, exponent: Int): Double {
    require(bpm > 0.0 && bpm.isFinite()) { "bpm must be positive and finite, was $bpm" }
    require(targetCadence > 0.0 && targetCadence.isFinite()) {
        "targetCadence must be positive and finite, was $targetCadence"
    }
    require(exponent in REACHABLE_EXPONENTS) {
        "exponent must be in $REACHABLE_EXPONENTS, was $exponent"
    }

    return targetCadence / (bpm * (1 shl exponent))
}
