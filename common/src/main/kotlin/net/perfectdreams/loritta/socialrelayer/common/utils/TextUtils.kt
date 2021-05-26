package net.perfectdreams.loritta.socialrelayer.common.utils

fun String.substringIfNeeded(range: IntRange = 0 until 2000, suffix: String = "..."): String {
    if (this.isEmpty()) {
        return this
    }

    if (this.length - 1 in range)
        return this

    // We have a Math.max to avoid issues when the string is waaaay too small, causing the range.last - suffix.length be negative
    return this.substring(range.start .. Math.max(0, range.last - suffix.length)) + suffix
}