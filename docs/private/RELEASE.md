# Release signing

## The key

| | |
|---|---|
| Location | `~/.android/runningmusic-release.jks` (outside the repo, mode 600) |
| Type | PKCS12, RSA 4096, SHA384withRSA |
| Alias | `runningmusic-release` |
| Validity | 10000 days, until 2054-01-23 |
| SHA-256 | `D5:B8:0F:70:A9:19:3D:98:9C:A5:37:64:80:93:0C:E2:37:97:26:40:16:BE:F0:5E:28:5D:2F:3D:86:7D:A5:D0` |

The store password and the key password are the same. **The password is not
written down in this repository.** It lives in a password manager; if it is lost,
so is the key.

Losing the key is unrecoverable. Android identifies an app by its signing
certificate, so a re-signed APK is a different app to the system: every user has
to uninstall and reinstall, losing their data. Back up the `.jks` somewhere that
is not this machine.

Regenerating it, should that ever be necessary:

```sh
keytool -genkeypair -v \
  -keystore ~/.android/runningmusic-release.jks \
  -storetype PKCS12 \
  -alias runningmusic-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RunningMusic, O=alphaliu01.lol"
```

## GitHub secrets

[`.github/workflows/release_stable.yml`](../../.github/workflows/release_stable.yml)
reads four repository secrets. Set them under Settings > Secrets and variables >
Actions.

| Secret | Contents | Becomes |
|---|---|---|
| `SIGNING_KEY` | The keystore, base64-encoded | decoded to `app/release_key.jks` |
| `KEYSTORE_PASSWORD` | Store password | `SIGNING_STORE_PASSWORD` |
| `KEY_ALIAS` | `runningmusic-release` | `SIGNING_KEY_ALIAS` |
| `KEY_PASSWORD` | Key password | `SIGNING_KEY_PASSWORD` |

The names differ on purpose: the workflow maps the secret names on the left to
the environment variable names on the right, which is what
[`app/build.gradle.kts`](../../app/build.gradle.kts) actually reads.

To produce the `SIGNING_KEY` value:

```sh
base64 -w0 ~/.android/runningmusic-release.jks
```

`-w0` matters. Without it `base64` wraps at 76 columns and the workflow's decode
step receives a mangled string.

Then dispatch the "Release Stable CI" workflow by hand; it has no trigger other
than `workflow_dispatch`.

## CI

The workflow has to compile aubio, so it cannot just set up a JDK and call
Gradle. Before the keystore is decoded it:

1. Installs Temurin 21, which is the daemon JVM pinned in
   `gradle/gradle-daemon-jvm.properties`.
2. Restores a cached Android SDK, keyed on
   [`scripts/setup-android-sdk.sh`](../../scripts/setup-android-sdk.sh), then
   runs that script. The script is the same one a local machine uses, so CI
   gets the same `ndk;29.0.14206865` and `cmake;3.31.6` the native module pins.
3. Decodes `SIGNING_KEY` and builds with `./gradlew :cadence:test assembleRelease`.
   Recording stays off; this is a shipped APK.

After the APK exists, two checks have to pass or nothing is published:

- [`scripts/check-so-alignment.sh`](../../scripts/check-so-alignment.sh) on the
  APK, not the aubio AAR. The APK also packs taglib's libraries, and Android 15
  will refuse any of them aligned to 4 KB.
- `apksigner verify --print-certs`, so an unsigned or wrongly-signed output
  cannot become a GitHub Release.

The keystore is deleted after the build even if the build failed. The four
secret names above are unchanged.

Nightly (`nightly_build.yml`) installs the same SDK and JDK so `assembleDebug`
can compile C. It does not use the release keystore.

## Building a signed release locally

The build looks for the keystore at `app/release_key.jks` — the same path CI
decodes into — and takes the passwords from the environment.

```sh
cp ~/.android/runningmusic-release.jks app/release_key.jks

SIGNING_STORE_PASSWORD='...' \
SIGNING_KEY_ALIAS='runningmusic-release' \
SIGNING_KEY_PASSWORD='...' \
./gradlew assembleRelease

rm app/release_key.jks
```

`*.jks` is gitignored, so the copy cannot be committed by accident, but removing
it afterwards is still the right habit.

Verify the result:

```sh
apksigner verify --print-certs app/build/outputs/apk/release/RunningMusic_*.apk
```

The SHA-256 it prints must match the one in the table above.

## Building an unsigned release

For checking R8 output or APK size, no keystore needed:

```sh
./gradlew assembleRelease -Prunningmusic.allowUnsignedRelease=true
```

Without that flag, a release build with no keystore fails with an explanation
from the `checkReleaseSigning` task. That task exists because AGP's own error for
this situation — `Keystore file not set for signing config release` — gives no
hint about what is missing or where to put it.

## Debug signing

Separate key, separate purpose: `~/.android/runningmusic-dev.jks`, configured
through `runningmusic.devKeystore*` properties in `~/.gradle/gradle.properties`.
It is shared between this machine and CI so debug builds from either source
install over each other instead of failing with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. It has no release significance.
