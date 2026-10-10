package com.ara.core.common

/**
 * Result type used across the app so the domain layer stays free of
 * library-specific error types.
 */
sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (AppError) -> Unit): AppResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value
}

data class AppError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
) {
    companion object {
        fun network(t: Throwable) = AppError("network", t.message ?: "Network error", t)
        fun crypto(t: Throwable) = AppError("crypto", t.message ?: "Encryption error", t)
        fun storage(t: Throwable) = AppError("storage", t.message ?: "Storage error", t)
        fun unexpected(t: Throwable) = AppError("unexpected", t.message ?: "Unexpected error", t)
    }
}

inline fun <T> runCatchingResult(map: (Throwable) -> AppError, block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        AppResult.Failure(map(t))
    }
