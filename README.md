<h1 align="center">RunningMusic</h1>

<p align="center">An offline music player for Android that matches your music to your running cadence.</p>

<p align="center">
  <a href="https://github.com/alpha-liu-01/RunningMusic/releases">
    <img src="https://img.shields.io/github/v/release/alpha-liu-01/RunningMusic?style=for-the-badge&logo=github" alt="Latest release"/>
  </a>
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=Kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3"/>
</p>

---

## About

RunningMusic is a fork of [Chocola](https://github.com/sosauce/Chocola) (formerly
CuteMusic) by [sosauce](https://github.com/sosauce), a fast, offline, Material 3
Expressive music player for Android.

The fork exists to add cadence-aware playback: analyse each track's BPM, detect
your step rate while you run, and build a queue whose tempo lines up with your
stride. Everything Chocola already does — the library, the metadata editor, the
lyrics support, the equalizer — comes along unchanged.

**This is early-stage and not yet released.** The cadence features are still
being built; what is here today is Chocola plus the fork's build and branding
setup.

## Inherited from Chocola

- Material 3 Expressive design
- Built-in equalizer
- Embedded and synced lyrics support
- Metadata editor
- Blacklist and whitelist folders, with additional scanning rules
- Custom playlists
- Speed and pitch control, repeat modes and a sleep timer
- Fully offline, small install footprint, no unnecessary permissions

## Planned

- Per-track BPM analysis with a durable, rescan-proof cache
- Sort and filter the library by BPM
- Step-rate detection and cadence matching, with octave folding so a 170 BPM
  track and an 85 BPM track can both suit a 170 spm cadence
- Queues built to fill a target run length

## Building

See [GET_STARTED.md](GET_STARTED.md). In short: JDK 21 for the Gradle daemon,
the Android SDK bootstrapped by [`scripts/setup-android-sdk.sh`](scripts/setup-android-sdk.sh),
then `./gradlew assembleDebug`.

## Credits

Chocola, and therefore almost all of the code here, is the work of
[sosauce](https://github.com/sosauce) and Chocola's contributors. If you like
this app, the person to thank and to support is sosauce — their support page is
linked from the app's About screen.

Translations were contributed to Chocola through its Weblate project. This fork
does not run its own translation project; please contribute translations
[upstream](https://hosted.weblate.org/engage/chocola/), where they benefit
everyone.

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
