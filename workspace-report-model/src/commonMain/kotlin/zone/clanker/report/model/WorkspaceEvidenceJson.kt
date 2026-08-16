package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical JSON codec shared by static evidence artifacts and the live service. */
@OptIn(ExperimentalSerializationApi::class)
data object WorkspaceEvidenceJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    val encodeDeclarationRequest: (WorkspaceDeclarationEvidenceRequest) -> String = { request ->
        format.encodeToString(request)
    }

    val decodeDeclarationRequest: (String) -> WorkspaceDeclarationEvidenceRequest = format::decodeFromString

    val encodeDeclarationPage: (WorkspaceDeclarationEvidencePage) -> String = format::encodeToString

    val decodeDeclarationPage: (String) -> WorkspaceDeclarationEvidencePage = format::decodeFromString

    val encodeReverseUsageRequest: (WorkspaceReverseUsageRequest) -> String = format::encodeToString

    val decodeReverseUsageRequest: (String) -> WorkspaceReverseUsageRequest = format::decodeFromString

    val encodeReverseUsagePage: (WorkspaceReverseUsagePage) -> String = format::encodeToString

    val decodeReverseUsagePage: (String) -> WorkspaceReverseUsagePage = format::decodeFromString

    val encodeOccurrenceRequest: (WorkspaceRelationshipOccurrenceRequest) -> String = format::encodeToString

    val decodeOccurrenceRequest: (String) -> WorkspaceRelationshipOccurrenceRequest = format::decodeFromString

    val encodeOccurrencePage: (WorkspaceRelationshipOccurrencePage) -> String = format::encodeToString

    val decodeOccurrencePage: (String) -> WorkspaceRelationshipOccurrencePage = format::decodeFromString

    val encodeStaticCatalog: (WorkspaceStaticEvidenceCatalog) -> String = { catalog ->
        format.encodeToString(catalog) + "\n"
    }

    val decodeStaticCatalog: (String) -> WorkspaceStaticEvidenceCatalog = format::decodeFromString

    val encodeStaticPartition: (WorkspaceStaticEvidencePartition) -> String = { partition ->
        format.encodeToString(partition) + "\n"
    }

    val decodeStaticPartition: (String) -> WorkspaceStaticEvidencePartition = format::decodeFromString

    val encodeStaticPage: (WorkspaceStaticEvidencePage) -> String = { page ->
        format.encodeToString(page) + "\n"
    }

    val decodeStaticPage: (String) -> WorkspaceStaticEvidencePage = format::decodeFromString
}
