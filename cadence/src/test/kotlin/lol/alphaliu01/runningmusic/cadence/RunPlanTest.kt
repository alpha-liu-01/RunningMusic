package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val THREE_MINUTES = 180_000L

private fun entry(name: String, stretchedMs: Long = THREE_MINUTES) =
    Selected(name, fold(170.0, 170.0), stretchedMs)

private fun planOf(vararg names: String) = RunPlan(names.map { entry(it) })

class RunPlanTest {

    @Test
    fun `a fresh plan has told the player nothing`() {
        val plan = planOf("a", "b", "c")

        assertEquals(0, plan.materialised)
        assertEquals(0, plan.cursor)
        assertEquals("a", plan.current?.ref)
        assertFalse(plan.isFinished)
    }

    @Test
    fun `materialising hands over the current track and the lookahead`() {
        val (plan, added) = planOf("a", "b", "c", "d", "e").materialise(lookahead = 2)

        assertContentEquals(listOf("a", "b", "c"), added.map { it.ref })
        assertEquals(3, plan.materialised)
    }

    /**
     * The window grows by exactly what the cursor consumed. Handing the player a
     * track it already holds would duplicate it in the queue.
     */
    @Test
    fun `materialising again after advancing adds only the new tail`() {
        val first = planOf("a", "b", "c", "d", "e").materialise(lookahead = 2)

        val second = first.plan.advanceTo { it == "b" }.materialise(lookahead = 2)

        assertContentEquals(listOf("d"), second.added.map { it.ref })
        assertEquals(4, second.plan.materialised)
    }

    @Test
    fun `materialising twice without advancing adds nothing`() {
        val first = planOf("a", "b", "c").materialise(lookahead = 1)

        val second = first.plan.materialise(lookahead = 1)

        assertTrue(second.added.isEmpty())
        assertEquals(first.plan, second.plan)
    }

    @Test
    fun `the window stops at the end of the plan`() {
        val (plan, added) = planOf("a", "b").materialise(lookahead = 10)

        assertContentEquals(listOf("a", "b"), added.map { it.ref })
        assertEquals(2, plan.materialised)
    }

    /**
     * Skipping backwards must not ask the player to re-add tracks it already
     * holds, and must not shrink the window.
     */
    @Test
    fun `going backwards does not un-materialise anything`() {
        val forward = planOf("a", "b", "c", "d").materialise(lookahead = 2).plan
            .advanceTo { it == "c" }
            .materialise(lookahead = 2).plan

        val back = forward.advanceTo { it == "a" }.materialise(lookahead = 2)

        assertTrue(back.added.isEmpty())
        assertEquals(4, back.plan.materialised)
        assertEquals(0, back.plan.cursor)
    }

    @Test
    fun `an unknown track leaves the cursor where it was`() {
        val plan = planOf("a", "b", "c").advanceTo { it == "b" }

        val unchanged = plan.advanceTo { it == "not in the run" }

        assertEquals(plan, unchanged)
        assertEquals(1, unchanged.cursor)
    }

    @Test
    fun `remaining time counts the current track and everything after it`() {
        val plan = planOf("a", "b", "c")

        assertEquals(3 * THREE_MINUTES, plan.remainingMs)
        assertEquals(2 * THREE_MINUTES, plan.advanceTo { it == "b" }.remainingMs)
        assertEquals(THREE_MINUTES, plan.advanceTo { it == "c" }.remainingMs)
    }

    @Test
    fun `filled time is the whole plan, including what has already played`() {
        val plan = planOf("a", "b", "c").advanceTo { it == "c" }

        assertEquals(3 * THREE_MINUTES, plan.filledMs)
        assertEquals(THREE_MINUTES, plan.remainingMs)
    }

    /**
     * Replanning is what a moved cadence triggers, and it must never interrupt
     * the track in progress.
     */
    @Test
    fun `rebuilding the tail keeps the current track and everything before it`() {
        val plan = planOf("a", "b", "c", "d")
            .materialise(lookahead = 3).plan
            .advanceTo { it == "b" }

        val replanned = plan.rebuildTail(kept = emptyList(), appended = listOf(entry("x")))

        assertContentEquals(listOf("a", "b", "x"), replanned.tracks.map { it.ref })
        assertEquals(1, replanned.cursor)
        assertEquals("b", replanned.current?.ref)
    }

    /**
     * The usual replan: the cadence moved a little, one track no longer fits and
     * the rest carry on where they were. The player has lost only that one track,
     * so the window covers everything still in it and materialising asks for the
     * top-up alone.
     */
    @Test
    fun `rebuilding the tail leaves what survived in the player`() {
        val plan = planOf("a", "b", "c", "d")
            .materialise(lookahead = 3).plan
            .advanceTo { it == "b" }

        val replanned = plan.rebuildTail(kept = listOf(entry("d")), appended = listOf(entry("x")))

        assertContentEquals(listOf("a", "b", "d", "x"), replanned.tracks.map { it.ref })
        assertEquals(3, replanned.materialised)

        val (grown, added) = replanned.materialise(lookahead = 3)
        assertContentEquals(listOf("x"), added.map { it.ref })
        assertEquals(4, grown.materialised)
    }

    /**
     * A track's speed can change without the track changing, when the target
     * moves under it. The plan has to record that, or skipping back to it would
     * replay it at a speed it was never actually played at.
     */
    @Test
    fun `rewriting the current entry leaves the cursor and the window alone`() {
        val plan = planOf("a", "b", "c").materialise(lookahead = 1).plan
            .advanceTo { it == "b" }

        val rewritten = plan.withCurrent(entry("b", stretchedMs = 999L))

        assertEquals(999L, rewritten.current?.stretchedDurationMs)
        assertEquals(1, rewritten.cursor)
        assertEquals(2, rewritten.materialised)
        assertContentEquals(listOf("a", "b", "c"), rewritten.tracks.map { it.ref })
    }

