package lol.alphaliu01.runningmusic.cadence

/**
 * A planned run, plus how much of it the player has been told about.
 *
 * The run is planned in full up front, and the player is normally told all of
 * it, so the runner can see the whole run and jump anywhere in it. The window
 * exists because the two cannot change in the same instant: a replan or a splice
 * edits the plan first, and [materialise] then says what the player is owed.
 *
 * The window only ever grows at the far end, and entries already played stay in
 * the player. That costs nothing, makes skipping backwards work, and means a
 * plan index and a player index are the same number, which removes the one
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
     * Puts [entry] next, so it plays when the current track ends.
     *
     * This is how a track the plan never chose joins a run: the runner asks for
     * something, it goes in at the front of what is coming rather than replacing
     * what is coming, and the run carries on afterwards. Seeking to it is the
     * caller's business.
     *
     * The window grows with it when the insertion lands inside what the player
     * already holds, because the caller inserts into the player at the same
     * index and the two counts have to keep agreeing.
     */
    fun insertAfterCursor(entry: Selected<T>): RunPlan<T> {
        val at = (cursor + 1).coerceAtMost(tracks.size)
        return copy(
            tracks = tracks.toMutableList().also { it.add(at, entry) },
            materialised = if (at <= materialised) materialised + 1 else materialised,
        )
    }

    /** Puts [entries] at the end of the run, on the same terms as [insertAfterCursor]. */
    fun append(entries: List<Selected<T>>): RunPlan<T> {
        if (entries.isEmpty()) return this

        return copy(
            tracks = tracks + entries,
            materialised = if (materialised == tracks.size) {
                materialised + entries.size
            } else {
                materialised
            },
        )
    }

    /**
     * Moves a queued entry, for a runner reordering what is coming up.
     *
     * Only entries that are both still to play and already in the player may
     * move, and only to somewhere they are still both. Anything else is refused
     * rather than clamped: the caller is mirroring this onto the player, and a
     * request quietly turned into a different one would desynchronise the two
     * far more confusingly than one that did nothing.
     */
    fun move(from: Int, to: Int): RunPlan<T> {
        val movable = (cursor + 1) until materialised
        if (from !in movable || to !in movable || from == to) return this

        return copy(
            tracks = tracks.toMutableList().also { it.add(to, it.removeAt(from)) },
        )
    }

    /** Drops a queued entry. Refused for anything played or playing, as [move]. */
    fun removeAt(index: Int): RunPlan<T> {
        if (index !in (cursor + 1) until materialised) return this

        return copy(
            tracks = tracks.toMutableList().also { it.removeAt(index) },
            materialised = materialised - 1,
        )
    }

    /**
     * Rewrites what is coming up as [kept] followed by [appended].
     *
     * This is a replan expressed as the small edit it usually is. [kept] must be
     * what survived of the existing tail, in the order it was already in, so the
     * player only has to lose the entries missing from it — which the caller
     * removes before calling this, leaving the window covering exactly the head
     * and [kept]. [materialise] then hands it [appended], as it would any other
     * growth.
     *
     * Played entries and the one playing now are untouched: a replan must never
     * interrupt the track in progress.
     */
    fun rebuildTail(kept: List<Selected<T>>, appended: List<Selected<T>>): RunPlan<T> {
        val head = tracks.take((cursor + 1).coerceAtMost(tracks.size))
        return RunPlan(
            tracks = head + kept + appended,
            cursor = cursor,
            materialised = head.size + kept.size,
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
