package lol.alphaliu01.runningmusic.cadence.accuracy

import java.io.File
import java.security.MessageDigest

/**
 * Where a track's ground truth came from.
 *
 * Recorded per track rather than assumed, because a tempo read out of a file's
 * own BPM tag is precisely the thing under test. Mixing tag truth into a
 * hand-tapped corpus without saying so would let the harness quietly grade the
 * detector against another detector's homework.
 */
enum class TruthSource {
    /** Tapped out by hand against the track. Slow to produce, and the good stuff. */
    TAPPED,

    /** From the file's own BPM tag. Convenient, and only as good as whatever wrote it. */
    TAG,

    /** From a release, a label, or a dataset that states it. */
    PUBLISHED;

    companion object {
        fun parse(text: String): TruthSource? =
            entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

/** One row of the corpus manifest. */
data class CorpusEntry(
    /** Relative to the corpus root, so the manifest is portable between machines. */
    val path: String,
    val sha256: String,
    val truthBpm: Double,
    val truthSource: TruthSource,
    val notes: String = ""
)

/** A manifest row that could not be read, kept so the CLI can report it. */
data class ManifestProblem(val lineNumber: Int, val line: String, val reason: String)

data class Manifest(
    val entries: List<CorpusEntry>,
    val problems: List<ManifestProblem>
)

private const val COLUMN_COUNT = 5

/**
 * Reads the tab-separated corpus manifest.
 *
 * Audio is never committed to the repository, so this is the only part of the
 * corpus that lives in git: paths into a local directory, hashes to prove it is
 * the same audio as last time, and the ground truth.
 *
 * A malformed row is collected rather than thrown, because losing a whole
 * evaluation run to one bad line in a hand-maintained file would be a poor
 * trade.
 */
fun parseManifest(file: File): Manifest {
    val entries = mutableListOf<CorpusEntry>()
    val problems = mutableListOf<ManifestProblem>()

    file.readLines().forEachIndexed { index, raw ->
        val lineNumber = index + 1
        val line = raw.trim()

        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

        fun problem(reason: String) {
            problems += ManifestProblem(lineNumber, line, reason)
        }

        val fields = line.split('\t')
        if (fields.size < COLUMN_COUNT - 1) {
            problem("expected $COLUMN_COUNT tab separated fields, found ${fields.size}")
            return@forEachIndexed
        }

        val truth = fields[2].trim().toDoubleOrNull()
        if (truth == null || truth <= 0.0 || !truth.isFinite()) {
            problem("truth_bpm '${fields[2].trim()}' is not a positive number")
            return@forEachIndexed
        }

        val source = TruthSource.parse(fields[3])
        if (source == null) {
            problem(
                "truth_source '${fields[3].trim()}' is not one of " +
                    TruthSource.entries.joinToString { it.name.lowercase() }
            )
            return@forEachIndexed
        }

        entries += CorpusEntry(
            path = fields[0].trim(),
            sha256 = fields[1].trim().lowercase(),
            truthBpm = truth,
            truthSource = source,
            notes = fields.getOrElse(4) { "" }.trim()
        )
    }

    return Manifest(entries, problems)
}

/**
 * Hashes a file, in chunks so a long track does not have to fit in memory.
 *
 * The hash is what makes two evaluation runs comparable. Without it, replacing
 * a file with a different rip, or a different encoding of the same song, would
 * silently change the number the harness reports and there would be no way to
 * tell from the output.
 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)

    file.inputStream().buffered().use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break

            digest.update(buffer, 0, read)
        }
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Hashes text, for identifying the manifest itself in a report header. */
fun sha256Of(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** What happened when a manifest entry was resolved against the corpus on disk. */
sealed interface Resolved {
    val entry: CorpusEntry

    /** Present and unchanged since the manifest was written. */
    data class Ready(override val entry: CorpusEntry, val file: File) : Resolved

    /** Named in the manifest but not on this machine. Skipped, not fatal. */
    data class Missing(override val entry: CorpusEntry) : Resolved

    /**
     * Present but different audio from what was measured last time.
     *
     * Reported loudly and skipped. Measuring it anyway would produce a figure
     * that is not comparable with the previous run, which defeats the point of
     * recording a hash at all.
     */
    data class Changed(
        override val entry: CorpusEntry,
        val file: File,
        val actualSha256: String
    ) : Resolved
}

fun CorpusEntry.resolveAgainst(corpusRoot: File): Resolved {
    val file = File(corpusRoot, path)

    if (!file.isFile) return Resolved.Missing(this)

    // An empty hash column means "do not check", which keeps adding a track to
    // the manifest a one-line job before its hash has been filled in.
    if (sha256.isEmpty()) return Resolved.Ready(this, file)

    val actual = sha256Of(file)

    return if (actual == sha256) Resolved.Ready(this, file) else Resolved.Changed(this, file, actual)
}
