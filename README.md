# iykyk — on-device unique-people collage

Processes a portrait video entirely on-device to find every **unique person**,
count their **appearances**, pick one **representative shot** per person, and
compose a single **collage** containing each person exactly once, then save it to
the gallery and share it.

- Kotlin, Jetpack Compose, `minSdk 26`
- Clean Architecture + MVVM (unidirectional Compose state)
- Everything on-device; processing never touches the main thread

## Status

**Phases 1 – 4.5 complete.** Architecture + on-device ML stack (Phase 1); video →
frames → multi-face detection → tracklets → appearance candidates (Phase 2,
hardened in Phase 2.5); appearance → recognition crop → alignment → 112×112 →
MobileFaceNet → 192-d aggregated embedding (Phase 3); embedding-assisted
intra-appearance split → unsupervised threshold calibration → observation-level
must-not-link → agglomerative identity clustering → `Person[]` (Phase 4);
dense change-point split refinement for suspicious/long tracklets + whip-pan
boundary detection + Sample 1/2/3 validation (Phase 4.5); a **diagnostic-only**
identity threshold-sweep experiment (Phase 4.6) which found that the embedding
similarity space does **not** support a stable identity threshold on the
emulator — provably-different faces sit at the same cosine as everything else.
Production is unchanged pending an embedding-pipeline investigation + a
physical-device pass (see Phase 4.6). Representative-frame selection, collage
generation and share are the remaining phases.

## Architecture

```
app/src/main/java/com/example/ikyky/
├── core/                     reusable infrastructure
│   ├── common/               result / error / constants / extensions
│   ├── dispatcher/           DispatcherProvider (keeps work off main thread)
│   ├── model/                framework-free domain types
│   │                         (BoundingBox, DetectedFace, FaceEmbedding, VideoFrame…)
│   ├── ml/
│   │   ├── detector/         FaceDetector  ← MlKitFaceDetector
│   │   ├── embedding/        FaceEmbedder  ← LiteRtFaceEmbedder (MobileFaceNet)
│   │   ├── model/            ModelSpec, EmbeddingModelLoader (loads once, reused)
│   │   ├── preprocessing/    FaceAligner, FacePreprocessor
│   │   ├── tracking/         Tracklet, FaceTracker (short-term temporal only)
│   │   ├── clustering/       IdentityClusterer, ClusteringResult
│   │   └── MlSmokeTest.kt    infra check used by the instrumented test
│   ├── media/                VideoFrameExtractor, VideoMetadataReader
│   ├── image/                ImageOps
│   ├── storage/              CollageStorage (MediaStore), ShareManager (ACTION_SEND)
│   ├── navigation/           AppDestinations, AppNavGraph
│   ├── ui/                   shared components / state envelope
│   └── di/                   AppContainer + AppViewModelFactory (manual DI)
└── features/
    ├── video_selection/      pick + validate the video
    ├── processing/           pipeline orchestration, progress, cancellation
    ├── people/               FaceObservation → Appearance → Person, use cases
    ├── collage/              data-driven LayoutEngine / LayoutTemplate / renderer
    └── result/               show collage, save, share
```

Each feature has `data / domain / presentation` layers. Dependency direction:
`presentation → domain ← data`. Domain models hold no `Context` / `Uri` /
`Bitmap` / Compose types except where a bitmap is genuinely the payload
(`VideoFrame`, `PreprocessedFace`, collage output).

### Dependency injection

A hand-rolled composition root (`core/di/AppContainer`) plus a
`ViewModelProvider.Factory`, provided to Compose via a `CompositionLocal`.
Hilt was intentionally **not** used: its Gradle plugin is not yet stable on
AGP 9 (`dagger/dagger#5083`, `#5099`). For a project this size a single lazily
initialised container is simpler and has zero annotation processing. The wiring
lives in one readable file and swapping the Phase-1 stubs for real
implementations in Phase 2 is a one-line change per binding.

### Collage layout engine

Data-driven, **not** count-branched. `LayoutTemplate` is a list of
`LayoutSlot`s in normalized `[0,1]` coordinates; each slot carries position,
size, aspect preference, z-index, rotation, overlap, corner radius, crop mode,
padding, priority and role. `LayoutEngine.buildTemplate(LayoutConfiguration)`
returns a template with `>= personCount` slots. Phase 1 ships one engine,
`GridLayoutEngine` (a count-adaptive uniform grid, rows/cols derived
arithmetically). `GRID / ASYMMETRIC / MASONRY / HERO / EDITORIAL / OVERLAP /
SCRAPBOOK / PHOTO_DUMP` are enumerated in `LayoutStyle`; new styles are added as
new engines without touching callers.

## On-device ML

### Face detection

Google **ML Kit Face Detection** (`com.google.mlkit:face-detection`, bundled
model — no Play Services model download). Configured `PERFORMANCE_MODE_ACCURATE`,
`LANDMARK_MODE_ALL`, `CLASSIFICATION_MODE_ALL`, tracking enabled. Wrapped by
`FaceDetector` / `MlKitFaceDetector`; the rest of the app sees only
`DetectedFace` (bounding box, landmarks, head Euler angles, eye-open & smiling
probabilities, ML Kit tracking id). ML Kit's tracking id is treated as
short-term temporal continuity only — never as a global identity.

### Face Embedding Model

| Field | Value |
|---|---|
| **Model** | MobileFaceNet (InsightFace / ArcFace-style training) |
| **Version** | `MobileFaceNet_9925_9680` lineage (TF → TFLite, TOCO) |
| **File** | `app/src/main/assets/models/mobile_face_net.tflite` (5.23 MB) |
| **SHA-256** | `be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854` |
| **Input** | `1 × 112 × 112 × 3`, `float32`, NHWC — tensor name `input` (verified against the binary) |
| **Input normalization** | `(pixel − 127.5) / 127.5` → `[-1, 1]`, RGB channel order (`InputTensorPacker` / `LiteRtFaceEmbedder`) |
| **Output** | `1 × 192` `float32` — tensor name `embeddings` (verified against the binary) |
| **Embedding dimension** | **192** |
| **Post-processing** | validate (dim 192 · all finite · non-zero norm) → L2-normalize |
| **Runtime** | **LiteRT / TensorFlow Lite** (`org.tensorflow:tensorflow-lite`), one cached `Interpreter`, inference serialized behind a `Mutex` |
| **Similarity metric** | Cosine similarity (dot product of L2-normalized vectors) |
| **Appearance embedding** | robust mean: plain-mean centroid → drop members with cosine < `EMBEDDING_OUTLIER_COSINE_FLOOR` (keeping ≥ `MIN_INLIERS_FOR_ROBUST_MEAN`) → re-mean → L2 |
| **Embeddings per appearance** | up to `MAX_EMBEDDINGS_PER_APPEARANCE` (5), chosen for quality + temporal spread; ≥ `MIN_EMBEDDINGS_PER_APPEARANCE` (1) even for weak appearances |
| **Placeholder identity-merge threshold** | `0.62` cosine (`PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD`) |

