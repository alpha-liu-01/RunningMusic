package lol.alphaliu01.runningmusic.cadence

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * Steps-per-beat exponents a runner or walker can actually use.
 *
 * At one step per beat (k = 0) a 170 spm runner needs 170 bpm; at two (k = 1),
 * 85 bpm. Those two alone cover running, and for a while they were the only two
 * allowed, on the argument that k = -1 would need 300-380 bpm tracks and k = 2
 * would need 37-48 bpm ones, neither of which meaningfully exist.
 *
 * That argument was only ever true for running. Once the target can be a walk,
 * k = -1 is not the exotic case, it is the *best* one: a 100 spm walk at one
 * step every two beats wants 200 bpm... but far more usefully, a 60-65 spm
 * stroll wants exactly the 120-130 bpm cluster that the octave-folding notes
 * call the single largest tempo peak in pop and EDM, and that a running cadence
 * can never reach. Leaving k at 0..1 meant every cadence below about 100 spm
 * matched nothing at all, because the tracks it needed were all one octave
 * above the highest fold on offer.
 *
 * k = 2 stays out. Four steps per beat is a beat too sparse to entrain to, and
 * the clamp is what stops a mis-detected 40 bpm ambient track reporting a
 * perfect match there.
 */
val REACHABLE_EXPONENTS = -1..1

/** `2^k` for a steps-per-beat exponent, which may be negative. */
internal fun octave(exponent: Int): Double = 2.0.pow(exponent)

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
    /** `2^k`: 0.5 for a step every other beat, 1 per beat, 2 per beat. */
    val stepsPerBeat: Double get() = octave(exponent)

    /**
     * The gait as a whole-number ratio, for saying it out loud.
     *
     * One of the two is always 1, so `1 to 2` reads "one step every two beats"
     * and `2 to 1` reads "two steps per beat".
     */
    val stepsToBeats: Pair<Int, Int>
        get() = if (exponent >= 0) (1 shl exponent) to 1 else 1 to (1 shl -exponent)

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
    val folded = bpm * octave(exponent)

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

    return targetCadence / (bpm * octave(exponent))
}
