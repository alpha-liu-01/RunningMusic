/*
 * Runs the shipped tempo estimator over raw PCM on stdin.
 *
 * The measuring half of the accuracy harness, and deliberately the dumbest
 * program that can be: read samples, feed them to rm_tempo, print three
 * numbers. Everything with a judgement in it -- what to decode, what the truth
 * is, whether an answer counts as right -- lives in the Kotlin driver.
 *
 * Reading from stdin rather than opening files is what lets both sources feed
 * it identically: ffmpeg pipes real audio in, and the driver writes generated
 * samples in for the synthetic corpus.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rm_tempo.h"

#define DEFAULT_SAMPLERATE 44100u
#define DEFAULT_BUFFER_SIZE 1024u
#define DEFAULT_HOP_SIZE 512u

static const char *USAGE =
    "Usage: bpm_probe [--samplerate HZ] [--buffer N] [--hop N]\n"
    "\n"
    "Reads 32-bit float mono PCM from stdin until end of input and writes one\n"
    "tab separated line to stdout:\n"
    "\n"
    "  <bpm>\\t<confidence>\\t<beats>\n"
    "\n"
    "A bpm of 0 means too few beats were found to take a median of, which is\n"
    "unknown rather than an answer.\n";

static int parse_uint(const char *text, uint32_t *out) {
    char *end = NULL;
    unsigned long value = strtoul(text, &end, 10);

    if (!end || *end != '\0' || value == 0ul || value > 0xffffffffUL) return 0;

    *out = (uint32_t) value;

    return 1;
}

int main(int argc, char **argv) {
    uint32_t samplerate = DEFAULT_SAMPLERATE;
    uint32_t buffer_size = DEFAULT_BUFFER_SIZE;
    uint32_t hop_size = DEFAULT_HOP_SIZE;

    for (int i = 1; i < argc; i++) {
        const char *name = argv[i];
        uint32_t *target = NULL;

        if (strcmp(name, "--help") == 0 || strcmp(name, "-h") == 0) {
            fputs(USAGE, stdout);
            return 0;
        } else if (strcmp(name, "--samplerate") == 0) {
            target = &samplerate;
        } else if (strcmp(name, "--buffer") == 0) {
            target = &buffer_size;
        } else if (strcmp(name, "--hop") == 0) {
            target = &hop_size;
        } else {
            fprintf(stderr, "Unknown argument: %s\n\n%s", name, USAGE);
            return 2;
        }

        if (++i >= argc) {
            fprintf(stderr, "%s needs a value\n\n%s", name, USAGE);
            return 2;
        }

        if (!parse_uint(argv[i], target)) {
            fprintf(stderr, "%s wants a positive integer, got %s\n", name, argv[i]);
            return 2;
        }
    }

    rm_tempo_t *tempo = rm_tempo_new(samplerate, buffer_size, hop_size);
    if (!tempo) {
        fprintf(stderr, "Could not create an estimator at %u Hz, buffer %u, hop %u\n",
                samplerate, buffer_size, hop_size);
        return 1;
    }

    float *hop = malloc(hop_size * sizeof(*hop));
    if (!hop) {
        fprintf(stderr, "Out of memory\n");
        rm_tempo_free(tempo);
        return 1;
    }

    for (;;) {
        size_t read = fread(hop, sizeof(*hop), hop_size, stdin);

        if (read == 0u) break;

        /* The last hop of a track is almost never full. Zero padding it is both
         * what rm_tempo_feed requires and harmless: it is at most 12 ms of
         * silence at the very end. */
        if (read < hop_size) {
            memset(hop + read, 0, (hop_size - read) * sizeof(*hop));
        }

        rm_tempo_feed(tempo, hop, hop_size);

        if (read < hop_size) break;
    }

    if (ferror(stdin)) {
        fprintf(stderr, "Failed reading samples from stdin\n");
        free(hop);
        rm_tempo_free(tempo);
        return 1;
    }

    printf("%.4f\t%.4f\t%u\n",
           (double) rm_tempo_bpm(tempo),
           (double) rm_tempo_confidence(tempo),
           rm_tempo_beats(tempo));

    free(hop);
    rm_tempo_free(tempo);

    return 0;
}
