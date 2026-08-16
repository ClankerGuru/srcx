@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.docx.site

import zone.clanker.gradle.docx.DocxAnalysisPlan
import zone.clanker.gradle.docx.DocxAnalysisPlanJson
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.SourceContentReference
import zone.clanker.report.model.WorkspaceSearchCatalogReference
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSiteStatus
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.io.ByteArrayInputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class DocxSiteInstaller {
    fun install(
        snapshotBytes: ByteArray,
        distributionBytes: ByteArray,
        plan: DocxAnalysisPlan,
        outputDirectory: Path,
        lockFile: Path,
    ): DocxSiteInstallResult {
        Files.createDirectories(lockFile.parent)
        return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                runCatching {
                    installLocked(snapshotBytes, distributionBytes, plan, outputDirectory)
                }.onFailure { error ->
                    if (canWriteFailureStatus(outputDirectory)) {
                        markFailedLocked(outputDirectory, error.message ?: error.javaClass.simpleName)
                    }
                }.getOrThrow()
            }
        }
    }

    fun markFailed(
        outputDirectory: Path,
        lockFile: Path,
        message: String,
    ) {
        Files.createDirectories(lockFile.parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { markFailedLocked(outputDirectory, message) }
        }
    }

    private fun markFailedLocked(
        outputDirectory: Path,
        message: String,
    ) {
        requireReplaceableDirectory(outputDirectory, "DOCX output directory")
        claimOwnership(outputDirectory)
        val manifest =
            outputDirectory
                .resolve(MANIFEST_FILE)
                .takeIf { file -> Files.isRegularFile(file) }
                ?.let { file -> runCatching { WorkspaceSiteJson.decodeManifest(Files.readString(file)) }.getOrNull() }
        val status =
            WorkspaceSiteStatus(
                state = WorkspaceSiteState.FAILED,
                generationId = manifest?.generationId,
                message = "DOCX generation failed; the last valid report was preserved. $message",
            )
        Files.createDirectories(outputDirectory)
        writeText(outputDirectory.resolve(STATUS_FILE), WorkspaceSiteJson.encodeStatus(status))
    }

    private fun installLocked(
        snapshotBytes: ByteArray,
        distributionBytes: ByteArray,
        plan: DocxAnalysisPlan,
        outputDirectory: Path,
    ): DocxSiteInstallResult {
        val staging = outputDirectory.resolveSibling("${outputDirectory.fileName}.staging")
        val rollback = outputDirectory.resolveSibling("${outputDirectory.fileName}.rollback")
        recoverInterruptedPublication(outputDirectory, staging, rollback)
        val snapshot = WorkspaceSnapshotJson.decode(snapshotBytes.decodeToString())
        val projection = DocxSiteProjection.apply(snapshot, plan)
        val planBytes = DocxAnalysisPlanJson.encode(plan).encodeToByteArray()
        val generationId = contentHash(SITE_PROJECTION_VERSION_BYTES, snapshotBytes, distributionBytes, planBytes)
        currentManifest(outputDirectory)
            ?.takeIf { it.generationId == generationId && validCurrentGeneration(outputDirectory, it) }
            ?.let { return DocxSiteInstallResult(it, projection.warnings) }

        deleteReplaceableTree(staging, "DOCX staging directory")
        deleteReplaceableTree(rollback, "DOCX rollback directory")
        return runCatching {
            Files.createDirectories(staging)
            claimOwnership(staging)
            val installedFiles = extractDistribution(distributionBytes, staging)
            writeText(staging.resolve(".gitignore"), "*\n!.gitignore\n")
            replaceBundledData(staging)
            val manifest = writeData(snapshot, projection, generationId, installedFiles, staging)
            retainPrevious(outputDirectory, staging)
            verify(staging, manifest)
            publish(staging, outputDirectory, rollback)
            DocxSiteInstallResult(manifest, projection.warnings)
        }.onFailure {
            restoreRollback(outputDirectory, rollback)
            deleteReplaceableTree(staging, "DOCX staging directory")
        }.getOrThrow()
    }

    private fun writeData(
        snapshot: WorkspaceSnapshot,
        projection: ProjectedDocxSite,
        generationId: String,
        installedFiles: List<String>,
        staging: Path,
    ): WorkspaceSiteManifest {
        val sourceContents = DocxSourceContentWriter().write(staging, projection.sourceContents)
        val shards =
            projection.projectGraphs.map { shard ->
                shard.copy(sourceContents = sourceContents[shard.projectId].orEmpty())
            }
        val references =
            shards
                .map { shard ->
                    val shardHash = contentHash(shard.projectId.encodeToByteArray()).take(SHARD_HASH_SIZE)
                    val file = "data/shards/project-$shardHash.json"
                    writeText(staging.resolve(file), WorkspaceSiteJson.encodeProject(shard))
                    ProjectShardReference(
                        projectId = shard.projectId,
                        file = file,
                        sourceContents = shard.sourceContents,
                    )
                }.sortedBy(ProjectShardReference::projectId)
        val workspace = projection.summary(references)
        writeText(staging.resolve(WORKSPACE_FILE), WorkspaceSiteJson.encodeWorkspace(workspace))
        val dashboard = WorkspaceDashboardProjection.apply(snapshot, projection)
        writeText(staging.resolve(DASHBOARD_FILE), WorkspaceSiteJson.encodeDashboard(dashboard))
        val atlasOverviews = WorkspaceAtlasOverviewsProjection.apply(snapshot, projection)
        val atlasOverview = atlasOverviews.frame(AtlasLens.FILES)
        writeText(staging.resolve(ATLAS_OVERVIEW_FILE), WorkspaceSiteJson.encodeAtlasFrame(atlasOverview))
        writeText(staging.resolve(ATLAS_OVERVIEWS_FILE), WorkspaceSiteJson.encodeAtlasOverviews(atlasOverviews))
        val searchCatalog = DocxStaticSearchWriter().write(staging, generationId, projection)
        val evidenceCatalog = DocxStaticEvidenceWriter().write(staging, generationId, projection)
        val manifest =
            WorkspaceSiteManifest(
                generationId = generationId,
                snapshotSchemaVersion = snapshot.schemaVersion,
                workspaceFile = WORKSPACE_FILE,
                dashboardFile = DASHBOARD_FILE,
                atlasOverviewFile = ATLAS_OVERVIEW_FILE,
                atlasOverviewsFile = ATLAS_OVERVIEWS_FILE,
                projectShards = references,
                assetFiles = installedFiles.filter { it.startsWith("assets/") }.sorted(),
                searchCatalog = searchCatalog,
                evidenceCatalog = evidenceCatalog,
            )
        writeText(staging.resolve(MANIFEST_FILE), WorkspaceSiteJson.encodeManifest(manifest))
        writeText(
            staging.resolve(STATUS_FILE),
            WorkspaceSiteJson.encodeStatus(
                WorkspaceSiteStatus(
                    state = WorkspaceSiteState.CURRENT,
                    generationId = generationId,
                    workspaceName = snapshot.workspace.name,
                    message = "The static DOCX report is current.",
                ),
            ),
        )
        return manifest
    }

    private fun recoverInterruptedPublication(
        outputDirectory: Path,
        staging: Path,
        rollback: Path,
    ) {
        requireReplaceableDirectory(outputDirectory, "DOCX output directory")
        requireReplaceableDirectory(staging, "DOCX staging directory")
        requireReplaceableDirectory(rollback, "DOCX rollback directory")
        deleteReplaceableTree(staging, "DOCX staging directory")
        if (!Files.exists(rollback)) return
        if (!Files.exists(outputDirectory)) {
            move(rollback, outputDirectory)
            return
        }
        val outputIsValid = currentManifest(outputDirectory)?.let { validPublishedSite(outputDirectory, it) } == true
        val rollbackIsValid = currentManifest(rollback)?.let { validPublishedSite(rollback, it) } == true
        when {
            outputIsValid -> deleteReplaceableTree(rollback, "DOCX rollback directory")
            rollbackIsValid -> {
                deleteReplaceableTree(outputDirectory, "DOCX output directory")
                move(rollback, outputDirectory)
            }
            else -> deleteReplaceableTree(rollback, "DOCX rollback directory")
        }
    }

    @Suppress("NestedBlockDepth")
    private fun extractDistribution(
        bytes: ByteArray,
        destination: Path,
    ): List<String> {
        val files = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var extractedBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence(zip::getNextEntry).forEach { entry ->
                require(seen.size < MAX_ZIP_ENTRIES) { "DOCX viewer distribution has too many entries" }
                val name = normalizedEntryName(entry.name)
                require(seen.add(name)) { "DOCX viewer distribution contains duplicate entry: $name" }
                val target = destination.resolve(name).normalize()
                require(target.startsWith(destination)) { "DOCX viewer entry escapes the site: $name" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output ->
                        val buffer = ByteArray(ZIP_BUFFER_SIZE)
                        generateSequence { zip.read(buffer).takeIf { it >= 0 } }.forEach { count ->
                            extractedBytes += count
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "DOCX viewer distribution is too large"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    files += name
                }
                zip.closeEntry()
            }
        }
        require(files.count { it == INDEX_FILE } == 1) { "DOCX viewer distribution must contain one root index.html" }
        require(files.filter { it != INDEX_FILE }.all { it.startsWith("assets/") || it.startsWith("data/") }) {
            "DOCX viewer runtime files must live under assets/"
        }
        require(files.any { it.endsWith(".wasm") }) { "DOCX viewer distribution must contain a Wasm runtime" }
        require(files.any { it.endsWith(".js") || it.endsWith(".mjs") }) {
            "DOCX viewer distribution must contain a JavaScript bootstrap"
        }
        return files.sorted()
    }

    private fun replaceBundledData(staging: Path) {
        deleteGeneratedTree(staging.resolve("data"))
        Files.createDirectories(staging.resolve("data/shards"))
        Files.createDirectories(staging.resolve("data/sources"))
        Files.createDirectories(staging.resolve("data/search/shards"))
    }

    private fun retainPrevious(
        outputDirectory: Path,
        staging: Path,
    ) {
        if (!Files.isDirectory(outputDirectory) || currentManifest(outputDirectory) == null) return
        val previous = staging.resolve("generations/previous")
        Files.walk(outputDirectory).use { paths ->
            paths
                .filter { source -> source != outputDirectory }
                .filter { source -> outputDirectory.relativize(source).getName(0).toString() != "generations" }
                .sorted(Comparator.naturalOrder())
                .forEach { source -> copyPreviousEntry(outputDirectory, previous, source) }
        }
    }

    private fun copyPreviousEntry(
        outputDirectory: Path,
        previous: Path,
        source: Path,
    ) {
        val target = previous.resolve(outputDirectory.relativize(source).toString())
        if (Files.isDirectory(source)) {
            Files.createDirectories(target)
        } else {
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun verify(
        staging: Path,
        manifest: WorkspaceSiteManifest,
    ) {
        require(Files.isRegularFile(staging.resolve(INDEX_FILE))) { "DOCX viewer index is missing" }
        manifest.assetFiles.forEach { asset ->
            require(Files.isRegularFile(staging.resolve(asset))) { "Missing viewer asset: $asset" }
        }
        require(manifest.assetFiles.any { it.endsWith(".wasm") }) { "DOCX manifest has no Wasm asset" }
        require(manifest.assetFiles.any { it.endsWith(".js") || it.endsWith(".mjs") }) {
            "DOCX manifest has no JavaScript bootstrap"
        }
        WorkspaceSiteJson.decodeManifest(Files.readString(staging.resolve(MANIFEST_FILE)))
        val workspace = WorkspaceSiteJson.decodeWorkspace(Files.readString(staging.resolve(manifest.workspaceFile)))
        val dashboard = WorkspaceSiteJson.decodeDashboard(Files.readString(staging.resolve(manifest.dashboardFile)))
        require(dashboard.workspaceId == workspace.workspace.id) {
            "DOCX workspace dashboard does not belong to its workspace summary"
        }
        require(dashboard.builds.map { build -> build.buildId } == workspace.builds.map { build -> build.id }) {
            "DOCX workspace dashboard and summary build catalogs disagree"
        }
        require(
            dashboard.projects.associate { project -> project.projectId to project.buildId } ==
                workspace.projects.associate { project -> project.id to project.buildId },
        ) {
            "DOCX workspace dashboard and summary project ownership catalogs disagree"
        }
        verifyAtlasData(staging, manifest, workspace.workspace.id)
        manifest.projectShards.forEach { reference ->
            val shard = WorkspaceSiteJson.decodeProject(Files.readString(staging.resolve(reference.file)))
            require(shard.projectId == reference.projectId) {
                "DOCX project shard identity does not match its manifest"
            }
            require(shard.sourceContents == reference.sourceContents) {
                "DOCX project source contents do not match their manifest"
            }
            reference.sourceContents.forEach { source -> verifySourceContent(staging, source) }
        }
        manifest.searchCatalog?.let { reference ->
            verifySearchCatalog(staging, manifest, workspace.workspace.id, reference)
        }
        manifest.evidenceCatalog?.let { reference ->
            DocxStaticEvidenceVerifier.verify(staging, manifest, workspace.workspace.id, reference)
        }
        val status = WorkspaceSiteJson.decodeStatus(Files.readString(staging.resolve(STATUS_FILE)))
        require(status.generationId == null || status.generationId == manifest.generationId) {
            "DOCX status generation does not match its manifest"
        }
        verifyIndexReferences(staging)
    }

    private fun verifyAtlasData(
        staging: Path,
        manifest: WorkspaceSiteManifest,
        workspaceId: String,
    ) {
        manifest.atlasOverviewFile?.let { file ->
            val overview = WorkspaceSiteJson.decodeAtlasFrame(Files.readString(staging.resolve(file)))
            require(overview.scope.workspaceId == workspaceId) {
                "DOCX Atlas overview does not belong to its workspace summary"
            }
        }
        manifest.atlasOverviewsFile?.let { file ->
            val overviews = WorkspaceSiteJson.decodeAtlasOverviews(Files.readString(staging.resolve(file)))
            require(overviews.frames.all { frame -> frame.scope.workspaceId == workspaceId }) {
                "DOCX Atlas lens overviews do not belong to its workspace summary"
            }
            require(
                manifest.atlasOverviewFile == null ||
                    overviews.frame(AtlasLens.FILES) ==
                    WorkspaceSiteJson.decodeAtlasFrame(
                        Files.readString(staging.resolve(requireNotNull(manifest.atlasOverviewFile))),
                    ),
            ) { "DOCX Atlas file overview must agree with its lens bundle" }
        }
    }

    private fun verifyIndexReferences(staging: Path) {
        val index = Files.readString(staging.resolve(INDEX_FILE))
        INDEX_RESOURCE_REGEX.findAll(index).map { it.groupValues[1] }.forEach { rawReference ->
            val external = rawReference.startsWith("#") || "://" in rawReference || rawReference.startsWith("data:")
            if (external) return@forEach
            val reference = rawReference.substringBefore('?').substringBefore('#').removePrefix("/")
            if (reference.isNotBlank()) {
                val normalized = normalizedEntryName(reference)
                require(Files.isRegularFile(staging.resolve(normalized))) {
                    "DOCX index references a missing local asset: $rawReference"
                }
            }
        }
    }

    private fun verifySourceContent(
        staging: Path,
        reference: SourceContentReference,
    ) {
        val sourceFile = staging.resolve(reference.file)
        require(Files.isRegularFile(sourceFile)) { "Missing source content: ${reference.file}" }
        val content = Files.readAllBytes(sourceFile)
        require(content.size.toLong() == reference.encodedByteSize) {
            "Source content byte size does not match its reference: ${reference.file}"
        }
        require(sourceContentHash(content) == reference.contentHash) {
            "Source content hash does not match its reference: ${reference.file}"
        }
    }

    private fun verifySearchCatalog(
        staging: Path,
        manifest: WorkspaceSiteManifest,
        workspaceId: String,
        reference: WorkspaceSearchCatalogReference,
    ) {
        val encoded = verifiedSearchPayload(staging, reference.file, reference.contentHash, reference.encodedByteSize)
        val catalog = WorkspaceSearchJson.decodeCatalog(encoded)
        require(catalog.generationId == manifest.generationId) {
            "Workspace search catalog generation does not match its manifest"
        }
        require(catalog.workspaceId == workspaceId) {
            "Workspace search catalog does not belong to its workspace summary"
        }
        catalog.shards.forEach { shardReference ->
            val shardEncoded =
                verifiedSearchPayload(
                    staging,
                    shardReference.file,
                    shardReference.contentHash,
                    shardReference.encodedByteSize,
                )
            val shard = WorkspaceSearchJson.decodeShard(shardEncoded)
            require(shard.prefix == shardReference.prefix && shard.pageIndex == shardReference.pageIndex) {
                "Workspace search shard identity does not match its catalog entry"
            }
            require(shard.entries.size == shardReference.entryCount) {
                "Workspace search shard count does not match its catalog entry"
            }
        }
    }

    private fun verifiedSearchPayload(
        staging: Path,
        file: String,
        expectedHash: String,
        expectedByteSize: Long,
    ): String {
        val sourceFile = staging.resolve(file)
        require(Files.isRegularFile(sourceFile)) { "Missing workspace search payload: $file" }
        val content = Files.readAllBytes(sourceFile)
        require(content.size.toLong() == expectedByteSize) {
            "Workspace search payload byte size does not match its reference: $file"
        }
        require(sourceContentHash(content) == expectedHash) {
            "Workspace search payload hash does not match its reference: $file"
        }
        return content.decodeToString()
    }

    private fun publish(
        staging: Path,
        outputDirectory: Path,
        rollback: Path,
    ) {
        requireReplaceableDirectory(staging, "DOCX staging directory")
        require(isDocxOwnedDirectory(staging)) { "DOCX staging directory is not marked as DOCX-owned" }
        requireReplaceableDirectory(outputDirectory, "DOCX output directory")
        requireReplaceableDirectory(rollback, "DOCX rollback directory")
        if (Files.exists(outputDirectory)) move(outputDirectory, rollback)
        runCatching { move(staging, outputDirectory) }
            .onFailure { restoreRollback(outputDirectory, rollback) }
            .getOrThrow()
        deleteReplaceableTree(rollback, "DOCX rollback directory")
    }

    private fun restoreRollback(
        outputDirectory: Path,
        rollback: Path,
    ) {
        if (!Files.exists(rollback)) return
        requireReplaceableDirectory(rollback, "DOCX rollback directory")
        deleteReplaceableTree(outputDirectory, "DOCX output directory")
        move(rollback, outputDirectory)
    }

    private fun move(
        source: Path,
        destination: Path,
    ) {
        runCatching {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source, destination)
        }.getOrThrow()
    }

    private fun currentManifest(outputDirectory: Path): WorkspaceSiteManifest? =
        outputDirectory
            .resolve(MANIFEST_FILE)
            .takeIf { file -> Files.isRegularFile(file) }
            ?.let { file -> runCatching { WorkspaceSiteJson.decodeManifest(Files.readString(file)) }.getOrNull() }

    private fun validPublishedSite(
        outputDirectory: Path,
        manifest: WorkspaceSiteManifest,
    ): Boolean =
        runCatching {
            verify(outputDirectory, manifest)
            true
        }.getOrDefault(false)

    private fun validCurrentGeneration(
        outputDirectory: Path,
        manifest: WorkspaceSiteManifest,
    ): Boolean {
        if (!validPublishedSite(outputDirectory, manifest)) return false
        val status =
            outputDirectory
                .resolve(STATUS_FILE)
                .takeIf { file -> Files.isRegularFile(file) }
                ?.let { file -> runCatching { WorkspaceSiteJson.decodeStatus(Files.readString(file)) }.getOrNull() }
                ?: return false
        return status.state == WorkspaceSiteState.CURRENT && status.generationId == manifest.generationId
    }

    private fun canWriteFailureStatus(outputDirectory: Path): Boolean =
        runCatching {
            requireReplaceableDirectory(outputDirectory, "DOCX output directory")
            true
        }.getOrDefault(false)

    private fun requireReplaceableDirectory(
        directory: Path,
        label: String,
    ) {
        if (!Files.exists(directory)) return
        require(!Files.isSymbolicLink(directory)) { "$label must not be a symbolic link: $directory" }
        require(Files.isDirectory(directory)) { "$label must be a directory: $directory" }
        require(isEmptyDirectory(directory) || isDocxOwnedDirectory(directory)) {
            "$label is non-empty and is not a DOCX-owned site: $directory"
        }
    }

    private fun isEmptyDirectory(directory: Path): Boolean =
        Files.newDirectoryStream(directory).use { entries -> !entries.iterator().hasNext() }

    private fun isDocxOwnedDirectory(directory: Path): Boolean =
        ownershipMarkerMatches(directory) ||
            currentManifest(directory)?.let { manifest -> validPublishedSite(directory, manifest) } == true

    private fun ownershipMarkerMatches(directory: Path): Boolean =
        directory
            .resolve(OWNERSHIP_MARKER_FILE)
            .takeIf { marker -> Files.isRegularFile(marker) }
            ?.let { marker -> runCatching { Files.readString(marker) == OWNERSHIP_MARKER_CONTENT }.getOrDefault(false) }
            ?: false

    private fun claimOwnership(directory: Path) {
        Files.createDirectories(directory)
        writeText(directory.resolve(OWNERSHIP_MARKER_FILE), OWNERSHIP_MARKER_CONTENT)
    }

    private fun deleteReplaceableTree(
        path: Path,
        label: String,
    ) {
        requireReplaceableDirectory(path, label)
        deleteGeneratedTree(path)
    }

    private fun normalizedEntryName(rawName: String): String {
        require(rawName.isNotBlank() && '\u0000' !in rawName) { "DOCX viewer entry name must not be blank" }
        require(!rawName.startsWith('/') && '\\' !in rawName && ':' !in rawName) {
            "DOCX viewer entry must be relative and normalized: $rawName"
        }
        val segments = rawName.removeSuffix("/").split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "DOCX viewer entry must not traverse the output: $rawName"
        }
        return segments.joinToString("/")
    }

    private fun writeText(
        file: Path,
        content: String,
    ) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun deleteGeneratedTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { candidate -> Files.deleteIfExists(candidate) }
        }
    }

    private fun contentHash(vararg values: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            digest.update(value.size.toString().encodeToByteArray())
            digest.update(0.toByte())
            digest.update(value)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
        }
    }

    private companion object {
        const val INDEX_FILE: String = "index.html"
        const val OWNERSHIP_MARKER_FILE: String = ".docx-site"
        const val OWNERSHIP_MARKER_CONTENT: String = "zone.clanker.gradle.docx/static-site/v1\n"
        const val MANIFEST_FILE: String = "data/manifest.json"
        const val ATLAS_OVERVIEW_FILE: String = "data/atlas-overview.json"
        const val ATLAS_OVERVIEWS_FILE: String = "data/atlas-overviews.json"
        const val DASHBOARD_FILE: String = "data/dashboard.json"
        const val WORKSPACE_FILE: String = "data/workspace.json"
        const val STATUS_FILE: String = "status.json"
        const val SHARD_HASH_SIZE: Int = 16
        const val ZIP_BUFFER_SIZE: Int = 8_192
        const val BYTE_MASK: Int = 0xff
        const val HEX_RADIX: Int = 16
        const val MAX_ZIP_ENTRIES: Int = 10_000
        const val MAX_EXTRACTED_BYTES: Long = 256L * 1024 * 1024
        val SITE_PROJECTION_VERSION_BYTES: ByteArray =
            "zone.clanker.gradle.docx/site-projection/v5".encodeToByteArray()
        val INDEX_RESOURCE_REGEX: Regex = Regex("(?:src|href)=[\\\"']([^\\\"']+)[\\\"']")
    }
}

internal data class DocxSiteInstallResult(
    val manifest: WorkspaceSiteManifest,
    val warnings: List<String>,
)
