# Device safety

- Never run `connectedAndroidTest`, `connectedDebugAndroidTest`, `adb uninstall`, `adb shell pm clear`, or another command that can reinstall, uninstall, or clear this app on the user's physical device as part of testing.
- Run instrumented tests on an emulator or Gradle managed virtual device. If neither is available, compile the instrumented-test APK without running it.
- Use `adb install -r` for manual verification on the user's physical device because it preserves existing app data. Do not assume an Android test task will preserve that data.
- If the user explicitly asks to run instrumented tests on their physical device, explain beforehand that the test runner may erase the downloaded Gemma model and other private app data, then obtain explicit approval.