    @Test
    fun `rewriting the current entry of a finished plan does nothing`() {
        val plan = RunPlan(listOf(entry("a")), cursor = 1, materialised = 1)

        assertEquals(plan, plan.withCurrent(entry("b")))
    }

    @Test
    fun `a plan whose cursor has walked off the end is finished`() {
        val plan = RunPlan(listOf(entry("a")), cursor = 1, materialised = 1)

        assertTrue(plan.isFinished)
        assertNull(plan.current)
        assertEquals(0L, plan.remainingMs)
    }

    @Test
    fun `an empty plan is finished and offers nothing`() {
        val plan = RunPlan<String>(emptyList())

        assertTrue(plan.isFinished)
        assertNull(plan.current)
        assertEquals(0L, plan.remainingMs)
        assertTrue(plan.materialise(lookahead = 5).added.isEmpty())
    }

    @Test
    fun `rejects impossible bookkeeping`() {
        assertFailsWith<IllegalArgumentException> { planOf("a").copy(cursor = -1) }
        assertFailsWith<IllegalArgumentException> { planOf("a").copy(materialised = 2) }
        assertFailsWith<IllegalArgumentException> { planOf("a").materialise(lookahead = -1) }
    }
}

/**
 * The runner picking their own track, and rearranging what is coming up.
 *
 * These all edit a plan the player is already holding in full, so what they have
 * to preserve is the invariant the class exists for: entry n of the plan is item
 * n of the player. The tests check it the way the manager relies on it, by
 * making the same edit to a stand-in player and finding the two agree.
 */
class RunQueueEditsTest {

    /** A plan the player has been told all of, with [at] playing. */
    private fun running(vararg names: String, at: Int) =
        RunPlan(names.map { entry(it) }, cursor = at, materialised = names.size)

    private fun assertMatchesPlayer(plan: RunPlan<String>, vararg player: String) {
        assertEquals(player.size, plan.materialised, "window and player disagree on size")
        assertContentEquals(player.toList(), plan.tracks.map { it.ref })
    }

    @Test
    fun `a picked track goes in next and the run carries on after it`() {
        val plan = running("a", "b", "c", "d", at = 1)

        val spliced = plan.insertAfterCursor(entry("picked"))

        assertMatchesPlayer(spliced, "a", "b", "picked", "c", "d")
        assertEquals(2, spliced.indexOf { it == "picked" })
        assertEquals("b", spliced.current?.ref)
    }

    @Test
    fun `a picked track at the end of the run still goes in next`() {
        val spliced = running("a", "b", at = 1).insertAfterCursor(entry("picked"))

        assertMatchesPlayer(spliced, "a", "b", "picked")
    }

    @Test
    fun `topping the run up puts the new tracks at the end`() {
        val plan = running("a", "b", at = 0)

        assertMatchesPlayer(plan.append(listOf(entry("c"), entry("d"))), "a", "b", "c", "d")
        assertEquals(plan, plan.append(emptyList()))
    }

    /**
     * The one case where the window does not grow: the player was not holding
     * the end of the plan, so tracks appended after it are not its business yet.
     */
    @Test
    fun `appending past what the player holds leaves the window alone`() {
        val plan = RunPlan(listOf(entry("a"), entry("b")), materialised = 1)

        assertEquals(1, plan.append(listOf(entry("c"))).materialised)
    }

    @Test
    fun `a queued track can be moved to another queued position`() {
        val plan = running("a", "b", "c", "d", "e", at = 1)

        assertMatchesPlayer(plan.move(from = 4, to = 2), "a", "b", "e", "c", "d")
        assertMatchesPlayer(plan.move(from = 2, to = 4), "a", "b", "d", "e", "c")
    }

    /**
     * Reordering is about what is coming up. Dragging the playing track would
     * mean seeking, and dragging a played one would mean rewriting history; both
     * are refused outright rather than reinterpreted, because the caller is
     * making the same edit to the player and a silently different one would put
     * the two out of step.
     */
    @Test
    fun `a move that would disturb the past or the present is refused`() {
        val plan = running("a", "b", "c", "d", at = 1)

        assertEquals(plan, plan.move(from = 1, to = 3))
        assertEquals(plan, plan.move(from = 0, to = 3))
        assertEquals(plan, plan.move(from = 3, to = 1))
        assertEquals(plan, plan.move(from = 3, to = 0))
        assertEquals(plan, plan.move(from = 2, to = 2))
        assertEquals(plan, plan.move(from = 2, to = 9))
    }

    @Test
    fun `a queued track can be dropped from the run`() {
        val plan = running("a", "b", "c", "d", at = 1)

        assertMatchesPlayer(plan.removeAt(2), "a", "b", "d")
    }

    @Test
    fun `dropping the played or the playing is refused`() {
        val plan = running("a", "b", "c", at = 1)

        assertEquals(plan, plan.removeAt(1))
        assertEquals(plan, plan.removeAt(0))
        assertEquals(plan, plan.removeAt(5))
    }

    /**
     * The whole point of splicing rather than replacing: the detour plays, and
     * the run the user planned resumes behind it.
     */
    @Test
    fun `the run resumes after a track spliced into it`() {
        val plan = running("a", "b", "c", at = 0)
            .insertAfterCursor(entry("picked"))

        val playing = plan.advanceTo { it == "picked" }

        assertEquals("picked", playing.current?.ref)
        assertContentEquals(listOf("b", "c"), playing.tracks.drop(playing.cursor + 1).map { it.ref })
    }
}
