package com.example.ikyky.features.collage.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.render.CollageRenderer
import com.example.ikyky.features.collage.domain.render.CollageTile
import com.example.ikyky.features.collage.domain.usecase.ChooseCollageImagesUseCase
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import kotlinx.coroutines.withContext

/**
 * Wires the collage feature end to end:
 *
 *   Person[] (from [peopleRepository])
 *     -> [chooseImages]           (Person -> one image URI, or a documented skip)
 *     -> [registry]                (person count -> a LayoutTemplate)
 *     -> decode each assigned URI  (one Bitmap per person, never re-decoded)
 *     -> [renderer]                (template + tiles -> one Bitmap)
 *
 * Every stage's failure mode is explicit (see the AppResult.Failure branches
 * below) rather than crashing or silently producing a blank/garbage bitmap.
 */
class DefaultGenerateCollageUseCase(
    private val context: Context,
    private val peopleRepository: PeopleResultRepository,
    private val chooseImages: ChooseCollageImagesUseCase,
    private val registry: CollageTemplateRegistry,
    private val renderer: CollageRenderer,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : GenerateCollageUseCase {

    override suspend fun invoke(
        sessionId: String,
        outputWidth: Int,
        outputHeight: Int,
        preferredStyle: LayoutStyle?,
    ): AppResult<Bitmap> {
        val people = peopleRepository.getPeople(sessionId)
        if (people.isEmpty()) {
            return AppResult.Failure(AppError.NoPeopleDetected())
        }

        val selection = chooseImages.choose(people)
        for (skipped in selection.skipped) {
            logger.w(TAG, "collage: skipping ${skipped.personId}: ${skipped.reason}")
        }
        if (selection.assignments.isEmpty()) {
            return AppResult.Failure(
                AppError.NoPeopleDetected("No usable representative images for this session"),
            )
        }

        val templates = registry.templatesFor(selection.assignments.size, outputWidth, outputHeight)
        val template: LayoutTemplate = (
            if (preferredStyle != null) templates.firstOrNull { it.style == preferredStyle } else null
            ) ?: templates.firstOrNull()
            ?: return AppResult.Failure(
                AppError.Unexpected(
                    "No collage template supports ${selection.assignments.size} people",
                ),
            )

        // One assignment per slot, in the template's own priority order so
        // higher-priority (HERO/PRIMARY) slots get filled first regardless of
        // which order people happened to be listed in.
        val orderedSlots = template.slots.indices.sortedByDescending { template.slots[it].priority }
        val orderedAssignments = selection.assignments.sortedByDescending { it.qualityScore }

        val tiles = ArrayList<CollageTile>(minOf(orderedSlots.size, orderedAssignments.size))
        val decodedBitmaps = ArrayList<Bitmap>(tiles.size)
        try {
            for (i in orderedAssignments.indices) {
                if (i >= orderedSlots.size) break // more people than this template's slots
                val assignment = orderedAssignments[i]
                val bitmap = decode(assignment.imageUri)
                if (bitmap == null) {
                    logger.w(TAG, "collage: could not decode image for ${assignment.personId}")
                    continue
                }
                decodedBitmaps += bitmap
                tiles += CollageTile(
                    personId = assignment.personId,
                    bitmap = bitmap,
                    slotIndex = orderedSlots[i],
                )
            }

            if (tiles.isEmpty()) {
                return AppResult.Failure(
                    AppError.Unexpected("None of the assigned representative images could be decoded"),
                )
            }

            return withContext(dispatchers.default) {
                renderer.render(template, tiles, outputWidth, outputHeight)
            }
        } finally {
            for (b in decodedBitmaps) if (!b.isRecycled) b.recycle()
        }
    }

    /** Decodes a small local (file://) representative-image Uri. Never throws. */
    private suspend fun decode(uriString: String): Bitmap? = withContext(dispatchers.io) {
        runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "GenerateCollage"
    }
}
