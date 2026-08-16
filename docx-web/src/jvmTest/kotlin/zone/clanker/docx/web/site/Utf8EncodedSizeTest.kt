package zone.clanker.docx.web.site

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8EncodedSizeTest {
    @Test
    fun countsAsciiAndMultibyteTextWithoutMaterializingTransportBytes() {
        val text = "Atlas / café / 🌍"

        assertEquals(text.encodeToByteArray().size.toLong(), text.utf8EncodedByteSize())
    }
}
