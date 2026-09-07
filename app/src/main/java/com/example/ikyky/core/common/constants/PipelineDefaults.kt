package com.example.ikyky.core.common.constants

/**
 * Central, documented tuning knobs for the on-device pipeline.
 *
 * Every value here is an *initial* default for the assignment and is expected to
 * be calibrated against the provided sample videos. Nothing in the pipeline
 * should hard-code these numbers inline.
 */
object PipelineDefaults {

    // ---------------------------------------------------------------------------
    // Frame sampling
    // ---------------------------------------------------------------------------

    /**
     * Frames sampled per second of video.
     *
     * **FROZEN (Phase 5I §9.2).** Raised from the Phase-2 default of 4 FPS. The
     * Phase 5F density sweep (4 / 8 / 12 / 25 FPS) showed 8 FPS is where tracklet
     * continuity stops improving; higher rates only add cost. ≈240 timestamps for
     * a 30 s clip.
     */
    const val FRAME_SAMPLE_FPS: Float = 8f

    /** Back-compat alias for [FRAME_SAMPLE_FPS] (kept so older call sites compile). */
    const val FRAME_SAMPLING_FPS: Float = FRAME_SAMPLE_FPS

    /**
     * Longer edge (px) that a decoded frame is downscaled to before detection.
     *
     * Phase 2.5 evidence (Sample 1): at 720 px a genuine second face in the
     * 10.1–11.5 s overlap is only ~400 px wide (≈0.38 of the short edge) and is
     * missed unless [MLKIT_MIN_FACE_FRACTION] drops to 0.04, which also raises
     * detector noise. At 1080 px that face is resolved at the default 0.06 with
     * far fewer degenerate boxes. Cost: ~8.5 s vs ~2.7 s detect for a 30 s clip
     * — acceptable; correctness wins. Set to 0 to disable downscaling.
     */
    const val FRAME_DECODE_MAX_EDGE_PX: Int = 1080

    /**
     * Frame-seek strategy — see [com.example.ikyky.core.media.SeekOption].
     * CLOSEST keeps samples on the real timeline (CLOSEST_SYNC collapses many
     * timestamps onto the same keyframe on this clip). Stored as a string here
     * to keep this file free of the `android.media` import; the extractor maps
     * it.
     */
    const val FRAME_SEEK_OPTION_NAME: String = "CLOSEST"

    // ---------------------------------------------------------------------------
    // Face detection / observation filtering
    // ---------------------------------------------------------------------------

    /** Minimum face bounding-box size (px, longer edge, canonical coords) to keep an observation. */
    const val MIN_FACE_SIZE_PX: Int = 40

    /**
     * Reject a detection whose box is degenerate: an edge at/near zero or an
     * aspect ratio no real upright face has. Phase 2.5 saw ML Kit emit boxes
     * like `530x0` and `901x8` on hard frames.
     */
    const val MIN_FACE_EDGE_PX: Int = 24
    const val MAX_FACE_ASPECT_RATIO: Float = 2.5f

    /** ML Kit min face size as a fraction of the image's longer dimension. */
    const val MLKIT_MIN_FACE_FRACTION: Float = 0.06f

    // ---------------------------------------------------------------------------
    // Short-term tracking (tracklets)
    // ---------------------------------------------------------------------------

    /**
     * IoU above which two boxes in nearby frames are linked into the same
     * tracklet when ML Kit tracking ids are unavailable / unreliable.
     * Initial value only — to be tuned on Sample 1.
     */
    const val TRACKLET_IOU_THRESHOLD: Float = 0.3f

    /** Back-compat alias for [TRACKLET_IOU_THRESHOLD]. */
    const val TRACKLET_MIN_IOU: Float = TRACKLET_IOU_THRESHOLD

    /**
     * Fallback match also accepts a pair whose centre distance is within this
     * fraction of the mean box size, even if IoU is low (handles fast motion).
     */
    const val TRACKLET_CENTER_DIST_FRACTION: Float = 0.6f

    /** Face-size ratio must stay within [1/x, x] for a fallback match. */
    const val TRACKLET_SIZE_RATIO_TOLERANCE: Float = 2.0f

