package com.example.ikyky.features.collage.domain.model

/**
 * A single placement rectangle within a [LayoutTemplate], expressed in
 * normalized [0,1] canvas coordinates so one template works at any output
 * resolution. Every visual property a renderer might need is described as data —
 * renderers never branch on person count.
 */
data class LayoutSlot(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val aspectPreference: Float? = null, // desired w/h of the photo inside the slot
    val zIndex: Int = 0,
    val rotationDegrees: Float = 0f,
    val allowOverlap: Boolean = false,
    val cornerRadius: Float = 0f,        // fraction of min(slot w,h)
    val cropMode: CropMode = CropMode.CENTER_CROP,
    val padding: Float = 0f,             // fraction of slot size
    val priority: Int = 0,              // higher = filled by higher-quality person first
    val role: SlotRole = SlotRole.SECONDARY,
)

enum class CropMode { CENTER_CROP, FIT, FACE_CENTERED }

enum class SlotRole { HERO, PRIMARY, SECONDARY }
