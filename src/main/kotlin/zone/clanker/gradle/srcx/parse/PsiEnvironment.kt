package zone.clanker.gradle.srcx.parse

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Manages a [KotlinCoreEnvironment] for PSI-based source parsing.
 *
 * Creates one environment per instance. Must be closed when done to free
 * IntelliJ platform resources. Handles .kt, .java, and .gradle.kts files.
 */
@Suppress("OPT_IN_USAGE_ERROR")
class PsiEnvironment : AutoCloseable {
    private val disposable = Disposer.newDisposable("srcx-psi")

    val environment: KotlinCoreEnvironment
    val psiManager: PsiManager

    init {
        setIdeaIoUseFallback()
        val configuration =
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                put(CommonConfigurationKeys.MODULE_NAME, "srcx")
            }
        environment =
            KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
        psiManager = PsiManager.getInstance(environment.project)
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}
