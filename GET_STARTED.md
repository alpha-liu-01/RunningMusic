<h1>🚀 Getting Started</h1>

RunningMusic is a fork of [Chocola](https://github.com/sosauce/Chocola). Most of
the codebase is Chocola's, so the guidelines below are inherited from upstream
and still apply.

### Code Guidelines

- Clutter-free experience : This is self explanatory, avoid adding elements if un-needed, two example could be :
    - flashing music icon : The music icon in the searchbar flashes red, indicating that it is clickable / has an action related to it
    - the restart button, the seek to previous automatically becomes one 10 seconds in the song instead being a whole new button which brings us to the next guideline :
 - Make things clear : If you are adding a feature, make sure it is clear what it does, clear text/description, accurate icon
 - Landscape : The app <b>MUST</b> be fully compatible with landscape mode, if a portrait design doesn't adapt well to landscape, you will have to make a separate landscape one
 - Creativity : This isn't mandatory, but if you are designing a screen or something else, be creative! Try things no other apps has before, be unique! Remember, failure is just a step closer to perfection!

One guideline specific to this fork: we rebase on upstream Chocola regularly.
Prefer adding new code in its own package over editing existing files, and keep
edits to high-churn upstream files (`MusicViewModel`, `PlaybackService`,
`CuteSearchbar.kt`, `Enums.kt`) as small as you can. Every line changed in those
is a future merge conflict.

### Prerequisites

- JDK 21. Gradle's daemon requirement is pinned in `gradle/gradle-daemon-jvm.properties`;
  the toolchain is provisioned automatically if you don't have it. The app itself
  compiles to JVM 17 bytecode.
- The Android SDK, including NDK and CMake. On Linux, `scripts/setup-android-sdk.sh`
  installs everything at the pinned versions.
- Git.
- Android Studio is optional. The command line is enough.

### Installation

1. **Clone the repository:**
   ```sh
   git clone https://github.com/alpha-liu-01/RunningMusic.git
   cd RunningMusic
   ```

2. **Install the Android SDK:**
   ```sh
   scripts/setup-android-sdk.sh
   ```
   Add `--with-emulator` if you want the emulator and system images too. The
   script prints the `ANDROID_HOME` and `PATH` exports to add to your shell
   profile; make sure the SDK's `platform-tools` comes before any distro-packaged
   `adb`, which is usually too old to talk to a current device.

3. **Build:**
   ```sh
   ./gradlew assembleDebug
   ```
   The APK lands in `app/build/outputs/apk/debug/`.

4. **Run it:**
   ```sh
   ./gradlew installDebug
   ```
   Debug builds are signed with a shared dev key when one is configured, so
   builds from this machine and from CI install over each other instead of
   failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

   Note that the debug application id is `lol.alphaliu01.runningmusic.debug`. If
   you previously installed a build from before the rebrand, uninstall it first:
   ```sh
   adb uninstall com.sosauce.cutemusic.debug
   ```

Release builds need a signing keystore. The local signing notes are not
published with the repository.

### Contributing

1. **Fork the repository:**
   - Click the `Fork` button on the top right of the repository page.

2. **Create a new branch:**
   ```sh
   git checkout -b feature/YourFeatureName
   ```

3. **Make your changes:**
   - Implement your feature or bug fix.
   - Ensure your code follows the project's coding standards.
4. **Commit your changes:**
   ```sh
   git add .
   git commit -m "Add feature: YourFeatureName"
   ```
5. **Push to your fork:**
   ```sh
   git push origin feature/YourFeatureName
   ```
6. **Create a Pull Request:**
   - Go to the original repository.
   - Click on `Pull Requests` and then `New Pull Request`.
   - Select your branch and submit the pull request.

If your change fixes something that is also broken in Chocola, please consider
sending it upstream as well — it helps everyone, and it means one less patch for
this fork to carry.

Thank you to anyone taking their time to contribute and improve the app :heart:!!!
