# Phase 6 — physical-device validation runbook

The final gate before Phase 6 is declared **FROZEN**. This is a *verification*
run, not a tuning exercise: **do not change any threshold or algorithm parameter
based on its output** unless it reveals an actual correctness failure.

## Prerequisites

- One physical Android device, **API 26+** (`minSdk`), USB-debugging enabled.
- `adb devices` lists it as `device` (not `unauthorized` — accept the RSA prompt
  on the handset if so).

## Run

```bash
export PATH=$PATH:~/Library/Android/sdk/platform-tools
adb devices                 # confirm exactly one physical device
adb logcat -G 16M           # the run emits more than the default buffer holds
adb logcat -c

./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.ikyky.pipeline.Phase6FrozenPipelineTest
```

Then capture the diagnostics:

```bash
adb logcat -d -s PHASE6:I | sed 's/.*PHASE6\s*:\s*//' > phase6_device_run.txt
```

## Pull the representative crops

The test writes the **exact 112x112 recognition tensors** — what MobileFaceNet
actually sees — named `c<cluster>_<appearance>_t<timestampMs>_<5pt|FALLBACK>.png`:

```bash
adb shell 'ls /sdcard/Android/data/com.example.ikyky/files/phase6_crops'
adb pull /sdcard/Android/data/com.example.ikyky/files/phase6_crops ./phase6_crops
```

## What to inspect visually

| check | what a PASS looks like |
|---|---|
| orientation | faces upright; eyes above mouth |
| mirroring | crops are not left-right flipped relative to the source video |
| framing | face fills the frame, roughly eye-line at ~46% height |
| grouping | files sharing a `c<NN>` prefix are the **same person** |
| filename tag | every file ends `_5pt`; any `_FALLBACK` means alignment failed |

## Automated guards (the test fails on these already)

- every recognition crop used the 5-point transform — zero box-crop fallbacks
- must-not-link violations == 0
- all embeddings 192-d and L2-normalised (norm == 1.000)
- whip-pan transitions > 0
- appearances and identity clusters non-empty

## Failure rule

If the run shows failed alignment, wrong orientation, mirrored/inverted crops,
missing frames, decode corruption, a crash/OOM, or materially different tracking
or clustering behaviour: **STOP and report the exact discrepancy. Do not tune
around it.**

If the run is sane: **PHASE 6 = FROZEN.**

## Emulator baseline for comparison

Pixel_10 AVD, API 36 (Android 17 preview), software GPU:

| | S1 | S2 | S3 |
|---|---|---|---|
| sampled frames @8 FPS | 240/240 | 240/240 | 240/240 |
| shot-scan frames | 750 | 750 | 750 |
| whip-pan transitions | 17 | 17 | 17 |
| detected faces | 289 | 258 | 261 |
| multi-face frames | 49 | 22 | 25 |
| raw tracklets | 64 | 61 | 60 |
| appearances (>=2 obs) | 27 | 22 | 24 |
| 5-point alignments | 133/133 | 105/105 | 109/109 |
| identity clusters | 9 | 5 | 5 |
| MNL violations | 0 | 0 | 0 |
| total runtime | 86.6 s | 81.8 s | 81.7 s |

Runtime on real hardware is expected to be **substantially lower** — the
emulator figure is dominated by `MediaMetadataRetriever` decode under a software
GPU (shot scan ~54 s, frame re-decode ~10 s), while MobileFaceNet inference is
only ~0.85–1.1 s and tracking/clustering are single-digit milliseconds.

Detection counts may differ slightly from the emulator: ML Kit ships device
-specific optimisations and the bundled model can differ by Play Services
version. Small differences in face counts, tracklets and appearances are
expected and are **not** a failure. What must hold are the structural
properties in the guards above.
