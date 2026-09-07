## Verdict

All five goals are feasible, and Chocola was a good pick — Room, Media3 with a real `MediaLibraryService`, Koin DI, and a taglib binding are already wired up, so you're adding features rather than building infrastructure. Roughly: goal 5 is an afternoon, goal 2 is a day, goal 1 is the bulk of the engineering, goal 4 is the riskiest, and **goal 3 needed a design decision rather than more code, because matching raw BPM to raw cadence isn't musically achievable.** That decision is now settled below — octave folding with a tolerance band — and since it's the heart of the app, it's the longest section here.

The one structural thing to know up front: this app deliberately keeps no per-track cache. `AbstractTracksScanner` re-queries MediaStore on every launch and rebuilds `List<CuteTrack>` in memory; Room holds playlists and nothing else. BPM is expensive to compute and must persist, so you're introducing the app's first real track-metadata store. That's the spine everything else hangs off.

## Goal 1: BPM analysis

You need two things the app doesn't have: access to decoded audio, and somewhere to keep the result.

**Getting audio.** The equalizer works via `DynamicsProcessing` attached to ExoPlayer's output audio session, so no PCM ever reaches Kotlin. Don't try to fix that by inserting a custom `AudioProcessor` into playback — analysing during playback means a song's BPM is only known after you've already played it, which is useless for sorting. Instead decode files offline with `MediaExtractor` + `MediaCodec`, which runs many times faster than realtime. Downmix to mono, resample to ~22 kHz, and analyse maybe 60–90 seconds from the middle of the track rather than the whole thing. A typical song lands in the low seconds.

