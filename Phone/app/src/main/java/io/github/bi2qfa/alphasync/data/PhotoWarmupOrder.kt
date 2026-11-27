package io.github.bi2qfa.alphasync.data

/** Directory timestamps may stay unchanged on camera firmware; use their newest photo. */
internal object PhotoWarmupOrder {
    data class Photo(val path: String, val time: Long)

    private fun folderDate(path: String, photoTime: Long): Long {
        val name = path.substringAfterLast('/')
        if (name.length != 8 || name.any { !it.isDigit() }) return photoTime
        val calendar = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = photoTime
        val anchorYear = calendar.get(java.util.Calendar.YEAR)
        var year = anchorYear / 10 * 10 + name[3].digitToInt()
        if (year - anchorYear > 5) year -= 10
        if (anchorYear - year > 5) year += 10
        return try {
            calendar.clear()
            calendar.isLenient = false
            calendar.set(year, name.substring(4, 6).toInt() - 1, name.substring(6, 8).toInt())
            calendar.timeInMillis
        } catch (_: IllegalArgumentException) { photoTime }
    }

    fun latestDirectory(photos: List<Photo>): String? = photos
        .filter { it.path.startsWith("/DCIM/") && it.path.substringAfter("/DCIM/").contains('/') }
        .groupBy { it.path.substringBeforeLast('/') }
        .maxWithOrNull(compareBy<Map.Entry<String, List<Photo>>> { group ->
            folderDate(group.key, group.value.maxOf { it.time })
        }.thenBy { group -> group.value.maxOf { it.time } }.thenBy { it.key })?.key

    fun order(photos: List<Photo>, latest: String?, viewed: String): List<Photo> =
        JpgPreviewOrder.order(photos.sortedWith(compareBy<Photo> {
            when (it.path.substringBeforeLast('/')) {
                latest -> 0
                viewed -> 1
                else -> 2
            }
        }.thenByDescending { it.time }.thenByDescending { it.path })) { it.path }
}