> **Threshold calibration is a Phase-4 task.** `0.62` is a placeholder — no
> clustering is built around it yet. It will be calibrated from real Sample 1/2/3
> appearance embeddings against the assignment's ground truth (Sample 1: 5 unique
> people / 20 appearances). Clustering (agglomerative, cosine merge threshold) is
> not implemented in Phase 3 — this phase produces **appearances + embeddings**,
> never `Person`s.

#### Provenance & license (exact chain)

| Link | Detail |
|---|---|
| **Immediate binary source** | `github.com/MCarlomagno/FaceRecognitionAuth`, file `assets/mobilefacenet.tflite` |
| — its repo license | **BSD-3-Clause**, © 2020 Marcos Carlomagno (`LICENSE`) |
| **Original model / training graph** | `github.com/sirius-ai/MobileFaceNet_TF` |
| — its repo license | **Apache-2.0** (`LICENSE`) |
| **Separate weight license** | none published upstream; weights trained on public research face datasets (MS-Celeb-1M / refined sets) — research provenance, no explicit redistribution grant beyond the repo licenses |
| **Redistribution basis** | BSD-3-Clause **and** Apache-2.0 both permit bundling the unmodified binary in this app with attribution; both license texts are reproduced under `licenses/` alongside this note. Earlier drafts of this README said "MIT" — that was **incorrect** and is corrected here. |

No licensing problem was found, so the model is **not** being replaced.

**Why this model:** MobileFaceNet is the standard lightweight on-device
face-recognition backbone — ~5 MB, fast CPU inference, ArcFace-quality identity
embeddings, permissively licensed, and already a ready-to-bundle `.tflite`, so no
conversion pipeline is needed inside a time-boxed assignment. LiteRT was chosen
over ONNX Runtime Mobile because the model is already a `.tflite` and TFLite
integration is a single dependency with no NDK setup. `tensorflow-lite-support`
was deliberately excluded — it pulls the newer `com.google.ai.edge.litert`
artifacts which collide with classic `tensorflow-lite`
(`Duplicate class org.tensorflow.lite.*`); the embedder does its own pixel
packing so the support library adds nothing here.

### Model loading

`EmbeddingModelLoader` memory-maps the asset and creates **one**
`Interpreter` (4 threads), cached for the process lifetime. `LiteRtFaceEmbedder`
serializes `Interpreter.run` behind a `Mutex` (a single interpreter is not
re-entrant) and reuses one direct `ByteBuffer` for input. `AppContainer` holds
the loader, detector and embedder as singletons — the whole video reuses them.

### Recognition crop vs presentation crop

Kept as separate concepts (`PreprocessedFace.bitmap` vs
`PreprocessedFace.presentationCrop`). The recognition crop is aligned + resized to
112×112 for the model; the presentation crop is a margin-expanded crop for the
collage. The tiny detection box is never used directly as a collage tile.

## Verifying the ML stack

```bash
./gradlew :app:testDebugUnitTest          # framework-free core logic (cosine, IoU, grid engine)
./gradlew :app:connectedDebugAndroidTest  # MlInfrastructureSmokeTest — needs a device/emulator
```

`MlInfrastructureSmokeTest` (`app/src/androidTest/.../ml/`) asserts: model asset
present → model loads → runtime initializes → dummy `[-1,1]` inference runs →
output is `192`-d → values finite → ML Kit detector initializes. **Verified
passing on a Pixel 10 (API 37) emulator.**

## Phase 2 — video → frames → multi-face detection → tracklets → appearances

Implemented pipeline (`DefaultProcessVideoUseCase`):

```
video URI
 → MediaMetadataVideoReader        (duration / raw size / rotation; explicit AppError on every failure)
 → FrameSamplingRequest            (FRAME_SAMPLE_FPS = 4f  → ~120 timestamps for 30 s)
 → MediaMetadataFrameExtractor     (cold Flow, getScaledFrameAtTime → upright 720-long-edge bitmaps,
                                    one frame in flight, retriever released in finally)
 → per frame:
     MlKitFaceDetector             (ACCURATE, landmarks + classification + tracking; every face kept)
     FrameGeometry.toCanonical     (map detector boxes/landmarks back to full-res upright coords)
     FaceQuality.evaluate          (size + variance-of-Laplacian blur + eye/pose → 0..1, usable flag)
     → FaceObservation             (canonical box, trackingId, qualityScore) ; bitmap recycled now
 → GreedyFaceTracker.buildTracklets
     (ML Kit trackingId primary; IoU ≥ 0.3 / centre-dist / size-ratio fallback;
      MAX_TRACK_GAP_MS = 600 → tolerates 2 missed samples, then closes)
 → AppearanceSegmenter
     (≥ 2 observations AND ≥ 300 ms ⇒ one AppearanceCandidate per tracklet;
      nothing merged across time or across faces — that is Phase 3/4)
 → ProcessingResultRepository      (in-memory, keyed by session UUID)
```

All tunables live in `PipelineDefaults` (`FRAME_SAMPLE_FPS`, `TRACKLET_IOU_THRESHOLD`,
`MAX_TRACK_GAP_MS`, `MIN_APPEARANCE_OBSERVATIONS`, `MIN_APPEARANCE_DURATION_MS`, …) —
none hard-coded at call sites. Old Phase-1 names kept as aliases.

Background execution: the use case does `withContext(dispatchers.default)`; the
extractor `flowOn(dispatchers.io)`; ML Kit uses its own worker. The UI thread is
never touched. Cancellation: `ensureActive()` every frame, `CancellationException`
re-thrown, retriever released in `finally`, one bitmap alive at a time.

Diagnostics surfaced live in `ProcessingScreen` and in `ProcessingOutcome`:
`framesPlanned/Sampled/DecodedOk/RejectedAsInvalid`, `framesWithFaces`,
`totalFaceObservations`, `observationsRejectedLowQuality`, `multiFaceFrames`,
`maxFacesInAnyFrame`, `trackletsCreated`, `appearancesDetected`,
`totalProcessingMs`, `avgFrameProcessingMs`.

