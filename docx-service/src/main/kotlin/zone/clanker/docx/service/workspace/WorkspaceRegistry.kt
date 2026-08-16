package zone.clanker.docx.service.workspace

import zone.clanker.report.model.WorkspaceAvailability
import zone.clanker.report.model.WorkspaceCatalogResponse
import zone.clanker.report.model.WorkspaceEvent
import zone.clanker.report.model.WorkspaceEventKind
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceMount
import zone.clanker.report.model.WorkspaceRegistrationRequest
import zone.clanker.report.model.WorkspaceSiteJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal data class WorkspaceMutation(
    val mount: WorkspaceMount,
    val event: WorkspaceEvent?,
)

internal class WorkspaceRegistry(
    private val store: WorkspaceRegistryStore,
    private val indexingEnabled: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val sequence = AtomicLong()
    private var registrations: Map<String, Path> = loadRegistrations()
    private var mounts: Map<String, WorkspaceMount> = initialMounts(registrations)

    fun register(request: WorkspaceRegistrationRequest): WorkspaceMutation {
        val workspaceId = requireWorkspaceId(request.workspaceId)
        val siteDirectory = requireSiteDirectory(request.siteDirectory)
        return synchronized(lock) {
            val previousDirectory = registrations[workspaceId]
            val previousMount = mounts[workspaceId]
            val observed = observe(workspaceId, siteDirectory, previousMount, previousDirectory != siteDirectory)
            val nextRegistrations = registrations + (workspaceId to siteDirectory)
            store.save(nextRegistrations.toStored())
            registrations = nextRegistrations
            mounts = mounts + (workspaceId to observed)
            val changed = previousDirectory != siteDirectory || previousMount?.sameObservation(observed) != true
            val kind = if (previousDirectory == null) WorkspaceEventKind.REGISTERED else WorkspaceEventKind.UPDATED
            WorkspaceMutation(observed, event(kind, observed).takeIf { changed })
        }
    }

    fun unregister(workspaceId: String): WorkspaceEvent? {
        val validId = requireWorkspaceId(workspaceId)
        return synchronized(lock) {
            val previous = mounts[validId] ?: return@synchronized null
            val nextRegistrations = registrations - validId
            store.save(nextRegistrations.toStored())
            registrations = nextRegistrations
            mounts = mounts - validId
            event(WorkspaceEventKind.UNREGISTERED, previous.copy(index = WorkspaceIndexStatus.Disabled))
        }
    }

    fun catalog(): WorkspaceCatalogResponse =
        synchronized(lock) {
            WorkspaceCatalogResponse(mounts.values.sortedBy(WorkspaceMount::workspaceId))
        }

    fun mount(workspaceId: String): WorkspaceMount? = synchronized(lock) { mounts[workspaceId] }

    fun siteDirectory(workspaceId: String): Path? = synchronized(lock) { registrations[workspaceId] }

    fun snapshotEvents(): List<WorkspaceEvent> =
        synchronized(lock) {
            mounts.values
                .sortedBy(WorkspaceMount::workspaceId)
                .map { mount -> event(WorkspaceEventKind.SNAPSHOT, mount) }
        }

    fun refresh(): List<WorkspaceEvent> =
        synchronized(lock) {
            registrations
                .toSortedMap()
                .mapNotNull { (workspaceId, directory) ->
                    val previous = mounts.getValue(workspaceId)
                    val observed = observe(workspaceId, directory, previous, false)
                    observed
                        .takeUnless(previous::sameObservation)
                        ?.also { mounts = mounts + (workspaceId to it) }
                        ?.let { event(WorkspaceEventKind.UPDATED, it) }
                }
        }

    fun pendingGenerations(): List<WorkspaceGeneration> =
        synchronized(lock) {
            mounts.values.mapNotNull { mount ->
                mount.pendingGeneration(registrations[mount.workspaceId])
            }
        }

    fun completeIndex(
        request: WorkspaceGeneration,
        status: WorkspaceIndexStatus,
    ): WorkspaceEvent? =
        synchronized(lock) {
            val previous = mounts[request.workspaceId] ?: return@synchronized null
            if (previous.generationId != request.generationId || previous.index != WorkspaceIndexStatus.Pending) {
                return@synchronized null
            }
            val updated = previous.copy(index = status, observedAtEpochMilliseconds = clock())
            mounts = mounts + (request.workspaceId to updated)
            event(WorkspaceEventKind.UPDATED, updated)
        }

    private fun observe(
        workspaceId: String,
        directory: Path,
        previous: WorkspaceMount?,
        forceIndex: Boolean,
    ): WorkspaceMount {
        val candidate = observeSite(workspaceId, directory, clock(), previous?.index ?: disabledOrPending())
        val generationChanged = candidate.generationId != previous?.generationId
        val index =
            when {
                !indexingEnabled -> WorkspaceIndexStatus.Disabled
                candidate.availability != WorkspaceAvailability.AVAILABLE ->
                    previous?.index ?: WorkspaceIndexStatus.Pending
                forceIndex || generationChanged -> WorkspaceIndexStatus.Pending
                else -> previous?.index ?: WorkspaceIndexStatus.Pending
            }
        val observed = candidate.copy(index = index)
        return if (previous?.sameObservation(observed) == true) previous else observed
    }

    private fun disabledOrPending(): WorkspaceIndexStatus =
        if (indexingEnabled) WorkspaceIndexStatus.Pending else WorkspaceIndexStatus.Disabled

    private fun loadRegistrations(): Map<String, Path> =
        store
            .load()
            .mapNotNull { stored ->
                runCatching {
                    requireWorkspaceId(stored.workspaceId) to Path.of(stored.siteDirectory).toAbsolutePath().normalize()
                }.getOrNull()
            }.toMap()

    private fun initialMounts(entries: Map<String, Path>): Map<String, WorkspaceMount> =
        entries.mapValues { (workspaceId, directory) -> observe(workspaceId, directory, null, false) }

    private fun event(
        kind: WorkspaceEventKind,
        mount: WorkspaceMount,
    ): WorkspaceEvent =
        WorkspaceEvent(
            sequence = sequence.incrementAndGet(),
            kind = kind,
            workspaceId = mount.workspaceId,
            workspace = mount.takeUnless { kind == WorkspaceEventKind.UNREGISTERED },
        )
}

