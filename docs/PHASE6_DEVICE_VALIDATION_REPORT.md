# Phase 6 — device validation report

**Status: NOT FROZEN. A physical-device run crashed; the failure rule applies —
reporting, not tuning around it.**

Two runs were performed:

1. **vivo I2012 (physical, Android 14)** — the run that matters. **CRASHED**
   mid-execution before completing sample_1.
2. **Android emulator (`sdk_gphone16k_arm64`, API 37)** — completed cleanly;
   included here only as a structural sanity check and for visual crop review.

---

## 1. vivo I2012 — physical device — CRASHED

### Device

| field | value |
|---|---|
| manufacturer / model | vivo / I2012 (product `2012i`, device `2012`) |
| Android version | **14** (API 34) |
| ABIs | arm64-v8a, armeabi-v7a, armeabi |
| dalvik heap growth limit | **256 MB** |
| dalvik heap size (max) | 512 MB |
| connection | wireless ADB |

### Result

```
testsuite time="251.823" failures="1" errors="0"
  <failure></failure>                        (empty — no assertion failure)
<system-err>Test run failed to complete.
  Instrumentation run failed due to Process crashed.</system-err>
```

- The app process **died** ~252 s in — no assertion, no JUnit failure message.
- **Zero representative crops were written**, and no `PHASE6-DIAG` block was
  emitted for any sample. Crops and the diag block are written *after*
  clustering, so the process died **during sample_1**, before its clustering
  stage — i.e. somewhere in the shot scan, detection pass, or gate embedding.
- ~252 s is consistent with roughly one-and-a-bit samples' worth of the
  every-frame shot scan (the emulator shot scan alone is ~56 s/sample, and the
  physical device is faster per frame but still decode-bound).
- `adb logcat` is access-restricted on this vivo build for third-party tags, and
  `/data/tombstones` is not readable without root, so **no tombstone or native
  crash signature was recoverable** while the device was connected. The device
  then dropped off wireless ADB and is currently unreachable.

### What the crash most likely is (not yet confirmed)

The empty `<failure/>` plus `Process crashed` (not `Test failed`) means a
**process-level abort**, not a Kotlin exception the test could catch. Candidates,
in rough order of likelihood:

1. **Native OOM / allocation failure in the decode path.** The shot scan opens a
   *second* `MediaMetadataRetriever` pass over all ~750 frames at 320 px, decoded
   through the platform codec. On a 256 MB-heap-limit device with vendor codec
   quirks, a native `Bitmap` allocation or codec buffer can abort the process
   without a Java `OutOfMemoryError` ever being thrown. The emulator has a
   576 MB *large* heap and showed native PSS climbing 77 → 130 → 173 MB across
   the three samples — i.e. native memory is **not** being fully released
   between samples. On the vivo that trend could cross a hard limit mid-run.
2. **A vendor codec / `MediaMetadataRetriever` fault** on this specific device
   for this clip — `getScaledFrameAtTime` / `getFrameAtTime` returning through a
   native crash rather than null. vivo ships heavily customised media HALs.
3. **ML Kit face-detector native abort** under memory pressure.

All three are **environmental / resource** issues in the decode-and-scan
plumbing, not the frozen recognition algorithm. But this is **not confirmed**,
and per the failure rule I am stopping here rather than guessing further or
changing anything.

### Required next step

Re-run on a physical device with diagnostics that survive a process crash:

- write the `PHASE6-DIAG` block and a heartbeat line to a **file** on external
  storage after *every stage of every sample* (not only at the end), so a crash
  leaves a trail showing exactly which stage and which sample died;
- capture `logcat -b crash` / `dumpsys dropbox` immediately, or run on a device
  where `/data/tombstones` is readable (rooted or `userdebug` build);
- if it reproduces in the shot-scan pass, that confirms candidate (1)/(2) and
  the fix is a **decode-plumbing** change (explicit retriever release between
  passes, forced GC / `Bitmap.recycle` discipline, smaller scan decode, or
  chunked scanning) — **not** an algorithm or threshold change.

This crash **blocks the Phase 6 freeze**.

---

## 2. Emulator run — structural sanity + visual inspection

**Not a substitute for physical-device validation.** Recorded here because the
harness additions (device info, memory, crop export) were being smoke-tested when
the physical device briefly appeared, and the crops are useful for the visual
check the brief calls for.

### Environment

| field | value |
|---|---|
| model | Google `sdk_gphone16k_arm64` (Pixel_10 AVD) |
| Android version | 17 (API 37) — **note: the AVD image auto-updated** from the API level used in the Phase 6 implementation runs |
| ABI | arm64-v8a |
| heap limit / large | 192 MB / 576 MB |
| GPU | software (swiftshader) |

### Per-sample diagnostics