    /**
     * Maximum wall-clock gap between two consecutive detections of the same
     * tracklet. At 4 FPS, one *missed frame* shows up as a 500 ms gap
     * (detect @ t, nothing @ t+250, detect @ t+500), so 500 ms tolerates exactly
     * one missed frame and 750 ms (two missed frames) closes the tracklet.
     *
     * Rule #10 of the brief: "if a face disappears for one sampled frame, do NOT
     * terminate; if it disappears for several consecutive intervals, terminate."
     * Reduced from the Phase-2 value of 600 ms so a two-frame absence is a hard
     * appearance boundary. Person-vs-person boundaries that occur *without* a gap
     * are handled by [TRACKLET_BRIDGE_ACROSS_TRACKING_IDS] instead.
     */
    const val MAX_TRACK_GAP_MS: Long = 500L

    /**
     * When false (default), the spatial fallback will NOT link an observation
     * whose ML Kit tracking id differs from the tracklet's last known id — a
     * changed id is treated as a different face. Prefer a false split
     * (embeddings can re-merge) over a false merge (destroys the appearance
     * boundary). See rule #8 of the Phase 2.5 brief.
     */
    const val TRACKLET_BRIDGE_ACROSS_TRACKING_IDS: Boolean = false

    /** Back-compat: max missed *samples* (derived from [MAX_TRACK_GAP_MS] at 4 FPS). */
    const val TRACKLET_MAX_FRAME_GAP: Int = 1

    // ---------------------------------------------------------------------------
    // Appearance segmentation
    // ---------------------------------------------------------------------------

    /**
     * A tracklet must span at least this many observations to become an
     * appearance candidate (filters 1-frame detector noise / whip-pan blips).
     */
    const val MIN_APPEARANCE_OBSERVATIONS: Int = 2

    /**
     * An appearance candidate must last at least this long.
     *
     * **FROZEN to 0 (Phase 5I).** The Python reference's appearance gate is
     * `min_obs >= 2` ONLY (`min_duration_ms = 0`); duration is never a filter.
     * The old 300 ms value was calibrated at 4 FPS, where two observations span
     * 250 ms; at the frozen 8 FPS two adjacent observations span just 125 ms, so
     * keeping 300 ms would silently discard every genuine two-frame appearance
     * and diverge from the validated pipeline. The observation-count gate below
     * is what actually removes detector noise.
     */
    const val MIN_APPEARANCE_DURATION_MS: Long = 0L

    // ---------------------------------------------------------------------------
    // Lightweight quality signal (representative-frame scoring is a later phase)
    // ---------------------------------------------------------------------------

    /** Variance-of-Laplacian below this ⇒ the crop is considered blurred. */
    const val BLUR_VARIANCE_MIN: Double = 12.0

    /** Face longer-edge below this fraction of the frame ⇒ "too small" penalty. */
    const val QUALITY_MIN_FACE_FRACTION: Float = 0.05f

    // ---------------------------------------------------------------------------
    // Face embedding model (MobileFaceNet) — unchanged from Phase 1
    // ---------------------------------------------------------------------------

    const val EMBEDDING_INPUT_SIZE: Int = 112
    const val EMBEDDING_DIMENSION: Int = 192

    /**
     * Cosine-similarity threshold above which two appearance embeddings are
     * considered the same identity.
     *
     * **FROZEN at 0.475 (Phase 5I §9.2 / decision 6).** Selected by a rule fixed
     * in advance — zero must-not-link violations, then maximum mean pair-F1 across
     * all three samples, then stability, then the lower threshold — which never
     * consults the expected person count. It sits mid-plateau: [0.450, 0.575]
     * gives identical cluster membership on all three samples, so the exact value
     * is not delicate.
     *
     * Do NOT retune this per video. Phase 5I validated one threshold for all
     * samples; per-sample tuning was explicitly ruled out.
     */
    const val IDENTITY_MERGE_COSINE_THRESHOLD: Float = 0.475f

    // ---------------------------------------------------------------------------
    // Crops
    // ---------------------------------------------------------------------------

    /**
     * Presentation (collage) crop — generous margin around the detection box.
     *
     * **RETIRED as of Phase 8.1.** At 0.6 (60% expansion on every side) this
     * routinely pulled a neighboring person into the crop whenever two people
     * stood at typical conversational distance in frame — the exact bug Phase
     * 8.1 fixed. [com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper]
     * is the replacement; it uses [PRESENTATION_CROP_MARGIN_HORIZONTAL] /
     * [PRESENTATION_CROP_MARGIN_VERTICAL] instead. Kept only so
     * [com.example.ikyky.core.ml.preprocessing.SimilarityTransformFaceAligner.presentationCrop]
     * (currently unused in production — `includePresentationCrop = false` at its
     * one call site) still compiles; do not wire new callers to either.
     */
    const val PRESENTATION_CROP_MARGIN: Float = 0.6f

