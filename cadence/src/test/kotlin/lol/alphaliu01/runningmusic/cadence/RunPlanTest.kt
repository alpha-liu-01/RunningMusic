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

    /**
     * Replanning is what a moved cadence triggers, and it must never interrupt
     * the track in progress.
     */
    @Test
    fun `replacing the tail keeps the current track and everything before it`() {
        val plan = planOf("a", "b", "c", "d")
            .materialise(lookahead = 3).plan
            .advanceTo { it == "b" }

        val replanned = plan.replaceTail(listOf(entry("x"), entry("y")))

        assertContentEquals(listOf("a", "b", "x", "y"), replanned.tracks.map { it.ref })
        assertEquals(1, replanned.cursor)
        assertEquals("b", replanned.current?.ref)
    }

    /**
     * After a replan the player still holds the old tail, so the window has to
     * shrink back to the current track for the caller to push the new one.
     */
    @Test
    fun `replacing the tail shrinks the window back to the current track`() {
        val plan = planOf("a", "b", "c", "d")
            .materialise(lookahead = 3).plan
            .advanceTo { it == "b" }

        val replanned = plan.replaceTail(listOf(entry("x"), entry("y")))

        assertEquals(2, replanned.materialised)

        val (_, added) = replanned.materialise(lookahead = 2)
        assertContentEquals(listOf("x", "y"), added.map { it.ref })
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