## Phase 2.5 — Sample 1 correctness/debugging pass

A dedicated instrumented harness (`app/src/androidTest/.../pipeline/Sample1DebugSweepTest.kt`)
runs the bundled Sample 1 clip through a detector-setting matrix, dumps a
per-frame detection timeline + a per-decision tracker trace (`TrackerTrace`), and
runs the full pipeline. Findings:

**Sample 1 container (verified, not assumed):** 1080×1920 portrait, rotation 0
(identity `tkhd` matrix), 30.000 s, H.264, 25 fps. `MediaMetadataRetriever`
reports `raw=1080x1920 rotation=0 display=1080x1920`. Bundled file is
byte-identical to the supplied one (SHA-256 `89122433…a432`).

**Root cause of "0 multi-face / 1 max face":** the frame extractor used
`OPTION_CLOSEST_SYNC`. On this clip that collapsed **102 of 119 consecutive
sampled frame-pairs onto the same keyframe** (a `frozenConsecutivePairs` counter
in the harness confirmed it) — so many timestamps returned a stale, re-scaled
keyframe, variance-of-Laplacian sat at ~3–10 (mush), and ML Kit hallucinated
degenerate boxes (`530x0`, `901x8`, 13-box clusters) on the garbage pixels.
Switching to `OPTION_CLOSEST` → **0 frozen pairs**, blur variance 1700–3200 (real
frames), quality ≈ 1.0, and the two overlap windows resolve as **2 faces each**.

**Detection experiments (720 px vs 1080 px, minFaceSize 0.06 / 0.04, seek option):**

| config | frozenPairs | framesWithFaces | multiFaceFrames | maxFaces | degenerateBoxes | detectMs | WIN_A 10.1–11.5 s | WIN_B 20.2–21.6 s |
|---|---|---|---|---|---|---|---|---|
| 720 / mf.06 / **SYNC** (old) | 102 | 79–89 | 47–65 (noise) | 3–5 | 19–75 | ~2.8 s | 0 faces | 2 faces (garbage boxes) |
| 720 / mf.06 / CLOSEST | 0 | 115–118 | 25–44 | 3–4 | 0–22 | ~8.1 s | **1 face** (2nd is ~400 px, ff 0.38, missed) | 2 faces |
| 720 / mf.04 / CLOSEST | 0 | 115–116 | 40–45 | 4 | 7–23 | ~8.1 s | **2 faces** | 2 faces |
| **1080 / mf.06 / CLOSEST** (chosen) | 0 | 115 | 23–24 | 4 | **0** | ~8.3 s | **2 faces** | **2 faces** |
| 1080 / mf.04 / CLOSEST | 0 | 116 | 24–38 | 4 | 0–5 | ~8.3 s | 2 faces | 2 faces |

`1080 / mf.06 / CLOSEST` is the pick: it resolves both overlap windows at the
default minFaceSize with **zero degenerate boxes**, whereas 720 px needs
minFaceSize 0.04 (which roughly doubles noise). `FAST` mode and `tracking off`
each collapsed to <10 detections total on the emulator and were discarded.
`ACCURATE` + tracking is kept. Cost of the change: ~8.3 s vs ~2.8 s detect for a
30 s clip — accepted; correctness over speed as instructed.

**Detector still emits occasional degenerate boxes** (`Nx0`, extreme aspect) on
hard frames even with `CLOSEST` — now filtered in the pipeline by
`MIN_FACE_EDGE_PX` (24) and `MAX_FACE_ASPECT_RATIO` (2.5).

**Tracker diagnostics — the 8-second `tid15` / old `app_5` tracklet:** the
`TrackerTrace` showed the pre-2.5 spatial fallback bridging **two different ML
Kit tracking ids** across a jump:
`MATCH … via=SPATIAL_FALLBACK iou=0.48 cDist=215 sRatio=1.33 prevTid=11 newTid=14 idChanged=true`.
Per rule #8 the tracker now defaults to **`bridgeAcrossTrackingIds = false`**: the
spatial fallback will not link an observation whose tracking id differs from the
tracklet's last known id — a false split (embeddings re-merge later) is preferred
over a false merge (destroys the appearance boundary). With this, the standalone
trace splits that region into 15 tracklets instead of 12.
`MAX_TRACK_GAP_MS` was reduced **600 → 500** so a two-missed-frame absence is a
hard boundary while a single missed frame is still tolerated.

**Appearance segmentation (raw → filtered → candidates):**
`rawTracklets = 15 · trackletsFilteredOut = 3 (short/1-frame noise) · candidates = 12`.
The `MIN_APPEARANCE_OBSERVATIONS = 2` / `MIN_APPEARANCE_DURATION_MS = 300` filter
is only removing genuine 1–2 frame blips.

**Quality field renamed** `observationsRejectedLowQuality` →
`observationsLowQuality` (low-quality observations are **kept** for tracking, not
rejected — only flagged for the future representative-frame scorer).

### Sample 1 — final numbers (1080 px / CLOSEST / mf 0.06 / bridge off / gap 500 ms, Pixel 10 API 37 emulator, 4 FPS)

| metric | pre-2.5 | **post-2.5** |
|---|---|---|
| framesWithFaces | 78 | **115** |
| totalFaceObservations | 78 | **157** |
| observationsLowQuality | 57 | **12** |
| **multiFaceFrames** | **0** | **36** |
| **maxFacesInAnyFrame** | **1** | **3** |
| degenerate boxes kept | (unmeasured) | **0** |
| rawTracklets | 8 | **15** |
| trackletsFilteredOut | — | 3 |
| **appearanceCandidates** | **7** | **12** |
| totalProcessingMs | 3836 | **8854** |
| avgFrameProcessingMs | 15.9 | **26.2** |

Overlap windows are now correctly two appearances each:
`10.75→12.25 s (tid13)` + `11.75→13.25 s (tid14)` for A+B;
`13.5→21.5 s (tid15)` + `20.25→21.25 s (tid18)` concurrent for C+D.

**Remaining discrepancy — 12 candidates vs the assignment's 20 appearances:**

- Three tracklets are still long: `tid3` 5.25→11.5 s (26 obs), `tid15`
  13.5→21.5 s (32 obs), `tid21` 23.5→29.75 s (25 obs). In each, ML Kit holds a
  **single stable tracking id with no ≥2-frame gap**, so there is no
  Phase-2-visible boundary to split on. If some of these spans contain two
  different people who never leave frame and are never assigned distinct ids by
  ML Kit, only the embedding stage can tell them apart — but per the brief,
  clustering must *re-group*, not *create*, appearances. This is the honest gap:
  documented, not forced. Options for a later pass: an embedding-assisted
  intra-tracklet split, or a stricter same-tracklet appearance-length cap.
