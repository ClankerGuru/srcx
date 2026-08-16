package zone.clanker.docx.index.importing

import zone.clanker.docx.index.IndexImportOptions
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal fun <Input, Output> consumeDecodedInOrder(
    inputs: List<Input>,
    options: IndexImportOptions,
    observer: DecodeWindowObserver = DecodeWindowObserver {},
    decode: (Input) -> Output,
    consume: (Input, Output) -> Unit,
) {
    if (inputs.isEmpty()) return
    val executor = Executors.newFixedThreadPool(options.parallelism)
    val pending = ArrayDeque<PendingDecode<Input, Output>>(options.decodeWindowSize)
    var nextIndex = 0
    val outcome =
        runCatching {
            while (nextIndex < inputs.size && pending.size < options.decodeWindowSize) {
                pending.add(inputs.submitAt(nextIndex, executor::submit, decode))
                nextIndex += 1
                observer.observe(pending.size)
            }
            while (pending.isNotEmpty()) {
                val decoded = pending.removeFirst().await()
                consume(decoded.input, decoded.output)
                if (nextIndex < inputs.size) {
                    pending.add(inputs.submitAt(nextIndex, executor::submit, decode))
                    nextIndex += 1
                    observer.observe(pending.size)
                }
            }
        }
    if (outcome.isFailure) pending.forEach { scheduled -> scheduled.future.cancel(true) }
    executor.shutdown()
    val termination = runCatching { executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    if (termination.exceptionOrNull() is InterruptedException) executor.shutdownNow()
    if (!executor.isTerminated) executor.shutdownNow()
    if (outcome.exceptionOrNull() is InterruptedException || termination.exceptionOrNull() is InterruptedException) {
        Thread.currentThread().interrupt()
    }
    outcome.getOrThrow()
}

private fun <Input, Output> List<Input>.submitAt(
    index: Int,
    submit: (Callable<Output>) -> Future<Output>,
    decode: (Input) -> Output,
): PendingDecode<Input, Output> {
    val input = get(index)
    return PendingDecode(input, submit(Callable { decode(input) }))
}

private fun <Input, Output> PendingDecode<Input, Output>.await(): Decoded<Input, Output> {
    val result = runCatching { future.get(DECODE_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
    val failure = result.exceptionOrNull()
    if (failure is ExecutionException) throw failure.cause ?: failure
    return Decoded(input, result.getOrThrow())
}

private data class PendingDecode<Input, Output>(
    val input: Input,
    val future: Future<Output>,
)

private data class Decoded<Input, Output>(
    val input: Input,
    val output: Output,
)

private const val DECODE_TIMEOUT_MINUTES: Long = 5
private const val SHUTDOWN_TIMEOUT_SECONDS: Long = 30