    /**
     * The FIXED, single-person-safe presentation crop margins (Phase 8.1).
     * Deliberately the same values already proven not to swallow a neighboring
     * face at typical framing —
     * [com.example.ikyky.core.ml.preprocessing.TrackerAppearanceCrop] has used
     * this same expansion in production since Phase 6 — but declared as a
     * SEPARATE constant so presentation framing can be tuned independently of
     * that frozen recognition-adjacent value without ever touching it.
     */
    const val PRESENTATION_CROP_MARGIN_HORIZONTAL: Float = 0.30f
    const val PRESENTATION_CROP_MARGIN_VERTICAL: Float = 0.40f

    // -------------------------------------------------------------------------
    // Phase 8.2 — representative-candidate quality gates
    //
    // These gate REPRESENTATIVE-IMAGE candidates only. They never touch
    // detection, tracking (Config K), MNL, embeddings, or the frozen 0.475
    // clustering threshold. A detection rejected here stays a fully valid
    // tracking/clustering observation; it is simply not eligible to be the
    // face shown for that person.
    // -------------------------------------------------------------------------

    /**
     * **C1 — oversized detection gate.** Reject a representative candidate whose
     * detection box longer edge exceeds this fraction of the frame's short edge.
     *
     * Evidence (Phase 8.2 audit, per-observation geometry over sample_1/2/3,
     * n≈694): `max(ff_w,ff_h)` has median **0.82** and p90 ≈ **1.1–1.2** — on
     * this close-up talking-head material ML Kit's boxes are *systematically*
     * large, so a gate anywhere near the median would reject most legitimate
     * frames and leave people with no representative. Only boxes **wider than
     * the frame itself** (`ff > 1.0`, p90–max range, physically impossible for a
     * real face) are unambiguously pathological. 1.05 gives a small tolerance
     * above exactly-frame-width. Normal large close-ups are instead handled by
     * the landmark-tight crop (which ignores the oversized box) and down-ranked
     * by the composite face-size term.
     */
    const val REP_MAX_FACE_FRACTION: Float = 1.05f

    /**
     * **C1 (area form).** Detection-box area over frame area. Audit p90 ≈
     * 0.55–0.61, max ≈ 0.79; a box covering **> 62 %** of the frame is
     * head + shoulders + background, not a face.
     */
    const val REP_MAX_FACE_AREA_FRACTION: Float = 0.62f

    /**
     * **C1 (minimum).** A representative face must occupy at least this fraction
     * of the frame short edge. Every legitimate close-up in the audit is
     * ff ≥ 0.37; a box at ff < 0.20 (e.g. audit `[690,644,770,726]`, ff 0.076)
     * is a distant / background face ML Kit fitted landmarks to — far too small
     * for a portrait. This is a *representative* gate, not a detection gate:
     * the small detection stays valid for tracking.
     */
    const val REP_MIN_FACE_FRACTION: Float = 0.20f

    /**
     * **C2 — corner false-positive gate.** A candidate is a corner artefact when
     * ALL of:
     *  - its box has ≥ [REP_CORNER_MIN_EDGES] sides within [REP_CORNER_EDGE_PX]
     *    of the frame border (pinned into a corner),
     *  - the box is small — longer edge < [REP_CORNER_MAX_FACE_FRACTION] of the
     *    frame short edge (a real corner-touching face on this material is
     *    large, ff ≳ 0.6; the audit's false positive `[0,0,490,518]` is
     *    ff ≈ 0.48),
     *  - its landmarks span less than [REP_MIN_LANDMARK_SPAN] of the box (a real
     *    face fills its box with landmarks; the false positive does not).
     */
    const val REP_CORNER_EDGE_PX: Int = 40
    const val REP_CORNER_MIN_EDGES: Int = 2
    const val REP_CORNER_MAX_FACE_FRACTION: Float = 0.55f
    const val REP_MIN_LANDMARK_SPAN: Float = 0.45f