- ML Kit's tracking-id assignment is **mildly non-deterministic** under
  `ACCURATE` on the emulator: repeated runs of the identical config gave
  115–118 framesWithFaces and 24–44 multiFaceFrames. Candidate count moved 11–12
  across runs. A physical-device run is the next validation step.
- No face is being *missed* because it is too small/blurred that we can see: the
  second face in each overlap window is now detected in every non-whip-pan
  sampled frame.

### Final selected parameters (all in `PipelineDefaults`, none inline)

| parameter | Phase 2 | **Phase 2.5** | why |
|---|---|---|---|
| `FRAME_SAMPLE_FPS` | 4 | **4** | unchanged baseline |
| `FRAME_DECODE_MAX_EDGE_PX` | 720 | **1080** | resolves 2nd overlap face at default minFaceSize, 0 degenerate boxes |
| `FRAME_SEEK_OPTION_NAME` | (SYNC, hard-coded) | **CLOSEST** | SYNC froze 102/119 frame-pairs on this clip |
| `MLKIT_MIN_FACE_FRACTION` | 0.06 | **0.06** | 0.04 doubled noise; 1080 px makes 0.06 sufficient |
| `MIN_FACE_EDGE_PX` / `MAX_FACE_ASPECT_RATIO` | — | **24 / 2.5** | drop `Nx0` / extreme-aspect hallucinations |
| `MAX_TRACK_GAP_MS` | 600 | **500** | 2-missed-frame absence = hard boundary |
| `TRACKLET_BRIDGE_ACROSS_TRACKING_IDS` | (implicitly true) | **false** | rule #8 — never bridge two different ML Kit ids |
| detector mode | ACCURATE + tracking | **ACCURATE + tracking** | FAST / no-tracking collapsed to <10 detections |

## Phase 3 — face crop → alignment → 112×112 → MobileFaceNet → 192-d embedding

Phase 3 turns each Phase-2 `AppearanceCandidate` into per-observation embeddings
and one aggregated appearance embedding. **No global clustering, no `Person`s, no
duration cap** — clustering is Phase 4.

```
AppearanceCandidate (+ lightweight observation refs: box, landmarks, quality — no bitmaps)
 → EmbeddingSampleSelector    quality gate + temporal-diversity buckets → ≤ MAX_EMBEDDINGS_PER_APPEARANCE picks
 → VideoFrameExtractor.decodeFrameAt(ts)   re-decode just the chosen frames (own retriever, released each time)
 → RecognitionCrop.expandAndClamp          margin FACE_CROP_MARGIN_H/V, clamped to canonical frame, isUsable() guard
 → SimilarityTransformFaceAligner          eye-line similarity transform (rotate+scale, never mirrored);
                                           falls back to a margin-expanded box crop if eyes missing/implausible
 → 112×112 ARGB  (DefaultFacePreprocessor)
 → InputTensorPacker / LiteRtFaceEmbedder  NHWC, RGB order, (px−127.5)/127.5, one cached Interpreter, Mutex
 → validate: dim==192 · all finite · non-zero norm → L2-normalize   → FaceEmbedding
 → AppearanceEmbeddingAggregator           plain-mean centroid → drop cosine-outliers (keep ≥2) → re-mean → L2
 → AppearanceEmbedding  (+ per-obs EmbeddedFaceObservation list, for drift diagnostics)
```

**Model verification (against the actual `.tflite` binary):** single subgraph,
input tensor `input` = `[1,112,112,3]` `float32` NHWC, output tensor `embeddings`
= `[1,192]` `float32`. Matches `ModelSpec.MOBILE_FACE_NET` exactly — no
discrepancy, model unchanged.

**License correction:** earlier drafts said "MIT" — wrong. The binary source
(`MCarlomagno/FaceRecognitionAuth`) is **BSD-3-Clause**; the model source
(`sirius-ai/MobileFaceNet_TF`) is **Apache-2.0**. Both permit bundling with
attribution; full texts under [`licenses/`](licenses/). See the provenance table
above.

### Sample 1 — Phase 3 result (Pixel 10 API 37 emulator, 4 FPS, this run's Phase-2 gave 13 appearances)

| metric | value |
|---|---|
| appearances processed / embedded | 13 / 13 (0 without an embedding) |
| observations selected / embedding calls / failures | 65 / 65 / **0** |
| frames re-decoded | 65 · avg **88.8 ms** each (dominates the stage) |
| alignment: landmark vs box-fallback | 60 / 5 |
| outliers rejected by aggregator | 0 |
| model load | **2 ms** (already warm from Phase 2) |
| avg inference | **7.4 ms** / call · total 484 ms |
| align + preprocess total | 9 ms |
| **total Phase-3 stage** | **6.27 s** (≈ 92 % is `MediaMetadataRetriever` re-decode) |

Every appearance embedding is 192-d, finite, unit-magnitude.

**Pairwise cosine (appearance embeddings)** — clear structure, *not* turned into
clusters here:

- `app_9 / app_10 / app_11` (all 15.25–16.25 s, a multi-face window) form a tight
  triangle with **each other** at 0.59–0.86 while sitting near-zero / negative to
  everyone else → three distinct people co-visible, correctly kept separate.
- `app_4 / app_3 / app_12` mutually 0.79–0.87 → very likely one identity split
  into three appearances by Phase-2 gaps (re-merge candidate for Phase 4).
- `app_5 / app_14` at 0.86, `app_5 / app_8` 0.73, `app_0 / app_13` 0.70 → further
  cross-appearance identity links for calibration.

### Investigating the long tracklets (Step 22 — evidence only, no auto-split)

This run's long tracklets (structural equivalents of Phase 2.5's tid3/15/21;
tracking-id numbers differ per run due to documented ML Kit non-determinism):

| appearance | tid | obs | span | member→centroid cosine over time | verdict |
|---|---|---|---|---|---|
| `app_5` | 5 | 31 | 5.25–13.0 s | 0.83 · 0.74 · **0.50** · **0.52** · 0.72 | **POTENTIAL IDENTITY/TRACK MERGE** — mid-segment dip |
| `app_14` | 14 | 26 | 23.5–29.75 s | 0.83 · 0.78 · **0.39** · 0.71 · 0.65 | **POTENTIAL IDENTITY/TRACK MERGE** — sharp dip at 26 s |
| `app_0` | 0 | 17 | 0–4.5 s | 0.64 · 0.68 · 0.66 · 0.67 · **0.44** | **POTENTIAL** — trailing frame diverges |
| `app_12` | 12 | 12 | 17.0–19.75 s | **0.51 · 0.49** · 0.86 · 0.81 · 0.86 | **POTENTIAL** — first two frames diverge (likely a bad crop, not two people) |

