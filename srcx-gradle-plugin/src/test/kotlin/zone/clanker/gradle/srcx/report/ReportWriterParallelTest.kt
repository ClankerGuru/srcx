package zone.clanker.gradle.srcx.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val WORKER_COUNT = 2
private const val SYNCHRONIZATION_TIMEOUT_SECONDS = 10L

class ReportWriterParallelTest :
    BehaviorSpec({
        given("a bounded parallel map") {
            `when`("work overlaps and finishes independently") {
                val active = AtomicInteger()
                val maximumActive = AtomicInteger()
                val firstWindowReady = CountDownLatch(WORKER_COUNT)
                val releaseFirstItem = CountDownLatch(1)

                val results =
                    ReportWriter.runParallelMapped(
                        items = listOf(1, 2, 3, 4, 5),
                        workerCount = WORKER_COUNT,
                    ) { item ->
                        val activeNow = active.incrementAndGet()
                        maximumActive.updateAndGet { current -> maxOf(current, activeNow) }
                        if (item <= WORKER_COUNT) {
                            firstWindowReady.countDown()
                            check(firstWindowReady.await(SYNCHRONIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                        if (item == 1) {
                            check(releaseFirstItem.await(SYNCHRONIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        } else if (item == 2) {
                            releaseFirstItem.countDown()
                        }
                        active.decrementAndGet()
                        item * 10
                    }

                then("it retains input order despite independent completion") {
                    results shouldBe listOf(10, 20, 30, 40, 50)
                }

                then("it never admits more work than the configured worker bound") {
                    maximumActive.get() shouldBe WORKER_COUNT
                    active.get() shouldBe 0
                }
            }

            `when`("the worker count exceeds the hard memory bound") {
                then("it rejects the request before starting work") {
                    shouldThrow<IllegalArgumentException> {
                        ReportWriter.runParallelMapped(
                            items = listOf("project"),
                            workerCount = ReportWriter.MAX_WORKER_COUNT + 1,
                        ) { it }
                    }
                }
            }
        }
    })
