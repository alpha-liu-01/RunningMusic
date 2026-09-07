/*
 * Whole-file tempo estimation over aubio.
 *
 * Deliberately plain C with no JNI in it, so the host evaluation harness and
 * the phone run the same code. Anything that lives only on one side of that
 * line belongs in rm_tempo_jni.c or in the Kotlin wrapper, not here.
 */

#ifndef RM_TEMPO_H
#define RM_TEMPO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Fewer intervals than this is not a median, it is a guess. */
#define RM_TEMPO_MIN_BEATS 4

typedef struct rm_tempo rm_tempo_t;

/**
 * Creates an estimator. Pass 0 for buf_size or hop_size to take the defaults,
 * 1024 and 512, which is what aubio's own beat tracker uses.
 *
 * Returns NULL if the arguments are unusable or allocation fails.
 */
rm_tempo_t *rm_tempo_new(uint32_t samplerate, uint32_t buf_size, uint32_t hop_size);

void rm_tempo_free(rm_tempo_t *o);

/** The hop size the estimator was created with; feed() wants exactly this many. */
uint32_t rm_tempo_hop_size(const rm_tempo_t *o);

/**
 * Feeds one hop of mono PCM, nominally in [-1, 1].
 *
 * @param n must equal rm_tempo_hop_size; a short final hop should be zero
 *          padded by the caller rather than passed short.
 * @return 1 if a beat landed in this hop, 0 if not, -1 on a bad argument.
 */
int rm_tempo_feed(rm_tempo_t *o, const float *hop, uint32_t n);

/**
 * The whole-file tempo, from the median interval between detected beats.
 *
 * Not aubio's running aubio_tempo_get_bpm, which reflects the last few seconds
 * and wanders over a track. Returns 0 when fewer than RM_TEMPO_MIN_BEATS beats
 * were found, meaning unknown rather than zero.
 */
float rm_tempo_bpm(const rm_tempo_t *o);

/**
 * Median of aubio's confidence at each detected beat, since aubio reports it
 * per hop rather than per file. 0 when there is nothing to be confident about.
 */
float rm_tempo_confidence(const rm_tempo_t *o);

/** How many beats were detected, which is worth reporting even when bpm is 0. */
uint32_t rm_tempo_beats(const rm_tempo_t *o);

#ifdef __cplusplus
}
#endif

#endif /* RM_TEMPO_H */
