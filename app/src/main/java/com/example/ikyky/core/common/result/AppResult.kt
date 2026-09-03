package com.example.ikyky.core.common.result

import com.example.ikyky.core.common.error.AppError

/**
 * A minimal, allocation-cheap result type used across domain boundaries.
 *
 * Domain and data layers return [AppResult] instead of throwing so that
 * failure handling is explicit and testable. Framework exceptions are mapped
 * to [AppError] at the edge (data sources / ML wrappers).
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

/** Wraps a throwing block, mapping any [Throwable] into an [AppError.Unexpected]. */
inline fun <T> runCatchingResult(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Unexpected(t.message ?: t::class.simpleName ?: "Unknown", t))
    }
