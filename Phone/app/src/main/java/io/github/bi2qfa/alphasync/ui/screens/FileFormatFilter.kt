package io.github.bi2qfa.alphasync.ui.screens

/** Folders remain navigable; filtering never changes the underlying page cursor. */
enum class FileFormatFilter(val label: String) {
    All("全部"), Jpg("JPG"), Arw("ARW");

    fun accepts(name: String, isDirectory: Boolean): Boolean {
        if (isDirectory || this == All) return true
        val extension = name.substringAfterLast('.', "")
        return when (this) {
            All -> true
            Jpg -> extension.equals("jpg", true) || extension.equals("jpeg", true)
            Arw -> extension.equals("arw", true)
        }
    }
}
