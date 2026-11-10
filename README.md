# KAMI NO KURUMA (神の車)

An offline escape room game prototype for the Honda Accord Android Automotive OS emulator, inspired by the mythology of Izumo in Japan's San'in region.

## Current scope

The app displays a launch screen and a simulated Earth Seal puzzle using a single Activity. START GAME opens the puzzle; SIMULATE SUCCESS displays EARTH SEAL UNLOCKED. BACK TO START returns to the launch screen. Starting again resets the simulated puzzle. The current screen survives Activity recreation through saved instance state. Vehicle data integration is not implemented.

The application is intended for parked use only. It is not marked distraction optimized. Verify the Honda emulator's driving restrictions before testing the simulated puzzle.

## Build environment

- Android Studio with Android SDK 37 and the Honda AAOS emulator
- Gradle 9.6.0 and Android Gradle Plugin 9.5.1
- Gradle daemon: JDK 25 (configured in `gradle/gradle-daemon-jvm.properties`)
- Java source and target compatibility: 24
- Minimum Android API: 24; compile and target API: 37

Java language compatibility does not provide the full Java SE 24 library on Android.

## Run

1. Open this repository's root directory in Android Studio and sync Gradle.
2. Select the shared `app` run configuration and the Honda AAOS emulator.
3. Keep the emulator parked and click Run.
4. Tap START GAME and confirm the chapter, seal titles, and instruction appear.
5. Tap SIMULATE SUCCESS and confirm EARTH SEAL UNLOCKED appears and the simulation button is disabled.
6. Tap BACK TO START (or use system Back), then start again and confirm the puzzle is reset.
7. Verify that driving restrictions block the activity, including the puzzle and simulation control.

To build on Windows, set `JAVA_HOME` to your JDK 25 installation and run:

```powershell
.\gradlew.bat :app:assembleDebug
```

To run Android Lint:

```powershell
.\gradlew.bat :app:lintDebug
```

## Project layout

```text
app/src/main/
    java/com/example/escaperoomapp/MainActivity.java
    res/layout/activity_main.xml  # Start screen and shared scrolling container
    res/layout/screen_earth_seal.xml
    res/values/                 # Text, colors, and the default theme
    res/values-night/           # Dark theme
    res/drawable/               # Graphics
    res/mipmap-*/               # Launcher icons
    res/xml/                   # Backup configuration
    AndroidManifest.xml
.run/app.run.xml               # Shared Android Studio run configuration
gradle/                        # Wrapper, dependency versions, and daemon JDK criteria
```

`MainActivity` displays the XML layout. User-facing text belongs in string resources. Introduce packages and tests when real features need them; no placeholder architecture is required. The generated sample tests have been removed; there are currently no automated behavior tests.

## Repository contents

Commit source code, resources, Gradle scripts, both Gradle wrapper scripts, `gradle-wrapper.jar`, and the shared run configuration. Local IDE settings, SDK paths, build outputs, and signing credentials are ignored. The default Android launcher artwork is still a placeholder.

This repository is self-contained for application logic and resources. Initial dependency and SDK downloads may require internet access; the app itself does not use networking.