The internal drift is real and now measured. Per the brief these are **flagged,
not split** — an embedding-assisted intra-tracklet split is a Phase-4 option once
the merge threshold is calibrated.

### Phase 3 parameters (all in `PipelineDefaults`)

| parameter | value | role |
|---|---|---|
| `FACE_CROP_MARGIN_HORIZONTAL` / `_VERTICAL` | 0.30 / 0.40 | recognition-crop context (not the collage crop) |
| `MIN_RECOGNITION_CROP_PX` | 24 | reject near-edge / degenerate crops |
| `MAX_EMBEDDINGS_PER_APPEARANCE` | 5 | bound on embeddings per appearance |
| `MIN_EMBEDDINGS_PER_APPEARANCE` | 1 | a weak appearance never ends up with zero |
| `EMBEDDING_MIN_QUALITY` | 0.35 | first-pass sample gate (soft — falls back to best-N) |
| `EMBEDDING_OUTLIER_COSINE_FLOOR` | 0.35 | aggregator drops members below this vs provisional centroid |
| `MIN_INLIERS_FOR_ROBUST_MEAN` | 2 | never collapse the centroid below 2 members |
| `IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK` | 0.62 | used only when calibration finds no gap (Phase 4) |

## Phase 4 — intra-appearance split → calibration → must-not-link → identity clustering → Person[]

Phase 4 is **not** just "embeddings → clustering". Phases 2/2.5 gave evidence of
*both* over-splitting (one identity fragmented across appearances) and
over-merging (one tracklet spanning two people with no visible gap), so Phase 4
first corrects over-merged appearances with embeddings, then groups.

```
AppearanceCandidate[]  (+ per-observation embeddings from Phase 3)
 → IntraAppearanceSplitter    1-D temporal scan for a single best change point;
                              cut at argmax( mean within-half cosine − mean across-half cosine ),
                              only if separation ≥ INTRA_APPEARANCE_SPLIT_MIN_MARGIN and each side
                              keeps ≥ 2 embeddings; recursable (≤ 3 passes). Fragments keep every
                              observation, contiguous non-overlapping time spans.
 → re-aggregate one AppearanceEmbedding per corrected fragment (robust mean, Phase-3 aggregator)
 → MustNotLinkBuilder         observation-level: two appearances that each hold a face in the SAME
                              sampled frame whose boxes overlap ≤ MUST_NOT_LINK_MAX_IOU (≠ the same
                              face detected twice) → hard cannot-be-same-person edge.
                              NOT inferred from timestamp-range overlap alone.
 → SimilarityCalibrator       unsupervised: histogram all pairwise appearance-embedding cosines,
                              find the widest valley between the inter-identity mode and the
                              intra-identity mode → threshold = valley centre; confidence = valley
                              depth vs the smaller peak. No meaningful gap ⇒ fall back to
                              IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK, mark low-confidence.
                              + sensitivity sweep at ±0.02/0.05/0.10. Never uses a person count.
 → AgglomerativeIdentityClusterer   average-linkage cosine, single threshold cut, must-not-link is
                              a HARD constraint that overrides similarity (transitively). Fully
                              deterministic: sorted-id processing, id-pair tie-break, no k, no RNG.
 → Person[]   every appearance instance preserved in Person.appearanceIds
```

Representative-frame selection and the collage are still later phases — Phase 4
stops at `Person[]`.

### Sample 1 — Phase 4 result (Pixel 10 API 37 emulator; this run's Phase 2 gave 13 appearances)

| stage | outcome |
|---|---|
| intra-appearance split | 13 → **15** (2 splits): `app_6`→`app_6/app_6_s1` @6.75 s (sep 0.34), `app_7`→`app_7/app_7_s1` @10.25 s (sep 0.66) |
| pairwise cosine histogram | 105 pairs; inter-identity mode ≈ **+0.23**, intra-identity mode ≈ **+0.58**, wide valley between |
| **calibrated merge threshold** | **0.425** — gap-derived, **not** fallback; valley depth (confidence) 0.67 |
| sensitivity sweep | **5 people** stable across t ∈ [0.375, 0.525]; 4 people at t = 0.325 |
| must-not-link edges | 3 (`app_10↔app_11` @20.25 s IoU 0.11; `app_13↔app_12` @21.75 s IoU 0.10; `app_14↔app_15` @23.5 s IoU 0.18) — all respected in the output |
| merges blocked by must-not-link | 0 (similarity alone already kept those pairs apart at 0.425) |
| **people** | **5** — appearance counts 7 / 2 / 2 / 2 / 2, **15 instances grouped, none lost** |
| Phase 4 stage time | ~12 ms |

> The assignment's "5 people / 20 appearances" for Sample 1 is **validation
> evidence, not a training target** — the threshold was chosen purely from the
> cosine distribution. That it lands on 5 people, stable across a ±0.1 band, is a
> good sign; the calibration is reported as gap-derived / non-fallback but the
> intra-identity mode is thin (few repeat-appearance pairs on this clip), so
> `IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK` stays configurable and the
> sensitivity sweep is part of the output. Appearance *count* (15 here, target
> 20) is lower than the assignment's — Phase 2's ML Kit tracking non-determinism
> and the conservative split margin account for the gap; clustering re-groups,
> it does not invent appearances.

### Phase 4 parameters (all in `PipelineDefaults`)

| parameter | value | role |
|---|---|---|
| `INTRA_APPEARANCE_SPLIT_MIN_MARGIN` | 0.20 | min (within − across) cosine separation to accept a cut |
| `INTRA_APPEARANCE_SPLIT_MIN_SIDE_EMBEDDINGS` | 2 | each side of a cut must keep this many embeddings |
| `INTRA_APPEARANCE_SPLIT_MAX_DEPTH` | 3 | max recursive split passes per original appearance |
| `CALIBRATION_HISTOGRAM_BINS` | 40 | pairwise-cosine histogram resolution |
| `CALIBRATION_MIN_VALLEY_DEPTH_FRACTION` | 0.30 | valley must be this much below the smaller peak, else fallback |
| `CALIBRATION_THRESHOLD_MIN` / `_MAX` | 0.40 / 0.85 | calibrated threshold is clamped here |
| `IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK` | 0.62 | threshold when no meaningful gap exists (low-confidence) |
| `MUST_NOT_LINK_FRAME_TOLERANCE` | 0 | frame-index window for "same instant" |
| `MUST_NOT_LINK_MAX_IOU` | 0.30 | above this, two concurrent boxes are the same face, not two people |

