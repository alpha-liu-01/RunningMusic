/*
 * The Android half of the tempo library: a mechanical translation between
 * AubioTempo.kt and rm_tempo.h, and nothing else.
 *
 * Anything with a decision in it belongs in rm_tempo.c, so that the host
 * evaluation harness measures the same code that runs on the phone.
 */

#include <jni.h>
#include <stdlib.h>

#include "rm_tempo.h"

/*
 * Handles cross the boundary as jlong. The Kotlin side owns the lifetime and
 * refuses to call anything once it has closed, so these only have to survive a
 * zero handle, which is what a closed object presents.
 *
 * These are instance methods rather than statics on a companion, purely so the
 * mangled names stay the plain Java_..._AubioTempo_native* form.
 */
static rm_tempo_t *handle_of(jlong handle) {
    return (rm_tempo_t *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeNew(
        JNIEnv *env, jobject thiz,
        jint samplerate, jint buf_size, jint hop_size) {
    (void) env;
    (void) thiz;

    if (samplerate <= 0 || buf_size <= 0 || hop_size <= 0) return 0;

    rm_tempo_t *tempo = rm_tempo_new((uint32_t) samplerate,
                                     (uint32_t) buf_size,
                                     (uint32_t) hop_size);

    return (jlong) (intptr_t) tempo;
}

JNIEXPORT void JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeFree(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;

    rm_tempo_free(handle_of(handle));
}

JNIEXPORT jboolean JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeFeed(
        JNIEnv *env, jobject thiz,
        jlong handle, jfloatArray hop, jint length) {
    (void) thiz;

    /* Critical rather than Get/ReleaseFloatArrayElements: this is called once
     * per 512 samples for the whole of a track, and the alternative copies the
     * array every time. The window holds no JNI calls, which is its rule. */
    jfloat *samples = (*env)->GetPrimitiveArrayCritical(env, hop, NULL);
    if (!samples) return JNI_FALSE;

    int beat = rm_tempo_feed(handle_of(handle), samples, (uint32_t) length);

    (*env)->ReleasePrimitiveArrayCritical(env, hop, samples, JNI_ABORT);

    return beat == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeBpm(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;

    return rm_tempo_bpm(handle_of(handle));
}

JNIEXPORT jfloat JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeConfidence(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;

    return rm_tempo_confidence(handle_of(handle));
}

JNIEXPORT jint JNICALL
Java_lol_alphaliu01_runningmusic_aubio_AubioTempo_nativeBeats(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;

    return (jint) rm_tempo_beats(handle_of(handle));
}
