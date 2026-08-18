package zone.clanker.srcx.atlas

/**
 * Read-only SQLite file walker for the Atlas store.
 *
 * Used by JVM tests and the Wasm seed reader so neither path depends on sql.js.
 */
class AtlasSqliteFileReader(
    private val bytes: ByteArray,
) {
    init {
        require(bytes.size >= HEADER_SIZE) { "atlas.sqlite is too small" }
        require(bytes.decodeToString(0, 15) == "SQLite format 3") { "not a SQLite database" }
    }

    private val pageSize: Int =
        when (val raw = fileU16(16)) {
            1 -> 65536
            else -> raw
        }
    private val reserved = bytes[20].toInt() and 0xFF
    private val usablePageSize: Int get() = pageSize - reserved

    fun readSeed(): AtlasStoreContents {
        val tables = sqliteMaster()
        val metaRows = tableRows(tables.getValue(AtlasStoreSchema.META_TABLE))
        require(metaRows.size == 1) { "meta must contain exactly one row" }
        val meta = metaRows.single()
        return AtlasStoreContents(
            meta =
                AtlasStoreMeta(
                    schemaVersion = meta.int("schema_version"),
                    generatedAt = meta.text("generated_at"),
                    workspace = meta.text("workspace"),
                    seedLimit = meta.int("seed_limit"),
                ),
            nodes =
                tableRows(tables.getValue(AtlasStoreSchema.NODES_TABLE))
                    .filter { row -> row.int("seed") == 1 }
                    .map { row ->
                        AtlasNodeRecord(
                            id = row.text("id"),
                            entity = row.text("entity"),
                            seed = row.int("seed"),
                            build = row.textOrEmpty("build"),
                            project = row.textOrEmpty("project"),
                            sourceSet = row.textOrEmpty("source_set"),
                            path = row.textOrEmpty("path"),
                            name = row.textOrEmpty("name"),
                            line = row.intOrZero("line"),
                            kind = row.textOrEmpty("kind"),
                            semantic = row.textOrEmpty("semantic"),
                            fileId = row.textOrEmpty("file_id"),
                            payload = row.blobOrEmpty("payload"),
                        )
                    },
            relationships =
                tableRows(tables.getValue(AtlasStoreSchema.RELATIONSHIPS_TABLE)).map { row ->
                    AtlasRelationshipRecord(
                        id = row.text("id"),
                        sourceId = row.text("source_id"),
                        targetId = row.text("target_id"),
                        kind = row.text("kind"),
                        family = row.text("family"),
                        recordCount = row.int("record_count"),
                        payload = row.blobOrEmpty("payload"),
                    )
                },
            imports =
                tableRows(tables.getValue(AtlasStoreSchema.IMPORTS_TABLE)).map { row ->
                    AtlasImportRecord(
                        sourceFileId = row.text("source_file_id"),
                        targetId = row.text("target_id"),
                        targetFileId = row.textOrEmpty("target_file_id"),
                        recordCount = row.int("record_count"),
                        payload = row.blobOrEmpty("payload"),
                    )
                },
        )
    }

    private fun sqliteMaster(): Map<String, MasterTable> {
        val rows = mutableListOf<MasterTable>()
        walkTable(1) { payload ->
            val record = Record(payload)
            if (record.text(0) != "table") return@walkTable
            rows +=
                MasterTable(
                    name = record.text(1),
                    columns = parseColumnNames(record.text(4)),
                    rootPage = record.int(3),
                )
        }
        return rows.associateBy { table -> table.name }
    }

    private fun tableRows(table: MasterTable): List<NamedRow> {
        val rows = mutableListOf<NamedRow>()
        walkTable(table.rootPage) { payload ->
            rows += NamedRow(table.columns, Record(payload))
        }
        return rows
    }

    private fun walkTable(
        pageNumber: Int,
        onLeaf: (ByteArray) -> Unit,
    ) {
        val page = page(pageNumber)
        val header = if (pageNumber == 1) HEADER_SIZE else 0
        val kind = page[header].toInt() and 0xFF
        val cellCount = u16(page, header + 3)
        when (kind) {
            PAGE_LEAF_TABLE -> {
                val pointers = (0 until cellCount).map { index -> u16(page, header + 8 + index * 2) }
                pointers.forEach { offset -> onLeaf(leafPayload(page, offset)) }
            }
            PAGE_INTERIOR_TABLE -> {
                val right = u32(page, header + 8)
                val pointers = (0 until cellCount).map { index -> u16(page, header + 12 + index * 2) }
                pointers.forEach { offset -> walkTable(u32(page, offset), onLeaf) }
                walkTable(right, onLeaf)
            }
            else -> error("unsupported sqlite page type $kind")
        }
    }

    private fun leafPayload(
        page: ByteArray,
        offset: Int,
    ): ByteArray {
        var cursor = offset
        val payloadSize = readVarint(page, cursor).also { cursor += it.second }.first.toInt()
        cursor += readVarint(page, cursor).second
        val local = localPayloadSize(payloadSize)
        val onPage = page.copyOfRange(cursor, cursor + local)
        if (local >= payloadSize) return onPage
        val overflow = u32(page, cursor + local)
        return onPage + overflowBytes(overflow, payloadSize - local)
    }

    private fun overflowBytes(
        firstPage: Int,
        remaining: Int,
    ): ByteArray {
        val out = ArrayList<Byte>(remaining)
        var pageNumber = firstPage
        var left = remaining
        while (left > 0 && pageNumber != 0) {
            val page = page(pageNumber)
            val next = u32(page, 0)
            val chunk = minOf(left, usablePageSize - 4)
            repeat(chunk) { index -> out += page[4 + index] }
            left -= chunk
            pageNumber = next
        }
        return out.toByteArray()
    }

    private fun localPayloadSize(payloadSize: Int): Int {
        val maxLocal = usablePageSize - 35
        if (payloadSize <= maxLocal) return payloadSize
        val minLocal = ((usablePageSize - 12) * 32 / 255) - 23
        val local = minLocal + (payloadSize - minLocal) % (usablePageSize - 4)
        return if (local <= maxLocal) local else minLocal
    }

    private fun page(number: Int): ByteArray {
        val start = (number - 1) * pageSize
        return bytes.copyOfRange(start, start + pageSize)
    }

    private fun fileU16(offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u16(
        page: ByteArray,
        offset: Int,
    ): Int = ((page[offset].toInt() and 0xFF) shl 8) or (page[offset + 1].toInt() and 0xFF)

    private fun u32(
        page: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        repeat(4) { index ->
            value = (value shl 8) or (page[offset + index].toInt() and 0xFF)
        }
        return value
    }

    private data class MasterTable(
        val name: String,
        val columns: List<String>,
        val rootPage: Int,
    )

    private class Record(
        payload: ByteArray,
    ) {
        private val values: List<Any?>

        init {
            var cursor = 0
            val header = readVarint(payload, cursor).also { cursor += it.second }
            val headerEnd = header.first.toInt()
            val serials = mutableListOf<Int>()
            while (cursor < headerEnd) {
                serials += readVarint(payload, cursor).also { next -> cursor += next.second }.first.toInt()
            }
            values =
                serials.map { serial ->
                    val decoded = decodeColumn(payload, cursor, serial)
                    cursor += decoded.second
                    decoded.first
                }
        }

        fun value(index: Int): Any? = values[index]

        fun text(index: Int): String = values[index] as String

        fun int(index: Int): Int =
            when (val value = values[index]) {
                is Long -> value.toInt()
                is Int -> value
                else -> error("column $index is not an integer")
            }

        private fun decodeColumn(
            payload: ByteArray,
            offset: Int,
            serial: Int,
        ): Pair<Any?, Int> =
            when (serial) {
                0 -> null to 0
                1 -> signed(payload, offset, 1) to 1
                2 -> signed(payload, offset, 2) to 2
                3 -> signed(payload, offset, 3) to 3
                4 -> signed(payload, offset, 4) to 4
                5 -> signed(payload, offset, 6) to 6
                6 -> signed(payload, offset, 8) to 8
                8 -> 0L to 0
                9 -> 1L to 0
                else ->
                    if (serial >= 12 && serial % 2 == 0) {
                        val size = (serial - 12) / 2
                        payload.copyOfRange(offset, offset + size) to size
                    } else if (serial >= 13 && serial % 2 == 1) {
                        val size = (serial - 13) / 2
                        payload.decodeToString(offset, offset + size) to size
                    } else {
                        error("unsupported serial type $serial")
                    }
            }

        private fun signed(
            payload: ByteArray,
            offset: Int,
            width: Int,
        ): Long {
            var value = payload[offset].toLong()
            repeat(width - 1) { index ->
                value = (value shl 8) or (payload[offset + 1 + index].toInt() and 0xFF).toLong()
            }
            return value
        }
    }

    private class NamedRow(
        private val columns: List<String>,
        private val record: Record,
    ) {
        fun text(name: String): String = record.text(index(name))

        fun textOrEmpty(name: String): String = record.value(index(name)) as? String ?: ""

        fun int(name: String): Int = record.int(index(name))

        fun intOrZero(name: String): Int =
            when (val value = record.value(index(name))) {
                is Long -> value.toInt()
                is Int -> value
                else -> 0
            }

        fun blobOrEmpty(name: String): ByteArray =
            when (val value = record.value(index(name))) {
                is ByteArray -> value
                else -> ByteArray(0)
            }

        private fun index(name: String): Int {
            val found = columns.indexOf(name)
            require(found >= 0) { "missing column $name" }
            return found
        }
    }

    companion object {
        private const val HEADER_SIZE = 100
        private const val PAGE_INTERIOR_TABLE = 5
        private const val PAGE_LEAF_TABLE = 13

        fun parseColumnNames(sql: String): List<String> {
            val open = sql.indexOf('(')
            val close = sql.lastIndexOf(')')
            require(open >= 0 && close > open) { "CREATE TABLE must list columns" }
            return sql
                .substring(open + 1, close)
                .split(',')
                .map { part -> part.trim().substringBefore(' ').trim('"', '`', '[', ']') }
                .filter { name ->
                    name.isNotEmpty() &&
                        name.uppercase() != "PRIMARY" &&
                        name.uppercase() != "CONSTRAINT"
                }
        }

        fun readVarint(
            page: ByteArray,
            offset: Int,
        ): Pair<Long, Int> {
            var value = 0L
            var consumed = 0
            while (consumed < 8) {
                val byte = page[offset + consumed].toInt() and 0xFF
                consumed += 1
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return value to consumed
            }
            val last = page[offset + consumed].toInt() and 0xFF
            consumed += 1
            value = (value shl 8) or last.toLong()
            return value to consumed
        }
    }
}