## Phase 4.5 — dense temporal-split refinement + Sample 1/2/3 appearance-count validation

Phase 4's cheap 5-embedding splitter left Sample 1 short on **appearance count**
(5 people ✓, but ~15 of 20 appearances). Phase 4.5 adds a **dense refinement
path** that runs *only* for suspicious appearances — the ordinary per-appearance
embedding budget (`MAX_EMBEDDINGS_PER_APPEARANCE = 5`) is untouched.

```
per Phase-2 appearance:
  cheap Phase-4 IntraAppearanceSplitter always runs (baseline)
  SuspiciousAppearanceSelector flags it if ANY of:
     duration > DENSE_SUSPICIOUS_MIN_DURATION_MS   (a trigger for ANALYSIS — never a reason to split; no duration cap)
     global 5-embedding compactness < DENSE_SUSPICIOUS_COMPACTNESS_MAX
     largest consecutive global-embedding cosine drop ≥ DENSE_SUSPICIOUS_DRIFT_MIN
  if flagged → denseEmbed 8–15 temporally-spread observations (adaptive: ~1 / DENSE_ANALYSIS_SECONDS_PER_SAMPLE)
            → WhipPanGapAnalyzer   : wall-clock gap > WHIP_PAN_GAP_MS between two BLURRED
                                     observations (quality ≤ WHIP_PAN_MAX_QUALITY) → hard cut,
                                     even if ML Kit kept the tracking id across it
            → DenseTemporalChangePointAnalyzer : per candidate cut compute left/right window
                                     compactness + cross-window similarity; accept only when the
                                     coherent side is a real segment AND cross similarity drops
                                     ≥ DENSE_SPLIT_MIN_DROP below it, and the other side is either
                                     also coherent (clean 2-way split) or *severely* incoherent
                                     (still a different mixture → recursion splits it). The
                                     "both sides only moderately coherent" band = gradual pose
                                     drift → never split. Recursive (≤ DENSE_SPLIT_MAX_DEPTH),
                                     so a tracklet can yield several boundaries.
            → low-quality observations are DOWN-WEIGHTED in the scoring, never removed
  dense result replaces the naive one only if it does not UNDER-split (dense with 0 cuts → keep naive)
then: re-aggregate per-fragment embeddings → must-not-link → calibrate → cluster  (unchanged Phase-4 order)
```

### Sample 1 / 2 / 3 — full pipeline result (Pixel 10 API 37 emulator, 4 FPS)

`SampleIdentityRefinementTest` runs Phase 2→3→4/4.5 on each bundled clip. **Four
runs were captured because Phase 2 is not deterministic on the emulator** — ML Kit
`ACCURATE` + tracking gives a *different appearance-candidate count every run*:

| run | S1 Phase-2 appearances | S1 after dense refine | S1 people | S1 dense splits |
|---|---|---|---|---|
| a | 13 | 15 | 5 | 2 |
| b | 10 | 14 | 6 | 4 |
| c | 19 | 24 | 11 | 5 |
| d | 13 | 21 | 10 | 7 |
| e | 11 | 18 | 11 | 6 |

Representative final run (e), all three samples:

| | Sample 1 | Sample 2 | Sample 3 |
|---|---|---|---|
| Phase-2 appearance candidates | 11 | 10 | 15 |
| after dense refinement | **18** | 14 | **20** |
| naive-splitter appearances | 16 | 14 | 18 |
| dense splits beyond naive | +1 (`app_8`) | 0 | +1 (`app_0`) − 1 (`app_4`) |
| calibrated threshold | 0.62 **(fallback, low-confidence)** | 0.62 (fallback) | 0.62 (fallback) |
| people | 11 | 7 | 11 |
| dense embedding calls | 65 | 50 | 51 |
| dense analysis time | 5.8 s | 4.3 s | 4.7 s |
| ground-truth reference | 5 people / 20 app. | — | — |

Every accepted dense cut has sound evidence — e.g. Sample 1 `app_8` split at
11.75 s with left/right compactness 0.92 / 0.93 and cross-similarity **0.23**;
Sample 3 `app_7` split at 18.75 s (L 0.94, cross **0.05**). Gradual-drift
candidates are correctly rejected (`app_13`: coherent-side margin 0.13 < 0.20).

### Why the counts still don't lock onto 5 / 20

Evidence-based decision per the brief (**CASE B**, with a touch of C that was fixed):

1. **Dominant cause — Phase-2 non-determinism (detector/tracker, CASE B).**
   Sample 1's appearance-candidate count swings **10 → 19** across identical
   emulator runs. Everything downstream inherits that. Phase 4/4.5 is *itself*
   fully deterministic (the test re-runs it on a fixed Phase-2 input and asserts
   identical `Person[]`), so the instability is entirely upstream. **A physical
   device is required** to validate the 5 / 20 target — `ACCURATE` + tracking is
   known to be much steadier on real hardware than on the SwiftShader emulator.
2. **Splitter was under-cutting severely-incoherent blobs (CASE C — fixed).**
   The first Phase-4.5 dense analyzer refused to cut a window when *no* 2-way
   split produced a coherent half (a 0.24-compactness tracklet of 3+ interleaved
   people). Added a bounded "rescue" branch: when the whole window's compactness
   is below `DENSE_SPLIT_SEVERELY_INCOHERENT`, take the cut with the largest
   cross-similarity drop and let the recursion isolate coherent sub-segments.
   After the fix the dense path recovers **18–24** Sample 1 appearances vs the
   naive path's 15–18.
3. **Calibration correctly falls back (not a defect).** With only 10–20
   appearance embeddings (~50–200 pairwise cosines) the distribution has an
   inter-identity mode but only a thin intra-identity tail — no valley. The
   unsupervised gap statistic reports **fallback / low-confidence** and uses
   `IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK = 0.62`, exactly as designed. The
   philosophy was **not** changed and the threshold was **never** tuned to
   produce 5 people (the sensitivity sweep shows 0.62 → 7–11 people; no threshold
   in [0.52, 0.72] yields 5 on these emulator embeddings). Real-device embeddings,
   or Samples 2+3 pooled, should give the gap statistic enough separation.

