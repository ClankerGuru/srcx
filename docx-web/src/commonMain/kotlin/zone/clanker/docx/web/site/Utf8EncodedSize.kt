package zone.clanker.docx.web.site

/** Counts UTF-8 transport bytes without allocating another full copy of a large shard. */
internal fun String.utf8EncodedByteSize(): Long {
    var byteSize = 0L
    var index = 0
    while (index < length) {
        val codeUnit = this[index].code
        byteSize +=
            when {
                codeUnit <= SINGLE_BYTE_LIMIT -> SINGLE_BYTE_SIZE
                codeUnit <= DOUBLE_BYTE_LIMIT -> DOUBLE_BYTE_SIZE
                codeUnit.isLeadingSurrogateFor(this, index) -> {
                    index += 1
                    QUADRUPLE_BYTE_SIZE
                }
                else -> TRIPLE_BYTE_SIZE
            }
        index += 1
    }
    return byteSize
}

private fun Int.isLeadingSurrogateFor(
    text: String,
    index: Int,
): Boolean =
    this in HIGH_SURROGATE_RANGE &&
        index + 1 < text.length &&
        text[index + 1].code in LOW_SURROGATE_RANGE

private const val SINGLE_BYTE_LIMIT: Int = 0x7F
private const val DOUBLE_BYTE_LIMIT: Int = 0x7FF
private val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
private const val SINGLE_BYTE_SIZE: Long = 1
private const val DOUBLE_BYTE_SIZE: Long = 2
private const val TRIPLE_BYTE_SIZE: Long = 3
private const val QUADRUPLE_BYTE_SIZE: Long = 4
