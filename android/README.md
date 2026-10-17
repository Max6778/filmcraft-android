# FilmCraft Android

This is the native Android application module. The `app/src/main` editor screen is an Android
Activity with a touch-sized preview, separate video/overlay/audio lanes, editing tools, and Android's
Storage Access Framework picker for selecting local media without broad storage permissions. Image
overlays import onto a separate video track and support drag, pinch-scale, and rotate gestures in the
preview; those transforms save back to the native motion effect and can be animated with keyframes.
The Edit panel can add position/scale/rotation keyframes or write a scale-bounce animation.

Selected documents are copied into app-private storage and imported into a FilmCraft Rust `Session`
through the JNI bridge. The bridge exposes FilmCraft’s registered command catalog and accepts validated JSON command calls, so Android features can use the same Rust session operations instead of reimplementing project edits in Kotlin. The first imported clip creates a sequence; later imports are placed at the
end of its active timeline. The session saves a mobile `.fcproj` draft and reopens it on startup.
Photos decode asynchronously at a capped preview size; AndroidX Media3 ExoPlayer renders video to
its hardware-backed surface with a 720p track preference (using the source track when no smaller alternative exists) and an 8 MiB buffer target for low-memory phones. The effects browser reads FilmCraft's video effect catalog and applies selected effects to
the saved sequence clip. Black & White and Invert also have a live preview; other FilmCraft effects
are stored for rendering but do not yet display live in this Android preview. The EffectCraft repository is not present in this checkout, so its separate effect model cannot yet be adapted or packaged. The Android UI also does not expose every FilmCraft engine command yet; the command bridge is the integration point for building out those native controls.

## Build

Install Android Studio with Android SDK 36 and the NDK, Rust 1.95+, the Android Rust targets, and
`cargo-ndk`. Open this `android/` directory in Android Studio or run Gradle from this directory:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
./gradlew :app:assembleDebug
```

The Gradle `preBuild` task cross-compiles `filmcraft-android-native` for ARM64 and x86_64 and places
the resulting JNI libraries under `app/src/main/jniLibs` before packaging the APK.

GitHub Actions builds this debug APK on pushes, pull requests, and manual runs. Download the
`filmcraft-android-debug` artifact from the completed workflow run. The workflow is defined in
`../.github/workflows/android-apk.yml`.

## Project structure

- `app/src/main/AndroidManifest.xml`: launcher Activity and app metadata.
- `app/src/main/java/com/filmcraft/mobile/MainActivity.kt`: native editor, media preview, local draft state, and document picker.
- `app/src/main/res/values/styles.xml`: Android system bars and app theme.
- `../apps/filmcraft-android-native`: JNI bridge to the Rust editing session.
