# Kotlin ↔ Gemma evaluation results — 27 September 2026

The available automated checks reveal six handback phrase failures. This is a partial evaluation: no live Gemma responses or multi-turn device sessions were executed, so the 40-case suite cannot be declared passed.

## Executed checks

| Check | Result |
|---|---|
| New eval phrase tests | 34 run: 28 passed, 6 failed |
| Existing unit tests | 96 run: 66 passed, 30 failed |
| Combined unit run | 130 run: 94 passed, 36 failed |
| Android instrumented-test APK | Successfully compiled; not installed or run |
| Live Gemma tone, refusals, context, conversation depth | Not tested |

The new tests call the actual `isLikelyDigression` and `requestsFindWineSwitch` functions. They cover 33 explicit-input rows plus a corrected four-word boundary input. They do not execute the whole responder or reproduce the stated conversation histories. Passing helper checks are marked PARTIAL in the original suite, not full passes.

The 30 existing failures all report `Method elapsedRealtime in android.os.SystemClock not mocked`. The desktop test setup lacks an implementation of Android's clock. These are test-environment failures, not evidence that those 30 product behaviors are broken. They also occurred in the baseline run before the new tests were compiled.

## Confirmed phrase failures

| Case | Input | Actual |
|---|---|---|
| B2 | can you help me find one? | No handback |
| B3 | I want to choose a wine now | No handback |
| B4 | just pick one for me | No handback |
| B5 | show me some wine options | No handback |
| B6 | can you recommend an actual bottle? | No handback |
| B8 | ok let's actually find one | No handback |

All six are expected to hand control to Kotlin. The current explicit phrase list misses them; in Curious mode they proceed towards Gemma instead. B8 fails at phrase recognition before conversation depth is relevant.

## Additional findings from source inspection, not live tests

- **B19 cannot work through the current Chat UI:** the input is disabled during a reply, and the send handler rejects submissions while replying. The responder also serializes requests; no interrupt-and-handback path was demonstrated.
- **A handover helper pass does not guarantee Gemma wakes:** selection answers are matched before the digression helper is consulted. For example, A7 can be consumed as a red-wine preference at the type question. A13 begins with “Any,” which the app treats as “no preference,” bypassing the model during selection.
- **Digressions automatically return to Kotlin:** `unmatchedFindWineAnswer` creates a fresh model conversation, closes it after one answer, and appends the pending Kotlin question. It does not remain with Gemma until the user explicitly asks to select a wine. Earlier digression history is not supplied to that fresh conversation.
- **Opening screen differs from the selection flow:** while mode is Undecided, the app checks mode-choice phrases rather than the digression trigger. The suite should specify whether “1st/2nd/3rd” means selection question, overall chat turn, or digression number.
- **A7 contains six words**, despite its four-word label. The original input was retained, and “I love red wine” was added as the actual four-word helper boundary check.
- **A11, A17 and A20 need concrete fixtures** for repeatable execution; their inputs currently describe a scenario rather than give an exact message. A11 also requires prior-turn history.
- **B16 and B17 remain product decisions**, as requested by the suite. Neither phrase matches the current handback list by inspection; no live output was collected.

Source evidence: `app/src/main/java/com/sheldondesousa/uncork/model/ChatFlow.kt`, `GemmaConversationResponder.kt` in the same directory, and `app/src/main/java/com/sheldondesousa/uncork/ui/conversation/ConversationScreen.kt`.

## Environment and reproducibility

No AVD (Android virtual device) is configured: the emulator's device listing returned no entries and the local AVD directory is empty. Project safety instructions therefore require compiling instrumented tests without running them. No physical-device tests, installations, data clearing, or model deletion were performed. The initial read-only device inventory attempt was blocked by the sandbox starting ADB; it was not needed for the emulator-free fallback.

The default shell could not find Java. Android Studio's bundled runtime successfully launched Gradle, using the project's configured toolchain and cached dependencies.

