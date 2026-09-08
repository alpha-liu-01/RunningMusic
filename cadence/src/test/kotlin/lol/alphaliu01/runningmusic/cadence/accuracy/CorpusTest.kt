package lol.alphaliu01.runningmusic.cadence.accuracy

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CorpusTest {

    @Test
    fun `a manifest row carries a path, a hash and a ground truth`(@TempDir dir: File) {
        val manifest = write(
            dir,
            "runs/steady.flac\tabc123\t170\ttapped\tconstant throughout"
        )

        assertEquals(emptyList(), manifest.problems)

        val entry = manifest.entries.single()
        assertEquals("runs/steady.flac", entry.path)
        assertEquals("abc123", entry.sha256)
        assertEquals(170.0, entry.truthBpm, 1e-12)
        assertEquals(TruthSource.TAPPED, entry.truthSource)
        assertEquals("constant throughout", entry.notes)
    }

    @Test
    fun `comments and blank lines are not rows`(@TempDir dir: File) {
        val manifest = write(
            dir,
            "# relative_path\tsha256\ttruth_bpm\ttruth_source\tnotes",
            "",
            "   ",
            "a.mp3\t\t128\ttag\t"
        )

        assertEquals(1, manifest.entries.size)
        assertEquals(emptyList(), manifest.problems)
    }

    @Test
    fun `the notes column may be left off entirely`(@TempDir dir: File) {
        val manifest = write(dir, "a.mp3\t\t128\tpublished")

        assertEquals("", manifest.entries.single().notes)
    }

    @Test
    fun `truth taken from a tag is recorded as such, not assumed`(@TempDir dir: File) {
        // The distinction the manifest exists to keep: a tag is another
        // detector's output, and grading against it is not the same experiment.
        val manifest = write(
            dir,
            "tapped.mp3\t\t128\ttapped\t",
            "tagged.mp3\t\t128\tTAG\t"
        )

        assertEquals(
            listOf(TruthSource.TAPPED, TruthSource.TAG),
            manifest.entries.map { it.truthSource }
        )
    }

    @Test
    fun `an unrecognised truth source is refused rather than guessed at`(@TempDir dir: File) {
        val manifest = write(dir, "a.mp3\t\t128\tguessed\t")

        assertEquals(emptyList(), manifest.entries)
        assertTrue(manifest.problems.single().reason.contains("truth_source"))
    }

    @Test
    fun `a bad tempo is reported with its line number`(@TempDir dir: File) {
        val manifest = write(
            dir,
            "good.mp3\t\t128\ttapped\t",
            "bad.mp3\t\tfast\ttapped\t",
            "negative.mp3\t\t-4\ttapped\t"
        )

        assertEquals(1, manifest.entries.size)
        assertEquals(listOf(2, 3), manifest.problems.map { it.lineNumber })
    }

    @Test
    fun `one bad row does not cost the whole run`(@TempDir dir: File) {
        val manifest = write(
            dir,
            "a.mp3\t\t128\ttapped\t",
            "truncated",
            "b.mp3\t\t170\ttapped\t"
        )

        assertEquals(listOf("a.mp3", "b.mp3"), manifest.entries.map { it.path })
        assertEquals(1, manifest.problems.size)
    }

    @Test
    fun `the committed manifest parses and is still empty of data`() {
        val committed = File("docs/private/bpm-corpus.tsv")
        assertTrue(committed.isFile, "expected ${committed.absolutePath} to exist")

        val manifest = parseManifest(committed)

        assertEquals(emptyList(), manifest.problems)
        assertEquals(emptyList(), manifest.entries)
    }

    @Test
    fun `an unchanged file resolves ready`(@TempDir dir: File) {
        val audio = File(dir, "a.wav").apply { writeText("pretend this is audio") }
        val entry = entryFor("a.wav", sha256Of(audio))

        val resolved = entry.resolveAgainst(dir)

        assertTrue(resolved is Resolved.Ready)
    }

    @Test
    fun `a file that has changed since the manifest is refused, not measured`(@TempDir dir: File) {
        val audio = File(dir, "a.wav").apply { writeText("the audio that was measured") }
        val entry = entryFor("a.wav", sha256Of(audio))

        audio.writeText("a different rip of the same song")

        val resolved = entry.resolveAgainst(dir)

        assertTrue(resolved is Resolved.Changed)
        assertEquals(sha256Of(audio), (resolved as Resolved.Changed).actualSha256)
    }

    @Test
    fun `a file that is not on this machine is skipped rather than fatal`(@TempDir dir: File) {
        val resolved = entryFor("absent.wav", "abc123").resolveAgainst(dir)

        assertTrue(resolved is Resolved.Missing)
    }

    @Test
    fun `an empty hash means the check is not wanted yet`(@TempDir dir: File) {
        File(dir, "a.wav").writeText("audio")

        assertTrue(entryFor("a.wav", "").resolveAgainst(dir) is Resolved.Ready)
    }

    @Test
    fun `hashing is the standard sha-256, so sha256sum agrees with it`(@TempDir dir: File) {
        val file = File(dir, "empty").apply { writeText("") }

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Of(file)
        )
        assertEquals(sha256Of(file), sha256Of(""))
    }

    private fun write(dir: File, vararg lines: String): Manifest =
        parseManifest(File(dir, "manifest.tsv").apply { writeText(lines.joinToString("\n")) })

    private fun entryFor(path: String, sha256: String) = CorpusEntry(
        path = path,
        sha256 = sha256,
        truthBpm = 128.0,
        truthSource = TruthSource.TAPPED
    )
}
