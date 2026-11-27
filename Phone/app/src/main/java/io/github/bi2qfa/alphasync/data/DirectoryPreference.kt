package io.github.bi2qfa.alphasync.data

internal object DirectoryPreference {
    fun normalize(path: String?): String {
        if (path == null || !path.startsWith('/') || '\u0000' in path) return "/"
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return "/"
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    fun containsFile(directory: String, path: String): Boolean =
        path.substringBeforeLast('/', "") == normalize(directory).let { if (it == "/") "" else it }
}
