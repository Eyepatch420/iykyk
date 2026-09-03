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
     * Frames sampled per second of video. Initial default 4 FPS
     * (≈120 timestamps for a 30 s clip). 2 / 4 / 5 FPS will be benchmarked later.
     */
    const val FRAME_SAMPLE_FPS: Float = 4f

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

    /** An appearance candidate must last at least this long. */
    const val MIN_APPEARANCE_DURATION_MS: Long = 300L

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
     * considered the same identity. Placeholder — calibrated in Phase 3/4.
     */
    const val IDENTITY_MERGE_COSINE_THRESHOLD: Float = 0.62f

    // ---------------------------------------------------------------------------
    // Crops
    // ---------------------------------------------------------------------------

    /** Presentation (collage) crop — generous margin around the detection box. */
    const val PRESENTATION_CROP_MARGIN: Float = 0.6f

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
}
