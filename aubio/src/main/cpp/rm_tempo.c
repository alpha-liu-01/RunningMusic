#include "rm_tempo.h"

#include <stdlib.h>
#include <string.h>

#include "aubio.h"

#define RM_TEMPO_DEFAULT_BUF_SIZE 1024u
#define RM_TEMPO_DEFAULT_HOP_SIZE 512u

/* Enough for eleven minutes of beats at 180 BPM before the first grow. */
#define RM_TEMPO_INITIAL_CAPACITY 256u

struct rm_tempo {
    aubio_tempo_t *tempo;
    fvec_t *in;
    fvec_t *out;

    uint32_t samplerate;
    uint32_t hop_size;

    /* Absolute sample position of each detected beat, and aubio's confidence
     * at the moment it was detected. Parallel arrays, same length. */
    uint32_t *beat_at;
    float *confidence_at;
    uint32_t count;
    uint32_t capacity;
};

static int compare_uint32(const void *a, const void *b) {
    uint32_t x = *(const uint32_t *) a;
    uint32_t y = *(const uint32_t *) b;

    return (x > y) - (x < y);
}

static int compare_float(const void *a, const void *b) {
    float x = *(const float *) a;
    float y = *(const float *) b;

    return (x > y) - (x < y);
}

/* Median of an already sorted array. Even lengths take the mean of the middle
 * pair, which matters at low beat counts where one interval either way moves
 * the answer by a percent or two. */
static double median_of_sorted_u32(const uint32_t *sorted, uint32_t n) {
    if (n % 2u == 1u) return (double) sorted[n / 2u];

    return ((double) sorted[n / 2u - 1u] + (double) sorted[n / 2u]) / 2.0;
}

static double median_of_sorted_f32(const float *sorted, uint32_t n) {
    if (n % 2u == 1u) return (double) sorted[n / 2u];

    return ((double) sorted[n / 2u - 1u] + (double) sorted[n / 2u]) / 2.0;
}

static int record_beat(rm_tempo_t *o, uint32_t position, float confidence) {
    if (o->count == o->capacity) {
        uint32_t grown = o->capacity * 2u;

        uint32_t *beats = realloc(o->beat_at, grown * sizeof(*beats));
        if (!beats) return 0;
        o->beat_at = beats;

        float *confidences = realloc(o->confidence_at, grown * sizeof(*confidences));
        if (!confidences) return 0;
        o->confidence_at = confidences;

        o->capacity = grown;
    }

    o->beat_at[o->count] = position;
    o->confidence_at[o->count] = confidence;
    o->count++;

    return 1;
}

rm_tempo_t *rm_tempo_new(uint32_t samplerate, uint32_t buf_size, uint32_t hop_size) {
    if (buf_size == 0u) buf_size = RM_TEMPO_DEFAULT_BUF_SIZE;
    if (hop_size == 0u) hop_size = RM_TEMPO_DEFAULT_HOP_SIZE;

    if (samplerate == 0u || hop_size >= buf_size) return NULL;

    rm_tempo_t *o = calloc(1u, sizeof(*o));
    if (!o) return NULL;

    o->samplerate = samplerate;
    o->hop_size = hop_size;
    o->capacity = RM_TEMPO_INITIAL_CAPACITY;

    o->tempo = new_aubio_tempo("default", buf_size, hop_size, samplerate);
    o->in = new_fvec(hop_size);
    /* aubio writes the beat position within the hop here; length 1 is what its
     * own examples use. */
    o->out = new_fvec(1u);
    o->beat_at = malloc(o->capacity * sizeof(*o->beat_at));
    o->confidence_at = malloc(o->capacity * sizeof(*o->confidence_at));

    if (!o->tempo || !o->in || !o->out || !o->beat_at || !o->confidence_at) {
        rm_tempo_free(o);
        return NULL;
    }

    return o;
}

void rm_tempo_free(rm_tempo_t *o) {
    if (!o) return;

    if (o->tempo) del_aubio_tempo(o->tempo);
    if (o->in) del_fvec(o->in);
    if (o->out) del_fvec(o->out);

    free(o->beat_at);
    free(o->confidence_at);
    free(o);
}

uint32_t rm_tempo_hop_size(const rm_tempo_t *o) {
    return o ? o->hop_size : 0u;
}

int rm_tempo_feed(rm_tempo_t *o, const float *hop, uint32_t n) {
    if (!o || !hop || n != o->hop_size) return -1;

    memcpy(o->in->data, hop, n * sizeof(smpl_t));

    aubio_tempo_do(o->tempo, o->in, o->out);

    if (o->out->data[0] == 0.f) return 0;

    record_beat(o,
                aubio_tempo_get_last(o->tempo),
                (float) aubio_tempo_get_confidence(o->tempo));

    return 1;
}

float rm_tempo_bpm(const rm_tempo_t *o) {
    if (!o || o->count < RM_TEMPO_MIN_BEATS) return 0.f;

    uint32_t n = o->count - 1u;
    uint32_t *intervals = malloc(n * sizeof(*intervals));
    if (!intervals) return 0.f;

    uint32_t usable = 0u;
    for (uint32_t i = 1u; i < o->count; i++) {
        /* Beat positions come back monotonic in practice, but a zero interval
         * would divide by zero and a negative one is meaningless, so neither
         * gets a vote. */
        if (o->beat_at[i] <= o->beat_at[i - 1u]) continue;

        intervals[usable++] = o->beat_at[i] - o->beat_at[i - 1u];
    }

    float bpm = 0.f;
    if (usable >= RM_TEMPO_MIN_BEATS - 1u) {
        qsort(intervals, usable, sizeof(*intervals), compare_uint32);

        double median = median_of_sorted_u32(intervals, usable);
        bpm = (float) (60.0 * (double) o->samplerate / median);
    }

    free(intervals);

    return bpm;
}

float rm_tempo_confidence(const rm_tempo_t *o) {
    if (!o || o->count == 0u) return 0.f;

    float *sorted = malloc(o->count * sizeof(*sorted));
    if (!sorted) return 0.f;

    memcpy(sorted, o->confidence_at, o->count * sizeof(*sorted));
    qsort(sorted, o->count, sizeof(*sorted), compare_float);

    float confidence = (float) median_of_sorted_f32(sorted, o->count);

    free(sorted);

    return confidence;
}

uint32_t rm_tempo_beats(const rm_tempo_t *o) {
    return o ? o->count : 0u;
}
