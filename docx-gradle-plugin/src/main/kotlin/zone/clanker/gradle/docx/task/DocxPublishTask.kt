package zone.clanker.gradle.docx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import zone.clanker.gradle.docx.Docx
import zone.clanker.report.model.WorkspaceMount
import zone.clanker.report.model.WorkspaceRegistrationRequest
import zone.clanker.report.model.WorkspaceServiceJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Registers an atomically generated static site with an optional standalone service. */
@DisableCachingByDefault(because = "Registration changes an explicitly configured external service")
abstract class DocxPublishTask : DefaultTask() {
    @get:InputDirectory
    abstract val siteDirectory: DirectoryProperty

    @get:Input
    abstract val workspaceId: Property<String>

    @get:Input
    abstract val serviceUrl: Property<String>

    @get:Internal
    abstract val serviceToken: Property<String>

    @get:Input
    abstract val endpointFile: Property<String>

    init {
        group = Docx.GROUP
        description = "Register the generated DOCX site with a standalone workspace service"
    }

    @TaskAction
    fun publishSite() {
        val connection = resolveConnection(serviceUrl.get(), serviceToken.get(), Path.of(endpointFile.get()))
        val mount =
            publish(
                service = connection.first,
                token = connection.second,
                workspace = workspaceId.get(),
                site = siteDirectory.get().asFile.toPath(),
            )
        logger.lifecycle("docx: registered ${mount.workspaceId} at ${connection.first.resolve(mount.viewerPath)}")
    }

    internal fun publish(
        service: URI,
        token: String,
        workspace: String,
        site: Path,
        client: HttpClient = HttpClient.newHttpClient(),
    ): WorkspaceMount {
        require(token.isNotBlank()) { "DOCX service token must not be blank" }
        val registration =
            WorkspaceRegistrationRequest(
                workspaceId = workspace,
                siteDirectory = site.toAbsolutePath().normalize().toString(),
            )
        val request =
            HttpRequest
                .newBuilder(service.resolve("api/v1/workspaces"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(WorkspaceServiceJson.encodeRegistration(registration)))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == OK || response.statusCode() == CREATED) {
            "DOCX service registration failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return WorkspaceServiceJson.decodeMount(response.body()).also { mount ->
            require(mount.workspaceId == workspace) { "DOCX service returned a different workspace registration" }
        }
    }

    internal fun resolveConnection(
        configuredUrl: String,
        configuredToken: String,
        endpointFile: Path,
    ): Pair<URI, String> {
        if (configuredUrl.isNotBlank()) {
            return normalizeServiceUri(configuredUrl) to configuredToken
        }
        require(Files.isRegularFile(endpointFile)) {
            "No running DOCX service was discovered at $endpointFile; configure docx.service.url or start docx-service"
        }
        val endpoint = WorkspaceServiceJson.decodeEndpoint(Files.readString(endpointFile))
        return normalizeServiceUri(endpoint.baseUrl) to configuredToken.ifBlank { endpoint.token }
    }

    private fun normalizeServiceUri(raw: String): URI {
        val parsed = URI(raw)
        require(parsed.scheme == "http" || parsed.scheme == "https") { "DOCX service URL must use HTTP or HTTPS" }
        require(parsed.host != null) { "DOCX service URL must include a host" }
        val path = parsed.path.orEmpty().let { current -> if (current.endsWith('/')) current else "$current/" }
        return URI(parsed.scheme, parsed.userInfo, parsed.host, parsed.port, path, null, null)
    }

    private companion object {
        const val OK: Int = 200
        const val CREATED: Int = 201
    }
}
