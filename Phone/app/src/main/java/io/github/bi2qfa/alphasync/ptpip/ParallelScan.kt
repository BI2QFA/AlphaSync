package io.github.bi2qfa.alphasync.ptpip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll

/** Start all routes together; a useful fast result cancels and closes the remaining routes. */
internal suspend fun <T> scanParallel(
    routes: List<suspend () -> List<T>>,
    firstMatch: Boolean,
    repeatingRoutes: List<suspend () -> List<T>> = emptyList(),
    repeatIntervalMs: Long = 1_000,
): List<T> = coroutineScope {
    data class Result<T>(val items: List<T>, val repeatingIndex: Int? = null)
    val completed = Channel<Result<T>>(Channel.UNLIMITED)
    suspend fun attempt(route: suspend () -> List<T>): List<T> = try {
        route()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    val jobs = routes.map { route ->
        launch {
            completed.send(Result(attempt(route)))
        }
    } + repeatingRoutes.mapIndexed { index, route ->
        launch {
            while (isActive) {
                val started = System.nanoTime()
                completed.send(Result(attempt(route), index))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                delay((repeatIntervalMs - elapsedMs).coerceAtLeast(1))
            }
        }
    }
    val results = mutableListOf<T>()
    try {
        var finished = 0
        val checkedRepeaters = mutableSetOf<Int>()
        while (finished < routes.size || checkedRepeaters.size < repeatingRoutes.size) {
            val result = completed.receive()
            if (result.repeatingIndex == null) finished++ else checkedRepeaters.add(result.repeatingIndex)
            results.addAll(result.items)
            if (firstMatch && result.items.isNotEmpty()) break
        }
        results
    } finally {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        completed.close()
    }
}