| metric | S1 | S2 | S3 |
|---|---|---|---|
| sampled frames @8 FPS | 240 / 240 | 240 / 240 | 240 / 240 |
| shot-scan frames | 750 | 750 | 750 |
| shot boundary spikes | 34 | 34 | 34 |
| **whip-pan transitions (barriers)** | **17** | **17** | **17** |
| detected faces | 287 | 292 | 259 |
| frames with faces | 231 | 231 | 233 |
| multi-face frames | 44 | 42 | 26 |
| max faces in a frame | 5 | 5 | 2 |
| raw tracklets | 69 | 70 | 56 |
| appearances (≥2 obs) | 31 | 30 | 26 |
| tracker gate embeddings | 287 | 292 | 259 |
| recognition inferences | 141 | 138 | 123 |
| **5-point alignments** | **136** | **135** | **118** |
| box-crop fallbacks | 5 | 3 | 5 |
| appearance embeddings | 31 | 30 | 26 |
| embedding dim | 192 | 192 | 192 |
| embedding L2 norm (min/max) | 1.000000 / 1.000000 | 1.000000 / 1.000000 | 1.000000 / 1.000000 |
| must-not-link edges | 15 | 8 | 3 |
| **identity clusters** | **9** | **9** | **8** |
| merges blocked by MNL | 3 | 0 | 0 |
| **MNL violations** | **0** | **0** | **0** |
| Java heap in use | 4 MB | 4 MB | 4 MB |
| total PSS at sample end | 195 MB | 248 MB | 291 MB |
| — dalvik / native PSS | 3 / 77 | 3 / 130 | 3 / 173 |
| shot scan | 55.9 s | 57.0 s | 56.2 s |
| gate embedding | 2.4 s | 2.6 s | 2.3 s |
| recognition inference only | 1.10 s | 1.11 s | 0.99 s |
| frame re-decode | 11.7 s | 11.2 s | 10.1 s |
| tracking | 9 ms | 6 ms | 3 ms |
| clustering | 8 ms | 5 ms | 3 ms |
| **total** | **87.0 s** | **88.6 s** | **86.0 s** |

### Comparison against the Phase-6-implementation emulator run (earlier session)

| | earlier (API level of impl run) | now (API 37) |
|---|---|---|
| S1 clusters | 9 | 9 |
| S2 clusters | 5 | 9 |
| S3 clusters | 5 | 8 |
| S1 5-point / fallback | 133 / 0 | 136 / 5 |
| S2 5-point / fallback | 105 / 0 | 135 / 3 |
| S3 5-point / fallback | 109 / 0 | 118 / 5 |
| detected faces S1/S2/S3 | 289 / 258 / 261 | 287 / 292 / 259 |
| transitions | 17 / 17 / 17 | 17 / 17 / 17 |
| MNL violations | 0 / 0 / 0 | 0 / 0 / 0 |

The AVD system image updated between sessions, which changed the **bundled ML
Kit face model**: face counts drifted, a handful of detections now lack the full
5-landmark set (hence the new fallbacks), and S2/S3 gained over-split clusters.
This is the ML-Kit-version nondeterminism the review explicitly said **not** to
chase. The invariants held: **17 transitions, 0 MNL violations, all embeddings
192-d and unit-norm, memory bounded.**

### Visual crop inspection (emulator)

Montages of every exported crop, grouped by cluster, were reviewed for all three
samples.

- **Orientation: PASS.** Every crop is upright; eyes above mouth; face centred on
  the ArcFace template with the eye-line at ~40% height.
- **Mirroring / inversion: PASS.** No crop is left-right flipped or rotated
  relative to the source. The measured-x landmark ordering is producing correct,
  non-mirrored alignments.
- **5-point alignment quality: PASS.** The `_5pt` crops are tightly and
  consistently framed. The handful of `_FALLBACK` crops are blurred profile /
  back-of-head frames where ML Kit returned <5 landmarks — correctly routed to
  the box-crop fallback rather than a bad transform.
- **Grouping:** each sample recovers **5 clean real-person clusters** (plus, in
  S3, a brief 6th cast member correctly isolated). The extra clusters are
  junk/partial-face groups (averted faces, chin/shoulder crops, the blur
  fallback) and — in S1 — one genuinely mixed person_A/person_E cluster. This is
  the **accepted, documented over-split behaviour**, not a new defect.

### Memory note

Java heap stays flat at 4 MB (the pipeline holds only vectors + metadata, one
bitmap at a time). **Native PSS grows monotonically across samples** (77 → 130 →
173 MB) and is not released between samples in the same process. The emulator's
576 MB large-heap absorbs it; a device with a tighter native budget may not.
This is the strongest lead for the vivo crash and is worth a targeted look **if
the review agrees** — it is a resource-plumbing concern, not an algorithm one.

---

## 3. Correctness discrepancies

| area | physical device (vivo) | assessment |
|---|---|---|
| **process stability** | **CRASHED before finishing sample_1** | **Correctness failure per the Phase 6 failure rule ("crashes/OOM"). Blocks freeze.** |
| alignment / orientation / mirroring | not reached | unknown on physical hardware; PASS on emulator |
| frame sampling | not reached | unknown on physical hardware |
| tracking / clustering behaviour | not reached | unknown on physical hardware |

No evidence of an *algorithmic* discrepancy — the crash is upstream of
recognition, in the decode/scan plumbing. But the required physical-device
validation **did not complete**, so the frozen behaviour is **unverified on real
hardware**.

---

## 4. Is Phase 6 frozen?

**No.**

- The one required physical-device run **crashed** and produced no diagnostics.
- Per the stated failure rule, a crash is a STOP-and-report condition, and I am
  not tuning or working around it.
- The emulator run is structurally sane and the crops inspect cleanly, but the
  emulator is explicitly not the validation gate, and its ML Kit version drifted
  besides.

**Recommended path (subject to review):**

1. Add crash-surviving per-stage file logging to `Phase6FrozenPipelineTest` (a
   pure diagnostic change — no pipeline code touched).
2. Re-run on a physical device (ideally one where tombstones are readable).
3. If it dies in the shot-scan / decode pass as suspected, the fix is confined
   to **decode plumbing** (retriever lifecycle, bitmap recycling, GC between the
   two decode passes, or a lighter scan) — **not** the frozen algorithm,
   threshold, tracker, or model.

Until a physical-device run completes cleanly, Phase 6 stays **NOT FROZEN**, and
no Phase 7 / collage work begins.