Run from the project root:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest --offline --continue
```

The nonzero result is expected while the six new assertions and 30 existing clock-dependent tests fail. Tests were not weakened or skipped to make the run green. No production code was changed.

Artifacts:

- Repeatable eval checks: `app/src/test/java/com/sheldondesousa/uncork/model/KotlinGemmaHandoverEvalTest.kt`
- HTML test results: `app/build/reports/tests/testDebugUnitTest/index.html`
- Machine-readable results: `app/build/test-results/testDebugUnitTest/`
- Compiled device tests: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Remaining work for full acceptance: configure an emulator capable of running the model, provide concrete inputs/history for the descriptive cases, exercise the actual responder and UI, and review the recorded model replies for tone, scope and context. The existing Android clock test setup also needs repair before the broader unit suite can provide a clean signal.

## Follow-up — emulator configured, 27 September 2026

The user created `Pixel_10_Pro_-_UnCork` with the API 36.1 Google Play ARM64 image, 8 GB RAM and 16 GB storage. Started it as `emulator-5554`, confirmed boot completion, built the current APKs and installed both using explicit emulator-only `adb -s emulator-5554 install -r` commands.

Executed `GemmaConversationResponderTest` using the Android instrumentation runner on that emulator: **25 tests passed in 0.805 seconds**. Raw output is saved in `Emulator-GemmaResponder-2026-09-27.txt`. These existing tests check parsing, routing helpers and deterministic selection behavior; they do not run a real Gemma model or complete the 40-case live eval suite. Earlier phrase failures remain unresolved.

Opened UnCork on the emulator. Its private files directory was not yet present on the initial check after launch; no model was provisioned by this run. Live evaluation remains pending model setup in the emulator. The physical device was not modified.

## Live Gemma readiness attempt — 27 September 2026

Confirmed emulator model size 2,583,085,056 bytes and readiness marker matching the app's expected SHA-256. Added `GemmaLiveHandoverSmokeTest` to exercise an actual selection digression, a Curious-mode answer, canonical handback and the next Kotlin question. Future runs require instrumentation argument `-e liveGemma true`; the initial run preceded this opt-in guard. The guarded test package compiled successfully.

The initial Kotlin type question completed. The next message, A1's “Does tannin cause headaches?”, did not return a completed response before the attempt was stopped at process elapsed time **3 minutes 52 seconds**. Consequently the later Curious/handback assertions were not reached. This is an **incomplete runtime attempt**, not a model-response quality failure or successful end-to-end handover.

Runtime logs identify the selected GPU adapter as `Goldfish GFXStream (llvmpipe (LLVM 21.1.4, 128 bits))`, `arch=software`, `adapterType=CPU / Software`. This suggests emulator graphics execution is a performance limitation; the logs do not conclusively distinguish slow inference from a stall. No completed live reply was obtained. The test process was explicitly stopped on emulator-5554 and UnCork reopened; app data and downloaded model were preserved. Any instrumentation process-crash result at the end reflects this deliberate stop.

Evidence: `Emulator-Live-Smoke-2026-09-27.txt`, `Emulator-Live-Smoke-Transcript-2026-09-27.txt`, and `Emulator-Live-Smoke-Runtime-2026-09-27.txt` in this folder. No physical-device action was performed. Full live evaluation remains blocked on a usable model runtime; a CPU-backend diagnostic would be a next investigation, not a result established here.

## CPU workaround investigation — 27 September 2026

Tested routing known emulator hardware (`ro.hardware=ranchu`) directly to LiteRT-LM `Backend.CPU()` instead of GPU. Build and installation succeeded, but the live test process crashed with **SIGILL / ILL_ILLOPC** inside `liblitertlm_jni.so` during model startup. This is a native illegal-instruction failure; the exact unsupported instruction/root cause has not been established. Both processing paths are therefore unvalidated on this AVD: GPU did not finish within the earlier observation window, and CPU crashed.

The experimental production change was removed and the original app rebuilt and restored on the emulator with `install -r`. No production code change remains from this investigation. Model data was preserved. The test suite following the live test did not execute because the process crashed. Evidence: `Emulator-CPU-Validation-2026-09-27.txt` and `Emulator-CPU-Crash-2026-09-27.txt`.

This is not resolved by increasing RAM or downloading the model again based on current evidence. A verified emulator fix requires further native-runtime compatibility work (identifying the failing instruction and testing a compatible LiteRT-LM build/system image). Real-device manual Chat verification is an alternative; no physical-device testing was performed or authorized by this investigation.
