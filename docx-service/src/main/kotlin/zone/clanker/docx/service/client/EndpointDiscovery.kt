package zone.clanker.docx.service.client

import zone.clanker.report.model.WorkspaceServiceJson
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class DiscoveredEndpoint(
    val baseUrl: URI,
    val token: String,
)

internal data object EndpointDiscovery {
    fun read(path: Path): DiscoveredEndpoint {
        require(Files.isRegularFile(path)) { "DOCX service endpoint file does not exist: $path" }
        val endpoint = WorkspaceServiceJson.decodeEndpoint(Files.readString(path))
        return DiscoveredEndpoint(URI(endpoint.baseUrl), endpoint.token)
    }
}