    /**
     * **C2b — small-box-against-an-edge gate.** A representative candidate whose
     * box touches a frame border (within [REP_EDGE_TOUCH_PX]) AND is smaller
     * than [REP_EDGE_SMALL_MAX_FACE_FRACTION] of the frame short edge is a
     * partial / false-positive detection. Audit: legitimate small close-ups
     * (ff ≈ 0.37–0.40) sit fully inside the frame; every spurious edge box seen
     * (`[191,0,635,349]` ff 0.41, `[0,0,490,518]` ff 0.48, `[0,0,456,268]`
     * ff 0.42) both touches a border and is small. A genuinely large face near
     * an edge (ff ≳ 0.5) is unaffected.
     */
    const val REP_EDGE_TOUCH_PX: Int = 4
    const val REP_EDGE_SMALL_MAX_FACE_FRACTION: Float = 0.50f

    /**
     * **C3 — sharpness floor for a representative (conservative gate).**
     * Variance-of-Laplacian below this is rejected outright as unshowably soft.
     * Set close to the Phase-2 tracking floor (`BLUR_VARIANCE_MIN = 12`) rather
     * than to typical blur-detection practice (100–1000), because the
     * per-observation blur distribution on this material has not been
     * characterised and a high floor risks rejecting every frame of a person
     * (STOP condition). Sharpness does most of its work as a **ranking** signal
     * ([REP_BLUR_VARIANCE_IDEAL]); this gate only removes the clearly-worst.
     * `NaN` (unmeasured) never rejects.
     */
    const val REP_MIN_BLUR_VARIANCE: Double = 15.0

    /**
     * Variance-of-Laplacian at which the ranking sharpness term saturates to
     * 1.0. A frame at or above this is "sharp enough"; below it the term scales
     * linearly. Tunable once the distribution is measured.
     */
    const val REP_BLUR_VARIANCE_IDEAL: Double = 150.0

    /**
     * **C3 — pose ceiling for a representative.** Reject a candidate whose head
     * yaw or pitch magnitude (ML Kit Euler degrees) exceeds this. 32° keeps
     * natural three-quarter views, drops hard profiles / extreme up-down.
     */
    const val REP_MAX_HEAD_ANGLE_DEG: Float = 32f

    /**
     * **Hard one-person check.** A non-target face is "substantially present" in
     * the proposed crop when EITHER this fraction of its own box area lies
     * inside the crop, OR at least [REP_FOREIGN_FACE_MAX_PX] of its width AND
     * height do — then the candidate is INVALID. The pixel form catches the
     * "5 % of a large face box = a whole recognisable cheek + eye" case that a
     * pure area fraction misses (audit sample_1 person_3).
     */
    const val REP_FOREIGN_FACE_MAX_OVERLAP: Float = 0.15f
    const val REP_FOREIGN_FACE_MAX_PX: Int = 60

    /**
     * **Face-shape sanity for a representative candidate.** A real upright face
     * detection box has height/width roughly in [0.72, 2.1]. ML Kit sometimes
     * emits a short wide slab (e.g. audit `[0,0,456,268]`, ratio 0.59) that
     * carries a full landmark set but is not a usable face — reject it.
     */
    const val REP_MIN_BOX_ASPECT: Float = 0.72f
    const val REP_MAX_BOX_ASPECT: Float = 2.10f

    /**
     * **Zero / partial-face check.** The target face must occupy at least this
     * fraction of the final crop area, else the crop is a fragment (ear-only,
     * background) and the candidate is INVALID.
     */
    const val REP_TARGET_MIN_FRACTION_OF_CROP: Float = 0.12f

    /**
     * Legacy single-margin recognition value (kept for older call sites). The
     * recognition crop now uses the split horizontal/vertical margins below.
     */
    const val RECOGNITION_CROP_MARGIN: Float = 0.2f

    /**
     * Recognition-crop margins as a fraction of the detection box size, added on
     * each side before the crop is clamped to the canonical frame. A face needs
     * some forehead / chin / cheek context for a stable embedding, but the crop
     * must stay *focused* — this is NOT the collage crop. Slightly more vertical
     * context (hairline + jaw) than horizontal.
     */
    const val FACE_CROP_MARGIN_HORIZONTAL: Float = 0.30f
    const val FACE_CROP_MARGIN_VERTICAL: Float = 0.40f

    /**
     * A recognition crop whose clamped rectangle is smaller than this many px on
     * its shorter edge is considered unusable (face too close to a frame edge /
     * too small) — the observation is skipped rather than embedded from mush.
     */
    const val MIN_RECOGNITION_CROP_PX: Int = 24

