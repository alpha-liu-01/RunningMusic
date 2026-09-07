cmake_minimum_required(VERSION 3.22)

# 3.22 rather than something older because CMake 4, which is what this machine
# has on PATH for the host build, refuses anything below 3.5. The Android build
# uses the SDK's 3.31.6 instead; see build.gradle.kts.
project(rmtempo C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(AUBIO_SRC "${CMAKE_CURRENT_SOURCE_DIR}/vendor/aubio/src")

# The tempo detector's dependency closure, traced through the includes, and
# nothing else. All of vendor/aubio/src is checked in so the tree stays a
# verbatim copy, but four fifths of it exists to bind libsndfile, libav,
# rubberband or CoreAudio and cannot be built here at all.
#
# Listed one by one rather than globbed: a re-vendor that adds a file should be
# a decision, not a surprise.
set(AUBIO_SOURCES
    # vectors and maths
    "${AUBIO_SRC}/fvec.c"
    "${AUBIO_SRC}/cvec.c"
    "${AUBIO_SRC}/lvec.c"
    "${AUBIO_SRC}/fmat.c"
    "${AUBIO_SRC}/mathutils.c"
    "${AUBIO_SRC}/musicutils.c"
    "${AUBIO_SRC}/vecutils.c"
    # utilities
    "${AUBIO_SRC}/utils/log.c"
    "${AUBIO_SRC}/utils/hist.c"
    "${AUBIO_SRC}/utils/scale.c"
    # spectral, on the bundled ooura FFT
    "${AUBIO_SRC}/spectral/fft.c"
    "${AUBIO_SRC}/spectral/ooura_fft8g.c"
    "${AUBIO_SRC}/spectral/phasevoc.c"
    "${AUBIO_SRC}/spectral/specdesc.c"
    "${AUBIO_SRC}/spectral/statistics.c"
    "${AUBIO_SRC}/spectral/awhitening.c"
    # onset detection, which the beat tracker runs on top of
    "${AUBIO_SRC}/onset/onset.c"
    "${AUBIO_SRC}/onset/peakpicker.c"
    # the beat tracker itself
    "${AUBIO_SRC}/tempo/tempo.c"
    "${AUBIO_SRC}/tempo/beattracking.c"
    # filters used by the peak picker
    "${AUBIO_SRC}/temporal/filter.c"
    "${AUBIO_SRC}/temporal/biquad.c"
    "${AUBIO_SRC}/temporal/a_weighting.c"
    "${AUBIO_SRC}/temporal/c_weighting.c"
)

add_library(aubio_tempo STATIC
    ${AUBIO_SOURCES}
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/rm_tempo.c"
)

target_include_directories(aubio_tempo PUBLIC
    "${AUBIO_SRC}"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
)

# aubio_priv.h guards even <stdlib.h> behind a HAVE_ macro, so without these
# nothing compiles at all. Upstream generates a config.h from its own build
# system; passing the same handful of defines directly is equivalent and means
# the vendored tree needs no generated file in it.
#
# Every optional backend stays off, so spectral/fft.c falls through to the
# bundled ooura implementation and the library has no external dependency of
# any kind. That is the whole reason this is tractable.
target_compile_definitions(aubio_tempo PRIVATE
    HAVE_STDLIB_H
    HAVE_STDIO_H
    HAVE_MATH_H
    HAVE_STRING_H
    HAVE_ERRNO_H
    HAVE_LIMITS_H
    HAVE_STDARG_H
    HAVE_C99_VARARGS_MACROS
)

# Upstream's own warnings are not ours to fix, and there are a few.
target_compile_options(aubio_tempo PRIVATE -Wall -Wno-unused-parameter)

if(NOT ANDROID)
    target_link_libraries(aubio_tempo PUBLIC m)
endif()

if(ANDROID)
    # What ships: the JNI shim, statically linked against everything above so
    # there is exactly one library to load and package.
    add_library(rmtempo SHARED "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/rm_tempo_jni.c")
    target_link_libraries(rmtempo PRIVATE aubio_tempo log)

    # Drops the unused three quarters of what got compiled. Worth doing: this
    # library lands in the APK for two ABIs.
    target_link_options(rmtempo PRIVATE -Wl,--gc-sections)
    target_compile_options(aubio_tempo PRIVATE -ffunction-sections -fdata-sections)
else()
    # The host build, which exists so accuracy is measured on the same C that
    # ships rather than on a second implementation of it.
    enable_testing()

    add_executable(tempo_smoke "${CMAKE_CURRENT_SOURCE_DIR}/tools/tempo_smoke.c")
    target_link_libraries(tempo_smoke PRIVATE aubio_tempo)

    add_test(NAME tempo_smoke COMMAND tempo_smoke)

    # The measuring half of the accuracy harness. Not a test: it reads PCM on
    # stdin and prints an estimate, and the Kotlin driver decides what that
    # estimate is worth.
    add_executable(bpm_probe "${CMAKE_CURRENT_SOURCE_DIR}/tools/bpm_probe.c")
    target_link_libraries(bpm_probe PRIVATE aubio_tempo)
endif()
