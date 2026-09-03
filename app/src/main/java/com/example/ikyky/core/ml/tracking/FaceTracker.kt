package com.example.ikyky.core.ml.tracking

/**
 * Groups per-frame [FaceObservation]s into short-term [Tracklet]s.
 *
 * Two APIs:
 *  - [buildTracklets] — batch: hand it every observation for the video.
 *  - streaming via [newSession] — feed one frame's observations at a time so the
 *    pipeline never holds every observation in memory at once.
 *
 * Linking strategy (see [GreedyFaceTracker]):
 *   1. ML Kit tracking id match (primary, cheap, reliable within a run)
 *   2. IoU / centre-distance / size-ratio fallback when the id is missing or
 *      changed unexpectedly
 *   3. a tracklet stays ACTIVE across a small time gap, then CLOSES
 *
 *   ML Kit tracking id  →  tracklet  →  appearance  →  (Phase 3) embedding  →  person
 */
interface FaceTracker {

    fun buildTracklets(observations: List<FaceObservation>): List<Tracklet>

    /** Start an incremental tracking session. */
    fun newSession(): Session

    interface Session {
        /**
         * Feed one sampled frame's observations (may be empty). Returns the
         * tracklets that CLOSED as a result of this frame (their trailing gap
         * exceeded the limit).
         */
        fun onFrame(frameTimestampMs: Long, observations: List<FaceObservation>): List<Tracklet>

        /** No more frames — closes and returns every still-active tracklet. */
        fun finish(): List<Tracklet>

        /** Total tracklets created so far (active + closed). */
        val trackletsCreated: Int
    }
}
