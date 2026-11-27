package org.openup.core.archive

/** A single entry of a tar archive. [name] never starts with "/" and uses "/" separators. */
data class TarEntry(
    val name: String,
    val type: Type,
    val size: Long = 0,
    val mode: Int = if (type == Type.DIRECTORY) 0x1ED /* 0755 */ else 0x1A4 /* 0644 */,
    val mtimeSeconds: Long = 0,
    val linkTarget: String = "",
) {
    enum class Type { FILE, DIRECTORY, SYMLINK, OTHER }

    val isFile get() = type == Type.FILE
    val isDirectory get() = type == Type.DIRECTORY
    val isSymlink get() = type == Type.SYMLINK
}
