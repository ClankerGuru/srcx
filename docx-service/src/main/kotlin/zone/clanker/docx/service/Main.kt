package zone.clanker.docx.service

import zone.clanker.docx.service.client.ServiceCommand
import zone.clanker.docx.service.client.ServiceLaunchOptions
import zone.clanker.docx.service.client.USAGE
import zone.clanker.docx.service.client.WorkspaceServiceClient
import zone.clanker.docx.service.client.parseCommand
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    runCatching { execute(parseCommand(arguments), System.out) }
        .onFailure { error ->
            System.err.print("docx-service: ${error.message ?: error.javaClass.simpleName}\n")
            exitProcess(1)
        }
}

internal fun execute(
    command: ServiceCommand,
    output: PrintStream,
) {
    when (command) {
        ServiceCommand.Help -> output.print(USAGE)
        is ServiceCommand.Serve -> serve(command, output)
        is ServiceCommand.Register -> register(command, output)
        is ServiceCommand.Unregister -> unregister(command, output)
        is ServiceCommand.ListWorkspaces -> list(command, output)
    }
}

private fun serve(
    command: ServiceCommand.Serve,
    output: PrintStream,
) {
    val config = command.options.toServiceConfig()
    val server = DocxWorkspaceServer.start(config)
    val shutdown = Thread(server::close, "docx-service-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    runCatching {
        output.println("docx-service: ${server.endpoint.baseUrl}")
        output.println("docx-service: endpoint ${config.endpointFile}")
        output.println("docx-service: index ${server.indexDescription}")
        output.println("docx-service: one process can host any number of registered workspaces")
        server.awaitShutdown()
    }.also {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        server.close()
    }.getOrThrow()
}

private fun ServiceLaunchOptions.toServiceConfig(): DocxServiceConfig =
    DocxServiceConfig(
        host = host,
        port = port,
        registryFile = registryFile,
        endpointFile = endpointFile,
        indexFile = indexFile,
        token = token,
        limits = limits,
    )

private fun register(
    command: ServiceCommand.Register,
    output: PrintStream,
) {
    val mount =
        WorkspaceServiceClient(command.connection).register(
            workspaceId = command.workspaceId,
            siteDirectory = command.siteDirectory,
        )
    val viewer = command.connection.baseUrl.resolve(mount.viewerPath)
    output.println("docx-service: registered ${mount.workspaceId} at $viewer")
}

private fun unregister(
    command: ServiceCommand.Unregister,
    output: PrintStream,
) {
    WorkspaceServiceClient(command.connection).unregister(command.workspaceId)
    output.println("docx-service: unregistered ${command.workspaceId}")
}

private fun list(
    command: ServiceCommand.ListWorkspaces,
    output: PrintStream,
) {
    val workspaces = WorkspaceServiceClient(command.connection).list().workspaces
    if (workspaces.isEmpty()) {
        output.println("docx-service: no registered workspaces")
    } else {
        workspaces.forEach { mount ->
            output.println(
                "${mount.workspaceId}\t${mount.availability.name.lowercase()}\t" +
                    command.connection.baseUrl.resolve(mount.viewerPath),
            )
        }
    }
}
