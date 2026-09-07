package lol.alphaliu01.runningmusic.cadence

/**
 * A planned run, plus how much of it the player has been told about.
 *
 * The run is planned in full up front, but the player is only ever handed a
 * short window of it. That matters because the target cadence can move during a
 * run, and everything past the window is still free to be replanned; a queue
 * materialised in one go would have to be torn down instead.
 *
 * The window only ever grows at the far end: entries already played stay in the
 * player. Keeping them costs nothing, makes skipping backwards work, and means
 * a plan index and a player index are the same number, which removes the one
 * bookkeeping error this class exists to avoid.
 *
 * @property cursor which entry is playing now.
 * @property materialised how many leading entries the player holds.
 */
data class RunPlan<T>(
    val tracks: List<Selected<T>>,
    val cursor: Int = 0,
    val materialised: Int = 0,
) {
    init {
        require(cursor >= 0) { "cursor must not be negative, was $cursor" }
        require(materialised in 0..tracks.size) {
            "materialised must be within 0..${tracks.size}, was $materialised"
        }
    }

    val size: Int get() = tracks.size

    /** True once the cursor has walked off the end of the plan. */
    val isFinished: Boolean get() = cursor >= tracks.size

    val current: Selected<T>? get() = tracks.getOrNull(cursor)

    /**
     * Stretched playing time from the current track to the end of the plan.
     *
     * The current track counts in full, so this is an overestimate by however
     * much of it has already played. It is a headline figure, not a timer.
     */
    val remainingMs: Long
        get() = tracks.drop(cursor).sumOf { it.stretchedDurationMs }

    /** Where in the plan the first entry matching [match] sits, or -1. */
    fun indexOf(match: (T) -> Boolean): Int = tracks.indexOfFirst { match(it.ref) }

    /**
     * Moves the cursor to the entry matching [match].
     *
     * An unmatched entry leaves the plan alone. That happens when something
     * outside running mode puts a track in the player, and the right response is
     * to keep the plan intact rather than to reset it to the start.
     */
    fun advanceTo(match: (T) -> Boolean): RunPlan<T> {
        val index = indexOf(match)
        return if (index < 0 || index == cursor) this else copy(cursor = index)
    }

    /**
     * Grows the window so the player holds [lookahead] entries beyond the
     * current one, and reports what has to be added to get there.
     */
    fun materialise(lookahead: Int): Materialisation<T> {
        require(lookahead >= 0) { "lookahead must not be negative, was $lookahead" }

        val target = (cursor + 1 + lookahead).coerceIn(materialised, tracks.size)
        return Materialisation(
            plan = copy(materialised = target),
            added = tracks.subList(materialised, target).toList(),
        )
    }

    /**
     * Rewrites the entry playing now, leaving the cursor and the window alone.
     *
     * The speed of a track already playing can change without the track itself
     * changing, when the target cadence moves under it. Recording that keeps the
     * plan honest about what is actually being played, which matters if the
     * runner skips back to it.
     */
    fun withCurrent(entry: Selected<T>): RunPlan<T> {
        if (cursor !in tracks.indices) return this

        return copy(tracks = tracks.toMutableList().also { it[cursor] = entry })
    }

    /**
     * Replaces everything after the current track with [tail].
     *
     * The window shrinks back to the current track, so the caller has to drop
     * the stale entries from the player and then [materialise] again. Played
     * entries and the one playing now are untouched: replanning must never
     * interrupt the track in progress.
     */
    fun replaceTail(tail: List<Selected<T>>): RunPlan<T> {
        val kept = tracks.take((cursor + 1).coerceAtMost(tracks.size))
        return RunPlan(
            tracks = kept + tail,
            cursor = cursor,
            materialised = kept.size,
        )
    }
}

/**
 * The outcome of growing a plan's window.
 *
 * @property added the entries to hand the player, in order, appended after what
 * it already holds.
 */
data class Materialisation<T>(
    val plan: RunPlan<T>,
    val added: List<Selected<T>>,
)
