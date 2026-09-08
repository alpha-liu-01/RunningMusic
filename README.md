<h1 align="center">RunningMusic</h1>

<p align="center">An offline music player for Android that matches your music to your running cadence.</p>

<p align="center">
  <a href="https://github.com/alpha-liu-01/RunningMusic/releases">
    <img src="https://img.shields.io/github/v/release/alpha-liu-01/RunningMusic?style=for-the-badge&logo=github" alt="Latest release"/>
  </a>
  <img src="https://img.shields.io/badge/Kotlin-96%25-7F52FF?style=for-the-badge&logo=Kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3"/>
</p>

---

## About

RunningMusic is a fork of [Chocola](https://github.com/sosauce/Chocola) (formerly
CuteMusic) by [sosauce](https://github.com/sosauce), a fast, offline, Material 3
Expressive music player for Android.

The fork adds cadence-aware playback: analyse each track's BPM, detect your
step rate while you run, and build a queue whose tempo lines up with your
stride. Everything Chocola already does — the library, the metadata editor, the
lyrics support, the equalizer — comes along unchanged.

**0.1.0 is the first public release.** The cadence features work, and they need
real runs to shake out bugs that a short test loop will not find.

Tempo estimation uses [aubio](https://github.com/aubio/aubio), compiled as
native C and called from Kotlin, which is why the project is no longer
Kotlin-only.

## Inherited from Chocola

- Material 3 Expressive design
- Built-in equalizer
- Embedded and synced lyrics support
- Metadata editor
- Blacklist and whitelist folders, with additional scanning rules
- Custom playlists
- Speed and pitch control, repeat modes and a sleep timer
- Fully offline, small install footprint, no unnecessary permissions

## RunningMusic

- Per-track BPM analysis, using an existing TBPM/BPM tag when the file has one,
  otherwise aubio. Results are stored in a cache that survives MediaStore
  rescans
- Sort the library by BPM
- Step-rate detection from the phone's step counter, including with the screen
  off
- Cadence matching with octave folding, so a 170 BPM track and an 85 BPM track
  can both suit a 170 spm run (and a walk can use the pop/EDM 120 BPM cluster)
- A stretch tolerance you can tighten or loosen, plus a suggested nearby
  cadence when your library would cover that one better
- Running mode: a queue built to fill a target duration, playback speed folded
  to your cadence, and the freedom to pick another track without ending the run

## Building

See [GET_STARTED.md](GET_STARTED.md). In short: JDK 21 for the Gradle daemon,
the Android SDK bootstrapped by [`scripts/setup-android-sdk.sh`](scripts/setup-android-sdk.sh),
then `./gradlew assembleDebug`.

## Credits

Chocola, and therefore most of the code here, is the work of
[sosauce](https://github.com/sosauce) and Chocola's contributors.

The heart button on the About screen supports this fork:
[buymeacoffee.com/alphaliu01](https://buymeacoffee.com/alphaliu01).

Chocola's existing translations remain in this tree. Simplified Chinese and
French for RunningMusic's own strings (cadence, tempo analysis, running mode)
are maintained here, in `values-zh-rCN` and `values-fr`. There is no Weblate
project for this fork.

## License

    Copyright (C) 2026 sosauce
    Copyright (C) 2026 alpha-liu-01

    This program is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
    more details.

    You should have received a copy of the GNU General Public License along
    with this program. If not, see <https://www.gnu.org/licenses/>.

The full text is in [LICENSE](LICENSE). The bundled font is licensed separately
under OFL-1.1; see [font_licence.txt](font_licence.txt).

RunningMusic is a modified version of Chocola. See [docs/NOTICE.md](docs/NOTICE.md) for the
GPLv3 section 5(a) modification notice and for the status of the branding assets.