    // ---------------------------------------------------------------------------
    // Embedding sampling & aggregation (Phase 3)
    // ---------------------------------------------------------------------------

    /**
     * Maximum embeddings computed per appearance candidate. An appearance can
     * hold dozens of observations; a handful of temporally-spread, good-quality
     * ones give a stable identity vector without embedding every frame.
     */
    const val MAX_EMBEDDINGS_PER_APPEARANCE: Int = 5

    /**
     * Minimum embeddings we try to keep for an appearance even if quality is
     * poor throughout — a legitimate short appearance must not vanish just
     * because every frame is slightly blurred.
     */
    const val MIN_EMBEDDINGS_PER_APPEARANCE: Int = 1

    /**
     * First-pass quality gate for choosing which observations to embed. Kept
     * deliberately low: it only *deprioritises* weak frames — if an appearance
     * has nothing above this line we still embed its best few (see
     * [MIN_EMBEDDINGS_PER_APPEARANCE]).
     */
    const val EMBEDDING_MIN_QUALITY: Float = 0.35f

    /**
     * When aggregating an appearance's embeddings, a member whose cosine
     * similarity to the provisional (plain-mean) centroid is below this is
     * treated as an outlier and dropped from the final centroid — as long as at
     * least [MIN_INLIERS_FOR_ROBUST_MEAN] members remain.
     */
    const val EMBEDDING_OUTLIER_COSINE_FLOOR: Float = 0.35f
    const val MIN_INLIERS_FOR_ROBUST_MEAN: Int = 2

    // ---------------------------------------------------------------------------
    // Phase 4 — intra-tracklet split / calibration / identity clustering
    // ---------------------------------------------------------------------------

    /**
     * Embedding-assisted intra-appearance split (1-D temporal scan). An
     * appearance is cut at the time index that maximises
     * `(mean within-half cosine) − (mean across-half cosine)`, but ONLY if that
     * separation reaches this margin. Below it, the drift is treated as pose /
     * lighting variation, not two identities. Deliberately conservative — a
     * false split is recoverable by clustering, a false merge is not.
     */
    const val INTRA_APPEARANCE_SPLIT_MIN_MARGIN: Float = 0.20f

    /**
     * Each side of an intra-appearance split must keep at least this many
     * embedded observations, otherwise the cut is rejected (too little evidence
     * on one side to trust the boundary).
     */
    const val INTRA_APPEARANCE_SPLIT_MIN_SIDE_EMBEDDINGS: Int = 2

    /** Max recursive split passes per original appearance (guards pathological input). */
    const val INTRA_APPEARANCE_SPLIT_MAX_DEPTH: Int = 3

    /**
     * Cosine-similarity threshold above which two appearance embeddings are
     * merged into the same identity by the agglomerative clusterer.
     *
     * PLACEHOLDER / FALLBACK. Phase 4 derives a threshold automatically from the
     * unsupervised pairwise-cosine gap statistic; this value is used only when
     * that distribution has no meaningful bimodal gap (calibration reported as
     * low-confidence). Keep configurable.
     */
    const val IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK: Float = 0.62f

    /** Number of bins for the pairwise-cosine histogram used in calibration. */
    const val CALIBRATION_HISTOGRAM_BINS: Int = 40

    /**
     * The valley the calibrator picks must be at least this much lower than the
     * smaller of the two surrounding peaks (as a fraction of that peak's height)
     * for the calibration to count as confident. Otherwise → fallback.
     */
    const val CALIBRATION_MIN_VALLEY_DEPTH_FRACTION: Float = 0.30f

    /** Calibrated threshold is clamped into this range regardless of the histogram. */
    const val CALIBRATION_THRESHOLD_MIN: Float = 0.40f
    const val CALIBRATION_THRESHOLD_MAX: Float = 0.85f

    /**
     * Two appearances get a hard must-not-link edge when they each contain an
     * observation in the same sampled frame (index within this tolerance) whose
     * face boxes overlap by no more than [MUST_NOT_LINK_MAX_IOU] — i.e. two
     * genuinely different faces visible at the same instant. A must-not-link
     * edge always overrides embedding similarity.
     */
    const val MUST_NOT_LINK_FRAME_TOLERANCE: Int = 0
    const val MUST_NOT_LINK_MAX_IOU: Float = 0.30f

