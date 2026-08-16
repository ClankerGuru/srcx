package zone.clanker.docx.index.importing

internal fun interface DecodeWindowObserver {
    fun observe(pendingCount: Int)
}