**Recoverable vs not:** the *appearance* count is essentially recovered on a good
Phase-2 run (Sample 3 hit exactly 20; Sample 1 reached 21–24). The *people* count
needs (a) a stable Phase-2 pass — physical device — and (b) enough embeddings for
the gap statistic, or an explicit human-set threshold via the still-configurable
`IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK`.

### Phase 4.5 parameters (all in `PipelineDefaults`)

| parameter | value | role |
|---|---|---|
| `DENSE_SUSPICIOUS_MIN_DURATION_MS` | 4000 | duration that *triggers analysis* (never a split reason) |
| `DENSE_SUSPICIOUS_COMPACTNESS_MAX` | 0.80 | global-embedding compactness below this ⇒ suspicious |
| `DENSE_SUSPICIOUS_DRIFT_MIN` | 0.20 | biggest consecutive global-embedding cosine drop ⇒ suspicious |
| `DENSE_ANALYSIS_MIN_SAMPLES` / `_MAX_SAMPLES` | 8 / 15 | dense sample count bounds |
| `DENSE_ANALYSIS_SECONDS_PER_SAMPLE` | 0.75 | adaptive dense spacing |
| `DENSE_SPLIT_MIN_SIDE_COMPACTNESS` | 0.55 | a "coherent segment" sanity floor |
| `DENSE_SPLIT_SEVERELY_INCOHERENT` | 0.45 | below ⇒ still a different mixture, recursion splits it |
| `DENSE_SPLIT_MIN_DROP` | 0.20 | coherent-side compactness − cross-similarity needed to cut |
| `DENSE_SPLIT_MIN_SIDE_SAMPLES` | 3 | each side of a dense cut keeps ≥ this many samples |
| `DENSE_SPLIT_MAX_DEPTH` | 4 | recursive dense-split depth cap (multiple boundaries allowed) |
| `DENSE_SPLIT_LOW_QUALITY` / `_WEIGHT` | 0.45 / 0.25 | low-quality observations down-weighted (not removed) in scoring |
| `WHIP_PAN_GAP_MS` | 700 | wall-clock gap between two blurred observations ⇒ hard appearance boundary |
| `WHIP_PAN_MAX_QUALITY` | 0.35 | "blurred" ceiling for the whip-pan rule |

## Phase 4.6 — identity threshold-sweep diagnostic (DIAGNOSTIC ONLY — production unchanged)

Phases 4/4.5 keep landing on 7–11 people for Sample 1 (target 5) with the
calibrator on its `0.62` fallback. Phase 4.6 is a **diagnostic-only** experiment
— no production algorithm changed — that runs the *existing* agglomerative
clusterer across a threshold range on the *exact* corrected appearance
embeddings + must-not-link edges the production path produced, to see whether the
embedding similarity space supports a stable identity threshold at all.

New pure-Kotlin diagnostic components (isolated in
`features/people/domain/diagnostic/`, never wired into `AppContainer` or any
ViewModel):

- **`IdentityThresholdSweep`** — runs `AgglomerativeIdentityClusterer.cluster(embeddings, t, mnl)` verbatim for `t ∈ [0.40, 0.80]` step `0.025`; reports per-threshold people count, appearance count, cluster-size signature, full membership, per-cluster mean/min pairwise cosine + best 2-way internal cut, MNL-violation count.
- **`PairwiseCosineStats`** — min/max/mean/median/P10–P90 + the *production* `SimilarityCalibrator` histogram & valley (via a new additive `SimilarityCalibrator.analyzeCosineArray` — `calibrate()` untouched); plus a must-not-link-vs-other cosine overlay.
- **`IdentityCalibrationDiagnostic`** — orchestrates the above per sample and, separately, a **pooled** distribution: cosines are pooled **within each sample only** (no cross-sample pairs → it can never cluster an appearance from one video with another; `crossVideoClusteringPerformed = false`).
- `BuildIdentitiesUseCase.Result` gained `appearanceEmbeddings` + `mustNotLinkEdges` (additive fields) so the diagnostic re-clusters the identical input.

`IdentityCalibrationSweepTest` runs Phase 2→3→4-split on the bundled Samples 1/2/3
and dumps everything to logcat tag `Phase46`.

### D/E/F — threshold sweeps (one emulator run; Phase 2 gave S1=23, S2=13, S3=21 corrected appearances)

Sample 1 (`app_*`, 23 appearances, 11 MNL edges) — `people` by threshold:

| t | 0.40 | 0.50 | 0.525 | 0.55 | 0.575 | 0.62 | 0.65 | 0.70 | 0.75 | 0.80 |
|---|---|---|---|---|---|---|---|---|---|---|
| people | **7** | **7** | 8 | 8 | 9 | 9 | 10 | 12 | 15 | 16 |
| size-signature | 8,5,3,2,2,2,1 | " | 5,5,3,3,2,2,2,1 | " | 5,5,3,3,2,2,1,1,1 | " | 5,3,3,3,2,2,2,1,1,1 | 4,3,3,2,2,2,2,1,1,1,1,1 | … | … |
| mean cluster compactness | 0.77 | 0.77 | 0.78 | 0.78 | 0.85 | 0.85 | 0.86 | 0.90 | 0.96 | 0.97 |
| MNL violations | **0** at every threshold | | | | | | | | | |

Transitions: 7→8 @0.525, 8→9 @0.575, 9→10 @0.650, 10→12 @0.700, 12→13 @0.725.
**Lowest threshold in the whole sweep = 0.40 → 7 people. There is no threshold
(down to the 0.40 clamp floor) that yields 5.** The 7-cluster floor is stable
across 0.40–0.50 but it is a *floor from over-merging*, not a natural grouping —
its biggest cluster is 8 appearances with mean pairwise cosine 0.77 and a
weakest internal cut of only ~0.57.

Sample 2 (13 appearances) — people: `0.40–0.55 → 5`, `0.575–0.625 → 6`,
`0.65–0.70 → 7`, `0.725 → 8`, `0.75–0.80 → 9`. Here the low-threshold floor **is
5** — but again by chaining: cluster 2 at t=0.575 is `app_1_s1, app_4, app_8`
with min pairwise **0.57**.

Sample 3 (21 appearances) — people: `0.40–0.50 → 6`, `0.525 → 7`, `0.55 → 8`,
`0.575 → 9`, `0.60 → 10`, `0.625–0.65 → 11`, up to `0.80 → 14`. Floor = 6.

### G/H — pairwise cosine distribution & calibration

