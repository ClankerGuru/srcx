package zone.clanker.docx.web.evidence

import zone.clanker.report.model.SourceRangeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceRangeProjectionTest {
    @Test
    fun preservesUtf16OffsetsAcrossCrLfAndSupplementaryCharacters() {
        val content = "class Emoji {\r\n  val icon = \"😀\"\r\n}\r\n"
        val start = content.indexOf("val")
        val end = content.indexOf("\r\n", start)

        assertEquals(
            listOf(SourceRangeLineSegment(line = 2, startColumn = 2, endColumnExclusive = 17)),
            sourceRangeLineSegments(content, SourceRangeSnapshot(start, end)),
        )
    }

    @Test
    fun projectsEveryNonEmptyLineOfAMultilineDeclaration() {
        val content = "fun call(\r\n  value: String,\r\n): String = value\r\n"

        assertEquals(
            listOf(
                SourceRangeLineSegment(1, 0, 9),
                SourceRangeLineSegment(2, 0, 16),
                SourceRangeLineSegment(3, 0, 17),
            ),
            sourceRangeLineSegments(
                content,
                SourceRangeSnapshot(0, content.indexOf("\r\n", content.indexOf("):"))),
            ),
        )
    }
}
