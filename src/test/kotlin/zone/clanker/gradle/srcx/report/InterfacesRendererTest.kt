@file:Suppress("LargeClass")

package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

class InterfacesRendererTest :
    BehaviorSpec({

        given("an InterfacesRenderer") {

            `when`("rendering with no interfaces") {
                val renderer = InterfacesRenderer(emptyList())
                val output = renderer.render()

                then("it shows the no-data message") {
                    output shouldContain "# Interfaces"
                    output shouldContain "No interfaces detected."
                }
            }

            `when`("rendering with interfaces") {
                val interfaces =
                    listOf(
                        InterfacesRenderer.InterfaceInfo(
                            name = "UserRepository",
                            packageName = "com.example.repo",
                            implementationCount = 2,
                            hasMock = true,
                            sourceSet = "main",
                        ),
                        InterfacesRenderer.InterfaceInfo(
                            name = "ILogger",
                            packageName = "com.example.log",
                            implementationCount = 1,
                            hasMock = false,
                            sourceSet = "main",
                        ),
                    )
                val renderer = InterfacesRenderer(interfaces)
                val output = renderer.render()

                then("it contains the interface table") {
                    output shouldContain "| Interface | Package | Implementations | Has Mock |"
                    output shouldContain "| `UserRepository` | com.example.repo | 2 | yes |"
                    output shouldContain "| `ILogger` | com.example.log | 1 | no |"
                }
            }
        }

        given("InterfacesRenderer.fromSummaries") {

            `when`("summaries have no interfaces") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("SomeClass"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("SomeClass.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it returns empty list") {
                    result.shouldBeEmpty()
                }
            }

            `when`("summaries have interface-like naming (Service suffix)") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("UserService"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("UserService.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("UserServiceImpl"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("UserServiceImpl.kt"),
                                                2,
                                            ),
                                            SymbolEntry(
                                                SymbolName("MockUserService"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("MockUserService.kt"),
                                                3,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the interface by Service suffix") {
                    val userSvc = result.first { it.name == "UserService" }
                    userSvc.implementationCount shouldBe 2
                    userSvc.hasMock shouldBe true
                }
            }

            `when`("summaries have I-prefixed interface") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":lib"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("ILogger"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.log"),
                                                FilePath("ILogger.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("LoggerImpl"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.log"),
                                                FilePath("LoggerImpl.kt"),
                                                2,
                                            ),
                                            SymbolEntry(
                                                SymbolName("FakeILogger"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.log"),
                                                FilePath("FakeILogger.kt"),
                                                3,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the I-prefixed interface with impl count and mock") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "ILogger"
                    result[0].implementationCount shouldBe 2
                    result[0].hasMock shouldBe true
                }
            }

            `when`("summaries have Repository suffix") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":data"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("UserRepository"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.data"),
                                                FilePath("UserRepository.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("UserRepositoryImpl"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.data"),
                                                FilePath("UserRepositoryImpl.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects Repository as interface-like") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "UserRepository"
                    result[0].implementationCount shouldBe 1
                    result[0].hasMock shouldBe false
                }
            }

            `when`("summaries have Provider suffix") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":core"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("ConfigProvider"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.core"),
                                                FilePath("ConfigProvider.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects Provider as interface-like") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "ConfigProvider"
                }
            }

            `when`("summaries have Factory suffix") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":core"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("WidgetFactory"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example.core"),
                                                FilePath("WidgetFactory.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects Factory as interface-like") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "WidgetFactory"
                }
            }

            `when`("summaries have analysis findings referencing interfaces") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("Dao"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("Dao.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("DefaultDao"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("DefaultDao.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                            analysis =
                                AnalysisSummary(
                                    findings =
                                        listOf(
                                            Finding(
                                                FindingSeverity.INFO,
                                                "Interface `Dao` has only one implementation",
                                                "Consider inlining",
                                            ),
                                        ),
                                    hubs = emptyList(),
                                    cycles = emptyList(),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it finds the interface from findings pattern") {
                    result.any { it.name == "Dao" } shouldBe true
                }
            }

            `when`("summaries have mock via Mock prefix pattern") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("IPayment"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("IPayment.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("MockIPayment"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("MockIPayment.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the mock via Mock prefix") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "IPayment"
                    result[0].hasMock shouldBe true
                }
            }

            `when`("summaries have mock via Fake prefix pattern") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("INotifier"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("INotifier.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("FakeINotifier"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("FakeINotifier.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the mock via Fake prefix") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "INotifier"
                    result[0].hasMock shouldBe true
                }
            }

            `when`("summaries have mock via Mock suffix pattern") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("UserRepository"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("UserRepository.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("UserRepositoryMock"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("UserRepositoryMock.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the mock via Mock suffix") {
                    val iface = result.first { it.name == "UserRepository" }
                    iface.hasMock shouldBe true
                }
            }

            `when`("summaries have Fake suffix pattern") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("EventProvider"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("EventProvider.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("EventProviderFake"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("EventProviderFake.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it detects the mock via Fake suffix") {
                    val iface = result.first { it.name == "EventProvider" }
                    iface.hasMock shouldBe true
                }
            }

            `when`("summaries have empty source sets") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":empty"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets = emptyList(),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it returns empty list") {
                    result.shouldBeEmpty()
                }
            }

            `when`("interface has Default prefix implementation") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("IConfig"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("IConfig.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("DefaultConfig"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("DefaultConfig.kt"),
                                                2,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it counts the Default prefix implementation") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "IConfig"
                    result[0].implementationCount shouldBe 1
                }
            }

            `when`("interface has endsWith baseName implementation") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("ICache"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("ICache.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("RedisCache"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("RedisCache.kt"),
                                                2,
                                            ),
                                            SymbolEntry(
                                                SymbolName("InMemoryCache"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("InMemoryCache.kt"),
                                                3,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it counts implementations matching endsWith pattern") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "ICache"
                    result[0].implementationCount shouldBe 2
                }
            }

            `when`("summaries are empty") {
                val result = InterfacesRenderer.fromSummaries(emptyList())

                then("it returns empty list") {
                    result.shouldBeEmpty()
                }
            }
        }
    })
