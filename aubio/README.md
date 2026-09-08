# :aubio

Tempo estimation, as a self-contained native library. Nothing here is wired into
the app; it produces `librmtempo.so` and a Kotlin handle over it, and that is
where this module stops.

## What is vendored

`vendor/aubio/` is a verbatim copy of [aubio](https://github.com/aubio/aubio)'s
`src/` directory at commit
[`ad5cf975aed08cc4562dd008cf9f83b12b82ffb8`](https://github.com/aubio/aubio/commit/ad5cf975aed08cc4562dd008cf9f83b12b82ffb8)
(2026-04-10), alongside its `COPYING`, `AUTHORS`, `ChangeLog` and `VERSION`.

A pinned commit rather than a release, because aubio's last tag is 0.4.9 from
2019 while master is still maintained. In-tree rather than a submodule, because
aubio is GPLv3 and so is this project: having the sources in the tree is the
cleanest way to satisfy the corresponding-source obligation, and it means a
clone needs no extra step.

The whole of `src/` is copied even though only a fifth of it is compiled.
`aubio.h` is an umbrella header that includes the `io/`, `synth/` and `effects/`
headers, so pruning would fork the tree and turn the next re-vendor from a copy
into a merge. Upstream's own `wscript_build`, `meson.build` and `CMakeLists.txt`
come along for the same reason; none of them is used.

To re-vendor, replace the directory wholesale from a new commit and update the
hash above, this module's source list, and [NOTICE.md](../docs/NOTICE.md).

## Why we build it ourselves

aubio builds with `waf`, and pointing waf at the Android NDK is a fight nobody
needs. The sources are clean C99 with no mandatory dependencies, so
[CMakeLists.txt](CMakeLists.txt) compiles the subset the tempo detector actually
needs and skips everything that exists to bind libsndfile, libav, rubberband or
CoreAudio.

That one file configures for two toolchains. Under the NDK it builds the shared
library the app will load; under the host compiler it builds `tempo_smoke`, so
the accuracy work in the next plan measures the same code that ships rather than
a parallel implementation of it.

## Building and testing

```bash
# Android, both ABIs
./gradlew :aubio:assembleDebug

# Host build plus the click-track tests, about a second
scripts/build-host-aubio.sh

# 16 KB page alignment, which Android 15 requires
./gradlew :aubio:verifyNativeAlignment

# On a connected phone: proves the arm64 library loads and agrees with the host
./gradlew :aubio:connectedDebugAndroidTest
```

## Estimating a tempo

`aubio_tempo_get_bpm` reports a running estimate that wanders over a track. For a
whole-file answer, `rm_tempo.c` collects the time of every beat and takes the
median interval between them, which is the standard approach and far steadier.
Fewer than four beats is not enough to take a median of, and reports 0, meaning
unknown.

`aubio_tempo_get_confidence` is likewise per-hop, so the whole-file figure is the
median of the readings taken at detected beats. Confidence matters more here than
in most applications: a wrong tempo does not produce a slightly-off playlist, it
produces a track played at a wildly wrong speed. Choosing the threshold below
which a track is marked unknown rather than guessed is the next plan's job; this
module only has to report the number.
