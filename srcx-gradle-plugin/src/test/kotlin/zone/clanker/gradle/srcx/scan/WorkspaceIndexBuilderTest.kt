package zone.clanker.gradle.srcx.scan

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.DeclarationSemantic
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import java.io.File

class WorkspaceIndexBuilderTest :
    BehaviorSpec({
        given("the same physical source scope is discovered more than once") {
            val declaration = typeSymbol("shared", ":api", "shared.api.Service")
            val duplicateScan = scan("shared", ":api", sourceFile(declaration))

            `when`("the cumulative workspace index is assembled") {
                val index = WorkspaceIndexBuilder.build(listOf(duplicateScan, duplicateScan))

                then("the physical declaration keeps one stable symbol identity") {
                    index.symbols.map { symbol -> symbol.identity } shouldHaveSize 1
                }
            }
        }

        given("cumulative usage across builds") {
            val target = typeSymbol("shared", ":api", "shared.api.Service")
            val firstCaller = callerScan("application-a", ":app", "app.a.Caller", target.qualifiedName)
            val secondCaller = callerScan("application-b", ":app", "app.b.Caller", target.qualifiedName)
            val targetScan = scan("shared", ":api", sourceFile(target))

            `when`("two builds reference a symbol that has no local references") {
                val index = WorkspaceIndexBuilder.build(listOf(targetScan, firstCaller, secondCaller))
                val usage = index.usages.first { it.symbol.qualifiedName == target.qualifiedName }

                then("local and cumulative inbound counts remain distinct") {
                    usage.localInbound shouldBe 0
                    usage.workspaceInbound shouldBe 2
                    usage.crossBuildInbound shouldBe 2
                    usage.isWorkspaceUsed shouldBe true
                }

                then("both source builds are retained") {
                    usage.incoming.map { it.source?.build } shouldContainExactly
                        listOf("application-a", "application-b")
                }
            }
        }

        given("owner-aware simple-name resolution") {
            val alpha = typeSymbol("alpha", ":lib", "alpha.Widget")
            val beta = typeSymbol("beta", ":lib", "beta.Widget")
            val alphaScan = scan("alpha", ":lib", sourceFile(alpha))
            val betaScan = scan("beta", ":lib", sourceFile(beta))
            val packageCaller = callerScan("client", ":app", "beta.Client", "beta.Widget")
            val buildCaller = callerScan("alpha", ":consumer", "other.Client", null, "Widget")
            val unrelatedCaller = callerScan("client", ":other", "other.Unrelated", null, "Widget")

            `when`("same simple names exist in different packages and builds") {
                val index =
                    WorkspaceIndexBuilder.build(
                        listOf(alphaScan, betaScan, packageCaller, buildCaller, unrelatedCaller),
                    )

                then("qualified source evidence disambiguates a globally duplicated simple name") {
                    val relationship = index.relationships.first { it.source?.qualifiedName == "beta.Client" }
                    relationship.target.qualifiedName shouldBe "beta.Widget"
                    relationship.target.build shouldBe "beta"
                }

                then("the owning build takes precedence when package ownership does not match") {
                    val relationship = index.relationships.first { it.source?.qualifiedName == "other.Client" }
                    relationship.target.qualifiedName shouldBe "alpha.Widget"
                    relationship.target.build shouldBe "alpha"
                }

                then("an unqualified name never escapes its owning build") {
                    index.references.any { it.sourceSymbol?.qualifiedName == "other.Unrelated" } shouldBe true
                    index.relationships.none { it.source?.qualifiedName == "other.Unrelated" } shouldBe true
                }
            }
        }

        given("duplicate fully qualified names") {
            val firstFile = sourceFile(typeSymbol("first", ":service", "duplicate.Service"))
            val firstCallerFile = callerFile("first", ":service", "first.Owner", "duplicate.Service")
            val first = scan("first", ":service", firstFile, firstCallerFile)
            val second = scan("second", ":service", sourceFile(typeSymbol("second", ":service", "duplicate.Service")))
            val outside = callerScan("outside", ":consumer", "outside.Owner", "duplicate.Service")
            val scans = listOf(first, second, outside)

            `when`("an owner and an outside build reference the duplicate FQN") {
                val index = WorkspaceIndexBuilder.build(scans)

                then("the owner resolves to its own scoped declaration") {
                    val relationship = index.relationships.first { it.source?.qualifiedName == "first.Owner" }
                    relationship.target.build shouldBe "first"
                    relationship.target.qualifiedName shouldBe "duplicate.Service"
                }

                then("the outside reference remains unresolved") {
                    index.references.any { it.sourceSymbol?.qualifiedName == "outside.Owner" } shouldBe true
                    index.relationships.none { it.source?.qualifiedName == "outside.Owner" } shouldBe true
                }

                then("duplicate declarations retain different stable scoped identities") {
                    val identities =
                        index.symbols
                            .filter { it.qualifiedName == "duplicate.Service" }
                            .map { it.identity.value }
                    identities.distinct() shouldHaveSize 2
                }

                then("reversing project input produces the same index") {
                    WorkspaceIndexBuilder.build(scans.reversed()) shouldBe index
                }
            }
        }

        given("interface implementation and import facts") {
            val contract = typeSymbol("api", ":api", "api.Contract", SymbolDetailKind.INTERFACE)
            val implementationFile = file("implementation", ":impl", "src/main/kotlin/impl/ContractImpl.kt")
            val implementation =
                Symbol(
                    "ContractImpl",
                    "impl.ContractImpl",
                    SymbolDetailKind.CLASS,
                    implementationFile,
                    4,
                    "impl",
                )
            val references =
                listOf(
                    Reference(
                        "Contract",
                        "api.Contract",
                        ReferenceKind.IMPORT,
                        implementationFile,
                        2,
                        "import api.Contract",
                    ),
                    Reference(
                        "Contract",
                        "api.Contract",
                        ReferenceKind.SUPERTYPE,
                        implementationFile,
                        4,
                        "Contract",
                        sourceQualifiedName = implementation.qualifiedName,
                    ),
                    Reference(
                        "Contract",
                        "api.Contract",
                        ReferenceKind.TYPE_REF,
                        implementationFile,
                        4,
                        "Contract",
                        sourceQualifiedName = implementation.qualifiedName,
                    ),
                )
            val api = scan("api", ":api", sourceFile(contract))
            val impl = scan("implementation", ":impl", sourceFile(implementation, references = references))

            `when`("the import and overlapping supertype facts resolve") {
                val index = WorkspaceIndexBuilder.build(listOf(api, impl))
                val contractUsage = index.usages.first { it.symbol.qualifiedName == "api.Contract" }

                then("the resolved target kind distinguishes implementation") {
                    index.relationships.count { it.kind == WorkspaceRelationshipKind.IMPLEMENTS } shouldBe 1
                    index.relationships.none { it.kind == WorkspaceRelationshipKind.TYPE_REFERENCE } shouldBe true
                }

                then("the parsed declaration form survives workspace indexing") {
                    contractUsage.symbol.declarationSemantic shouldBe DeclarationSemantic.INTERFACE
                    index.symbols
                        .first { it.qualifiedName == "impl.ContractImpl" }
                        .declarationSemantic shouldBe DeclarationSemantic.CONCRETE_CLASS
                }

                then("the import remains available without counting as usage") {
                    val import = index.relationships.first { it.kind == WorkspaceRelationshipKind.IMPORT }
                    import.source shouldBe null
                    contractUsage.workspaceInbound shouldBe 1
                    contractUsage.crossBuildInbound shouldBe 1
                    contractUsage.incoming.map { it.kind } shouldContainExactly
                        listOf(WorkspaceRelationshipKind.IMPLEMENTS)
                }

                then("source ownership and evidence retain file-level provenance") {
                    val relationship = contractUsage.incoming.single()
                    relationship.source?.qualifiedName shouldBe "impl.ContractImpl"
                    relationship.sourceEvidence.projectRelativeFile shouldBe
                        "src/main/kotlin/impl/ContractImpl.kt"
                    relationship.sourceEvidence.line shouldBe 4
                    relationship.sourceEvidence.context shouldBe "Contract"
                    relationship.evidence shouldBe ReferenceEvidence.DIRECT
                }
            }
        }

        given("specific relationship kinds") {
            val value = typeSymbol("api", ":api", "api.Value")
            val targetFile = file("api", ":api", "src/main/kotlin/api/Factory.kt")
            val factoryFunction =
                Symbol("Factory.create", "api.Factory.create", SymbolDetailKind.FUNCTION, targetFile, 3, "api")
            val api = scan("api", ":api", sourceFile(value), sourceFile(factoryFunction))
            val consumerFile = file("consumer", ":app", "src/main/kotlin/consumer/Use.kt")
            val consumer = Symbol("Use", "consumer.Use", SymbolDetailKind.CLASS, consumerFile, 1, "consumer")
            val property =
                Symbol("Use.value", "consumer.Use.value", SymbolDetailKind.PROPERTY, consumerFile, 3, "consumer")
            val function =
                Symbol("Use.run", "consumer.Use.run", SymbolDetailKind.FUNCTION, consumerFile, 5, "consumer")
            val references =
                listOf(
                    ownedReference(consumerFile, value, ReferenceKind.PROPERTY_TYPE, property, 3, "value: Value"),
                    ownedReference(consumerFile, value, ReferenceKind.PARAMETER_TYPE, function, 6, "input: Value"),
                    ownedReference(consumerFile, value, ReferenceKind.RETURN_TYPE, function, 7, "): Value"),
                    ownedReference(
                        consumerFile,
                        value,
                        ReferenceKind.CONSTRUCTOR,
                        function,
                        8,
                        "Value()",
                        ReferenceEvidence.HEURISTIC,
                    ),
                    ownedReference(consumerFile, value, ReferenceKind.TYPE_REF, function, 8, "Value"),
                    ownedReference(consumerFile, factoryFunction, ReferenceKind.CALL, function, 9, "Factory.create()"),
                    ownedReference(consumerFile, factoryFunction, ReferenceKind.NAME_REF, function, 9, "create"),
                )
            val consumerScan =
                scan(
                    "consumer",
                    ":app",
                    sourceFile(consumer, property, function, references = references),
                )

            `when`("overlapping direct and generic PSI facts resolve") {
                val index = WorkspaceIndexBuilder.build(listOf(api, consumerScan))
                val relationships = index.relationships.filter { it.source?.build == "consumer" }

                then("the most specific kinds are retained") {
                    relationships.map { it.kind }.toSet() shouldBe
                        setOf(
                            WorkspaceRelationshipKind.PROPERTY_TYPE,
                            WorkspaceRelationshipKind.PARAMETER_TYPE,
                            WorkspaceRelationshipKind.RETURN_TYPE,
                            WorkspaceRelationshipKind.CONSTRUCTOR,
                            WorkspaceRelationshipKind.CALL,
                        )
                }

                then("heuristic constructor evidence is not upgraded") {
                    relationships.first { it.kind == WorkspaceRelationshipKind.CONSTRUCTOR }.evidence shouldBe
                        ReferenceEvidence.HEURISTIC
                }

                then("incoming and outgoing relationships use scoped identities") {
                    val valueUsage = index.usages.first { it.symbol.qualifiedName == "api.Value" }
                    val functionUsage = index.usages.first { it.symbol.qualifiedName == "consumer.Use.run" }
                    valueUsage.workspaceInbound shouldBe 4
                    valueUsage.localInbound shouldBe 0
                    valueUsage.crossBuildInbound shouldBe 4
                    functionUsage.outgoing.map { it.kind }.toSet() shouldBe
                        setOf(
                            WorkspaceRelationshipKind.PARAMETER_TYPE,
                            WorkspaceRelationshipKind.RETURN_TYPE,
                            WorkspaceRelationshipKind.CONSTRUCTOR,
                            WorkspaceRelationshipKind.CALL,
                        )
                }

                then("grouped usage is exactly equivalent to filtering the retained relationships") {
                    index.usages.forEach { usage ->
                        usage.incoming shouldContainExactly
                            index.relationships.filter { relationship ->
                                relationship.kind != WorkspaceRelationshipKind.IMPORT &&
                                    relationship.targetIdentity == usage.symbol.identity
                            }
                        usage.outgoing shouldContainExactly
                            index.relationships.filter { relationship ->
                                relationship.kind != WorkspaceRelationshipKind.IMPORT &&
                                    relationship.sourceIdentity == usage.symbol.identity
                            }
                    }
                }
            }
        }

        given("a synthetic workspace with thousands of projects across eighty builds") {
            val buildCount = 80
            val projectsPerBuild = 32
            val projectCount = buildCount * projectsPerBuild
            val scans =
                (0 until buildCount).flatMap { buildOrdinal ->
                    (0 until projectsPerBuild).map { projectOrdinal ->
                        syntheticScan(buildOrdinal, projectOrdinal)
                    }
                }

            `when`("the cumulative index is built") {
                val index = WorkspaceIndexBuilder.build(scans)

                then("every declaration and reference is retained without a quadratic result expansion") {
                    index.symbols shouldHaveSize projectCount * 2
                    index.references shouldHaveSize projectCount
                    index.relationships shouldHaveSize projectCount
                    index.usages shouldHaveSize projectCount * 2
                }

                then("each retained edge is assigned to one incoming and one outgoing adjacency list") {
                    index.usages.sumOf { it.incoming.size } shouldBe projectCount
                    index.usages.sumOf { it.outgoing.size } shouldBe projectCount
                    index.usages.count { it.incoming.size == 1 } shouldBe projectCount
                    index.usages.count { it.outgoing.size == 1 } shouldBe projectCount
                }

                then("input order still has no effect at multi-build scale") {
                    WorkspaceIndexBuilder.build(scans.reversed()) shouldBe index
                }
            }
        }

        given("overloaded source declarations with one qualified name") {
            val target = typeSymbol("api", ":api", "api.Value")
            val sourcePath = file("consumer", ":app", "src/main/kotlin/consumer/Use.kt")
            val first =
                Symbol(
                    "Use.run",
                    "consumer.Use.run",
                    SymbolDetailKind.FUNCTION,
                    sourcePath,
                    5,
                    "consumer",
                    signature = "fun consumer.Use.run(kotlin.String)",
                )
            val second = first.copy(line = 20, signature = "fun consumer.Use.run(kotlin.Int)")
            val reference = ownedReference(sourcePath, target, ReferenceKind.TYPE_REF, second, 25, "Value")
            val scans =
                listOf(
                    scan("api", ":api", sourceFile(target)),
                    scan("consumer", ":app", sourceFile(first, second, references = listOf(reference))),
                )

            `when`("a reference follows both declarations in the same file") {
                val relationship = WorkspaceIndexBuilder.build(scans).relationships.single()

                then("the nearest preceding declaration remains the relationship source") {
                    relationship.source?.declarationLine shouldBe 20
                    relationship.source?.signature shouldBe "fun consumer.Use.run(kotlin.Int)"
                    relationship.sourceEvidence.line shouldBe 25
                }

                then("overload identities use signatures rather than source lines") {
                    val identities = WorkspaceIndexBuilder.build(scans).symbols.map { it.identity }
                    identities.distinct().size shouldBe identities.size
                }
            }
        }
    })

