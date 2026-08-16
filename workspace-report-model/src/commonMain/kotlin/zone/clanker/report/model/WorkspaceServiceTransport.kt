package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Registration command accepted by a standalone DOCX workspace service. */
@Serializable
data class WorkspaceRegistrationRequest(
    val workspaceId: String,
    val siteDirectory: String,
)

/** Public status for one generated site. Absolute host paths are intentionally omitted. */
@Serializable
data class WorkspaceMount(
    val workspaceId: String,
    val viewerPath: String,
    val availability: WorkspaceAvailability,
    val generationId: String? = null,
    val workspaceName: String? = null,
    val siteState: WorkspaceSiteState? = null,
    val message: String,
    val observedAtEpochMilliseconds: Long,
    val index: WorkspaceIndexStatus = WorkspaceIndexStatus.Disabled,
)

@Serializable
enum class WorkspaceAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/** The index is an optional acceleration layer; static site availability never depends on it. */
@Serializable
sealed interface WorkspaceIndexStatus {
    @Serializable
    data object Disabled : WorkspaceIndexStatus

    @Serializable
    data object Pending : WorkspaceIndexStatus

    @Serializable
    data class Current(
        val generationId: String,
        val indexedAtEpochMilliseconds: Long,
    ) : WorkspaceIndexStatus

    @Serializable
    data class Failed(
        val generationId: String?,
        val message: String,
    ) : WorkspaceIndexStatus
}

@Serializable
data class WorkspaceCatalogResponse(
    val workspaces: List<WorkspaceMount>,
)

@Serializable
data class WorkspaceSymbolSearchResponse(
    val results: List<WorkspaceSymbolHit>,
)

@Serializable
data class WorkspaceSymbolHit(
    val symbolId: String,
    val scope: WorkspaceSymbolScope,
    val declaration: WorkspaceSymbolDeclaration,
)

@Serializable
data class WorkspaceSymbolScope(
    val buildId: String,
    val projectId: String,
    val sourceSetId: String,
    val sourceSetName: String,
    val fileId: String,
    val filePath: String,
)

@Serializable
data class WorkspaceSymbolDeclaration(
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    val kind: SymbolKind,
    val declarationLine: Int,
)

@Serializable
data class WorkspaceRelationshipSummaryResponse(
    val results: List<WorkspaceRelationshipCount>,
)

@Serializable
data class WorkspaceRelationshipCount(
    val kind: RelationshipKind,
    val recordCount: Int,
    val sourceSymbolCount: Int,
    val targetSymbolCount: Int,
    val heuristicCount: Int,
)

@Serializable
data class WorkspaceFindingSummaryResponse(
    val results: List<WorkspaceFindingCount>,
)

@Serializable
data class WorkspaceFindingCount(
    val severity: FindingSeverity,
    val findingCount: Int,
    val projectCount: Int,
    val fileCount: Int,
)

@Serializable
data class WorkspaceEvent(
    val sequence: Long,
    val kind: WorkspaceEventKind,
    val workspaceId: String,
    val workspace: WorkspaceMount? = null,
)

@Serializable
enum class WorkspaceEventKind {
    SNAPSHOT,
    REGISTERED,
    UPDATED,
    UNREGISTERED,
}

/** Written beside a registry so local clients can discover an ephemeral service port. */
@Serializable
data class WorkspaceServiceEndpoint(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val baseUrl: String,
    val token: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported DOCX service endpoint schema: $schemaVersion" }
        require(baseUrl.isNotBlank()) { "DOCX service base URL must not be blank" }
        require(token.isNotBlank()) { "DOCX service token must not be blank" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class WorkspaceServiceError(
    val message: String,
)

/** Canonical JSON boundary shared by the service, Gradle registration task, and future Wasm client. */
@OptIn(ExperimentalSerializationApi::class)
data object WorkspaceServiceJson {
    fun encodeRegistration(request: WorkspaceRegistrationRequest): String =
        workspaceServiceFormat.encodeToString(request)

    fun decodeRegistration(content: String): WorkspaceRegistrationRequest =
        workspaceServiceFormat.decodeFromString(content)

    fun encodeMount(mount: WorkspaceMount): String = workspaceServiceFormat.encodeToString(mount)

    fun decodeMount(content: String): WorkspaceMount = workspaceServiceFormat.decodeFromString(content)

    fun encodeCatalog(catalog: WorkspaceCatalogResponse): String = workspaceServiceFormat.encodeToString(catalog)

    fun decodeCatalog(content: String): WorkspaceCatalogResponse = workspaceServiceFormat.decodeFromString(content)

    fun encodeEvent(event: WorkspaceEvent): String = workspaceServiceFormat.encodeToString(event)

    fun decodeEvent(content: String): WorkspaceEvent = workspaceServiceFormat.decodeFromString(content)

    fun encodeEndpoint(endpoint: WorkspaceServiceEndpoint): String =
        workspaceServiceFormat.encodeToString(endpoint) + "\n"

    fun decodeEndpoint(content: String): WorkspaceServiceEndpoint = workspaceServiceFormat.decodeFromString(content)

    fun encodeError(error: WorkspaceServiceError): String = workspaceServiceFormat.encodeToString(error)
}

/** JSON boundary for bounded queries over an optional generated-site index. */
data object WorkspaceQueryJson {
    fun encodeSymbolSearch(response: WorkspaceSymbolSearchResponse): String =
        workspaceServiceFormat.encodeToString(response)

    fun decodeSymbolSearch(content: String): WorkspaceSymbolSearchResponse =
        workspaceServiceFormat.decodeFromString(content)

    fun encodeRelationshipSummary(response: WorkspaceRelationshipSummaryResponse): String =
        workspaceServiceFormat.encodeToString(response)

    fun decodeRelationshipSummary(content: String): WorkspaceRelationshipSummaryResponse =
        workspaceServiceFormat.decodeFromString(content)

    fun encodeFindingSummary(response: WorkspaceFindingSummaryResponse): String =
        workspaceServiceFormat.encodeToString(response)

    fun decodeFindingSummary(content: String): WorkspaceFindingSummaryResponse =
        workspaceServiceFormat.decodeFromString(content)
}

@OptIn(ExperimentalSerializationApi::class)
private val workspaceServiceFormat =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
