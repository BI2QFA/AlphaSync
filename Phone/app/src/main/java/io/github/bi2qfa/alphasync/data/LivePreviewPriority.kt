package io.github.bi2qfa.alphasync.data

/** Used under the scheduler lock. Priority survives retries until the session resets. */
internal class LivePreviewPriority<T>(private val tokenOf: (T) -> String) {
    private val tokens = HashSet<String>()

    fun mark(items: List<T>) { items.forEach { tokens.add(tokenOf(it)) } }
    fun contains(item: T): Boolean = tokenOf(item) in tokens
    fun forget(item: T) { tokens.remove(tokenOf(item)) }
    fun clear() = tokens.clear()
    fun hasQueued(queued: Set<String>): Boolean = tokens.any { it in queued }

    fun promote(items: List<T>, queue: ArrayDeque<T>, queued: MutableSet<String>,
                cached: (T) -> Boolean) {
        val needed = items.filterNot(cached).distinctBy(tokenOf)
        val promoted = needed.mapTo(HashSet(), tokenOf)
        // Remove in one pass instead of scanning the backlog once per promoted file.
        queue.removeAll { tokenOf(it) in promoted }
        for (item in needed.asReversed()) {
            val token = tokenOf(item)
            queued.add(token)
            queue.addFirst(item)
        }
    }

    fun poll(queue: ArrayDeque<T>, queued: MutableSet<String>,
             prefer: ((T, Collection<T>) -> T?)? = null, cached: (T) -> Boolean): T? {
        // Most batches contain only old files. Avoid walking the whole backlog then.
        if (tokens.none { it in queued }) return null
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (!contains(item)) continue
            val preferred = prefer?.invoke(item, queue)
            if (preferred != null && contains(preferred) && !cached(preferred)) {
                queue.remove(preferred)
                queued.remove(tokenOf(preferred))
                return preferred
            }
            iterator.remove()
            queued.remove(tokenOf(item))
            if (!cached(item)) return item
        }
        return null
    }
}
