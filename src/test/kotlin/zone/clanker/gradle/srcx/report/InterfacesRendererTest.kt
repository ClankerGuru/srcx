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
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol

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
                    output shouldContain "| Interface | Qualified name | Scope | Implementations | Has Mock |"
                    output shouldContain
                        "| `UserRepository` | `com.example.repo.UserRepository` | `main` | 2 | yes |"
                    output shouldContain "| `ILogger` | `com.example.log.ILogger` | `main` | 1 | no |"
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
                    userSvc.implementationCount shouldBe 1
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
                    result[0].implementationCount shouldBe 1
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

            `when`("a summary only mentions an interface in recommendation prose") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            analysis =
                                AnalysisSummary(
                                    findings =
                                        listOf(
                                            Finding(
                                                FindingSeverity.INFO,
                                                "Interface `Dao` has only one implementation",
                                                "Consider inlining it",
                                            ),
                                        ),
                                    hubs = emptyList(),
                                    cycles = emptyList(),
                                ),
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries)

                then("it does not infer an interface candidate from prose") {
                    result.shouldBeEmpty()
                }
            }

            `when`("the workspace index has an exact interface and implementation relationship") {
                val iface =
                    WorkspaceSymbol(
                        build = "root",
                        project = ":app",
                        sourceSet = "main",
                        name = "Dao",
                        qualifiedName = "com.example.Dao",
                        kind = SymbolDetailKind.INTERFACE,
                        projectRelativeFile = "src/main/kotlin/com/example/Dao.kt",
                        declarationLine = 1,
                    )
                val implementation =
                    WorkspaceSymbol(
                        build = "root",
                        project = ":app",
                        sourceSet = "main",
                        name = "DefaultDao",
                        qualifiedName = "com.example.DefaultDao",
                        kind = SymbolDetailKind.CLASS,
                        projectRelativeFile = "src/main/kotlin/com/example/DefaultDao.kt",
                        declarationLine = 2,
                    )
                val reference =
                    WorkspaceReference(
                        build = "root",
                        project = ":app",
                        sourceSet = "main",
                        sourceSymbol = implementation,
                        targetName = iface.name,
                        targetQualifiedName = iface.qualifiedName,
                        kind = ReferenceKind.SUPERTYPE,
                        projectRelativeFile = implementation.projectRelativeFile,
                        line = 2,
                        context = "Dao",
                        evidence = ReferenceEvidence.DIRECT,
                    )
                val workspaceIndex =
                    WorkspaceIndex(
                        symbols = listOf(iface, implementation),
                        relationships =
                            listOf(
                                WorkspaceRelationship(
                                    source = implementation,
                                    target = iface,
                                    kind = WorkspaceRelationshipKind.IMPLEMENTS,
                                    sourceEvidence = reference,
                                    evidence = ReferenceEvidence.DIRECT,
                                ),
                            ),
                    )
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
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries, workspaceIndex)

                then("it documents the interface without parsing anti-pattern prose") {
                    result shouldHaveSize 1
                    result[0].name shouldBe "Dao"
                    result[0].packageName shouldBe "com.example"
                    result[0].implementationCount shouldBe 1
                    result[0].build shouldBe "root"
                    result[0].project shouldBe ":app"
                    result[0].qualifiedName shouldBe "com.example.Dao"
                    result[0].identity shouldBe iface.identity
                }
            }

            `when`("same-named exact interfaces exist in different workspace scopes") {
                val billingRepository =
                    workspaceSymbol("billing-build", ":contracts", "billing.api.Repository")
                val inventoryRepository =
                    workspaceSymbol("inventory-build", ":spi", "inventory.spi.Container.Repository").copy(
                        projectRelativeFile = "src/main/kotlin/inventory/spi/Container.kt",
                    )
                val billingImplementation =
                    workspaceSymbol(
                        "billing-build",
                        ":service",
                        "billing.data.SqlRepository",
                        SymbolDetailKind.CLASS,
                    )
                val firstInventoryImplementation =
                    workspaceSymbol(
                        "inventory-build",
                        ":runtime",
                        "inventory.data.JdbcRepository",
                        SymbolDetailKind.CLASS,
                    )
                val secondInventoryImplementation =
                    workspaceSymbol(
                        "inventory-build",
                        ":runtime",
                        "inventory.data.MemoryRepository",
                        SymbolDetailKind.CLASS,
                    )
                val workspaceIndex =
                    WorkspaceIndex(
                        symbols =
                            listOf(
                                secondInventoryImplementation,
                                billingRepository,
                                firstInventoryImplementation,
                                inventoryRepository,
                                billingImplementation,
                            ),
                        relationships =
                            listOf(
                                implementationRelationship(
                                    secondInventoryImplementation,
                                    inventoryRepository,
                                ),
                                implementationRelationship(billingImplementation, billingRepository),
                                implementationRelationship(
                                    firstInventoryImplementation,
                                    inventoryRepository,
                                ),
                            ),
                    )
                val summaries =
                    listOf(
                        legacyRepositorySummary(
                            project = ":contracts",
                            packageName = "billing.api",
                            filePath = "billing/api/Repository.kt",
                        ),
                        legacyRepositorySummary(
                            project = ":spi",
                            packageName = "inventory.spi",
                            filePath = "inventory/spi/Container.kt",
                        ),
                    )
                val result = InterfacesRenderer.fromSummaries(summaries, workspaceIndex)
                val output = InterfacesRenderer(result.reversed()).render()

                then("both exact identities survive without naming-fallback duplicates") {
                    result shouldHaveSize 2
                    result.map { it.identity }.toSet() shouldBe
                        setOf(billingRepository.identity, inventoryRepository.identity)
                }

                then("implementation counts remain scoped to each exact target") {
                    result.single { it.identity == billingRepository.identity }.implementationCount shouldBe 1
                    result.single { it.identity == inventoryRepository.identity }.implementationCount shouldBe 2
                }

                then("a nested qualified name is not mislabeled as its enclosing type package") {
                    val inventory = result.single { it.identity == inventoryRepository.identity }
                    inventory.qualifiedName shouldBe "inventory.spi.Container.Repository"
                    inventory.packageName shouldBe "inventory.spi"
                }

                then("rendering shows distinct scopes in deterministic order") {
                    val inventoryRow =
                        "| `Repository` | `inventory.spi.Container.Repository` | " +
                            "`inventory-build / :spi / main` | 2 | no |"
                    val billingRow =
                        "| `Repository` | `billing.api.Repository` | " +
                            "`billing-build / :contracts / main` | 1 | no |"
                    output shouldContain inventoryRow
                    output shouldContain billingRow
                    (output.indexOf(inventoryRow) < output.indexOf(billingRow)) shouldBe true
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

private fun workspaceSymbol(
    build: String,
    project: String,
    qualifiedName: String,
    kind: SymbolDetailKind = SymbolDetailKind.INTERFACE,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile = "src/main/kotlin/${qualifiedName.replace('.', '/')}.kt",
        declarationLine = 1,
    )

private fun implementationRelationship(
    implementation: WorkspaceSymbol,
    contract: WorkspaceSymbol,
): WorkspaceRelationship {
    val reference =
        WorkspaceReference(
            build = implementation.build,
            project = implementation.project,
            sourceSet = implementation.sourceSet,
            sourceSymbol = implementation,
            targetName = contract.name,
            targetQualifiedName = contract.qualifiedName,
            kind = ReferenceKind.SUPERTYPE,
            projectRelativeFile = implementation.projectRelativeFile,
            line = implementation.declarationLine,
            context = contract.qualifiedName,
            evidence = ReferenceEvidence.DIRECT,
        )
    return WorkspaceRelationship(
        source = implementation,
        target = contract,
        kind = WorkspaceRelationshipKind.IMPLEMENTS,
        sourceEvidence = reference,
        evidence = ReferenceEvidence.DIRECT,
    )
}

private fun legacyRepositorySummary(
    project: String,
    packageName: String,
    filePath: String,
): ProjectSummary =
    ProjectSummary(
        projectPath = ProjectPath(project),
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
                            SymbolName("Repository"),
                            SymbolKind.CLASS,
                            PackageName(packageName),
                            FilePath(filePath),
                            1,
                        ),
                    ),
                    listOf("src/main/kotlin"),
                ),
            ),
    )
