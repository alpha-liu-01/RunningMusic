# Modification notice

RunningMusic is a modified version of [Chocola](https://github.com/sosauce/Chocola)
(formerly CuteMusic) by sosauce.

Forked on 2026-09-07 from upstream commit
[`715de20`](https://github.com/sosauce/Chocola/commit/715de2094697596f0896d317e5c6409065bcfecb)
(2026-09-01). Modifications by alpha-liu-01 begin after that commit and are
recorded in this repository's git history.

This notice exists to satisfy GNU GPL version 3 section 5(a), which requires a
modified work to carry prominent notices stating that it was modified and giving
a relevant date.

## Copyright

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

The full license text is in [LICENSE](LICENSE). The bundled font is licensed
separately under OFL-1.1; see [font_licence.txt](font_licence.txt).

## Nature of the modifications

RunningMusic adapts Chocola into a music player for running, matching playback
to the runner's cadence. As of this notice the changes are:

- Rebranded from Chocola to RunningMusic, with a distinct `applicationId`
  (`lol.alphaliu01.runningmusic`) so both apps can be installed side by side.
- Build and release-signing changes: a shared dev keystore for debug builds, an
  Android SDK bootstrap script, relaxed Gradle daemon JVM criteria, and a
  release build that fails with an explanation rather than an AGP validation
  error when no keystore is present.

Further changes are documented in the git log rather than duplicated here.

## Branding assets

GPLv3 covers the source code, not the branding. The Chocola name and mascot
artwork belong to sosauce and are not ours to redistribute.

The name and `rootProject.name` have been changed. Two things have not been, and
both are **blockers for any public release**:

- `app/src/main/res/mipmap-*/ic_launcher*.webp` and
  `@drawable/ic_launcher_foreground` are still Chocola's mascot artwork.
- `fastlane/metadata/` is still Chocola's store listing — title, descriptions in
  five languages, screenshots and `icon.png` — inherited from upstream. This fork
  has no store listing of its own, so the directory should be replaced or removed
  before publishing anywhere.

Both are acceptable while the fork is private and undistributed.