| | S1 (253 pairs) | S2 (78) | S3 (210) | pooled (541, within-sample only) |
|---|---|---|---|---|
| min / max | −0.33 / 0.98 | −0.24 / 0.97 | −0.38 / 0.98 | −0.38 / 0.98 |
| P10 / median / mean / P90 | −0.04 / 0.23 / 0.27 / 0.65 | −0.02 / 0.22 / 0.28 / 0.69 | −0.12 / 0.20 / 0.25 / 0.67 | −0.06 / 0.21 / 0.26 / 0.67 |
| calibrator valley | **none → fallback 0.62** | none → fallback | none → fallback | **none → fallback** |

Every histogram is **unimodal**: a single broad mode around cosine 0.10–0.25 with
a long thin right tail (a handful of pairs > 0.8). There is no valley — the
production `SimilarityCalibrator` correctly reports fallback on all four,
**including the pooled 541-pair distribution**. Pooling Samples 1–3 does **not**
produce a usable calibration signal.

### J — the current 0.62 result

Sample 1 @ 0.62 → 9 people, signature `5,5,3,3,2,2,1,1,1`. The two 5-clusters
have mean pairwise cosine 0.74 / 0.79. Candidate near-threshold merges (would
join at a slightly lower t): the two 3-clusters `[app_17_s1,app_2,app_7]`
(mean 0.81) and `[app_19,app_4_s1,app_8]` (mean 0.75) merge into the 8-cluster at
0.50. **No dangerous merges appear** — MNL blocks 1 merge at t ≤ 0.50 and 0 above;
0 MNL violations at every threshold.

### K — must-not-link analysis (the decisive evidence)

Cosine of pairs the observation-level rule proves are **different people**, vs all
other pairs:

| sample | MNL pairs | MNL mean cos | MNL **max** cos | other-pairs mean cos |
|---|---|---|---|---|
| Sample 1 | 11 | **0.26** | **0.65** | 0.27 |
| Sample 2 | 2 | 0.17 | 0.35 | 0.29 |
| Sample 3 | 6 | 0.11 | 0.36 | 0.25 |

**Known-different-identity pairs have the same cosine distribution as the
unknown-mix pairs**, and on Sample 1 a *provably different* pair reaches cosine
**0.65** — above where several accepted same-person merges sit (0.56–0.74). The
embedding space, with this model + preprocessing + emulator-decoded frames, does
**not linearly separate identity**. No single cosine threshold can.

### L / M — stability & determinism

- MNL guarantee holds at **every** threshold in **every** sweep (0 violations, verified in the test).
- `personCount` is monotone non-decreasing in threshold on all three samples (verified — no implementation bug where a lower threshold splits more).
- The sweep is **bit-identical** on re-run (test re-runs Sample 1's sweep and diffs full membership).

### Phase 4.6 conclusion — **EMBEDDING PIPELINE NEEDS INVESTIGATION** (+ physical-device validation)

The 7-person Sample-1 result is **not** primarily a threshold problem — the
threshold sweep shows no value in [0.40, 0.80] reaches 5, and the floor at 0.40
is already an over-merge (`min pairwise 0.57` inside an 8-cluster). It is
**poor embedding separation**: provably-different faces (must-not-link) sit at
the *same* cosine as everything else, peaking at 0.65 on Sample 1. Contributing
but secondary: appearance fragmentation inflates the raw count (23 vs ~20).

`0.62` is **not defensible on measured evidence** — but neither is any other
fixed value, because the distribution is unimodal on every sample and on the
pool. **Do not change the fallback yet.** The likely root causes to investigate,
in order:

1. **Emulator frame decode.** `OPTION_CLOSEST` on SwiftShader may still be
   returning soft/degraded frames → weak embeddings. A physical device is the
   fastest way to rule this in or out.
2. **Alignment.** 60/65 crops align by landmarks, but a systematic scale/centre
   offset would blur every embedding equally → exactly this "everything ~0.2"
   signature. Worth dumping a few 112×112 recognition crops to inspect.
3. **Normalization / channel order** into MobileFaceNet — re-verify against a
   reference embedding for a known face pair (should be > 0.5 same, < 0.2
   different).

`IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK` stays at `0.62` and stays
configurable pending that investigation.

### Single physical-device validation to run before freezing anything

Run `IdentityCalibrationSweepTest` (and `SampleIdentityRefinementTest`) on a
**physical Android device** (any recent Pixel). Compare the **must-not-link vs
other cosine table** and the **histogram shape** to the emulator numbers above.
If on-device the MNL-pair mean drops well below the other-pair mean and a valley
appears → the emulator decode was the problem, re-calibrate from the device
distribution. If the histogram is still unimodal and MNL pairs still reach ~0.6 →
the embedding/alignment pipeline itself needs the fixes in (2)/(3).

### Phase 4.6 files

New (diagnostic, isolated): `features/people/domain/diagnostic/IdentityThresholdSweep.kt`,
`PairwiseCosineStats.kt`, `IdentityCalibrationDiagnostic.kt`;
`app/src/androidTest/.../pipeline/IdentityCalibrationSweepTest.kt`;
`app/src/test/.../identity/IdentityThresholdSweepTest.kt`, `PairwiseCosineStatsTest.kt`.
Modified (additive only): `SimilarityCalibrator.kt` (+`analyzeCosineArray`, `calibrate()` untouched),
`BuildIdentitiesUseCase.kt` / `DefaultBuildIdentitiesUseCase.kt` (Result +`appearanceEmbeddings`, +`mustNotLinkEdges`).
**No production algorithm, threshold, or wiring changed.**

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest           # 106 pure-Kotlin tests (tracker, segmenter, geometry, progress,
                                           #   crop geometry, embedding math, tensor packing, sample selection,
                                           #   aggregation, intra-appearance split, calibration, must-not-link,
                                           #   clustering, dense change-point, whip-pan, suspicion selection,
                                           #   threshold sweep, pairwise-cosine stats)
./gradlew :app:connectedDebugAndroidTest   # MlInfrastructureSmokeTest, Sample1PipelineTest,
                                           #   EmbeddingModelSmokeTest (real model, full path),
                                           #   Sample1EmbeddingAnalysisTest (Phase 2+3),
                                           #   SampleIdentityRefinementTest (Phase 2+3+4/4.5 on Samples 1, 2, 3),
                                           #   IdentityCalibrationSweepTest (Phase 4.6 threshold sweep + pooled distribution)
```

- AGP 9.3.2, Kotlin 2.2.10, Compose BOM 2026.02.01, Gradle 9.5, `compileSdk 37`, JDK 17
- `minSdk` was raised `24 → 26` per the assignment.
- `.tflite` assets are stored uncompressed (`androidResources.noCompress += "tflite"`).
