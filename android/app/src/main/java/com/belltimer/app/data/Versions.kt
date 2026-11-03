package com.belltimer.app.data

/**
 * Compares two "major.minor.patch[-suffix]" versions. Missing parts count as
 * zero and a version with a suffix (a pre-release) sorts before the same
 * version without one. Text that is not a version sorts first.
 */
fun compareVersions(a: String, b: String): Int {
    val left = parseVersion(a)
    val right = parseVersion(b)
    if (left == null || right == null) return compareValues(left != null, right != null)
    for (i in 0 until 3) {
        val diff = left.numbers[i].compareTo(right.numbers[i])
        if (diff != 0) return diff
    }
    return when {
        left.suffix == right.suffix -> 0
        left.suffix == null -> 1
        right.suffix == null -> -1
        else -> left.suffix.compareTo(right.suffix)
    }
}

fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

/** True when [version] is at least [minimum]; an unreadable minimum is not enforced. */
fun meetsMinimum(version: String, minimum: String?): Boolean {
    if (minimum.isNullOrBlank() || parseVersion(minimum) == null) return true
    return compareVersions(version, minimum) >= 0
}

private class ParsedVersion(val numbers: List<Int>, val suffix: String?)

private val VERSION_PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+](.+))?$""")

private fun parseVersion(text: String): ParsedVersion? {
    val match = VERSION_PATTERN.matchEntire(text.trim()) ?: return null
    val (major, minor, patch, suffix) = match.destructured
    return ParsedVersion(
        numbers = listOf(major, minor, patch).map { it.toIntOrNull() ?: 0 },
        suffix = suffix.ifEmpty { null },
    )
}
