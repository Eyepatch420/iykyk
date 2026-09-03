package com.example.ikyky.core.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over [Dispatchers] so the pipeline never hard-codes a thread pool
 * and tests can substitute a deterministic dispatcher.
 *
 * Assignment rule: video processing must never run on [main]. All pipeline
 * work is expected to run on [default] (CPU-bound: detection, embedding,
 * clustering, rendering) or [io] (decoding frames, MediaStore writes).
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

class StandardDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
}
