package zone.clanker.docx.index.importing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.docx.index.IndexImportOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderedDecodePipelineTest :
    BehaviorSpec({
        given("more inputs than the bounded decode window") {
            val parallelism = 3
            val firstWave = CyclicBarrier(parallelism)
            val laterFirstWaveDecodes = CountDownLatch(parallelism - 1)
            val activeDecoders = AtomicInteger()
            val maxActiveDecoders = AtomicInteger()
            val maxWindowSize = AtomicInteger()
            val consumed = mutableListOf<Pair<Int, String>>()

            `when`("the first manifest shard finishes after later shards") {
                consumeDecodedInOrder(
                    inputs = (0 until 9).toList(),
                    options = IndexImportOptions(parallelism),
                    observer =
                        DecodeWindowObserver { size ->
                            maxWindowSize.updateAndGet { current -> maxOf(current, size) }
                        },
                    decode = { input ->
                        val active = activeDecoders.incrementAndGet()
                        maxActiveDecoders.updateAndGet { current -> maxOf(current, active) }
                        val result =
                            runCatching {
                                if (input < parallelism) {
                                    firstWave.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                    if (input == 0) {
                                        check(
                                            laterFirstWaveDecodes.await(
                                                COORDINATION_TIMEOUT_SECONDS,
                                                TimeUnit.SECONDS,
                                            ),
                                        )
                                    } else {
                                        laterFirstWaveDecodes.countDown()
                                    }
                                }
                                "decoded-$input"
                            }
                        activeDecoders.decrementAndGet()
                        result.getOrThrow()
                    },
                    consume = { input, decoded -> consumed += input to decoded },
                )

                then("decode concurrency and scheduled work never exceed the configured window") {
                    maxActiveDecoders.get() shouldBe parallelism
                    maxWindowSize.get() shouldBe parallelism
                }

                then("the single consumer still receives manifest order") {
                    consumed shouldContainExactly (0 until 9).map { input -> input to "decoded-$input" }
                }
            }
        }

        given("a decode failure with other workers blocked") {
            val blockedWorkersStarted = CountDownLatch(2)
            val interruptedWorkers = CountDownLatch(2)
            val neverRelease = CountDownLatch(1)
            val consumed = mutableListOf<Int>()

            `when`("the manifest-leading decode fails") {
                then("pending work is interrupted and no decoded shard reaches the writer") {
                    shouldThrow<IllegalStateException> {
                        consumeDecodedInOrder(
                            inputs = listOf(0, 1, 2),
                            options = IndexImportOptions(parallelism = 3),
                            decode = { input ->
                                if (input == 0) {
                                    check(
                                        blockedWorkersStarted.await(
                                            COORDINATION_TIMEOUT_SECONDS,
                                            TimeUnit.SECONDS,
                                        ),
                                    )
                                    error("fixture decode failure")
                                }
                                blockedWorkersStarted.countDown()
                                val blocked = runCatching { neverRelease.await() }
                                if (blocked.exceptionOrNull() is InterruptedException) {
                                    interruptedWorkers.countDown()
                                }
                                blocked.getOrThrow()
                                input
                            },
                            consume = { _, decoded -> consumed += decoded },
                        )
                    }.message shouldBe "fixture decode failure"
                    interruptedWorkers.count shouldBe 0L
                    consumed shouldBe emptyList()
                }
            }
        }

        given("an import parallelism outside the bounded range") {
            then("the typed options reject it") {
                shouldThrow<IllegalArgumentException> { IndexImportOptions(parallelism = 0) }
                shouldThrow<IllegalArgumentException> {
                    IndexImportOptions(parallelism = IndexImportOptions.MAX_PARALLELISM + 1)
                }
            }
        }

        given("an empty manifest") {
            then("no decoder, observer, or consumer is started") {
                var callbackCount = 0
                consumeDecodedInOrder<Int, Int>(
                    inputs = emptyList(),
                    options = IndexImportOptions(parallelism = 2),
                    observer = DecodeWindowObserver { callbackCount += 1 },
                    decode = { input -> input.also { callbackCount += 1 } },
                    consume = { _, _ -> callbackCount += 1 },
                )
                callbackCount shouldBe 0
            }
        }
    })

private const val COORDINATION_TIMEOUT_SECONDS: Long = 5