    // ---------------------------------------------------------------------------
    // Phase 4.5 — dense temporal-split refinement for SUSPICIOUS appearances only
    // ---------------------------------------------------------------------------

    /**
     * An appearance is flagged for the (more expensive) dense analysis if ANY of:
     *  - its duration exceeds [DENSE_SUSPICIOUS_MIN_DURATION_MS];
     *  - its 5 global embeddings' mean pairwise cosine (compactness) is below
     *    [DENSE_SUSPICIOUS_COMPACTNESS_MAX];
     *  - the largest drop between consecutive global-embedding cosines exceeds
     *    [DENSE_SUSPICIOUS_DRIFT_MIN].
     * Duration is only a *trigger for analysis*, never a reason to split — there
     * is no MAX_APPEARANCE_DURATION.
     */
    const val DENSE_SUSPICIOUS_MIN_DURATION_MS: Long = 4_000L
    const val DENSE_SUSPICIOUS_COMPACTNESS_MAX: Float = 0.80f
    const val DENSE_SUSPICIOUS_DRIFT_MIN: Float = 0.20f

    /**
     * How many temporally-distributed observations to embed for a suspicious
     * appearance's dense analysis. This does NOT change
     * [MAX_EMBEDDINGS_PER_APPEARANCE] for ordinary appearances — the dense path
     * is a refinement only. Adaptive: roughly one sample per
     * [DENSE_ANALYSIS_SECONDS_PER_SAMPLE], clamped to
     * [[DENSE_ANALYSIS_MIN_SAMPLES], [DENSE_ANALYSIS_MAX_SAMPLES]].
     */
    const val DENSE_ANALYSIS_MIN_SAMPLES: Int = 8
    const val DENSE_ANALYSIS_MAX_SAMPLES: Int = 15
    const val DENSE_ANALYSIS_SECONDS_PER_SAMPLE: Float = 0.75f

    /**
     * Dense change-point acceptance. A cut is accepted only when BOTH sides are
     * far more internally coherent than the cross-window similarity —
     * `sideCompactness − crossSimilarity ≥ [DENSE_SPLIT_MIN_DROP]` for each side —
     * AND each side clears a low absolute sanity floor
     * [DENSE_SPLIT_MIN_SIDE_COMPACTNESS]. Gradual pose drift fails the margin
     * test (cross trails the sides only slightly); a sharp identity step passes.
     */
    const val DENSE_SPLIT_MIN_SIDE_COMPACTNESS: Float = 0.55f
    const val DENSE_SPLIT_MIN_DROP: Float = 0.20f

    /**
     * A window whose internal compactness is below this is treated as a *still-mixed*
     * multi-identity segment (not gradual drift): a cut that isolates a coherent
     * segment on the *other* side is accepted, and the recursion then splits this
     * side further. The "both sides only moderately coherent" band between this and
     * [DENSE_SPLIT_MIN_SIDE_COMPACTNESS] is gradual drift and never splits.
     */
    const val DENSE_SPLIT_SEVERELY_INCOHERENT: Float = 0.45f

    /** Each side of a dense cut must keep at least this many dense samples. */
    const val DENSE_SPLIT_MIN_SIDE_SAMPLES: Int = 3

    /** Max recursive dense-split passes per suspicious appearance (multiple boundaries allowed). */
    const val DENSE_SPLIT_MAX_DEPTH: Int = 4

    /**
     * Observations below this quality contribute at a reduced weight to
     * change-point scoring (never removed from the appearance's data).
     */
    const val DENSE_SPLIT_LOW_QUALITY: Float = 0.45f
    const val DENSE_SPLIT_LOW_QUALITY_WEIGHT: Float = 0.25f

    /**
     * Whip-pan / disappearance boundary: a wall-clock gap between consecutive
     * observations larger than this, with the bracketing observations blurred
     * (quality ≤ [WHIP_PAN_MAX_QUALITY]), closes the appearance at the gap —
     * regardless of whether ML Kit kept a tracking id across it. At 4 FPS one
     * missed frame is 500 ms, so 700 ms ⇒ ≥2 missed frames.
     */
    const val WHIP_PAN_GAP_MS: Long = 700L
    const val WHIP_PAN_MAX_QUALITY: Float = 0.35f

