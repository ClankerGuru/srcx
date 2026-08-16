package zone.clanker.gradle.srcx.scan

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.util.GradleVersion
import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File
import java.lang.reflect.InvocationTargetException

internal fun hasConfiguredSourceModel(project: Project): Boolean =
    project.extensions.findByType(JavaPluginExtension::class.java) != null ||
        project.extensions.findByName("kotlin") != null

internal fun configuredJavaSourceSets(project: Project): List<ConfiguredSourceSet> =
    project.extensions
        .findByType(JavaPluginExtension::class.java)
        ?.sourceSets
        ?.map { sourceSet ->
            ConfiguredSourceSet(
                name = SourceSetName(sourceSet.name),
                directories = sourceSet.allSource.srcDirs.toList(),
            )
        }.orEmpty()

internal fun configuredKotlinSourceSets(project: Project): List<ConfiguredSourceSet> {
    val extension = project.extensions.findByName("kotlin") ?: return emptyList()
    return runCatching {
        val sourceSets = invokeNoArg(extension, "getSourceSets") as? Iterable<*> ?: return@runCatching emptyList()
        sourceSets.mapNotNull { sourceSet ->
            sourceSet ?: return@mapNotNull null
            val name = invokeNoArg(sourceSet, "getName") as? String ?: return@mapNotNull null
            val kotlinSources = invokeNoArg(sourceSet, "getKotlin") ?: return@mapNotNull null
            val sourceDirectories = invokeNoArg(kotlinSources, "getSrcDirs") as? Iterable<*>
            ConfiguredSourceSet(
                name = SourceSetName(name),
                directories = (sourceDirectories ?: emptyList<Any?>()).filterIsInstance<File>(),
            )
        }
    }.getOrElse { error -> throwKotlinDiscoveryFailure(project, extension, error) }
}

internal fun projectOutputDirectories(project: Project): List<File> {
    val configuredBuildDirectory =
        runCatching {
            project.layout.buildDirectory
                .get()
                .asFile
        }.getOrElse { File(project.projectDir, "build") }
    return normalizeDirectories(defaultOutputDirectories(project.projectDir) + configuredBuildDirectory)
}

private fun throwKotlinDiscoveryFailure(
    project: Project,
    extension: Any,
    error: Throwable,
): Nothing {
    val cause = unwrapInvocationFailure(error)
    if (cause is Error) throw cause
    throw GradleException(
        "srcx: unable to inspect configured Kotlin source sets for project '${project.path}' " +
            "with Gradle ${GradleVersion.current().version} and extension ${extension.javaClass.name}: " +
            (cause.message ?: cause.javaClass.name),
        cause,
    )
}

private fun invokeNoArg(receiver: Any, methodName: String): Any? =
    receiver.javaClass.methods
        .first { method -> method.name == methodName && method.parameterCount == 0 }
        .let { method ->
            method.isAccessible = true
            method.invoke(receiver)
        }

private tailrec fun unwrapInvocationFailure(error: Throwable): Throwable =
    if (error is InvocationTargetException && error.targetException != null) {
        unwrapInvocationFailure(error.targetException)
    } else {
        error
    }
