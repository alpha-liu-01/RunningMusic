/*
 * Does the vendored aubio actually recover a tempo?
 *
 * A smoke test, not an accuracy measurement. It proves the library builds,
 * links, runs and produces a sane answer on input whose tempo is known exactly.
 * Measuring accuracy on real music is a separate harness over a real corpus.
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "rm_tempo.h"

#define SAMPLERATE 44100u
#define HOP_SIZE 512u
#define DURATION_SECONDS 30u

/*
 * How far off a tempo may be, as a fraction of an octave, after folding.
 *
 * 0.05 is about 3.5%, which is far tighter than anything the app needs and
 * still leaves room for the beat tracker's own quantisation.
 */
#define TOLERANCE 0.05

/*
 * Distance to the nearest octave of the true tempo.
 *
 * Scored this way because a beat tracker reporting half or double is not wrong
 * in any way that matters here: playback speed is chosen by folding the ratio
 * into a single octave anyway, so 60, 120 and 240 all lead to the same music.
 */
static double octave_distance(double estimate, double truth) {
    if (estimate <= 0.0 || truth <= 0.0) return INFINITY;

    double ratio = log2(estimate / truth);

    return fabs(ratio - round(ratio));
}

/*
 * Writes a click into buffer at the given offset.
 *
 * A bare impulse is a poor stimulus: aubio's onset detector works on spectral
 * flux over a 1024-sample window, and a single sample is nearly invisible to
 * it. A short decaying tone burst is both realistic and unambiguous.
 */
static void write_click(float *buffer, uint32_t total, uint32_t offset) {
    const double frequency = 1000.0;
    const uint32_t length = SAMPLERATE / 50u; /* 20 ms */

    for (uint32_t i = 0u; i < length && offset + i < total; i++) {
        double t = (double) i / SAMPLERATE;
        double envelope = exp(-t * 120.0);

        buffer[offset + i] += (float) (0.8 * envelope * sin(2.0 * M_PI * frequency * t));
    }
}

/*
 * Fills the buffer with a quiet noise floor, around -65 dBFS.
 *
 * Not decoration. aubio discards any beat it predicts inside a hop quieter than
 * -90 dBFS, and the gaps between clicks would otherwise be exact digital
 * silence, so most of the beats it correctly predicts would be thrown away and
 * the tempo would come out several times too slow. Real recordings always have
 * a floor; this makes the fixture representative rather than pathological.
 *
 * The generator is a fixed-seed LCG rather than rand(), so the test is the same
 * run to run and on every platform.
 */
static void write_noise_floor(float *buffer, uint32_t total) {
    uint32_t state = 0x5eed1234u;

    for (uint32_t i = 0u; i < total; i++) {
        state = state * 1664525u + 1013904223u;

        double sample = (double) (state >> 8u) / (double) (1u << 24u) - 0.5;
        buffer[i] = (float) (sample * 0.002);
    }
}

static float *make_click_track(double bpm, uint32_t *out_length) {
    uint32_t total = SAMPLERATE * DURATION_SECONDS;

    /* Rounded up to a whole number of hops so the final hop is never short. */
    total = ((total + HOP_SIZE - 1u) / HOP_SIZE) * HOP_SIZE;

    float *buffer = calloc(total, sizeof(*buffer));
    if (!buffer) return NULL;

    write_noise_floor(buffer, total);

    double interval = 60.0 / bpm * SAMPLERATE;
    for (uint32_t beat = 0u; (uint32_t) (beat * interval) < total; beat++) {
        write_click(buffer, total, (uint32_t) (beat * interval));
    }

    *out_length = total;

    return buffer;
}

static int check_tempo(double truth) {
    uint32_t length = 0u;
    float *track = make_click_track(truth, &length);
    if (!track) {
        fprintf(stderr, "  %.0f BPM: out of memory\n", truth);
        return 1;
    }

    rm_tempo_t *tempo = rm_tempo_new(SAMPLERATE, 0u, HOP_SIZE);
    if (!tempo) {
        fprintf(stderr, "  %.0f BPM: rm_tempo_new failed\n", truth);
        free(track);
        return 1;
    }

    for (uint32_t offset = 0u; offset + HOP_SIZE <= length; offset += HOP_SIZE) {
        rm_tempo_feed(tempo, track + offset, HOP_SIZE);
    }

    float estimate = rm_tempo_bpm(tempo);
    float confidence = rm_tempo_confidence(tempo);
    uint32_t beats = rm_tempo_beats(tempo);
    double distance = octave_distance(estimate, truth);

    int failed = 0;

    if (beats < RM_TEMPO_MIN_BEATS) {
        fprintf(stderr, "  %.0f BPM: only %u beats in %u seconds\n",
                truth, beats, DURATION_SECONDS);
        failed = 1;
    } else if (distance > TOLERANCE) {
        fprintf(stderr, "  %.0f BPM: estimated %.2f, %.3f octaves off\n",
                truth, estimate, distance);
        failed = 1;
    } else if (confidence <= 0.f) {
        fprintf(stderr, "  %.0f BPM: estimated %.2f but reported no confidence\n",
                truth, estimate);
        failed = 1;
    } else {
        printf("  %.0f BPM: got %6.2f  (%.3f octaves off, confidence %.2f, %u beats)\n",
               truth, estimate, distance, confidence, beats);
    }

    rm_tempo_free(tempo);
    free(track);

    return failed;
}

static int check_rejects_bad_arguments(void) {
    int failed = 0;

    if (rm_tempo_new(0u, 1024u, 512u)) {
        fprintf(stderr, "  a samplerate of zero was accepted\n");
        failed = 1;
    }

    /* A hop at least as long as the analysis window leaves the phase vocoder
     * nothing to overlap. */
    if (rm_tempo_new(SAMPLERATE, 512u, 512u)) {
        fprintf(stderr, "  a hop as long as the window was accepted\n");
        failed = 1;
    }

    rm_tempo_t *tempo = rm_tempo_new(SAMPLERATE, 0u, HOP_SIZE);
    if (!tempo) {
        fprintf(stderr, "  rm_tempo_new failed on valid arguments\n");
        return 1;
    }

    float hop[HOP_SIZE] = {0.f};

    if (rm_tempo_feed(tempo, hop, HOP_SIZE - 1u) != -1) {
        fprintf(stderr, "  a short hop was accepted\n");
        failed = 1;
    }

    if (rm_tempo_feed(tempo, NULL, HOP_SIZE) != -1) {
        fprintf(stderr, "  a null hop was accepted\n");
        failed = 1;
    }

    /* Silence has no beats, and no beats has to mean unknown rather than 0 BPM
     * presented as an answer. */
    for (uint32_t i = 0u; i < 100u; i++) {
        rm_tempo_feed(tempo, hop, HOP_SIZE);
    }

    if (rm_tempo_bpm(tempo) != 0.f) {
        fprintf(stderr, "  silence produced a tempo\n");
        failed = 1;
    }

    rm_tempo_free(tempo);

    /* Freeing null is a no-op, which the JNI layer relies on. */
    rm_tempo_free(NULL);

    return failed;
}

int main(void) {
    int failed = 0;

    printf("arguments\n");
    failed |= check_rejects_bad_arguments();

    printf("click tracks\n");
    failed |= check_tempo(100.0);
    failed |= check_tempo(120.0);
    failed |= check_tempo(160.0);

    if (failed) {
        printf("FAILED\n");
        return 1;
    }

    printf("ok\n");

    return 0;
}