private fun syntheticScan(
    buildOrdinal: Int,
    projectOrdinal: Int,
): ProjectScan {
    val build = "build-${buildOrdinal.toString().padStart(2, '0')}"
    val project = ":project-${projectOrdinal.toString().padStart(2, '0')}"
    val packageName = "scale.build$buildOrdinal.project$projectOrdinal"
    val sourcePath = file(build, project, "src/main/kotlin/scale/Project.kt")
    val target = Symbol("Target", "$packageName.Target", SymbolDetailKind.CLASS, sourcePath, 1, packageName)
    val caller = Symbol("Caller", "$packageName.Caller", SymbolDetailKind.CLASS, sourcePath, 2, packageName)
    val reference =
        Reference(
            targetName = target.name,
            targetQualifiedName = target.qualifiedName,
            kind = ReferenceKind.TYPE_REF,
            file = sourcePath,
            line = 3,
            context = target.name,
            sourceQualifiedName = caller.qualifiedName,
        )
    return scan(build, project, sourceFile(target, caller, references = listOf(reference)))
}

private fun typeSymbol(
    build: String,
    project: String,
    qualifiedName: String,
    kind: SymbolDetailKind = SymbolDetailKind.CLASS,
): Symbol =
    Symbol(
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = kind,
        file = file(build, project, "src/main/kotlin/${qualifiedName.replace('.', '/')}.kt"),
        line = 1,
        packageName = qualifiedName.substringBeforeLast('.', ""),
    )