    // ===========================================================================
    // Phase 6 — FROZEN Phase 5I configuration (Option A / "Config K")
    // ===========================================================================
    //
    // Everything below is a PORT of the validated Python pipeline, not a tuning
    // surface. Values come from PHASE_5I_REPORT.md §9.2. Changing any of them
    // invalidates the Phase 5I validation.
    //
    // The two crops are deliberately distinct and must never be merged:
    //   TRACKER  gate crop  -> expanded box (FACE_CROP_MARGIN_*), see
    //                          TrackerAppearanceCrop
    //   RECOGNITION crop    -> arcface_5pt similarity transform, see
    //                          RecognitionFaceCrop / ArcFaceFivePointAligner
    // ---------------------------------------------------------------------------

    // -- shot / whip-pan detection (every decoded frame, no neural net) ---------

    /** Grayscale thumbnail longest edge for the MAD / histogram / edge signals. */
    const val SHOT_THUMB_EDGE_PX: Int = 64

    /** Larger thumbnail edge used only for the variance-of-Laplacian sharpness. */
    const val SHOT_SHARP_EDGE_PX: Int = 256

    /** Per-channel HSV histogram bins (H, S and V each get this many). */
    const val SHOT_HIST_BINS: Int = 32

    /** Equal weights: the three cheap signals contribute identically. */
    const val SHOT_W_MAD: Double = 1.0
    const val SHOT_W_HIST: Double = 1.0
    const val SHOT_W_EDGE: Double = 1.0

    /**
     * A candidate boundary must exceed `mean + z*std` of the whole score series
     * AND the absolute floor. The score is normalised so each raw signal's 99th
     * percentile is ~1.0: genuine cuts land 0.8–1.2, handheld motion stays below
     * ~0.45, so the histogram is cleanly bimodal and these are not delicate.
     */
    const val SHOT_Z_THRESHOLD: Double = 2.5
    const val SHOT_ABS_FLOOR: Double = 0.55

    /** A candidate must be a local maximum within ± this many frames. */
    const val SHOT_NMS_RADIUS: Int = 3

    /** Ignore candidates this close to the first / last frame (decode warm-up). */
    const val SHOT_EDGE_GUARD: Int = 2

    /**
     * Two spikes closer than this are the blur-in / blur-out bracket of ONE
     * whip-pan and are fused into a single transition span.
     */
    const val SHOT_PAIR_MAX_GAP_FRAMES: Int = 14

    /** A lone spike is a hard cut; pad it by this many frames on each side. */
    const val SHOT_HARD_CUT_PAD_FRAMES: Int = 1

    /**
     * A frame belongs to a transition when its sharpness falls below this
     * fraction of the video's median sharpness. Spans are widened across the
     * blurred run so the whole unusable stretch becomes a barrier.
     */
    const val SHOT_BLUR_RATIO: Double = 0.45

    // -- shot-aware tracking (replaces the Phase-2 greedy geometry gates) -------

    /**
     * Maximum wall-clock gap between consecutive observations of one track.
     * At the frozen 8 FPS one missed frame is 125 ms, so 400 ms tolerates three.
     */
    const val SHOT_AWARE_MAX_GAP_MS: Long = 400L

    /** Geometry gate: minimum IoU between the track's last box and a candidate. */
    const val SHOT_AWARE_MIN_IOU: Float = 0.20f

    /** Geometry gate: centre distance as a fraction of the FRAME DIAGONAL. */
    const val SHOT_AWARE_MAX_CENTER_DIST_FRACTION: Float = 0.18f

    /** Geometry gate: box-AREA ratio must stay within [1/x, x]. */
    const val SHOT_AWARE_MAX_SIZE_RATIO: Float = 2.2f

    /**
     * Embedding gate: reject an association whose cosine to the track's
     * representation is below this. The decision is made BEFORE the track's
     * appearance state is updated (anti-contamination — a rejected observation
     * must never move the representation).
     */
    const val SHOT_AWARE_APPEARANCE_MIN_COS: Float = 0.50f

    /** How many recently-ACCEPTED embeddings the representation is drawn from. */
    const val SHOT_AWARE_APPEARANCE_HISTORY: Int = 5

    /** Candidate scoring weights when several tracks compete for one detection. */
    const val SHOT_AWARE_W_IOU: Float = 1.0f
    const val SHOT_AWARE_W_APPEARANCE: Float = 0.5f
}
