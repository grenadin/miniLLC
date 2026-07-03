package org.microg.locationtest

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/** The buggy/fixed LastLocationCapsule.getLocation() snippets, and a tiny GitHub-dark syntax highlighter. */
object CodeSnippets {
    const val BUGGY_CODE = """// LastLocationCapsule.kt (microg/GmsCore)
fun getLocation(
    effectiveGranularity: @Granularity Int
): Location? {
    val location = when (effectiveGranularity) {
        GRANULARITY_COARSE -> lastCoarseLocationTimeCoarsed
        GRANULARITY_FINE -> lastCoarseLocation
        else -> return null
    } ?: return null
    ...
}

// lastCoarseLocation is updated by BOTH gps and
// network fixes -> FINE granularity leaks
// network-level accuracy even with a fresh GPS fix."""

    const val FIXED_CODE = """// LastLocationCapsule.kt (proposed fix)
fun getLocation(
    effectiveGranularity: @Granularity Int
): Location? {
    val location = when (effectiveGranularity) {
        GRANULARITY_COARSE -> lastCoarseLocationTimeCoarsed
        GRANULARITY_FINE -> lastFineLocation
        else -> return null
    } ?: return null
    ...
}

// lastFineLocation is updated ONLY by GPS fixes
// via updateFineLocation() -> FINE granularity
// now correctly returns the GPS-only location."""

    /** Minimal Kotlin syntax highlighter, colored to match GitHub's dark theme. */
    fun highlightKotlin(code: String): SpannableStringBuilder {
        val keywordColor = Color.parseColor("#FF7B72")
        val commentColor = Color.parseColor("#8B949E")
        val annotationColor = Color.parseColor("#D2A8FF")
        val typeColor = Color.parseColor("#79C0FF")

        val keywords = setOf(
            "fun", "val", "var", "when", "else", "return", "private", "class",
            "companion", "object", "const", "override", "this", "import", "package"
        )

        val builder = SpannableStringBuilder(code)

        fun colorRegion(regex: Regex, color: Int, bold: Boolean = false) {
            for (match in regex.findAll(code)) {
                builder.setSpan(ForegroundColorSpan(color), match.range.first, match.range.last + 1, 0)
                if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), match.range.first, match.range.last + 1, 0)
            }
        }

        colorRegion(Regex("//[^\\n]*"), commentColor)
        colorRegion(Regex("@\\w+"), annotationColor)
        colorRegion(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), typeColor)
        for (keyword in keywords) {
            colorRegion(Regex("\\b$keyword\\b"), keywordColor, bold = true)
        }

        return builder
    }
}
