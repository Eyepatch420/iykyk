package com.example.ikyky.features.people.domain.model

/**
 * A unique identity: one or more appearance instances grouped by embedding
 * clustering (Phase 4). Every appearance instance assigned to this person is
 * preserved in [appearanceIds] — the count is what the assignment reports.
 */
data class Person(
    val id: String,
    val label: String,
    val appearanceIds: List<String>,
    val representativeFrame: RepresentativeFrame?,
) {
    val appearanceCount: Int get() = appearanceIds.size
}

/**
 * Metadata for the single representative shot chosen per [Person]. The actual
 * cropped bitmap lives in an in-memory session holder, referenced by
 * [presentationCropKey]. Populated in a later phase (representative-frame
 * selection); Phase 4 leaves it null.
 */
data class RepresentativeFrame(
    val personId: String,
    val sourceObservationId: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val presentationCropKey: String,
    val qualityScore: Float,
)
