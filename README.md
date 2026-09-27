# Lockr

**Minimal. Private. Yours.**

Lockr is an offline Android app-locker prototype built with Kotlin, Jetpack Compose, and Material 3. It is designed around a small, local app selector and an honest security model. The current implementation includes persistent app selection, best-effort foreground package monitoring, a PIN/biometric authentication flow, and in-memory package sessions. It does **not provide guaranteed app interception**.

## Overview

Lockr stores selected launchable applications by package name in Preferences DataStore. It has no accounts, network client, analytics, ads, or `INTERNET` permission. The interface follows system light/dark mode and uses dynamic colors on Android 12 and newer.

## Screenshots

Screenshots will be added after a device or emulator run is available.

## Features

- Installed launchable app discovery, search, icons, and persistent selection
- Select all / clear selection controls
- Compose and Material 3 foundation with dynamic system colors
- Replaceable `AppMonitor` interface with UsageStats-based foreground observation
- User-started monitoring service, permission-loss handling, and foreground transition debug logs
- In-memory package sessions and a testable lock decision engine
- Android Keystore-encrypted PBKDF2 PIN verifier, local failure throttling, and AndroidX BiometricPrompt authentication
- Notification handoff to authentication after Lockr observes a protected app transition

Device verification remains outstanding. The notification handoff can be delayed or unavailable, and the target application may already be visible before the user authenticates.

## Architecture

- `data`: installed app discovery and Preferences DataStore
- `monitoring`: `AppMonitor` interface, UsageStats implementation, and user-started foreground service
- `security`: lock decision engine, in-memory sessions, PBKDF2 PIN verifier encrypted with an AES-GCM Android Keystore key, and prompt dispatch
- `ui.auth`: non-exported authentication Activity and Compose setup/unlock/change-PIN UI
- `ui`: Compose screens, ViewModel, navigation, and theme
- `MainActivity`: app entry point and dependency composition

## Android Monitoring Limitation

`UsageStatsManager` reports observed usage events. It does not provide a guaranteed pre-launch callback. Lockr may notice a protected app only after its UI has appeared; event delivery and background execution can also be delayed or interrupted. When authentication is required, Lockr posts a private notification that the user taps to open the authentication Activity. Android restricts activities launched from the background, so this notification handoff cannot guarantee that the authentication UI appears over the target app.

Lockr is an **observation-based app protection prototype**, not equivalent to Samsung's system-level app protection or operating-system app isolation. It cannot guarantee app interception or prevent the target app from being seen or used before authentication. Multi-window can have more than one resumed activity, and vendor background policies can interrupt service behavior. Samsung One UI behavior requires device testing.

An `AccessibilityService` is not enabled as a workaround. Although its event callbacks can be more immediate, Android describes accessibility services as tools for assisting users with disabilities; app-launch monitoring is not Lockr's accessibility function. Requesting that access would also expose broader UI state than this monitor needs.

## How Lockr works

A user grants Usage Access, grants notification permission on Android 13 or later, and starts monitoring. A foreground service observes user-facing package transitions every two seconds while the screen is interactive and pauses queries while it is off. Protected transitions are evaluated by `LockDecisionEngine`. When authentication is required, Lockr posts a generic private notification; tapping it opens the non-exported Authentication Activity. A valid package session is held only in memory. Sessions are cleared on process death/reboot, and can also be cleared when the configured timeout expires, the foreground package changes under immediate-lock mode, or screen-off locking is enabled.

The Authentication Activity is not externally launchable. It accepts a protected package only through an explicit internal intent and checks that the package remains selected. Biometric verification uses AndroidX `BiometricPrompt` with strong biometrics. PINs are 4 or 6 digits, processed with PBKDF2-HMAC-SHA256, then the verifier record is encrypted using AES-GCM with an Android Keystore key. PIN failure counters and temporary lockout deadlines are stored locally; PIN contents are never persisted or logged.

## Permissions

The app declares no network permission. Monitoring declares `PACKAGE_USAGE_STATS`, which signals the intended use; users must enable Usage Access in Android Settings and can revoke it. The user-started foreground service declares `FOREGROUND_SERVICE` and Android 14+'s `FOREGROUND_SERVICE_SPECIAL_USE`, with its monitoring use case in the manifest. `POST_NOTIFICATIONS` is required on Android 13+ so Lockr can deliver a user-tapped authentication request when Android blocks background Activity launches. Android requires an ongoing notification. Special-use service declarations are subject to platform and distribution review. Lockr does not request Accessibility or notification-listener access.

## Security model

Lockr is intended to reduce casual access to selected apps when someone temporarily has an unlocked phone, subject to the monitoring limitation above. Android's app sandbox and OS remain the actual security boundary. The Authentication Activity uses `FLAG_SECURE`. AndroidX BiometricPrompt returns only an authentication result; Lockr never receives biometric data.

Lockr cannot defend against rooted-device attackers, a compromised Android OS, sufficiently privileged ADB/device-owner attacks, physical forensic extraction, stronger malicious accessibility services, or system-level bypasses. It is not equivalent to operating-system application isolation.

## Privacy

Selection data is stored locally. There is no telemetry or collection of notification, biometric, or PIN contents. Foreground package observation remains on-device. Notification access is not requested. The app has no `INTERNET` permission.

## Known Android limitations

- Usage Access reports recorded usage events, not a pre-launch interception callback.
- Two-second event polling reduces average observation delay but does not make detection immediate or reliable enough to guarantee a lock.
- Background execution, event delivery, process survival, and battery behavior vary by Android version and manufacturer.
- Multi-window and picture-in-picture may make “the foreground app” ambiguous; activity event order is not a universal top-window API.
- A user can revoke special access; monitoring then stops and reports unavailable.
- System UI transitions, recents, reboot, process death, and rapid app switching need additional recovery handling.
- Monitoring must be started again after reboot; no boot receiver is installed.
- The target app may render before a notification or authentication screen appears. No unsupported termination or overlay behavior is used.
- Sessions are in-memory and are lost if Android kills Lockr; after reboot, the user must start monitoring again.

## Building from source

Open this directory in Android Studio with Android SDK Platform 36 installed. The project uses Gradle Kotlin DSL and requires JDK 17. Build with `gradlew.bat :app:assembleDebug` (Windows) or `./gradlew :app:assembleDebug` (macOS/Linux) after adding the standard Gradle wrapper, or use Android Studio's Gradle sync/build actions.

## Contributing

Keep the app offline, avoid unnecessary permissions and dependencies, and document limitations accurately. Add automated coverage for policy and session changes; verify authentication and monitoring behavior on supported Android versions before making stronger protection claims. Do not add an accessibility service or continuous high-frequency polling without clear justification and user disclosure.

## License

No license has been selected yet. Until one is added, standard copyright applies; please do not assume the source is licensed for redistribution.