**Computing BPM. Decided: [aubio](https://github.com/aubio/aubio).** GPLv3 C, so a perfect license fit, and the strongest accuracy of the realistic options. The cost is that it drags in an NDK toolchain the project doesn't have — there's no `externalNativeBuild` or CMake anywhere today, and the existing `abiFilters` entry is only there because taglib ships prebuilt native libraries. Build and integration details are in the build section below.

Recorded for completeness, since both stay viable if aubio disappoints:

[TarsosDSP](https://github.com/JorenSix/TarsosDSP) is pure Java, also GPLv3, with onset detection and a BeatRoot port, and it would need no native toolchain at all. The catch is that the Maven Central artifact `be.tarsos.dsp:core:2.5` dates to 2023 and omits the Android I/O classes, so you'd vendor the source. Worth keeping in mind as the fallback that costs nothing but a dependency.

Essentia is the most accurate of the three, but it's AGPLv3 and a heavy C++ build — the license alone complicates GPLv3 distribution. Skip.

Rolling your own — spectral flux into autocorrelation or a comb filter bank — is also genuinely viable at maybe 300 lines of Kotlin, especially given the octave-agnostic accuracy note below. Not needed now that aubio is chosen, but it's the reason not to panic if the NDK work gets ugly.

Whichever engine is in play, **put it behind an interface that takes `FloatArray` PCM and returns BPM plus a confidence value.** That keeps the swap cheap and, as the build section explains, is what makes the DSP testable off-device.

Also read the file's existing `TBPM`/`BPM` tag via taglib first and skip analysis when it's present — free and instant for tracks that already carry it. Note that if you later want to *write* BPM back to files, the current save path in `Extensions.kt` rebuilds a fixed property map from a whitelist, so it silently drops unknown tags. Writing BPM means merging into the existing `propertyMap` instead — which is arguably an upstream bug fix worth contributing back.

**The key design decision: what to key the cache on.** Your instinct will be `mediaId`, but that's `MediaStore._ID`, which is not stable — it changes when the media store is rebuilt or files move, and SAF tracks use `uri.hashCode()`. The existence of `PlaylistCleanup.kt`, which purges stale mediaIds from playlists, is proof this happens in the wild. Losing an entire library's analysis to a rescan would be brutal. Key on something durable instead: path plus file size plus duration, or a hash of the first chunk of file bytes. Get this right on day one; migrating it later means re-analysing everyone's library.

**Running it at scale.** For a 2000-track library you need a proper background job with progress UI, cancellation, and charging/idle awareness. WorkManager isn't a dependency yet, so that's one addition. Room is at version 2 with an existing `MIGRATION_1_2` you can copy the pattern from.

## Goal 2: sort by BPM

Easy, with two small traps.

Library sorting currently happens in SQL — `tracksSettingsToMediaStore()` builds an `ORDER BY` clause for the `ContentResolver.query()`. BPM lives in your database, not MediaStore, so BPM sort has to be an in-memory pass after the query. Fine, and the album/artist/playlist screens already sort in memory via `ordered()` extensions, so there's precedent.

The trap is that `TrackSort` is persisted as an **integer index** into `enum.entries`. Append `BPM` at the end; inserting it mid-enum silently rewrites every existing user's saved sort preference. Related: `AS_ADDED` already sits at index 5 but `TrackSortPopupContent()` only renders `repeat(5)` options, so there's a latent off-by-one in that popup you'll be editing. Decide too where un-analysed tracks sort — nulls last is the sane default.

## Goal 3: speed matched to a target BPM

The code is nearly trivial and the product design is genuinely hard.

The easy half: Media3's `PlaybackParameters.speed` already time-stretches while *preserving pitch* (it uses Sonic internally), and `pitch` is a fully separate parameter the app exposes independently. So tempo-only adjustment is free — you literally already have `PlayerActions.SetSpeed`. You'd want the existing "snap" toggle, which ties pitch to speed, forced off in running mode. And `MusicViewModel.onMediaItemTransition` already resolves the `CuteTrack` for each new item, so the recompute hook is a few lines in an existing block.

Now the hard half. Running cadence is roughly 150–190 steps per minute. Most music sits at 90–140 BPM. Forcing a 95 BPM track to 170 BPM literally requires 1.79× speed, which sounds mangled even with pitch preserved. **Matching raw BPM to raw cadence doesn't survive contact with a real music library.**

### Octave folding, and why it fixes the original objection

Runners don't step once per beat — they step on beats or *between* beats. An 85 BPM track at two steps per beat already *is* 170 SPM with no stretch at all. So the quantity to match isn't the track's BPM, it's the track's BPM times some step-per-beat factor. Work in log space: fold each track's BPM by powers of two until it lands nearest the target cadence, and the residual is the stretch you actually have to apply.

```
k        = round(log2(target / bpm))     // steps per beat, as a power of two
folded   = bpm * 2^k
speed    = target / folded               // the stretch you must apply
```

Because `k` is rounded, the residual is bounded: `speed` always falls in `[2^-0.5, 2^0.5]`, i.e. **±41% worst case, and that bound is unconditional.** That's the number you identified, and it's the key structural fact. A track sitting exactly halfway between two octaves is equidistant from both, and 41% is genuinely too much to listen to — so folding alone isn't sufficient, which is where your tolerance band comes in.

Now the part that reconciles this with your original instinct against filtering. Accept a track only when its residual is within a ratio `r`, and the accepted set is not one narrow BPM window — it's a window repeated at *every* octave:

```
accept if  bpm ∈ ⋃  [ target·2^k / r , target·2^k · r ]
                  k
```

For a 100 SPM target at `r = 1.2` that's 83–120 **and** 167–240 **and** 42–60, exactly as you described. That union is why this isn't the naive filtering you objected to. Naive filtering means "only songs at 100 BPM ± a bit," which throws away almost everything; octave-folded banding keeps a slice of every tempo region in the library. Your original objection was really to *single-band* filtering, and this is a different animal.

The two ideas also turn out to be the same formula. Adjacent bands touch when `r² = 2`, so at `r = √2 ≈ 1.414` the bands tile the whole number line and the filter accepts everything — which is precisely the "fold and accept any residual" case. **`r` is a single dial from "accept everything, up to ±41% stretch" down to "accept little, near-zero stretch."** That's a clean design, and I'd build exactly this.

As a back-of-envelope for what `r` costs you: if track BPMs were spread uniformly in log space, the fraction of the library accepted is just `2·log₂(r)`.

| `r` | max stretch | rough share of library kept |
| --- | --- | --- |
| 1.05 | ±5% | ~14% |
| 1.10 | ±10% | ~28% |
| 1.20 | ±20% | ~53% |
| 1.414 | ±41% | 100% |

Treat those as an upper bound, not a prediction — real BPM distributions are lumpy, which is the next problem.

### In practice it's a binary choice, not an infinite grid

Worth noticing before you write the code: for real running cadences, only two values of `k` are usable. Reachable bands for a 150–190 SPM target are 150–190 BPM at one step per beat (`k = 0`), and 75–95 BPM at two (`k = 1`). The next step up, four steps per beat, would need 37–48 BPM tracks — which barely exist, and a beat that sparse is impossible to entrain to anyway. Going the other way, one step per two beats, would need 300–380 BPM tracks, which don't exist. So the whole octave-folding machinery collapses to: **compute the speed for one step per beat and for two, take whichever is closer to 1.0.** Clamp `k` to `{0, 1}` explicitly rather than trusting `round()`, or a mis-detected 40 BPM ambient track will happily report a perfect match at four steps per beat.

### The 120–130 BPM trap

This is the thing your 100 SPM example hides, and it's the most important practical consequence. The single largest tempo cluster in pop and EDM is 120–130 BPM. Check whether it's reachable from a running cadence: 125 doubles to 250 and halves to 62.5, and no power-of-two multiple of 125 lands anywhere in 150–190. A 170 SPM runner is at `120 × √2`, meaning the biggest cluster in the library sits at the *exact worst case* of the fold — 41% off, in both directions.

So library coverage is a lumpy function of the target cadence, not a smooth one. Cadences whose half-value lands on a real tempo peak do well (160 → 80, 180 → 90, 190 → 95); cadences that land in a trough do badly (170 → 85, between the 80 and 90 clusters). This suggests a feature rather than a compromise: compute the folded-BPM histogram of the user's own library, and let the app **suggest a target cadence** within a few SPM of their measured one that their library actually supports. Human cadence has a comfortable range of several SPM anyway, and nudging it slightly upward is generally considered good running form, so this is a defensible nudge rather than a hack. Shifting a target from 170 to 178 can unlock a large chunk of library at a much tighter `r`.

The gap at `√2` can only be filled by non-power-of-two factors. Three steps per two beats would put a 120 BPM track at 180 SPM with essentially zero stretch — mathematically perfect, but it's a 3:2 polyrhythm against a straight 4/4 groove and most people find it fights the music. It *is* natural in 6/8 and shuffled material. Ship it as an experimental opt-in at most; don't put it in the default factor set.

### Set the tolerance from run length, not the other way round

Your observation that a bigger library allows a tighter range generalises nicely, and inverting it makes for better UX. Rather than having the user pick `r` and discover how many tracks survive, have them state how long they're running. Rank every analysed track by absolute log-deviation, take tracks until the duration is filled, and `r` falls out as the worst deviation you had to include. That's self-tuning, guarantees the run is covered, and minimises average distortion instead of just bounding it. It also gives you an honest thing to display: "45 minutes, 68 tracks, at most ±7% stretch."

Two caveats. Keep a hard musical ceiling on `r` (somewhere around 1.15) so a thin library degrades by admitting a shorter queue rather than by producing unlistenable audio. And don't order the queue strictly by deviation, or every run starts with the same songs — filter by deviation, then shuffle within, optionally weighted toward smaller deviation.

One more knob worth testing: the band probably shouldn't be symmetric. Speeding up adds energy that suits running, while slowing down tends to drag, so something like +12%/−8% may feel better than ±10%. That's a hypothesis to validate on real runs, not a fact.

### This makes Goal 1 easier

A useful consequence: because the whole scheme folds by powers of two, the classic failure mode of tempo detectors becomes harmless. Reporting 85 when the truth is 170 — the octave error that the MIR field tracks separately as "Accuracy2" versus "Accuracy1" — produces an identical playback speed here. You only need octave-agnostic accuracy, which is much easier to hit and is a solid argument for the roll-your-own detector suggested above.

### Implementation gotchas

**Speed logic must live in the service, not the ViewModel.** `MusicViewModel` is UI-layer. On an actual run the screen is off and the activity is very likely destroyed while `PlaybackService` keeps playing — at which point track transitions stop triggering your recompute and the speed freezes at whatever the last foreground track needed. `PlaybackService.listener` doesn't implement `onMediaItemTransition` today. Moving the BPM/speed logic into the service or a Koin-injected domain object is a slightly larger refactor than the ViewModel hook suggests, and it's the same conclusion you'll reach for the step counter.

**Persistence needs rethinking.** Speed currently lives in `MusicState.speed` and is written to DataStore in `onCleared()`. Once speed is derived per track, that stored value is meaningless on restore. What you want to persist is the *target cadence* and the tolerance, plus a mode flag distinguishing manual speed from cadence-locked. Also ramp speed changes rather than stepping them, to avoid clicks at track boundaries.

**Never change the step-per-beat factor mid-track.** Once the step counter is drifting the target (Goal 4), the optimal `k` for the current track can flip while it's playing — and applying that means a 2× speed jump mid-song. Re-evaluate `k` only at track transitions; mid-track, allow only the residual stretch to move, clamped and ramped.

**The queue has to be built lazily.** A drifting target means the accepted set of tracks changes during the run, so materialising a fixed playlist up front is wrong. Today `PlayerActions.StartPlaylist` does `setMediaItems(targetTracks.fastMap { it.toMediaItem() }, 0, 0)` — the entire list, once. For running mode you want a short lookahead window that gets revised as the target moves, via `replaceMediaItems` / `addMediaItems`. That's a genuinely different queue architecture from what exists, and it's easier to design for now than to retrofit.

**A cosmetic setting becomes load-bearing.** `CuteSlider.kt` already divides displayed time by `musicState.speed` when the `DYNAMIC_DURATION` preference is on. Since every track in running mode plays at a non-unity speed, that existing feature turns into the correct default rather than a curiosity — and the same correction has to reach anywhere you show a track or playlist duration, including the run-length estimate above.

## Goal 4: step counter driving the target

Technically medium, but this carries the most risk — both platform and UX.

Use `Sensor.TYPE_STEP_DETECTOR` (one event per step, low latency, hardware-batched on most devices) rather than `TYPE_STEP_COUNTER` (cumulative and laggy) and derive cadence from inter-event intervals. Avoid raw accelerometer polling; it's a battery sink. `ACTIVITY_RECOGNITION` is a runtime permission from API 29, and your `minSdk` is 28, so you need both paths. The manifest currently declares no sensor permissions and there is zero sensor code in the repo.

The platform uncertainty worth verifying early on a real device: the service is `foregroundServiceType="mediaPlayback"`, and background sensor access under newer Android versions has restrictions. You may need to add the `health` FGS type with its accompanying permissions. Test this on a modern device before building much on top of it.

The UX risk is bigger and it's a control-theory problem. **Naive feedback runs away**: you speed up the music, the runner speeds up to match, cadence rises, you speed up again. You need aggressive smoothing (a 30–60 second median, not an instantaneous reading), a deadband so small fluctuations do nothing, and rate limiting on how fast the target can move. Honestly, the best default may be a "measure then lock" mode — sample the runner's natural cadence for the first minute, set the target, and hold it, with continuous tracking as an opt-in. You also need to detect stopping and walking so you hold the last target instead of collapsing to 0.6× at a traffic light.

## Goal 5: license and fork housekeeping

Cheap, and worth doing before you write a line of feature code.

GPLv3 obligations on a fork: keep the license, retain sosauce's copyright notice (add yours alongside, don't replace), and state that you modified the files — that's §5(a). The bundled font is separately OFL-1.1, so keep `font_licence.txt` intact.

Your library candidates are all fine: TarsosDSP and aubio are GPLv3, a perfect match. SoundTouch is LGPLv2.1 and compatible, though you don't need it since Media3 already time-stretches. Essentia's AGPLv3 is the only one that changes your license story.

The practical fork chores nobody remembers:

`applicationId` is still `com.sosauce.cutemusic` while the namespace is `com.sosauce.chocola`. You **must** change the applicationId or your app can never be installed alongside Chocola or distributed independently. The release APK is also named `Chocola_${versionName}.apk` in `app/build.gradle.kts`. Separately, GPLv3 licenses the code, not the branding — the "Chocola" name and the mascot artwork aren't yours to ship, so plan a rebrand of name and assets. `GET_STARTED.md` still tells contributors to clone `sosauce/CuteMusic`, and there's a `release_key.jks` signing path plus GitHub Actions secrets pointing at the upstream author's setup.

One more thing that will matter for years: you'll want to keep pulling upstream improvements. Your changes as scoped would touch `CuteTrack`, `AbstractTracksScanner`, `Enums.kt`, `CuteSearchbar.kt`, `MusicViewModel`, and `PlaybackService` — all high-churn upstream files. Keeping new logic in its own package and minimising edits to existing files will make those rebases dramatically less painful. Also note the toolchain is bleeding-edge (compileSdk 37, AGP 9.3.2, Material3 alpha, Navigation3), so expect churn independent of your work.

## Build and test setup

Short version: the plan is feasible, and **the move to Kubuntu deleted most of what this section used to be about.** Roughly half the build goal was already done upstream, and the other half — a Linux toolchain that can cross-compile C for Android — is now simply the machine you're sitting at. Build on the host, let GitHub Actions produce APKs, and don't build the Docker image.

### What already exists

`.github/workflows/nightly_build.yml` is a working Ubuntu + JDK 17 job that runs `./gradlew assembleDebug` and publishes the APK to a prerelease GitHub release. That is precisely "an automated build producing a test-signed APK," already written and already working. `release_stable.yml` handles the release-signed path too, decoding a base64 keystore from `secrets.SIGNING_KEY` into `app/release_key.jks` and feeding the three signing env vars that `app/build.gradle.kts` already reads. Both are `workflow_dispatch`, so nothing fires until you ask it to.

The fork plumbing is done as well: `origin` is `alpha-liu-01/RunningMusic`, `upstream` is `sosauce/Chocola`, and you're currently on branch `test`. Both workflows reference only repo-relative paths, so they'll run unchanged on your fork the moment you dispatch them. What's left for "automated build → APK" is cosmetic and administrative — the release APK is still named `Chocola_${versionName}.apk`, the upload artifact is still called `CuteMusic Release`, the nightly job globs `./**/*.apk` indiscriminately, and the three signing secrets are still sosauce's. Hours, not days. Building a parallel Docker pipeline to do the same thing would mean maintaining two build definitions that will drift.

Two other pleasant surprises: `.gitignore` already covers `.cxx/` and `.externalNativeBuild/`, so someone anticipated native code, and `*.jks` / `*.keystore` are already ignored, so the obvious keystore-leak footgun is pre-disarmed.

### What doesn't exist: any tests at all

`testInstrumentationRunner` is declared in `defaultConfig`, but there is **no `app/src/test/`, no `app/src/androidTest/`, and not a single test dependency** in `app/build.gradle.kts`. No JUnit, no androidx.test, no Espresso. You are starting testing from zero, which is worth knowing before writing a "test setup" — there is no setup to extend.

### Docker: no longer worth building

On Windows this was the load-bearing recommendation, because a container was the only convenient way to get a Linux toolchain at all. On Kubuntu that argument evaporates. The image was doing three jobs — providing Linux, isolating the NDK, and hosting a native aubio build for evaluation — and the host now does all three.

The Gradle build was never the case for Docker anyway: GitHub Actions gives you a clean Ubuntu image per run, which is stronger reproducibility than a local container, for free.

The NDK isn't a case for it either, once you look at what it actually is. `ndk;29.x` is a self-contained Clang toolchain shipping its own sysroots; it doesn't link against the host's libc or pick up host headers. Installing it through `sdkmanager` on the host produces the same cross-compilation as installing it in an image. The "rots between machines" failure mode is really "rots between NDK versions," and you fix that by pinning `ndkVersion` in `app/build.gradle.kts`, not by maintaining an 8 GB image.

And the Linux host build of aubio — the thing that makes the BPM evaluation harness fast — is now just `gcc` on this machine, with CMake 4.2.3 and ffmpeg 8.0.1 already installed.

Two cases would still justify an image later, neither urgent: reproducing a CI failure you can't reproduce locally, and pinning the evaluation harness so an accuracy figure from six months ago stays comparable. Docker 29.4.1 is installed and your user is in the `docker` group, so that option costs nothing to keep open — it just isn't the right default any more. The shape to aim for now is the host owning day-to-day builds and the native toolchain, and GitHub Actions owning APK production.

### The host: three things to fix before the first build

The filesystem problem is gone. The checkout lives at `/home/alpha/Documents/GitHub/RunningMusic` on ext4 with no translation layer in the way, and there's no second operating system to accidentally build the same tree from. The machine is a Ryzen 9 7945HX with 32 threads, 62 GB of RAM and 290 GB free — comfortably more than this build needs, which matters for one setting below.

Three concrete problems, all cheap, all worth clearing before anything else:

**`~/.gradle` is owned by root.** It's `drwxr-xr-x root root` and dated April, so at some point Gradle ran under `sudo` on this install. The wrapper doesn't degrade gracefully — `./gradlew --version` fails outright today with `Could not create parent directory for lock file`. A single `sudo chown -R alpha:alpha ~/.gradle` fixes it. Worth understanding rather than just fixing, because it's precisely the root-owned-artifacts failure the old container advice was warning about, arrived at without any container.

**There is no JDK 17.** The system has OpenJDK 21 and 25, and `java` resolves to 25. Both workflows use Temurin 17, and the project compiles to JVM 17 bytecode. Gradle 9.7 with AGP 9.3.2 on a JDK 25 daemon is not a combination this project has ever been built with, and when a JDK is too new for AGP the symptom is an obscure Kotlin-daemon or bytecode error rather than a clear message. `openjdk-17-jdk` is in the archive: install it and pin it via `org.gradle.java.home` in your *user-level* `~/.gradle/gradle.properties` — not the repo's, so you don't commit a machine-specific path — and local builds will then agree with CI.

**There is no Android SDK.** `ANDROID_HOME` is unset, `~/Android/Sdk` doesn't exist, and there's no `local.properties`. The Debian `/usr/lib/android-sdk` is platform-tools only, at 34.0.5, and will not build anything. You need `cmdline-tools` and an `sdkmanager` install — the one part of the old Dockerfile that survives, now as a shell script.

One tuning note while you're in there: `gradle.properties` sets `org.gradle.jvmargs=-Xmx1536M` with a matching 1536 MB Kotlin daemon. That's an upstream default sized for modest machines, and it's about four percent of your RAM. Raise both, and consider `org.gradle.parallel` and `org.gradle.caching`. Note that `org.gradle.configuration-cache=true` is already enabled, which is good but is also the thing most likely to complain once you add `externalNativeBuild` and a test source set — if a configuration-cache error appears right after those changes, suspect the new code rather than the machine.

### adb: it just works now

The USB passthrough problem doesn't exist on a native Linux host. `android-udev-rules` is installed, your user is in `plugdev`, and `~/.android/adbkey` already exists from an earlier session, so an authorised device should appear on plug-in with no further setup. Nothing is attached at the moment, so confirm it rather than assume it.

The one real wrinkle is version skew. The `adb` on your `PATH` is Debian's 34.0.5 from 2023, and `sdkmanager` will install Google's current `platform-tools` alongside it. Two adb binaries at different versions will repeatedly kill each other's servers, which is a genuinely baffling five minutes the first time. Put the SDK's `platform-tools` first on `PATH` and either remove the Debian package or ignore it consistently — but decide once.

Wireless debugging still matters, and for goal 4 it stops being a convenience and becomes necessary: **you cannot be tethered by USB while running.** `adb pair` then `adb connect` over the LAN, plus `logcat`, is how you'll actually observe a cadence control loop in the field. If `adb mdns check` reports discovery unavailable — Debian's adb build sometimes omits it — pair using the explicit `host:port` the phone displays instead of relying on autodiscovery.

### One dev keystore, or you will lose an afternoon

Debug builds are signed with `~/.android/debug.keystore`, which is generated per-machine at random. The moment CI builds a debug APK — or you rebuild this laptop, or add a second one — the signatures differ and `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, so you uninstall and lose app data every time you switch build source. Generate **one** dedicated dev keystore, keep it outside the repo (it's gitignored anyway), and wire it into an explicit `debug` signingConfig. Do this on day one; conveniently, `~/.android` holds no `debug.keystore` at all right now, since nothing has ever built on this machine, so you're setting the policy on a clean slate rather than migrating off an accidental one. The existing `applicationIdSuffix = ".debug"` already lets debug and release coexist on the device, which is good, and worth preserving.

### Three toolchain facts, now checked rather than assumed

The SDK question is settled: Google's stable channel currently publishes `platforms;android-37.0`, `37.1` and `37.2`, along with `build-tools;37.0.0`. No preview channel needed, and nothing here blocks. One detail that will bite when you write the install script, though — since Android 16 the platforms are minor-versioned, so **there is no bare `platforms;android-37` package**; `compileSdk = 37` wants `platforms;android-37.0`.

**Android 15+ requires native libraries to support 16 KB memory page sizes.** You're about to add native code for the first time, so this applies to you, and it's easy to miss until a store rejection or a crash on a 16 KB device. Use NDK r28 or newer, where it's the default — stable currently offers 28.2.x, 29.0.x and 30.0.x. Pin an exact `ndkVersion` rather than letting AGP choose, and verify alignment on the built `.so` files rather than assuming.

Third, and new now that you're building on a host with its own toolchain: **pin the CMake version too.** The host has CMake 4.2.3 on `PATH`, and CMake 4 dropped compatibility with `cmake_minimum_required(VERSION < 3.5)`. Set `externalNativeBuild.cmake.version` to an SDK-provided CMake so Gradle uses that rather than whatever the host happens to have — this is also what keeps your native build and CI's native build the same build.

### Aubio specifics

Don't try to cross-compile aubio with its own build system. aubio uses `waf`, and making waf target the Android NDK is a fight. aubio's sources are clean C99 with essentially no mandatory dependencies, so the tractable route is to **write your own `CMakeLists.txt` over aubio's `src/`** and hook it up through Gradle's `externalNativeBuild { cmake { } }`, which is the supported, documented path. Build with aubio's bundled ooura FFT (`HAVE_FFTW3` off) so you pull in no external library at all.

Write that `CMakeLists.txt` so it configures for the host as well as for the NDK. It's the same C either way, and one source list configured twice — once with the NDK toolchain file for `arm64-v8a` and `armeabi-v7a`, once with plain `gcc` — hands you the evaluation binary described below for free, and guarantees the code you measure accuracy on is the code you ship. On Windows that dual build was the container's entire justification; here it's a second build directory.

Pin a specific git commit rather than the released tarball: aubio's last release is 0.4.9 from 2019, but master is still maintained. Vendoring a pinned commit in-tree also cleanly satisfies your GPLv3 corresponding-source obligation.

Keep the JNI surface tiny — a handle-based object holding an `aubio_tempo_t*`, one function that feeds a hop of mono float PCM, and getters for BPM and confidence. For a whole-file estimate, collect beat times and take the median inter-beat interval rather than trusting the running `aubio_tempo_get_bpm` value; that's the standard approach in the tempo-estimation literature.

Do use `aubio_tempo_get_confidence`. It matters more here than in most applications: a wrong BPM doesn't produce a slightly-off playlist, it produces a track played at a wildly wrong speed. Low confidence should mark a track "BPM unknown" and exclude it, not guess.

### Testing: the part your plan doesn't cover yet

You described building thoroughly and testing only as "I have a phone and adb." The phone is necessary but it's the *least* automatable layer, and the highest-value testing here is nowhere near it.

**BPM accuracy is an evaluation harness, not a unit test.** There's no assertion that passes or fails; there's an accuracy figure that you want to not regress. Build it as a native binary on the host over a corpus with known tempos, and — per the Goal 3 analysis — score it **octave-agnostically**, folding the log-ratio between estimate and truth into `[0, 0.5]`. That's the only metric that reflects what your app actually needs, and it's much more forgiving than the strict accuracy figures aubio is usually judged by. Also plot correctness against aubio's confidence, so you can pick the threshold below which you refuse to guess. Don't commit audio to the repo; commit a manifest of file hashes and ground-truth BPMs pointing at a local corpus. ffmpeg is already installed, so decoding that corpus to raw mono PCM is one command per file and the harness never needs `MediaCodec` — the same `FloatArray` boundary argument made two paragraphs down, arriving from the other direction. Hand-tapping fifty tracks from your own library is a perfectly respectable start, and synthetic click tracks give you exact-truth regression fixtures for free.

**The pure Kotlin logic is where cheap tests pay off most.** Octave folding, tolerance banding, queue selection, and duration estimation from Goal 3 are all pure functions with no Android dependency. They're also exactly the code where an off-by-one silently produces music at 1.4× instead of 1.05×. A plain `src/test/` source set with JUnit covers all of it in milliseconds, and it doesn't exist yet.

**Design the cadence loop to be testable or you'll be debugging it by going jogging.** This is the single most important testing decision in the project. Make the control loop a pure function over a stream of step timestamps, then add a debug mode that records raw step timestamps to a file. One real run then gives you a replayable fixture forever, and you can unit-test the failure modes that matter — runaway feedback, stopping at a traffic light, walking breaks, a dropped sensor — without leaving your chair. Retrofitting this later is painful; the natural instinct is to wire sensor callbacks straight into playback, and that produces a system you can only test by exercising.

**Reserve the device for what only a device can do.** `MediaExtractor` and `MediaCodec` are real Android APIs that Robolectric won't meaningfully fake, so keep the decode layer thin and cover it with instrumented tests. Room migrations deserve `room-testing`'s `MigrationTestHelper`, given you're adding a migration and the stable-key decision from Goal 1 is hard to undo. Playback feel, audio artefacts at various stretch ratios, and battery drain are manual. Don't invest in Espresso.

If you draw the boundary between decode and DSP at `FloatArray` PCM, the DSP becomes host-testable and the decoder becomes independently instrumentable. That's a good reason to define it that way even ignoring tests.

### Where your single test device falls short

Real hardware is essential for goals 3 and 4 — an emulator has no meaningful step counter and tells you nothing about battery. But three gaps are worth naming now:

crDroid is a custom ROM, so verify early that `TYPE_STEP_DETECTOR` exists and is hardware-backed (`adb shell dumpsys sensorservice`). A software-backed step detector behaves differently on latency and battery, and you'd be tuning against the wrong baseline.

Custom ROMs are also permissive about background execution. "Works on crDroid" says very little about Samsung or Xiaomi, where aggressive process killing is the norm. The foreground-service and background-sensor questions flagged in Goal 4 eventually need a stock OEM device — this is a real blind spot, not a theoretical one.

And your device is Android 16 (API 36) while the app targets API 37, so the behaviour changes you opt into by targeting 37 are exactly the ones you can't observe. `minSdk = 28` is likewise never exercised. Two emulator images, one at API 28 and one at API 37, close both gaps, and on this machine they're genuinely cheap: `/dev/kvm` exists, the CPU reports AMD-V, and an ACL already grants your user read-write access, so hardware acceleration works without even adding yourself to the `kvm` group. Budget a little patience for the emulator's graphics path instead — KDE on Wayland with an NVIDIA card is the configuration most likely to need a `-gpu` fallback.

### Practical host setup notes, for when we do it

Install `cmdline-tools` into `~/Android/Sdk`, accept the licenses, then `sdkmanager` the rest: `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`, an `ndk;29.x`, and a `cmake`. Write it as a checked-in shell script rather than commands you run once — see the GPL note below, and because CI will eventually want the identical list.

Expect roughly 8 GB on disk once the NDK is in; with 290 GB free, the constraint that mattered on Windows doesn't apply, and neither does the `.wslconfig` memory cap that used to get the Kotlin daemon OOM-killed. `local.properties` is gitignored and will be generated with the SDK path, which is correct and should stay uncommitted.

The one piece of container advice that carries over, inverted: keeping the Gradle and Kotlin daemons warm was the reason to prefer a long-lived container over one-shot `docker run`. On the host you get that for free — just don't habitually pass `--no-daemon`, and never run Gradle under `sudo`, which is how `~/.gradle` came to be root-owned in the first place.

### A GPL note

GPLv3's "Corresponding Source" includes the scripts used to control compilation and installation. A documented, scripted, reproducible build isn't just good practice for this project — it's part of what you're obliged to distribute. Worth keeping the build definition in-repo and readable rather than leaving it as tribal knowledge in one laptop's shell history. It's also the tidiest argument for writing that SDK bootstrap as a checked-in script.

## Suggested order

Step zero, before any of the features, is shorter than it was. Fix the root-owned `~/.gradle`, install JDK 17 and pin it, install the SDK, and get `./gradlew assembleDebug` to succeed once on this machine — that single green build retires most of the uncertainty in the section above. Then create the dev keystore and do the artifact renames in the two workflows. The `platforms;android-37` question is already answered and the fork remotes are already configured, so both drop off the list entirely.

Then I'd sequence it to de-risk early: the fork housekeeping and rebrand; then the persistence layer with the stable-key decision and a manually-entered BPM field, which lets you build and validate goal 2's sorting end-to-end with zero DSP; then the aubio toolchain and analyser behind that same interface; then the folding-and-tolerance matcher with a manual cadence slider and a fixed queue; and only last the step counter, which is what forces the dynamic queue and the control loop. That way each stage is independently useful, and the step counter — the piece most likely to fight the platform — lands on top of something already working rather than blocking it.

Two things to slot in earlier than instinct suggests, because retrofitting them is disproportionately painful: the JVM test source set (it doesn't exist, and the folding maths is the highest-value thing to test in the whole project), and the step-timestamp recorder, so that your first real run outdoors becomes a permanent test fixture.

The folding maths is cheap to write and worth prototyping off-device first: run it over a CSV of BPMs to see what tolerance your actual library supports at your actual cadence before committing to any UI. The remaining decision that's expensive to change later is the stable cache key from Goal 1.