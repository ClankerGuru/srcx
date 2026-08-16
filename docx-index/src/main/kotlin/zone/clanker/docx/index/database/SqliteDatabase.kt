package zone.clanker.docx.index.database

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

internal class SqliteDatabase(
    databaseFile: Path,
) {
    private val jdbcUrl = "jdbc:sqlite:$databaseFile"

    fun initialize() {
        read { connection ->
            connection.executeSql("PRAGMA journal_mode = WAL")
            connection.executeSql("PRAGMA synchronous = NORMAL")
            workspaceIndexSchema.forEach(connection::executeSql)
        }
    }

    fun <T> read(block: (Connection) -> T): T = openConnection().use(block)

    fun <T> snapshot(block: (Connection) -> T): T =
        openConnection().use { connection ->
            connection.executeSql("PRAGMA query_only = ON")
            connection.autoCommit = false
            runCatching { block(connection) }
                .also { connection.rollback() }
                .getOrThrow()
        }

    fun <T> transaction(block: (Connection) -> T): T =
        openConnection().use { connection ->
            connection.autoCommit = false
            runCatching { block(connection) }
                .onSuccess { connection.commit() }
                .onFailure { connection.rollback() }
                .getOrThrow()
        }

    private fun openConnection(): Connection =
        DriverManager.getConnection(jdbcUrl).also { connection ->
            connection.executeSql("PRAGMA foreign_keys = ON")
            connection.executeSql("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
        }

    private companion object {
        const val BUSY_TIMEOUT_MILLIS: Int = 5_000
    }
}

internal fun Connection.executeSql(sql: String) {
    createStatement().use { statement -> statement.execute(sql) }
}

internal fun PreparedStatement.bind(values: List<Any?>) {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
}

internal fun Connection.update(
    sql: String,
    values: List<Any?> = emptyList(),
): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(values)
        statement.executeUpdate()
    }

internal fun <T> Connection.query(
    sql: String,
    values: List<Any?> = emptyList(),
    row: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(values)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(row(result))
            }
        }
    }

internal fun <T> Connection.queryOne(
    sql: String,
    values: List<Any?> = emptyList(),
    row: (ResultSet) -> T,
): T? = query(sql, values, row).singleOrNull()

internal inline fun <T> PreparedStatement.executeBatches(
    values: Iterable<T>,
    bindValue: PreparedStatement.(T) -> Unit,
) {
    var pending = 0
    values.forEach { value ->
        clearParameters()
        bindValue(value)
        addBatch()
        pending += 1
        if (pending == JDBC_BATCH_SIZE) {
            check(executeBatch().size == pending) { "SQLite batch result did not match its input size" }
            pending = 0
        }
    }
    if (pending > 0) {
        check(executeBatch().size == pending) { "SQLite batch result did not match its input size" }
    }
}

private const val JDBC_BATCH_SIZE: Int = 1_000
