package kr.ac.kangwon.hai.vibefactory

import kotlinx.coroutines.CancellationException

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

internal suspend inline fun <T> runSuspendCatching(
    crossinline block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
