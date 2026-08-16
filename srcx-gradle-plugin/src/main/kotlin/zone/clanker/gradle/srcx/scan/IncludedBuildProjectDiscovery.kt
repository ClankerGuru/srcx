package zone.clanker.gradle.srcx.scan

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.IncludedBuild
import org.gradle.util.GradleVersion
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Loads and configures the projects owned by one included build before capturing their source models.
 *
 * Gradle does not expose included-build projects through its public API. Keeping this compatibility edge in one
 * class makes failure explicit instead of silently collapsing a multi-project build to its root directory.
 */
internal object IncludedBuildProjectDiscovery {
    internal fun discover(build: IncludedBuild): List<IncludedBuildProject> =
        runCatching { discoverProjects(build) }
            .getOrElse { error -> throwDiscoveryFailure(build, error) }

    private fun discoverProjects(build: IncludedBuild): List<IncludedBuildProject> {
        val target =
            invokeNoArg(build, "getTarget")
                ?: error("included build target is unavailable")
        ensureProjectsConfigured(target)
        val registry =
            invokeNoArg(target, "getProjects")
                ?: error("included build project registry is unavailable")
        val projectStates =
            invokeNoArg(registry, "getAllProjects") as? Iterable<*>
                ?: error("included build project registry did not return projects")
        val discovered =
            projectStates
                .map { projectState ->
                    projectState ?: error("included build project registry contained a null project")
                    val project =
                        invokeNoArg(projectState, "getMutableModel") as? Project
                            ?: error("included build project model is unavailable")
                    val path =
                        invokeNoArg(projectState, "getProjectPath")?.toString()
                            ?: error("included build project path is unavailable")
                    val directory =
                        invokeNoArg(projectState, "getProjectDir") as? File
                            ?: error("included build project directory is unavailable")
                    IncludedBuildProject(
                        path = path,
                        directory = directory,
                        sourceLayout = ProjectScanner.discoverSourceLayout(project),
                    )
                }.sortedBy { project -> project.path }
        validateProjects(build, discovered)
        return excludeNestedProjects(discovered)
    }

    internal fun ensureProjectsConfigured(target: Any) {
        invokeNoArg(target, "ensureProjectsLoaded")
        invokeNoArg(target, "ensureProjectsConfigured")
    }

    private fun throwDiscoveryFailure(
        build: IncludedBuild,
        error: Throwable,
    ): Nothing {
        val cause = unwrapInvocationFailure(error)
        if (cause is Error) throw cause
        throw GradleException(
            "srcx: unable to discover configured projects for included build '${build.name}' " +
                "with Gradle ${GradleVersion.current().version}: ${cause.message ?: cause.javaClass.name}",
            cause,
        )
    }

    private fun excludeNestedProjects(projects: List<IncludedBuildProject>): List<IncludedBuildProject> =
        projects.map { project ->
            val nestedProjects =
                projects
                    .asSequence()
                    .filterNot { candidate -> candidate === project }
                    .map { candidate -> candidate.directory }
                    .filter { candidate -> candidate.startsWith(project.directory) }
                    .toList()
            project.copy(
                sourceLayout = ProjectScanner.excludeDirectories(project.sourceLayout, nestedProjects),
            )
        }

    private fun validateProjects(
        build: IncludedBuild,
        projects: List<IncludedBuildProject>,
    ) {
        require(projects.isNotEmpty()) { "included build '${build.name}' did not expose any projects" }
        require(projects.count { project -> project.path == ":" } == 1) {
            "included build '${build.name}' must expose exactly one root project"
        }
        require(projects.map { project -> project.path }.distinct().size == projects.size) {
            "included build '${build.name}' exposed duplicate project paths"
        }
    }

    private fun invokeNoArg(receiver: Any, methodName: String): Any? {
        val method =
            receiver.javaClass.methods
                .firstOrNull { candidate -> candidate.name == methodName && candidate.parameterCount == 0 }
                ?: error("${receiver.javaClass.name} does not expose $methodName()")
        method.isAccessible = true
        return method.invoke(receiver)
    }

    private tailrec fun unwrapInvocationFailure(error: Throwable): Throwable =
        if (error is InvocationTargetException && error.targetException != null) {
            unwrapInvocationFailure(error.targetException)
        } else {
            error
        }
}