internal fun requireWorkspaceId(raw: String): String {
    val workspaceId = raw.trim()
    require(WORKSPACE_ID.matches(workspaceId)) {
        "Workspace ID must start with a lowercase letter or digit and contain only lowercase letters, digits, " +
            "'.', '_' or '-'."
    }
    return workspaceId
}

private fun requireSiteDirectory(raw: String): Path {
    val requested = Path.of(raw)
    require(requested.isAbsolute) { "Site directory must be an absolute path." }
    val directory = requested.normalize().toRealPath()
    require(Files.isDirectory(directory)) { "Site directory does not exist: $directory" }
    require(Files.isRegularFile(directory.resolve(INDEX_FILE))) { "Generated site has no index.html: $directory" }
    val manifest = directory.resolve(MANIFEST_FILE)
    require(Files.isRegularFile(manifest)) { "Generated site has no manifest: $manifest" }
    WorkspaceSiteJson.decodeManifest(Files.readString(manifest))
    return directory
}

private fun Map<String, Path>.toStored(): List<StoredWorkspace> =
    toSortedMap().map { (workspaceId, directory) -> StoredWorkspace(workspaceId, directory.toString()) }

private val WORKSPACE_ID: Regex = Regex("[a-z0-9][a-z0-9._-]{0,63}")

private fun WorkspaceMount.pendingGeneration(directory: Path?): WorkspaceGeneration? {
    val ready = availability == WorkspaceAvailability.AVAILABLE && index == WorkspaceIndexStatus.Pending
    return if (ready) {
        generationId?.let { generation ->
            directory?.let { path -> WorkspaceGeneration(workspaceId, path, generation) }
        }
    } else {
        null
    }
}
