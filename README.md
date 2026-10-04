# Gemini Legacy

A small Gemini chat client for old Android phones. It was built for a Galaxy S4 on Android 4.4.2 and installs on **Android 4.0 (API 14) and newer**.

> Unofficial. Not affiliated with, endorsed by or sponsored by Google. "Gemini" and the Gemini logo are trademarks of Google LLC.

## Features

- Chat with Gemini using **your own API key** (free keys come from [Google AI Studio](https://aistudio.google.com)). The key is typed in the app, stored only on the phone and sent only to Google.
- Streaming replies with an animated "Thinking" indicator and an optional thought summary
- Model dropdown (Pro / Flash / Flash Lite) with an extended-thinking switch; model ids are editable in Settings
- Markdown formatting, real tables, scrollable code blocks with a Copy button
- Send photos (from the gallery or the camera)
- Saved chats with search, rename and delete; swipe in from the left edge or tap the hamburger button
- Stop, retry, regenerate, copy, share and edit-and-resend
- Custom instructions sent with every request
- In-app updater that reads the latest release of this repo

## Why it works on old phones

Android 4.4 and older cannot talk to modern HTTPS servers on their own: TLS 1.2 is off or missing, and there are no AES-GCM cipher suites, which servers such as GitHub require. On Android 4.0 to 4.4 the app uses the bundled [Conscrypt](https://github.com/google/conscrypt) library plus a few extra root certificates (see `BundledRoots.java`). Newer Android versions use the system TLS stack.

## Install

Download the latest APK from [Releases](../../releases), allow "Unknown sources" on the phone and open the file. After that, **Settings > Check for updates** installs newer releases.

## Build

Requirements: JDK 11, Android SDK platform 28 with build-tools 28.0.3, Gradle 6.7.1 (Android Gradle Plugin 4.2.2).

```
echo sdk.dir=/path/to/android/sdk > local.properties
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. An update only installs over an existing copy if it is signed with the same key, so keep your signing key safe.

## Limits

- The free Gemini API tier does not allow image generation or web search, so those are not included.
- Android 4.4 may not trust some websites' newer HTTPS certificates, which can stop linked images from loading.