private fun callerScan(
    build: String,
    project: String,
    callerQualifiedName: String,
    targetQualifiedName: String?,
    targetName: String = targetQualifiedName?.substringAfterLast('.') ?: "Target",
): ProjectScan = scan(build, project, callerFile(build, project, callerQualifiedName, targetQualifiedName, targetName))

private fun callerFile(
    build: String,
    project: String,
    callerQualifiedName: String,
    targetQualifiedName: String?,
    targetName: String = targetQualifiedName?.substringAfterLast('.') ?: "Target",
): ProjectFileScan {
    val sourcePath = file(build, project, "src/main/kotlin/${callerQualifiedName.replace('.', '/')}.kt")
    val caller =
        Symbol(
            callerQualifiedName.substringAfterLast('.'),
            callerQualifiedName,
            SymbolDetailKind.CLASS,
            sourcePath,
            1,
            callerQualifiedName.substringBeforeLast('.', ""),
        )
    val reference =
        Reference(
            targetName,
            targetQualifiedName,
            ReferenceKind.TYPE_REF,
            sourcePath,
            3,
            targetName,
            sourceQualifiedName = callerQualifiedName,
        )
    return sourceFile(caller, references = listOf(reference))
}

@Suppress("LongParameterList")
private fun ownedReference(
    file: File,
    target: Symbol,
    kind: ReferenceKind,
    source: Symbol,
    line: Int,
    context: String,
    evidence: ReferenceEvidence = ReferenceEvidence.DIRECT,
): Reference =
    Reference(
        targetName = target.name.substringAfterLast('.'),
        targetQualifiedName = target.qualifiedName,
        kind = kind,
        file = file,
        line = line,
        context = context,
        sourceQualifiedName = source.qualifiedName,
        evidence = evidence,
    )

private fun sourceFile(
    vararg declarations: Symbol,
    references: List<Reference> = emptyList(),
): ProjectFileScan {
    val file = declarations.first().file
    val marker = "/src/"
    val relativePath = "src/${file.path.substringAfter(marker)}"
    return ProjectFileScan(SourceSetName("main"), relativePath, declarations.toList(), references)
}

private fun scan(
    build: String,
    project: String,
    vararg files: ProjectFileScan,
): ProjectScan {
    val path = ProjectPath(project)
    val summary =
        ProjectSummary(
            projectPath = path,
            symbols = emptyList(),
            dependencies = emptyList(),
            buildFile = "none",
            sourceDirs = emptyList(),
            subprojects = emptyList(),
        )
    return ProjectScan(build, path, files.toList(), summary)
}

private fun file(build: String, project: String, projectRelativeFile: String): File =
    File("/workspace/$build/${project.removePrefix(":")}/$projectRelativeFile")
