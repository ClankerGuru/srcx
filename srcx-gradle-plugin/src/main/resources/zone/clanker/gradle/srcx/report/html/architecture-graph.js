(function () {
    "use strict";

    wireDocumentNavigation();
    wireFindingFilters();
    wireArchitectureEvidenceActions();

    if (!window.d3) return;

    document.querySelectorAll("[data-srcx-architecture-graph]").forEach(safelyEnhanceGraph);

    function safelyEnhanceGraph(root, graphIndex) {
        if (root.dataset.srcxEnhanced === "true") return;
        try {
            if (!enhanceGraph(root, graphIndex)) restoreFallback(root);
        } catch (error) {
            if (typeof root.__srcxArchitectureCleanup === "function") root.__srcxArchitectureCleanup();
            restoreFallback(root);
            root.dataset.srcxEnhancement = "failed";
            if (window.console && typeof window.console.warn === "function") {
                window.console.warn("SRCX architecture map could not be enhanced; using the static fallback.", error);
            }
        }
    }

    function restoreFallback(root) {
        var fallback = root.querySelector("[data-srcx-graph-fallback]");
        var controls = root.querySelector("[data-srcx-graph-controls]");
        var navigatorElement = root.querySelector("[data-srcx-graph-navigator]");
        var svg = root.querySelector("[data-srcx-graph-svg]");
        var detail = root.querySelector("[data-srcx-detail]");
        var detailResize = root.querySelector("[data-srcx-detail-resize]");
        if (fallback) fallback.hidden = false;
        if (controls) controls.hidden = true;
        if (navigatorElement) navigatorElement.hidden = true;
        if (svg) svg.setAttribute("hidden", "");
        if (detail) {
            detail.classList.remove("is-open");
            detail.hidden = true;
        }
        if (detailResize) detailResize.hidden = true;
        delete root.dataset.srcxEnhanced;
    }

    function enhanceGraph(root, graphIndex) {
        var dataElement = root.querySelector("[data-srcx-architecture-data]");
        var svgElement = root.querySelector("[data-srcx-graph-svg]");
        var fallback = root.querySelector("[data-srcx-graph-fallback]");
        var controls = root.querySelector("[data-srcx-graph-controls]");
        var navigatorElement = root.querySelector("[data-srcx-graph-navigator]");
        var buildFilter = navigatorElement && navigatorElement.querySelector("[data-srcx-build-filter]");
        var projectFilter = navigatorElement && navigatorElement.querySelector("[data-srcx-project-filter]");
        var sourceSetFilter = navigatorElement && navigatorElement.querySelector("[data-srcx-source-set-filter]");
        var pathFilter = navigatorElement && navigatorElement.querySelector("[data-srcx-path-filter]");
        var pathBreadcrumb = pathFilter && pathFilter.querySelector("[data-srcx-path-breadcrumb]");
        var pathUp = pathFilter && pathFilter.querySelector("[data-srcx-path-up]");
        var pathAll = pathFilter && pathFilter.querySelector("[data-srcx-path-all]");
        var pathClear = pathFilter && pathFilter.querySelector("[data-srcx-path-clear]");
        var pathChips = pathFilter && pathFilter.querySelector("[data-srcx-path-chips]");
        var pathChipPrevious = pathFilter && pathFilter.querySelector("[data-srcx-path-chip-prev]");
        var pathChipNext = pathFilter && pathFilter.querySelector("[data-srcx-path-chip-next]");
        var pathChipStatus = pathFilter && pathFilter.querySelector("[data-srcx-path-chip-status]");
        var pathTree = pathFilter && pathFilter.querySelector("[data-srcx-path-tree]");
        var pathPrevious = pathFilter && pathFilter.querySelector("[data-srcx-path-previous]");
        var pathNext = pathFilter && pathFilter.querySelector("[data-srcx-path-next]");
        var pathPageStatus = pathFilter && pathFilter.querySelector("[data-srcx-path-page-status]");
        var pathStatus = pathFilter && pathFilter.querySelector("[data-srcx-path-status]");
        var filterContext = navigatorElement && navigatorElement.querySelector("[data-srcx-filter-context]");
        var viewport = root.querySelector(".srcx-dashboard__architecture-viewport");
        var detail = root.querySelector("[data-srcx-detail]");
        var detailResize = root.querySelector("[data-srcx-detail-resize]");
        var status = root.querySelector("[data-srcx-graph-status]");
        var search = controls && controls.querySelector("[data-srcx-graph-search]");
        var relationshipKindFilter = controls && controls.querySelector("[data-srcx-relationship-kind-filter]");
        var relationshipKindOptions = controls && controls.querySelector("[data-srcx-relationship-kind-options]");
        var searchRecovery = controls && controls.querySelector("[data-srcx-search-recovery]");
        var searchRecoveryStatus = controls && controls.querySelector("[data-srcx-search-recovery-status]");
        var searchRecoveryAction = controls && controls.querySelector("[data-srcx-search-recovery-action]");
        var fullscreenButton = controls && controls.querySelector("[data-srcx-graph-fullscreen]");
        var fullscreenChromeToggle = root.querySelector("[data-srcx-fullscreen-chrome-toggle]");
        var detailKicker = detail && detail.querySelector("[data-srcx-detail-kicker]");
        var detailTitle = detail && detail.querySelector("[data-srcx-detail-title]");
        var detailFields = detail && detail.querySelector("[data-srcx-detail-fields]");
        var detailClose = detail && detail.querySelector("[data-srcx-detail-close]");
        if (!dataElement || !svgElement || !fallback || !controls || !navigatorElement || !buildFilter ||
            !projectFilter || !sourceSetFilter || !filterContext || !viewport ||
            !detail || !status || !search ||
            !fullscreenButton || !fullscreenChromeToggle ||
            !relationshipKindFilter || !relationshipKindOptions || !searchRecovery || !searchRecoveryStatus ||
            !searchRecoveryAction || !detailKicker || !detailTitle || !detailFields || !detailClose ||
            !detailResize) return false;

        var data;
        try {
            data = JSON.parse(dataElement.textContent);
        } catch (ignored) {
            return false;
        }
        if (!validGraphData(data)) return false;

        var state = {
            view: data.defaultView,
            query: "",
            selectedId: null,
            selectedType: null,
            selectedEdge: null,
            rovingNodeId: null,
            rovingEdgeId: null,
            selectedBuild: null,
            selectedProject: null,
            selectedSourceSet: null,
            pathSelectionMode: "all",
            selectedPathKeys: new Set(),
            pathDirectoryParts: [],
            pathPage: 0,
            pathChipPage: 0,
            pathRovingEntryId: null,
            selectedRelationshipKind: "all",
            cycleStepEdgeId: null,
            detailWidth: null,
            selectedEvidence: null,
            previewEvidence: null,
            externalEvidenceRequest: null,
            selectedNodeIds: new Set(),
            boxSelectMode: false,
            fileIndexedCount: 0,
            fileCandidateCount: 0,
            fileRelationshipRecordCount: 0,
            symbolIndexedCount: 0,
            symbolCandidateCount: 0,
            symbolRelationshipRecordCount: 0,
        };
        var svg = d3.select(svgElement);
        var initialSize = viewportSize(viewport);
        var width = initialSize.width;
        var height = initialSize.height;
        var zoomLayer;
        var nodeGroups;
        var edgeGroups;
        var edgeLayer;
        var nodes = [];
        var links = [];
        var renderedLinks = [];
        var edgeNavigationLinks = [];
        var incidentLinksByNode = new Map();
        var renderEdgeSubset = function () {};
        var simulation;
        var buildGroups;
        var projectGroups;
        var subgroupLayer;
        var subgroupGroups;
        var layout;
        var lassoRectangle;
        var neighborhoodFocusContext = null;
        var pinnedLabelIds = new Set();
        var manualNodePositions = new Map();
        var manualBuildOffsets = new Map();
        var manualProjectOffsets = new Map();
        var buildByName = new Map(data.builds.map(function (build) { return [build.name, build]; }));
        var sourceFileById = new Map(data.sourceFiles.map(function (sourceFile) {
            return [sourceFile.id, sourceFile];
        }));
        var sourceFileByScopePath = new Map(data.sourceFiles.map(function (sourceFile) {
            return [scopePathKey(sourceFile.build, sourceFile.project, sourceFile.sourceSet, sourceFile.path), sourceFile];
        }));
        var declarationById = declarationIndex(data);
        var availableFileNodes = data.availableFileNodes || data.fileNodes;
        var availableFileEdges = data.availableFileEdges || data.fileEdges;
        var availableSymbolNodes = data.availableNodes || data.nodes;
        var availableSymbolEdges = data.availableEdges || data.edges;
        var availableObservedCycles = data.availableCycles || data.cycles;
        var fileEntityById = new Map(availableFileNodes.map(function (node) { return [node.id, node]; }));
        var fileEntityByScopePath = new Map(availableFileNodes.map(function (node) {
            return [scopePathKey(node.build, node.project, node.sourceSet, node.path), node];
        }));
        var symbolEntityById = new Map(availableSymbolNodes.map(function (node) { return [node.id, node]; }));
        var fileEdgeById = new Map(availableFileEdges.map(function (edge) { return [edge.id, edge]; }));
        var analysisCycleById = new Map(data.analysisCycles.map(function (cycle) { return [cycle.id, cycle]; }));
        var findingById = new Map(data.findings.map(function (finding) { return [finding.id, finding]; }));
        var availableRelationshipCategories = relationshipCategories(data);
        var dashboard = root.closest(".srcx-dashboard") || root;
        var drawGeneration = 0;
        var fitTimer = null;
        var resizeFrame = null;
        var resizeObserver = null;
        var userNavigated = false;
        var destroyed = false;
        var detailReturnFocus = null;
        var fallbackFullscreen = false;
        var fullscreenUiActive = false;
        var fullscreenFitTimer = null;
        var savedDocumentOverflow = null;
        var savedViewportPosition = null;
        var fullscreenReturnFocus = null;
        var detailCloseTimer = null;
        var highlightRestoreTimer = null;
        var activeEvidenceHost = null;
        var activeEvidenceStatus = null;
        var hoveredRelationshipPreview = null;
        var focusedRelationshipPreview = null;
        var selectionToolbar = null;
        var selectionStatus = null;
        var boxSelectButton = null;
        var selectProjectButton = null;
        var selectBuildButton = null;
        var frameSelectionButton = null;
        var frameProjectButton = null;
        var frameBuildButton = null;
        var clearNodeSelectionButton = null;
        var lassoGesture = null;
        var nodeDragContext = null;
        var scopeDragContext = null;
        var suppressScopeClickUntil = 0;
        var suppressNodeClickUntil = 0;
        var suppressCanvasClickUntil = 0;
        var filterRailCleanups = [];
        var chromeDisclosureResizePending = false;
        var chromeDisclosureResizeStableFrame = null;
        var chromeDisclosureResizeRevision = 0;
        var detailResizePointerId = null;
        var detailResizeStartX = 0;
        var detailResizeStartWidth = 0;
        var detailWidthStorageKey = "srcx-atlas-detail-width-" + graphIndex;
        var architectureEvidenceEventHandler = null;
        var pendingPathAnnouncement = null;
        var pathPageSize = 8;
        var pathChipPageSize = 6;
        var pathRootSentinel = "__srcx_bounded_path_root__";
        var staticLayoutNodeThreshold = 240;
        var minimumZoomScale = 0.01;
        var minReadableScale = 0.86;
        var maximumZoomScale = 4;
        var expandedSymbols = data.availableNodes ? {
            nodes: availableSymbolNodes,
            edges: availableSymbolEdges,
        } : expandedSymbolProjection();
        expandedSymbols.nodes.forEach(function (node) { symbolEntityById.set(node.id, node); });
        var zoom = d3.zoom()
            .scaleExtent([minimumZoomScale, maximumZoomScale])
            .filter(function (event) {
                if ((event.type === "mousedown" || event.type === "pointerdown") &&
                    (state.boxSelectMode || event.shiftKey)) return false;
                return event.type !== "wheel" || event.ctrlKey || event.metaKey;
            })
            .on("zoom", function (event) {
                if (event.sourceEvent) markUserNavigation();
                if (zoomLayer) zoomLayer.attr("transform", event.transform);
            });

        root.__srcxArchitectureCleanup = cleanup;
        svgElement.removeAttribute("hidden");
        svg.attr("viewBox", "0 0 " + width + " " + height).call(zoom).on("dblclick.zoom", null);
        draw();
        wireControls();
        wireLassoSelection();
        ensureSelectionToolbar();
        wireDetailResize();
        restoreDetailWidth();
        if (pathFilter) wirePathFilter();
        renderNavigator();
        renderRelationshipKindFilter();
        wireArchitectureEvidenceApi();

        root.dataset.srcxEnhanced = "true";
        root.dataset.srcxEnhancement = "ready";
        fallback.hidden = true;
        controls.hidden = false;
        navigatorElement.hidden = false;

        if (window.ResizeObserver) {
            resizeObserver = new ResizeObserver(requestResize);
            resizeObserver.observe(viewport);
        }
        window.addEventListener("resize", requestResize, { passive: true });
        return true;

        function draw() {
            var generation = ++drawGeneration;
            cancelScheduledFit();
            if (simulation) simulation.stop();
            neighborhoodFocusContext = null;
            simulation = null;
            userNavigated = false;
            zoomLayer = null;
            buildGroups = null;
            projectGroups = null;
            subgroupLayer = null;
            subgroupGroups = null;
            lassoRectangle = null;
            layout = null;
            svg.interrupt().call(zoom.transform, d3.zoomIdentity);
            svg.selectAll("*").remove();
            var projection = projectionForView();
            nodes = projection.nodes;
            links = projection.links;
            root.dataset.srcxGraphLens = state.view;
            root.dataset.srcxGraphDensity = nodes.length > 120 ? "dense" : "normal";
            root.dataset.srcxGraphFocus = "full";
            var nodeById = new Map(nodes.map(function (node) { return [node.id, node]; }));
            state.selectedNodeIds = new Set(Array.from(state.selectedNodeIds).filter(function (id) {
                return nodeById.has(id);
            }));
            links = links.filter(function (edge) {
                return nodeById.has(endpointId(edge.source)) && nodeById.has(endpointId(edge.target));
            });
            links.forEach(function (edge) {
                edge.source = nodeById.get(endpointId(edge.source));
                edge.target = nodeById.get(endpointId(edge.target));
            });
            assignNodeVisualHierarchy(nodes);
            assignLinkGeometry(links);
            incidentLinksByNode = indexIncidentLinks(links);
            ensureRovingEdge();
            var retainedSelection = reconcileSelection();
            if (nodes.length === 0) {
                state.rovingNodeId = null;
                state.rovingEdgeId = null;
                root.dataset.srcxEmpty = "true";
                svg.append("text")
                    .attr("class", "srcx-dashboard__architecture-empty-label")
                    .attr("x", width / 2)
                    .attr("y", height / 2)
                    .text(emptyViewMessage(projection));
                nodeGroups = svg.selectAll(".srcx-dashboard__architecture-svg-node");
                edgeGroups = svg.selectAll(".srcx-dashboard__architecture-svg-edge");
                updateStatus(projection);
                renderSearchRecovery(projection);
                updateSelectionToolbar();
                return;
            }
            root.dataset.srcxEmpty = "false";

            var markerId = "srcx-atlas-arrow-" + graphIndex;
            var definitions = svg.append("defs");
            definitions.append("marker")
                .attr("id", markerId)
                .attr("viewBox", "0 0 10 10")
                .attr("refX", 10)
                .attr("refY", 5)
                .attr("markerWidth", 10)
                .attr("markerHeight", 10)
                .attr("markerUnits", "userSpaceOnUse")
                .attr("orient", "auto")
                .append("path")
                .attr("d", "M0,0 L10,5 L0,10 Z");

            zoomLayer = svg.append("g").attr("class", "srcx-dashboard__architecture-zoom-layer");
            buildGroups = zoomLayer.append("g")
                .attr("class", "srcx-dashboard__architecture-build-regions")
                .selectAll("g")
                .data(representedBuilds(nodes, buildByName), function (build) { return build.name; })
                .join("g")
                .attr("class", "srcx-dashboard__architecture-build-region")
                .attr("role", "button")
                .attr("tabindex", "-1")
                .attr("aria-label", function (build) {
                    return "Build region " + build.name + "; drag to move the whole build";
                })
                .on("click", function (event, build) {
                    if (Date.now() < suppressScopeClickUntil) return;
                    event.stopPropagation();
                    selectRegionNodes("build", { build: build.name });
                });
            buildGroups.append("rect")
                .attr("class", "srcx-dashboard__architecture-build-region-body")
                .attr("fill", function (build) { return build.color; })
                .attr("stroke", function (build) { return build.color; });
            buildGroups.append("text").each(function (build) {
                appendBuildRegionLabel(d3.select(this), build);
            });
            buildGroups.append("rect")
                .attr("class", "srcx-dashboard__architecture-build-drag-handle")
                .attr("aria-hidden", "true");
            projectGroups = zoomLayer.append("g")
                .attr("class", "srcx-dashboard__architecture-project-regions")
                .selectAll("g")
                .data(representedProjects(nodes), function (project) { return project.key; })
                .join("g")
                .attr("class", "srcx-dashboard__architecture-project-region")
                .attr("role", "button")
                .attr("tabindex", "-1")
                .attr("aria-label", function (project) {
                    return "Project region " + projectDisplayName(project.project, project.build) +
                        "; drag to move the whole project";
                })
                .on("click", function (event, project) {
                    if (Date.now() < suppressScopeClickUntil) return;
                    event.stopPropagation();
                    selectRegionNodes("project", project);
                });
            projectGroups.append("rect").attr("class", "srcx-dashboard__architecture-project-region-body");
            projectGroups.append("text").text(projectRegionLabel);
            projectGroups.append("rect")
                .attr("class", "srcx-dashboard__architecture-project-drag-handle")
                .attr("aria-hidden", "true");
            subgroupLayer = zoomLayer.append("g")
                .attr("class", "srcx-dashboard__architecture-subgroup-regions")
                .attr("aria-hidden", "true");
            edgeLayer = zoomLayer.append("g").attr("class", "srcx-dashboard__architecture-edge-layer");
            renderEdgeSubset = function (nextLinks) {
                renderedLinks = nextLinks.slice();
                edgeGroups = edgeLayer.selectAll("g.srcx-dashboard__architecture-svg-edge")
                    .data(renderedLinks, function (edge) { return edge.id; })
                    .join(
                        function (enter) { return appendInteractiveEdges(enter, markerId, nodeById); },
                        function (update) { return update.attr("class", edgeClass); },
                        function (exit) { return exit.remove(); },
                    );
                positionRenderedEdges(edgeGroups, renderedLinks, nodes);
            };
            renderEdgeSubset([]);

            pinnedLabelIds = persistentLabelIds(nodes);
            ensureRovingNode();
            nodeGroups = zoomLayer.append("g")
                .selectAll("g")
                .data(nodes, function (node) { return node.id; })
                .join("g")
                .attr("class", nodeClass)
                .classed("is-label-pinned", function (node) { return pinnedLabelIds.has(node.id); })
                .classed("is-group-selected", function (node) { return state.selectedNodeIds.has(node.id); })
                .attr("role", "button")
                .attr("tabindex", function (node) { return node.id === state.rovingNodeId ? 0 : -1; })
                .attr("focusable", "true")
                .attr("aria-label", function (node) { return "Select " + accessibleNodeName(node); })
                .on("mouseenter", function (event, node) {
                    cancelHighlightRestore();
                    highlight(node.id);
                })
                .on("focus", function (event, node) {
                    cancelHighlightRestore();
                    setRovingNode(node, false);
                    highlight(node.id);
                })
                .on("mouseleave blur", scheduleHighlightRestore)
                .on("click", function (event, node) {
                    if (consumeSuppressedNodeClick(event)) return;
                    event.stopPropagation();
                    if (event.ctrlKey || event.metaKey || event.shiftKey) {
                        event.preventDefault();
                        toggleNodeSelection(node);
                        return;
                    }
                    selectNode(node, event.currentTarget);
                })
                .on("keydown", function (event, node) {
                    if (activationKey(event)) {
                        event.preventDefault();
                        selectNode(node, event.currentTarget);
                    } else if (event.altKey && arrowKey(event)) {
                        event.preventDefault();
                        nudgeNodeSelection(node, event.key);
                    } else if (arrowKey(event)) {
                        event.preventDefault();
                        focusAdjacentNode(node, event.key);
                    } else if (event.key === "r" || event.key === "R") {
                        event.preventDefault();
                        focusIncidentEdge(node);
                    }
                });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-hit")
                .attr("r", function (node) { return outerNodeRadius(node) + 6; });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-dot")
                .attr("r", nodeRadius)
                .attr("fill", function (node) { return buildColor(node.build, buildByName); });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-ring is-importance-ring")
                .attr("r", function (node) { return nodeRadius(node) + 4; });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-ring is-finding-ring")
                .attr("r", function (node) { return nodeRadius(node) + 7; });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-ring is-cycle-ring")
                .attr("r", function (node) { return nodeRadius(node) + 10; });
            nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-ring is-analysis-cycle-ring")
                .attr("r", function (node) { return nodeRadius(node) + 13; });
            nodeGroups.filter(function (node) {
                return node.entityType === "symbol" && node.sourceFileFindingCount > 0 &&
                    pinnedLabelIds.has(node.id);
            }).append("rect")
                .attr("class", "srcx-dashboard__architecture-symbol-file-finding-marker")
                .attr("width", 7)
                .attr("height", 7)
                .attr("x", -3.5)
                .attr("y", -3.5)
                .attr("transform", function (node) {
                    var distance = outerNodeRadius(node) * 0.72;
                    return "translate(" + distance + "," + -distance + ") rotate(45)";
                });
            nodeGroups.append("text")
                .attr("class", "srcx-dashboard__architecture-svg-node-name srcx-dashboard__architecture-svg-node-label")
                .each(function (node) { appendNodeLabel(d3.select(this), node); });
            nodeGroups.append("title").text(function (node) { return nodeTitle(node); });
            lassoRectangle = zoomLayer.append("rect")
                .attr("class", "srcx-dashboard__architecture-selection-box")
                .attr("pointer-events", "none")
                .style("display", "none");

            nodes.forEach(function (node) { node.labelPinned = pinnedLabelIds.has(node.id); });
            measureNodeLabels(nodeGroups);
            measureBuildLabels(buildGroups);
            layout = buildCellLayout(nodes, width, height, buildByName);
            applyManualScopeOffsets(nodes, layout);
            subgroupGroups = renderSubgroupRegions(subgroupLayer, layout.subgroups, layout.subgroupCells);
            initializeNodes(nodes, layout);
            restorePinnedNodePositions(nodes);
            assignLabelDirections(nodes, layout.cells, layout.subgroupCells);
            constrainNodesToBuildCells(nodes, layout.cells, layout.projectCells, layout.subgroupCells);
            positionLabels(nodeGroups);
            updateBuildRegions(nodes, buildGroups, layout.cells);
            updateProjectRegions(projectGroups, layout.projectCells, nodes);
            updateSubgroupRegions(subgroupGroups, layout.subgroupCells);
            assignLayoutDegrees(nodes, links);
            simulation = nodes.length > staticLayoutNodeThreshold ? staticNodeSimulation(nodes) :
                connectedNodeSimulation(nodes, links, layout);
            simulation.stop();
            if (nodes.length <= staticLayoutNodeThreshold) {
                for (var tick = 0; tick < 180; tick += 1) simulation.tick();
            }
            ticked();
            fitGraph(false);
            nodeGroups.call(d3.drag().on("start", dragStarted).on("drag", dragged).on("end", dragEnded));
            buildGroups.call(d3.drag()
                .subject(function (event) { return { x: event.x, y: event.y }; })
                .on("start", function (event, build) {
                    scopeDragStarted(event, "build", { build: build.name });
                })
                .on("drag", scopeDragged)
                .on("end", scopeDragEnded));
            projectGroups.call(d3.drag()
                .subject(function (event) { return { x: event.x, y: event.y }; })
                .on("start", function (event, project) { scopeDragStarted(event, "project", project); })
                .on("drag", scopeDragged)
                .on("end", scopeDragEnded));
            svg.on("click", function () {
                if (Date.now() < suppressCanvasClickUntil) return;
                clearSelection(true);
                clearNodeSelection();
            });
            if (retainedSelection) restoreSelection(retainedSelection);
            updateStatus(projection);
            renderSearchRecovery(projection);
            updateSelectionToolbar();
        }

        function connectedNodeSimulation(nodes, links, layout) {
            return d3.forceSimulation(nodes)
                .randomSource(d3.randomLcg(0.42))
                .force("link", d3.forceLink(links).id(function (node) { return node.id; })
                    .distance(linkDistance).strength(function (edge) { return edge.crossBuild ? 0.035 : 0.14; }))
                .force("charge", d3.forceManyBody().strength(-240).distanceMax(520))
                .force("collision", d3.forceCollide().radius(function (node) {
                    return collisionRadius(node, pinnedLabelIds);
                }).strength(0.92))
                .force("x", d3.forceX(function (node) { return layout.homes.get(node.id).x; })
                    .strength(homeForceStrength))
                .force("y", d3.forceY(function (node) { return layout.homes.get(node.id).y; })
                    .strength(homeForceStrength))
                .on("tick", ticked);
        }

        function staticNodeSimulation(nodes) {
            return d3.forceSimulation(nodes).stop().on("tick", ticked);
        }

        function appendInteractiveEdges(enter, markerId, nodeById) {
            var groups = enter.append("g")
                .attr("class", edgeClass)
                .attr("role", "button")
                .attr("tabindex", function (edge) { return edge.id === state.rovingEdgeId ? 0 : -1; })
                .attr("focusable", "true")
                .attr("aria-label", function (edge) { return relationshipLabel(edge, nodeById); })
                .on("mouseenter", function (event, edge) {
                    cancelHighlightRestore();
                    highlightEdge(edge);
                    hoveredRelationshipPreview = edge;
                    previewRelationshipEvidence(edge, "Hovered relationship");
                })
                .on("focus", function (event, edge) {
                    cancelHighlightRestore();
                    setRovingEdge(edge, false);
                    highlightEdge(edge);
                    focusedRelationshipPreview = edge;
                    previewRelationshipEvidence(edge, "Focused relationship");
                })
                .on("mouseleave", function (event, edge) {
                    hoveredRelationshipPreview = null;
                    restoreRelationshipPreview(edge);
                    scheduleHighlightRestore();
                })
                .on("blur", function (event, edge) {
                    focusedRelationshipPreview = null;
                    restoreRelationshipPreview(edge);
                    scheduleHighlightRestore();
                })
                .on("click", function (event, edge) {
                    event.stopPropagation();
                    selectEdge(edge, event.currentTarget);
                })
                .on("keydown", function (event, edge) {
                    if (activationKey(event)) {
                        event.preventDefault();
                        selectEdge(edge, event.currentTarget);
                    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
                        event.preventDefault();
                        focusAdjacentEdge(edge, event.key === "ArrowLeft" ? -1 : 1);
                    } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
                        event.preventDefault();
                        focusEdgeEndpoint(edge, event.key === "ArrowUp" ? "source" : "target");
                    }
                });
            groups.append("path")
                .attr("class", "srcx-dashboard__architecture-svg-edge-line")
                .attr("pointer-events", "none")
                .attr("marker-end", "url(#" + markerId + ")")
                .attr("stroke-width", function (edge) {
                    if (isAnalysisCycleEdge(edge)) return 2.4;
                    return Math.min(4, 0.8 + Math.log2(edgeRecordCount(edge) + 1));
                });
            groups.append("path").attr("class", "srcx-dashboard__architecture-svg-edge-hit");
            var badges = groups.filter(function (edge) { return !isAnalysisCycleEdge(edge); })
                .append("g")
                .attr("class", "srcx-dashboard__architecture-svg-edge-count")
                .attr("role", "img")
                .attr("aria-label", edgeRecordCountLabel)
                .attr("pointer-events", "none");
            badges.append("rect").attr("rx", 5).attr("ry", 5);
            badges.append("text")
                .attr("text-anchor", "middle")
                .attr("dominant-baseline", "central")
                .text(edgeRecordCountText);
            groups.append("title").text(function (edge) { return relationshipLabel(edge, nodeById); });
            return groups;
        }

        function positionRenderedEdges(groups, visibleLinks, visibleNodes) {
            var routeNodes = edgeRoutingNodes(visibleLinks, visibleNodes);
            visibleLinks.forEach(function (edge) {
                edge.route = routeEdgeAroundLabels(edge, routeNodes);
                edge.visualRoute = curveDirectEdge(edge, edge.route);
            });
            groups.selectAll("path").attr("d", edgePath);
            positionEdgeCountBadges(groups, visibleLinks, routeNodes);
        }

        function edgeRoutingNodes(visibleLinks, visibleNodes) {
            if (visibleNodes.length <= staticLayoutNodeThreshold) return visibleNodes;
            var endpointIds = new Set();
            visibleLinks.forEach(function (edge) {
                endpointIds.add(endpointId(edge.source));
                endpointIds.add(endpointId(edge.target));
            });
            var endpoints = visibleNodes.filter(function (node) { return endpointIds.has(node.id); });
            var obstacles = visibleNodes.filter(function (node) {
                return !endpointIds.has(node.id) && (node.labelPinned || nodeReviewPriority(node) > 0);
            }).sort(comparePersistentLabelPriority).slice(0, 180);
            return endpoints.concat(obstacles);
        }

        function projectionForView(relationshipKindOverride) {
            var relationshipKind = relationshipKindOverride === undefined ?
                state.selectedRelationshipKind : relationshipKindOverride;
            var refillFiles = scopedFileRefillActive(relationshipKind);
            var refillSymbols = scopedSymbolRefillActive(relationshipKind);
            var observedCycles = refillFiles ? availableObservedCycles : data.cycles;
            var cycleMembers = new Set(observedCycles.flatMap(function (cycle) { return cycle.memberIds; }));
            var cycleEdges = new Set(observedCycles.flatMap(function (cycle) { return cycle.edgeIds; }));
            var analysisFileMembers = new Set(data.analysisCycles.flatMap(function (cycle) {
                return cycle.memberFileIds;
            }));
            var analysisSymbolMembers = new Set(data.analysisCycles.flatMap(function (cycle) {
                return cycle.memberSymbolIds;
            }));
            var findingFileMembers = new Set(data.findings.flatMap(function (finding) {
                return finding.componentFileIds;
            }));
            var findingSymbolMembers = new Set(data.findings.flatMap(function (finding) {
                return finding.componentSymbolIds;
            }));
            var fileCatalog = refillFiles ? availableFileNodes : data.fileNodes;
            var fileEdgeCatalog = refillFiles ? availableFileEdges : data.fileEdges;
            var fileNodes = fileCatalog.map(function (node) {
                return Object.assign({}, node, {
                    entityType: "file",
                    clusterId: node.build,
                    hasCycle: cycleMembers.has(node.id),
                    hasAnalysisCycle: analysisFileMembers.has(node.id),
                    hasAnalyzerFinding: findingFileMembers.has(node.id),
                });
            });
            var symbolCatalogNodes = refillSymbols ? expandedSymbols.nodes : data.nodes;
            var symbolCatalogEdges = refillSymbols ? expandedSymbols.edges : data.edges;
            var symbolNodes = symbolCatalogNodes.map(function (node) {
                var declaringFile = fileEntityForSymbol(node);
                return Object.assign({}, node, {
                    entityType: "symbol",
                    hasAnalysisCycle: analysisSymbolMembers.has(node.id),
                    hasAnalyzerFinding: findingSymbolMembers.has(node.id),
                    sourceFileFindingCount: declaringFile ? declaringFile.findingIds.length : 0,
                });
            });
            var fileLinks = fileEdgeCatalog.map(function (edge) {
                return Object.assign({}, edge, {
                    entityType: "file-edge",
                    cycleEdge: cycleEdges.has(edge.id),
                });
            });
            if (state.view === "symbols") {
                return filteredProjection(
                    symbolNodes,
                    symbolCatalogEdges.map(function (edge) {
                        return Object.assign({}, edge, { entityType: "symbol-edge" });
                    }),
                    relationshipKind,
                    refillSymbols,
                );
            }
            var analysisProjection = analysisCycleProjection(fileNodes, symbolNodes);
            if (state.view === "problems") {
                fileNodes = fileNodes.filter(function (node) {
                    return node.fileFindingCount > 0 || cycleMembers.has(node.id) ||
                        analysisFileMembers.has(node.id) || findingFileMembers.has(node.id);
                });
                var problemIds = new Set(fileNodes.map(function (node) { return node.id; }));
                fileLinks = fileLinks.filter(function (edge) {
                    return problemIds.has(edge.source) && problemIds.has(edge.target);
                });
                fileNodes = mergeNodes(fileNodes, analysisProjection.nodes);
                fileNodes = mergeNodes(fileNodes, symbolNodes.filter(function (node) {
                    if (!findingSymbolMembers.has(node.id)) return false;
                    var source = sourceFileForNode(node);
                    return !source || !findingFileMembers.has(source.id);
                }));
                fileLinks = fileLinks.concat(analysisProjection.links);
            }
            if (state.view === "cycles") {
                fileNodes = fileNodes.filter(function (node) {
                    return cycleMembers.has(node.id) || analysisFileMembers.has(node.id);
                });
                fileNodes = mergeNodes(fileNodes, analysisProjection.nodes);
                fileLinks = fileLinks.filter(function (edge) { return cycleEdges.has(edge.id); });
                fileLinks = fileLinks.concat(analysisProjection.links);
            }
            return filteredProjection(fileNodes, fileLinks, relationshipKind, refillFiles);
        }

        function scopedFileRefillActive(relationshipKind) {
            return state.view === "problems" || state.view === "cycles" || Boolean(
                state.selectedBuild || state.selectedProject || state.selectedSourceSet ||
                state.pathSelectionMode !== "all" || state.query.trim(),
            );
        }

        function scopedSymbolRefillActive(relationshipKind) {
            return Boolean(
                state.selectedBuild || state.selectedProject || state.selectedSourceSet ||
                state.pathSelectionMode !== "all" || state.query.trim(),
            );
        }

        function analysisCycleProjection(fileNodes, symbolNodes) {
            var files = new Map(fileNodes.map(function (node) { return [node.id, node]; }));
            var symbols = new Map(symbolNodes.map(function (node) { return [node.id, node]; }));
            var projectedNodes = new Map();
            var projectedLinks = [];
            data.analysisCycles.forEach(function (cycle) {
                var routeNodes = cycle.route.map(function (component) {
                    var node = analysisParticipantNode(component, files, symbols);
                    if (node) projectedNodes.set(node.id, node);
                    return node;
                });
                for (var index = 0; index + 1 < cycle.route.length; index += 1) {
                    var source = routeNodes[index];
                    var target = routeNodes[index + 1];
                    if (!source || !target) continue;
                    projectedLinks.push({
                        id: "analysis-edge::" + cycle.id + "::" + index,
                        source: source.id,
                        target: target.id,
                        entityType: "analysis-cycle-edge",
                        analysisCycleId: cycle.id,
                        analysisStepIndex: index,
                        sourceComponent: cycle.route[index],
                        targetComponent: cycle.route[index + 1],
                        evidence: cycle.evidence,
                        crossBuild: false,
                    });
                }
            });
            return { nodes: Array.from(projectedNodes.values()), links: projectedLinks };
        }

        function analysisParticipantNode(component, files, symbols) {
            if (component.fileId && files.has(component.fileId)) return files.get(component.fileId);
            if (component.symbolId && symbols.has(component.symbolId)) return symbols.get(component.symbolId);
            return null;
        }

        function mergeNodes(primary, additional) {
            var byId = new Map(primary.map(function (node) { return [node.id, node]; }));
            additional.forEach(function (node) { byId.set(node.id, node); });
            return Array.from(byId.values());
        }

        function expandedSymbolProjection() {
            var nodesById = new Map();
            data.fileNodes.forEach(function (fileNode) {
                (fileNode.symbols || []).forEach(function (symbol) {
                    nodesById.set(symbol.id, {
                        id: symbol.id,
                        name: symbol.name,
                        qualifiedName: symbol.qualifiedName,
                        build: fileNode.build,
                        project: fileNode.project,
                        sourceSet: fileNode.sourceSet,
                        clusterId: fileNode.build + "::" + fileNode.project,
                        kind: symbol.kind,
                        declarationSemantic: symbol.declarationSemantic,
                        declarationSemanticLabel: symbol.declarationSemanticLabel,
                        declarationSemanticDetail: symbol.declarationSemanticDetail,
                        file: fileNode.path,
                        line: symbol.line,
                        localInbound: 0,
                        workspaceInbound: 0,
                        crossBuildInbound: 0,
                        outgoingRecordCount: 0,
                        importanceScore: symbol.importance,
                        importanceReasons: [],
                        important: symbol.important,
                    });
                });
            });
            data.nodes.forEach(function (node) {
                nodesById.set(node.id, Object.assign({}, node, {
                    localInbound: 0,
                    workspaceInbound: 0,
                    crossBuildInbound: 0,
                    outgoingRecordCount: 0,
                }));
            });

            var occurrences = [];
            availableFileEdges.forEach(function (edge) {
                (edge.occurrences || []).forEach(function (occurrence) {
                    occurrences.push(Object.assign({}, occurrence, { crossBuild: edge.crossBuild }));
                });
            });
            data.fileNodes.forEach(function (node) {
                (node.internalOccurrences || []).forEach(function (occurrence) {
                    occurrences.push(Object.assign({}, occurrence, { crossBuild: false }));
                });
            });
            data.edges.forEach(function (edge) {
                (edge.occurrences || []).forEach(function (occurrence) {
                    occurrences.push(Object.assign({}, occurrence, {
                        sourceSymbolId: occurrence.sourceSymbolId || endpointId(edge.source),
                        targetSymbolId: occurrence.targetSymbolId || endpointId(edge.target),
                        kind: occurrence.kind || edge.kind,
                        kindLabel: occurrence.kindLabel || edge.kindLabel,
                        evidence: occurrence.evidence || edge.evidence,
                        crossBuild: edge.crossBuild,
                    }));
                });
            });
            var uniqueOccurrences = new Map();
            occurrences.forEach(function (occurrence) {
                if (!nodesById.has(occurrence.sourceSymbolId) || !nodesById.has(occurrence.targetSymbolId)) return;
                var key = [
                    occurrence.sourceSymbolId,
                    occurrence.targetSymbolId,
                    occurrence.kind,
                    occurrence.evidence,
                    occurrence.build,
                    occurrence.project,
                    occurrence.sourceSet,
                    occurrence.file,
                    occurrence.line,
                    occurrence.context,
                ].join("\u0000");
                uniqueOccurrences.set(key, occurrence);
            });
            var groups = new Map();
            uniqueOccurrences.forEach(function (occurrence) {
                var key = [occurrence.sourceSymbolId, occurrence.targetSymbolId, occurrence.kind, occurrence.evidence]
                    .join("\u0000");
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(occurrence);
            });
            var edges = Array.from(groups.entries()).sort(function (left, right) {
                return left[0].localeCompare(right[0]);
            }).map(function (entry, index) {
                var group = entry[1].sort(compareSymbolOccurrences);
                var first = group[0];
                return {
                    id: "scoped-symbol-edge-" + (index + 1),
                    source: first.sourceSymbolId,
                    target: first.targetSymbolId,
                    kind: first.kind,
                    kindLabel: first.kindLabel || first.kind,
                    evidence: first.evidence,
                    crossBuild: group.some(function (occurrence) { return occurrence.crossBuild; }),
                    recordCount: group.length,
                    occurrences: group,
                };
            });
            edges.forEach(function (edge) {
                var source = nodesById.get(edge.source);
                var target = nodesById.get(edge.target);
                if (source) source.outgoingRecordCount = (source.outgoingRecordCount || 0) + edge.recordCount;
                if (target) {
                    target.workspaceInbound = (target.workspaceInbound || 0) + edge.recordCount;
                    if (edge.crossBuild) target.crossBuildInbound = (target.crossBuildInbound || 0) + edge.recordCount;
                    else if (source && source.project === target.project && source.build === target.build) {
                        target.localInbound = (target.localInbound || 0) + edge.recordCount;
                    }
                }
            });
            return { nodes: Array.from(nodesById.values()), edges: edges };
        }

        function compareSymbolOccurrences(left, right) {
            return String(left.file).localeCompare(String(right.file)) || left.line - right.line ||
                String(left.sourceSymbolId).localeCompare(String(right.sourceSymbolId)) ||
                String(left.targetSymbolId).localeCompare(String(right.targetSymbolId));
        }

        function filteredProjection(allNodes, allLinks, relationshipKind, refillCatalog) {
            var primaryNodes = allNodes.filter(matchesGraphContext);
            var primaryIds = new Set(primaryNodes.map(function (node) { return node.id; }));
            var relationshipLinks = allLinks.map(function (edge) {
                if (isAnalysisCycleEdge(edge)) return edge;
                return filterEdgeToRelationshipKind(edge, relationshipKind);
            }).filter(Boolean);
            var retainIncidentContext = refillCatalog && graphContextRestricted();
            var scopedLinks = relationshipLinks.filter(function (edge) {
                var sourceInScope = primaryIds.has(endpointId(edge.source));
                var targetInScope = primaryIds.has(endpointId(edge.target));
                return retainIncidentContext ? sourceInScope || targetInScope : sourceInScope && targetInScope;
            });
            var scopedIds = new Set(primaryIds);
            if (retainIncidentContext) scopedLinks.forEach(function (edge) {
                scopedIds.add(endpointId(edge.source));
                scopedIds.add(endpointId(edge.target));
            });
            var scopedNodes = allNodes.filter(function (node) { return scopedIds.has(node.id); }).map(function (node) {
                return Object.assign({}, node, { isScopeContext: !primaryIds.has(node.id) });
            });
            var relationshipScopedLinks = scopedLinks.filter(function (edge) { return !isAnalysisCycleEdge(edge); });
            if (relationshipKind !== "all" && (state.view === "files" || state.view === "symbols")) {
                var participantIds = new Set();
                relationshipScopedLinks.forEach(function (edge) {
                    participantIds.add(endpointId(edge.source));
                    participantIds.add(endpointId(edge.target));
                });
                scopedNodes.filter(function (node) {
                    return !node.isScopeContext && nodeHasInternalRelationshipKind(node, relationshipKind);
                }).forEach(function (node) { participantIds.add(node.id); });
                scopedNodes = scopedNodes.filter(function (node) { return participantIds.has(node.id); });
                scopedIds = new Set(scopedNodes.map(function (node) { return node.id; }));
                scopedLinks = scopedLinks.filter(function (edge) {
                    return scopedIds.has(endpointId(edge.source)) && scopedIds.has(endpointId(edge.target));
                });
            }
            var query = state.query.trim().toLowerCase();
            var allQueryMatches = query ? allNodes.filter(function (node) {
                return searchable(node).toLowerCase().includes(query);
            }) : allNodes;
            var directQueryMatches = query ? scopedNodes.filter(function (node) {
                return searchable(node).toLowerCase().includes(query);
            }) : scopedNodes;
            var retainedQueryIds = new Set(directQueryMatches.map(function (node) { return node.id; }));
            if (query) {
                scopedLinks.filter(function (edge) {
                    return retainedQueryIds.has(endpointId(edge.source)) ||
                        retainedQueryIds.has(endpointId(edge.target));
                }).forEach(function (edge) {
                    retainedQueryIds.add(endpointId(edge.source));
                    retainedQueryIds.add(endpointId(edge.target));
                });
            }
            var filteredNodes = query ? scopedNodes.filter(function (node) {
                return retainedQueryIds.has(node.id);
            }) : scopedNodes;
            var filteredCandidateNodeCount = filteredNodes.length;
            var availableInternalRecordCount = filteredNodes.reduce(function (sum, node) {
                return sum + scopedInternalRecordCount(node, relationshipKind);
            }, 0);
            var filteredLinks;
            var availableRelationshipRecordCount;
            if (state.view === "symbols") {
                state.symbolIndexedCount = primaryNodes.length;
                var recordWeights = new Map();
                scopedLinks.forEach(function (edge) {
                    var count = edgeRecordCount(edge);
                    [endpointId(edge.source), endpointId(edge.target)].forEach(function (id) {
                        recordWeights.set(id, (recordWeights.get(id) || 0) + count);
                    });
                });
                filteredNodes = filteredNodes.slice().sort(function (left, right) {
                    return Number(Boolean(right.important)) - Number(Boolean(left.important)) ||
                        (recordWeights.get(right.id) || 0) - (recordWeights.get(left.id) || 0) ||
                        String(left.qualifiedName || left.name).localeCompare(String(right.qualifiedName || right.name));
                });
                var symbolIds = new Set(filteredNodes.map(function (node) { return node.id; }));
                var symbolLinks = scopedLinks.filter(function (edge) {
                    return symbolIds.has(endpointId(edge.source)) && symbolIds.has(endpointId(edge.target));
                });
                availableRelationshipRecordCount = symbolLinks.reduce(function (sum, edge) {
                    return sum + edgeRecordCount(edge);
                }, 0);
                filteredLinks = symbolLinks;
                state.symbolCandidateCount = filteredNodes.length;
                state.symbolRelationshipRecordCount = availableRelationshipRecordCount;
            } else {
                state.fileIndexedCount = primaryNodes.length;
                state.symbolCandidateCount = 0;
                state.symbolRelationshipRecordCount = 0;
                var ids = new Set(filteredNodes.map(function (node) { return node.id; }));
                filteredLinks = scopedLinks.filter(function (edge) {
                    return ids.has(endpointId(edge.source)) && ids.has(endpointId(edge.target));
                });
                availableRelationshipRecordCount = filteredLinks.reduce(function (sum, edge) {
                    return sum + edgeRecordCount(edge);
                }, 0);
                state.fileCandidateCount = filteredNodes.length;
                state.fileRelationshipRecordCount = availableRelationshipRecordCount;
            }
            return {
                nodes: filteredNodes,
                links: filteredLinks,
                candidateNodeCount: filteredCandidateNodeCount,
                boundedQueryMatchCount: allQueryMatches.length,
                hiddenQueryMatchCount: Math.max(0, allQueryMatches.length - filteredNodes.length),
                availableRelationshipRecordCount: availableRelationshipRecordCount,
                availableInternalRecordCount: availableInternalRecordCount,
            };
        }

        function graphContextRestricted() {
            return Boolean(state.selectedBuild || state.selectedProject || state.selectedSourceSet ||
                state.pathSelectionMode !== "all");
        }

        function nodeHasInternalRelationshipKind(node, categoryId) {
            return (node.internalOccurrences || []).some(function (occurrence) {
                return relationshipCategory(occurrence.kind) === categoryId;
            });
        }

        function scopedInternalRecordCount(node, relationshipKind) {
            if (node.entityType !== "file" || node.isScopeContext) return 0;
            if (relationshipKind === "all") return node.shownInternalRecordCount || 0;
            return (node.internalOccurrences || []).filter(function (occurrence) {
                return relationshipCategory(occurrence.kind) === relationshipKind;
            }).length;
        }

        function matchesGraphContext(node) {
            if (state.selectedBuild && node.build !== state.selectedBuild) return false;
            if (state.selectedProject && node.project !== state.selectedProject) return false;
            if (state.selectedSourceSet && node.sourceSet !== state.selectedSourceSet) return false;
            return matchesSelectedPath(node);
        }

        function matchesSelectedPath(node) {
            if (!pathFilter) return true;
            if (state.pathSelectionMode === "all") return true;
            var path = entityProjectRelativePath(node);
            if (!path) return false;
            return state.selectedPathKeys.has(boundedPathLeafKey(
                node.build,
                node.project,
                node.sourceSet,
                path,
            ));
        }

        function ticked() {
            var positionedNodes = graphLayoutNodes();
            assignLabelDirections(positionedNodes, layout.cells, layout.subgroupCells);
            constrainNodesToBuildCells(positionedNodes, layout.cells, layout.projectCells, layout.subgroupCells);
            nodeGroups.attr("transform", function (node) { return "translate(" + node.x + "," + node.y + ")"; });
            positionLabels(nodeGroups);
            positionRenderedEdges(edgeGroups, renderedLinks, positionedNodes);
            updateBuildRegions(positionedNodes, buildGroups, layout.cells);
            updateProjectRegions(projectGroups, layout.projectCells, positionedNodes);
            updateSubgroupRegions(subgroupGroups, layout.subgroupCells);
        }

        function selectNode(node, origin) {
            prepareDetailFocus(origin);
            state.cycleStepEdgeId = null;
            state.selectedId = node.id;
            state.selectedType = node.entityType;
            state.selectedEdge = null;
            setNodeSelection([node.id]);
            setRovingNode(node, false);
            renderNodeDetail(node);
            enterNodeNeighborhoodFocus(node.id);
            focusOpenedDetail();
        }

        function selectEdge(edge, origin) {
            prepareDetailFocus(origin);
            exitNodeNeighborhoodFocus(true);
            state.cycleStepEdgeId = null;
            state.selectedId = edge.id;
            state.selectedType = edge.entityType;
            state.selectedEdge = edge;
            highlightEdge(edge);
            renderEdgeDetail(edge);
            focusOpenedDetail();
        }

        function clearSelection(restoreFocus) {
            var wasOpen = !detail.hidden;
            state.selectedId = null;
            state.selectedType = null;
            state.selectedEdge = null;
            state.cycleStepEdgeId = null;
            state.selectedEvidence = null;
            exitNodeNeighborhoodFocus(true);
            closeDetail();
            restoreHighlight();
            if (restoreFocus && wasOpen) restoreDetailFocus();
        }

        function setNodeSelection(ids, announcement) {
            var visibleIds = new Set(nodes.map(function (node) { return node.id; }));
            state.selectedNodeIds = new Set(Array.from(ids).filter(function (id) { return visibleIds.has(id); }));
            applyGroupSelectionClasses();
            updateSelectionToolbar(announcement);
        }

        function toggleNodeSelection(node) {
            clearSelection(false);
            var selected = new Set(state.selectedNodeIds);
            if (selected.has(node.id)) selected.delete(node.id);
            else selected.add(node.id);
            setNodeSelection(selected, selected.has(node.id) ? "Added " + nodeLabel(node) : "Removed " + nodeLabel(node));
        }

        function clearNodeSelection() {
            if (!state.selectedNodeIds.size) return updateSelectionToolbar();
            state.selectedNodeIds = new Set();
            applyGroupSelectionClasses();
            updateSelectionToolbar("Node selection cleared");
        }

        function applyGroupSelectionClasses() {
            if (!nodeGroups) return;
            nodeGroups.classed("is-group-selected", function (node) {
                return state.selectedNodeIds.has(node.id);
            });
        }

        function selectionAnchorNode() {
            var selectedNode = nodes.find(function (node) { return state.selectedNodeIds.has(node.id); });
            if (selectedNode) return selectedNode;
            var selectedEntity = state.selectedId && nodes.find(function (node) { return node.id === state.selectedId; });
            if (selectedEntity) return selectedEntity;
            var rovingNode = nodes.find(function (node) { return node.id === state.rovingNodeId; });
            return rovingNode || nodes[0] || null;
        }

        function selectScopeNodes(scope) {
            var anchor = selectionAnchorNode();
            if (!anchor) return;
            clearSelection(false);
            var selected = graphLayoutNodes().filter(function (node) {
                if (node.build !== anchor.build) return false;
                return scope === "build" || node.project === anchor.project;
            }).map(function (node) { return node.id; });
            var label = scope === "build" ? anchor.build : projectDisplayName(anchor.project, anchor.build);
            setNodeSelection(selected, "Selected " + selected.length + " nodes in " + label);
        }

        function nudgeNodeSelection(node, key) {
            if (!state.selectedNodeIds.has(node.id)) setNodeSelection([node.id]);
            var delta = eventShiftDistance(key);
            selectedVisibleNodes().forEach(function (selected) {
                selected.isManuallyPinned = true;
                selected.fx = selected.x + delta.x;
                selected.fy = selected.y + delta.y;
                selected.x = selected.fx;
                selected.y = selected.fy;
                storeManualNodePosition(selected);
            });
            markUserNavigation();
            ticked();
            updateSelectionToolbar("Moved selection " + key.replace("Arrow", "").toLowerCase());
        }

        function eventShiftDistance(key) {
            var distance = 12;
            return {
                x: key === "ArrowLeft" ? -distance : key === "ArrowRight" ? distance : 0,
                y: key === "ArrowUp" ? -distance : key === "ArrowDown" ? distance : 0,
            };
        }

        function graphLayoutNodes() {
            return neighborhoodFocusContext ? neighborhoodFocusContext.nodes : nodes;
        }

        function selectedVisibleNodes() {
            var visibleIds = neighborhoodFocusContext && neighborhoodFocusContext.nodeIds;
            return nodes.filter(function (node) {
                return state.selectedNodeIds.has(node.id) && (!visibleIds || visibleIds.has(node.id));
            });
        }

        function directNodeNeighborhood(nodeId) {
            var nodeIds = new Set([nodeId]);
            var incident = (incidentLinksByNode.get(nodeId) || []).slice();
            incident.forEach(function (edge) {
                nodeIds.add(endpointId(edge.source));
                nodeIds.add(endpointId(edge.target));
            });
            return {
                nodeIds: nodeIds,
                nodes: nodes.filter(function (node) { return nodeIds.has(node.id); }),
                links: incident,
            };
        }

        function enterNodeNeighborhoodFocus(nodeId) {
            var priorContext = neighborhoodFocusContext;
            var priorTransform = priorContext && priorContext.transform;
            var priorPositions = priorContext && priorContext.positions;
            var priorBuildOffsets = priorContext && priorContext.buildOffsets;
            var priorProjectOffsets = priorContext && priorContext.projectOffsets;
            var fullLayout = priorContext && priorContext.layout || layout;
            exitNodeNeighborhoodFocus(false);
            var neighborhood = directNodeNeighborhood(nodeId);
            var anchor = neighborhood.nodes.find(function (node) { return node.id === nodeId; });
            if (!anchor) return;
            neighborhoodFocusContext = {
                anchorId: nodeId,
                nodeIds: neighborhood.nodeIds,
                nodes: neighborhood.nodes,
                links: neighborhood.links,
                layout: fullLayout,
                transform: priorTransform || d3.zoomTransform(svgElement),
                buildOffsets: priorBuildOffsets || new Map(Array.from(manualBuildOffsets, function (entry) {
                    return [entry[0], { x: entry[1].x, y: entry[1].y }];
                })),
                projectOffsets: priorProjectOffsets || new Map(Array.from(manualProjectOffsets, function (entry) {
                    return [entry[0], { x: entry[1].x, y: entry[1].y }];
                })),
                positions: priorPositions || new Map(nodes.map(function (node) {
                    return [node.id, Object.assign(captureNodeDragState(node), {
                        subgroupKey: node.subgroupKey,
                        labelDirection: node.labelDirection,
                    })];
                })),
            };
            root.dataset.srcxGraphFocus = "direct-neighborhood";
            applyNeighborhoodVisibility(neighborhood.nodeIds);
            layout = buildCellLayout(neighborhood.nodes, width, height, buildByName);
            updateRegionLabels(neighborhood.nodes);
            subgroupLayer.selectAll("*").remove();
            subgroupGroups = renderSubgroupRegions(subgroupLayer, layout.subgroups, layout.subgroupCells);
            initializeNodes(neighborhood.nodes, layout);
            assignLayoutDegrees(neighborhood.nodes, neighborhood.links);
            neighborhood.nodes.forEach(function (node) {
                node.fx = null;
                node.fy = null;
                node.vx = 0;
                node.vy = 0;
                node.isManuallyPinned = false;
            });
            var anchorHome = layout.homes.get(anchor.id);
            anchor.fx = anchorHome.x;
            anchor.fy = anchorHome.y;
            simulation = neighborhoodNodeSimulation(neighborhood.nodes, neighborhood.links, layout);
            simulation.stop();
            for (var tick = 0; tick < 140; tick += 1) {
                simulation.tick();
                constrainNodesToBuildCells(
                    neighborhood.nodes,
                    layout.cells,
                    layout.projectCells,
                    layout.subgroupCells,
                );
            }
            ticked();
            highlight(nodeId);
            frameNodes(neighborhood.nodes, "direct neighborhood", neighborhood.links);
            var neighborCount = Math.max(0, neighborhood.nodes.length - 1);
            var hiddenCount = Math.max(0, nodes.length - neighborhood.nodes.length);
            updateSelectionToolbar(
                "Focused " + nodeLabel(anchor) + " with " + neighborCount + " direct " +
                (neighborCount === 1 ? "neighbor" : "neighbors") + "; hid " + hiddenCount + " unrelated " +
                (hiddenCount === 1 ? "node" : "nodes"),
            );
        }

        function neighborhoodNodeSimulation(focusNodes, focusLinks, focusLayout) {
            return d3.forceSimulation(focusNodes)
                .randomSource(d3.randomLcg(0.42))
                .force("link", d3.forceLink(focusLinks).id(function (node) { return node.id; })
                    .distance(neighborhoodLinkDistance).strength(0.78))
                .force("charge", d3.forceManyBody().strength(-150).distanceMax(360))
                .force("collision", d3.forceCollide().radius(function (node) {
                    return collisionRadius(node, pinnedLabelIds) + 4;
                }).strength(0.98))
                .force("x", d3.forceX(function (node) { return focusLayout.homes.get(node.id).x; }).strength(0.32))
                .force("y", d3.forceY(function (node) { return focusLayout.homes.get(node.id).y; }).strength(0.32))
                .on("tick", ticked);
        }

        function neighborhoodLinkDistance(edge) {
            if (edge.source.subgroupKey === edge.target.subgroupKey) return 72;
            if (edge.source.build === edge.target.build && edge.source.project === edge.target.project) return 108;
            if (edge.source.build === edge.target.build) return 150;
            return 210;
        }

        function applyNeighborhoodVisibility(visibleIds) {
            var builds = new Set();
            var projects = new Set();
            nodes.forEach(function (node) {
                if (!visibleIds.has(node.id)) return;
                builds.add(node.build);
                projects.add(projectKey(node.build, node.project));
            });
            nodeGroups.classed("is-neighborhood-hidden", function (node) { return !visibleIds.has(node.id); })
                .attr("aria-hidden", function (node) { return visibleIds.has(node.id) ? null : "true"; })
                .attr("tabindex", function (node) {
                    return visibleIds.has(node.id) && node.id === state.rovingNodeId ? 0 : -1;
                });
            buildGroups.classed("is-neighborhood-hidden", function (build) { return !builds.has(build.name); })
                .attr("aria-hidden", function (build) { return builds.has(build.name) ? null : "true"; });
            projectGroups.classed("is-neighborhood-hidden", function (project) { return !projects.has(project.key); })
                .attr("aria-hidden", function (project) { return projects.has(project.key) ? null : "true"; });
        }

        function updateRegionLabels(visibleNodes) {
            var builds = new Map(representedBuilds(visibleNodes, buildByName).map(function (build) {
                return [build.name, build];
            }));
            var projects = new Map(representedProjects(visibleNodes).map(function (project) {
                return [project.key, project];
            }));
            buildGroups.select("text").each(function (build) {
                var text = d3.select(this);
                text.selectAll("*").remove();
                appendBuildRegionLabel(text, builds.get(build.name) || build);
            });
            projectGroups.select("text").text(function (project) {
                var summary = projects.get(project.key);
                return summary ? projectRegionLabel(summary) : "";
            });
        }

        function exitNodeNeighborhoodFocus(restoreTransform) {
            if (!neighborhoodFocusContext) return;
            var context = neighborhoodFocusContext;
            neighborhoodFocusContext = null;
            if (simulation) simulation.stop();
            context.positions.forEach(function (position, id) {
                var node = nodes.find(function (candidate) { return candidate.id === id; });
                if (!node) return;
                restoreNodeDragState(node, position);
                node.subgroupKey = position.subgroupKey;
                node.labelDirection = position.labelDirection;
            });
            layout = context.layout;
            manualBuildOffsets = context.buildOffsets;
            manualProjectOffsets = context.projectOffsets;
            updateRegionLabels(nodes);
            subgroupLayer.selectAll("*").remove();
            subgroupGroups = renderSubgroupRegions(subgroupLayer, layout.subgroups, layout.subgroupCells);
            nodeGroups.classed("is-neighborhood-hidden", false).attr("aria-hidden", null);
            nodeGroups.attr("tabindex", function (node) { return node.id === state.rovingNodeId ? 0 : -1; });
            buildGroups.classed("is-neighborhood-hidden", false).attr("aria-hidden", null);
            projectGroups.classed("is-neighborhood-hidden", false).attr("aria-hidden", null);
            root.dataset.srcxGraphFocus = "full";
            simulation = staticNodeSimulation(nodes);
            ticked();
            if (restoreTransform) svg.interrupt().call(zoom.transform, context.transform);
        }

        function highlight(nodeId) {
            cancelHighlightRestore();
            var neighborIds = new Set([nodeId]);
            var allowedIds = neighborhoodFocusContext && neighborhoodFocusContext.nodeIds;
            var incident = (incidentLinksByNode.get(nodeId) || []).filter(function (edge) {
                return !allowedIds ||
                    allowedIds.has(endpointId(edge.source)) && allowedIds.has(endpointId(edge.target));
            });
            incident.forEach(function (edge) {
                var source = endpointId(edge.source);
                var target = endpointId(edge.target);
                if (source === nodeId) neighborIds.add(target);
                if (target === nodeId) neighborIds.add(source);
            });
            if (incident.length && !incident.some(function (edge) { return edge.id === state.rovingEdgeId; })) {
                state.rovingEdgeId = incident[0].id;
            }
            edgeNavigationLinks = incident;
            renderEdgeSubset(incident);
            nodeGroups.classed("is-dimmed", function (node) {
                return (!allowedIds || allowedIds.has(node.id)) && !neighborIds.has(node.id);
            })
                .classed("is-selected", function (node) { return node.id === nodeId; });
            edgeGroups.classed("is-active", function (edge) {
                return endpointId(edge.source) === nodeId || endpointId(edge.target) === nodeId;
            }).classed("is-dimmed", function (edge) {
                return endpointId(edge.source) !== nodeId && endpointId(edge.target) !== nodeId;
            });
        }

        function highlightEdge(edge) {
            cancelHighlightRestore();
            var source = endpointId(edge.source);
            var target = endpointId(edge.target);
            var selectedIds = new Set(edge.constituentEdgeIds || [edge.id]);
            selectedIds.add(edge.id);
            var selectedLinks = links.filter(function (candidate) { return selectedIds.has(candidate.id); });
            if (!selectedLinks.length) selectedLinks = [edge];
            state.rovingEdgeId = edge.id;
            var navigationContainsEdge = edgeNavigationLinks.some(function (candidate) {
                return candidate.id === edge.id;
            });
            renderEdgeSubset(navigationContainsEdge ? edgeNavigationLinks : selectedLinks);
            nodeGroups.classed("is-dimmed", function (node) { return node.id !== source && node.id !== target; });
            edgeGroups.classed("is-active", function (candidate) { return selectedIds.has(candidate.id); })
                .classed("is-dimmed", function (candidate) { return !selectedIds.has(candidate.id); });
        }

        function restoreHighlight() {
            if (state.cycleStepEdgeId) {
                var cycleStepEdge = links.find(function (item) { return item.id === state.cycleStepEdgeId; });
                if (cycleStepEdge) return highlightEdge(cycleStepEdge);
            }
            if (state.selectedId) {
                if (state.selectedType && state.selectedType.includes("edge")) {
                    var edge = state.selectedEdge || links.find(function (item) {
                        return item.id === state.selectedId;
                    });
                    if (edge) return highlightEdge(edge);
                }
                if (nodes.some(function (node) { return node.id === state.selectedId; })) {
                    if (!neighborhoodFocusContext) enterNodeNeighborhoodFocus(state.selectedId);
                    return highlight(state.selectedId);
                }
                clearSelection(false);
                return;
            }
            if (!nodeGroups || !edgeGroups) return;
            resetGraphHighlight();
        }

        function resetGraphHighlight() {
            if (!nodeGroups || !edgeGroups) return;
            if (neighborhoodFocusContext) return highlight(neighborhoodFocusContext.anchorId);
            nodeGroups.classed("is-dimmed", false).classed("is-selected", false);
            edgeNavigationLinks = [];
            renderEdgeSubset([]);
        }

        function scheduleHighlightRestore() {
            cancelHighlightRestore();
            highlightRestoreTimer = window.setTimeout(function () {
                highlightRestoreTimer = null;
                restoreHighlight();
            }, 160);
        }

        function cancelHighlightRestore() {
            if (highlightRestoreTimer === null) return;
            window.clearTimeout(highlightRestoreTimer);
            highlightRestoreTimer = null;
        }

        function renderNodeDetail(node) {
            if (node.entityType === "file") renderFileDetail(node);
            else renderSymbolDetail(node);
        }

        function renderFileDetail(node) {
            openDetail("File source", accessibleNodeName(node));
            var body = detailFields;
            var exactFindings = data.findings.filter(function (finding) {
                return node.findingIds.includes(finding.id);
            });
            var componentFindings = data.findings.filter(function (finding) {
                return !node.findingIds.includes(finding.id) && finding.componentFileIds.includes(node.id);
            });
            var findings = exactFindings.concat(componentFindings);
            var cycles = observedCyclesForCurrentProjection().filter(function (cycle) {
                return cycle.memberIds.includes(node.id);
            });
            var analysisCycles = analysisCyclesForNode(node);
            var evidenceRequest = state.externalEvidenceRequest;
            if (evidenceRequest && evidenceRequest.findingId) {
                findings.sort(function (left, right) {
                    return Number(right.id === evidenceRequest.findingId) - Number(left.id === evidenceRequest.findingId);
                });
            }
            if (evidenceRequest && evidenceRequest.cycleId) {
                cycles.sort(function (left, right) {
                    return Number(right.id === evidenceRequest.cycleId) - Number(left.id === evidenceRequest.cycleId);
                });
            }
            if (evidenceRequest && evidenceRequest.analysisCycleId) {
                analysisCycles.sort(function (left, right) {
                    return Number(right.id === evidenceRequest.analysisCycleId) -
                        Number(left.id === evidenceRequest.analysisCycleId);
                });
            }
            var requestedFinding = evidenceRequest && evidenceRequest.findingId ? findings.find(function (finding) {
                return finding.id === evidenceRequest.findingId;
            }) : null;
            var selectedFinding = requestedFinding || (state.view === "problems" ? findings.find(function (finding) {
                return Number.isInteger(finding.line) && finding.line > 0;
            }) || findings[0] : null);
            var showAnalysisCycleDetail = analysisCycles.length > 0 && (
                state.view === "problems" || !cycles.length || Boolean(
                    evidenceRequest && evidenceRequest.analysisCycleId,
                )
            );
            if (state.view === "problems") {
                appendFindingSection(
                    body,
                    exactFindings,
                    true,
                    node,
                    selectedFinding && selectedFinding.id,
                    "Exact file findings",
                    false,
                );
                if (componentFindings.length) {
                    appendFindingSection(
                        body,
                        componentFindings,
                        false,
                        node,
                        selectedFinding && selectedFinding.id,
                        "Analyzer component findings",
                        true,
                    );
                }
                if (showAnalysisCycleDetail) appendAnalysisCycleSection(body, node, analysisCycles);
                appendCycleParticipationSection(body, node, cycles);
            }
            appendNodeBadges(body, node);
            if (state.view === "cycles" && cycles.length && !showAnalysisCycleDetail) {
                appendCycleRoutes(body, node, cycles);
            }
            if (state.view === "cycles" && showAnalysisCycleDetail) {
                appendAnalysisCycleSection(body, node, analysisCycles);
            }
            if (state.view === "cycles" && analysisCycles.length && !showAnalysisCycleDetail) {
                appendAnalysisCycleParticipationSection(body, node, analysisCycles);
            }
            if (state.view === "cycles" && cycles.length && showAnalysisCycleDetail) {
                appendCycleParticipationSection(body, node, cycles);
            }
            body.appendChild(factList([
                ["Scope", scopeLabel(node)],
                ["Map signal", mapSignalFact(node)],
                ["Report-priority signal", node.importanceReasons.length ?
                    node.importanceReasons.join(", ") : "No ranking signal"],
            ]));
            body.appendChild(fileRecordSummary(node));
            if (state.view !== "problems" && exactFindings.length) {
                appendFindingSection(
                    body,
                    exactFindings,
                    false,
                    node,
                    selectedFinding && selectedFinding.id,
                    "Exact file findings",
                    false,
                );
            }
            var unlocated = data.findings.filter(function (finding) {
                return finding.build === node.build && finding.project === node.project && finding.filePath === null;
            }).length;
            if (unlocated) {
                appendSection(body, "Project-only findings", [
                    unlocated + " findings have no exact file evidence",
                ]);
            }
            if (state.view !== "cycles" && state.view !== "problems" && cycles.length) {
                appendCycleParticipationSection(body, node, cycles);
            }
            if (state.view !== "cycles" && state.view !== "problems" && analysisCycles.length) {
                appendAnalysisCycleParticipationSection(body, node, analysisCycles);
            }
            var hasCycleDetail = state.view === "cycles" && (cycles.length || analysisCycles.length) ||
                state.view === "problems" && showAnalysisCycleDetail;
            if (!hasCycleDetail) {
                var sourceFile = sourceFileForNode(node);
                setSelectedEvidence(selectedFinding ? findingEvidence(selectedFinding, node) : {
                    kind: "file",
                    sourceFileKey: sourceFileKey(sourceFile),
                    line: null,
                    reason: "File source: " + node.path,
                });
                appendFileRelationshipEvidenceBrowser(body, node, sourceFile);
                appendActiveEvidenceSection(body, "Source evidence");
            } else {
                appendFileRelationshipEvidenceBrowser(body, node, sourceFileForNode(node));
            }
        }

        function renderSymbolDetail(node) {
            openDetail("Symbol declaration", accessibleNodeName(node));
            var body = detailFields;
            var analysisCycles = analysisCyclesForNode(node);
            var sourceFileFindings = sourceFileFindingsForSymbol(node);
            var componentFindings = data.findings.filter(function (finding) {
                return finding.componentSymbolIds.includes(node.id);
            });
            var selectedFinding = componentFindings.find(function (finding) {
                return state.externalEvidenceRequest && finding.id === state.externalEvidenceRequest.findingId;
            }) || (state.view === "problems" ? componentFindings[0] : null);
            appendNodeBadges(body, node);
            if (state.view === "problems" && componentFindings.length) {
                appendFindingSection(
                    body,
                    componentFindings,
                    false,
                    node,
                    selectedFinding && selectedFinding.id,
                    "Analyzer component findings",
                    true,
                );
            }
            body.appendChild(factList([
                ["Declaration", node.file + ":" + node.line],
                ["Scope", node.build + " / " + node.project + " / " + node.sourceSet],
                ["Declaration type", declarationSemanticDetail(node)],
                ["Implementation evidence", semanticImplementationFact(node)],
                ["Map signal", mapSignalFact(node)],
                ["Declaring-file findings", sourceFileFindings.length ?
                    sourceFileFindings.length + " file-level findings; not necessarily attributed to this symbol" :
                    "No exact findings attached to the declaring file"],
                ["Relationship records", node.workspaceInbound + " inbound / " + node.outgoingRecordCount +
                    " outbound records / " + node.crossBuildInbound + " cross-build inbound records"],
                ["Report-priority signal", node.importanceReasons.length ?
                    node.importanceReasons.join(", ") : "No ranking signal"],
            ]));
            if (sourceFileFindings.length) {
                appendFindingSection(
                    body,
                    sourceFileFindings,
                    false,
                    node,
                    null,
                    "Findings in declaring file",
                    false,
                    "These findings belong to the source file. They provide nearby review context and are not " +
                        "automatically attributed to this declaration.",
                );
            }
            if ((state.view === "cycles" || state.view === "problems") && analysisCycles.length) {
                appendAnalysisCycleSection(body, node, analysisCycles);
            } else if (analysisCycles.length) {
                appendAnalysisCycleParticipationSection(body, node, analysisCycles);
            }
            if ((state.view === "cycles" || state.view === "problems") && analysisCycles.length) {
                return;
            }
            var sourceFile = sourceFileForNode(node);
            setSelectedEvidence(selectedFinding ? findingEvidence(selectedFinding, node) : {
                kind: "declaration",
                sourceFileKey: sourceFileKey(sourceFile),
                line: node.line,
                reason: "Declaration: " + node.file + ":" + node.line,
            });
            if (state.view !== "cycles" || !analysisCycles.length) {
                appendActiveEvidenceSection(body, "Source evidence");
            }
        }

        function renderEdgeDetail(edge) {
            if (isAnalysisCycleEdge(edge)) {
                renderAnalysisCycleEdgeDetail(edge);
                return;
            }
            var source = entityById(endpointId(edge.source));
            var target = entityById(endpointId(edge.target));
            openDetail("Relationship evidence", accessibleNodeName(source) + " -> " + accessibleNodeName(target));
            var occurrences = edge.occurrences || [];
            if (!occurrences.length) {
                detailFields.appendChild(factList([
                    ["Direction", accessibleNodeName(source) + " -> " + accessibleNodeName(target)],
                    ["Boundary", edge.crossBuild ? "Cross-build" : "Within build"],
                    ["Relationship records", "0"],
                ]));
                var empty = document.createElement("p");
                empty.className = "srcx-dashboard__architecture-muted";
                empty.textContent = "No exact source occurrence was included for this relationship.";
                detailFields.appendChild(empty);
                setSelectedEvidence(null);
                appendActiveEvidenceSection(detailFields, "Source evidence");
                return;
            }
            renderOccurrencePager(edge, occurrences, source, target, detailFields);
        }

        function renderOccurrencePager(edge, occurrences, source, target, parent) {
            parent = parent || detailFields;
            var allOccurrences = occurrences.slice();
            var fileGroups = occurrenceFileGroups(allOccurrences);
            var activeIndex = 0;
            parent.appendChild(factList([
                ["Direction", accessibleNodeName(source) + " -> " + accessibleNodeName(target)],
                ["Boundary", edge.crossBuild ? "Cross-build" : "Within build"],
                ["Displayed relationship records", String(allOccurrences.length)],
                ["Source files", String(fileGroups.length)],
                ["Distinct source lines", String(occurrenceDistinctLineCount(allOccurrences))],
            ]));
            var section = detailSection("Exact source occurrence");
            var fileJumps = document.createElement("div");
            fileJumps.className = "srcx-dashboard__architecture-occurrence-files";
            fileJumps.setAttribute("role", "group");
            fileJumps.setAttribute("aria-label", "Jump to relationship source file");
            var fileButtons = fileGroups.map(function (group) {
                var button = occurrenceButton(
                    compactOccurrenceFileLabel(group.label) + " (" + group.count + ")",
                    "Jump to " + group.label + "; " + group.count +
                        (group.count === 1 ? " relationship record" : " relationship records"),
                );
                button.dataset.srcxOccurrenceFile = group.key;
                button.addEventListener("click", function () {
                    activeIndex = group.firstIndex;
                    renderActiveOccurrence();
                });
                fileJumps.appendChild(button);
                return button;
            });
            var controls = document.createElement("div");
            controls.className = "srcx-dashboard__architecture-occurrence-controls";
            var previous = occurrenceButton("Previous", "Previous exact relationship occurrence");
            var pageStatus = document.createElement("span");
            pageStatus.className = "srcx-dashboard__architecture-occurrence-status";
            pageStatus.setAttribute("role", "status");
            pageStatus.setAttribute("aria-live", "polite");
            pageStatus.setAttribute("aria-atomic", "true");
            var next = occurrenceButton("Next", "Next exact relationship occurrence");
            var metadata = document.createElement("div");
            metadata.className = "srcx-dashboard__architecture-occurrence-metadata";
            controls.append(previous, pageStatus, next);
            section.append(fileJumps, controls, metadata);
            parent.appendChild(section);
            bindEvidenceHost(section);
            previous.addEventListener("click", function () {
                if (activeIndex > 0) activeIndex -= 1;
                renderActiveOccurrence();
            });
            next.addEventListener("click", function () {
                if (activeIndex + 1 < allOccurrences.length) activeIndex += 1;
                renderActiveOccurrence();
            });
            renderActiveOccurrence();

            function renderActiveOccurrence() {
                var occurrence = allOccurrences[activeIndex];
                var activeFileKey = occurrenceSourceFileKey(occurrence);
                var sourceId = occurrence.sourceSymbolId || endpointId(edge.source);
                var targetId = occurrence.targetSymbolId || endpointId(edge.target);
                var sourceDeclaration = declarationById.get(sourceId);
                var targetDeclaration = declarationById.get(targetId);
                var kind = occurrence.kindLabel || edge.kindLabel || edge.kind || edgeKinds(edge);
                var evidence = occurrence.evidence || edge.evidence || edgeEvidence(edge);
                metadata.replaceChildren(factList([
                    ["Relationship kind", kind],
                    ["Evidence", evidence],
                    ["Source declaration", declarationIdentity(sourceDeclaration, sourceId)],
                    ["Target declaration", declarationIdentity(targetDeclaration, targetId)],
                    ["Scope", occurrence.build + " / " + occurrence.project + " / " + occurrence.sourceSet],
                    ["Location", occurrence.file + ":" + occurrence.line],
                ]));
                var navigation = document.createElement("div");
                navigation.className = "srcx-dashboard__architecture-declaration-navigation";
                appendDeclarationButton(navigation, "Open source declaration", sourceDeclaration);
                appendDeclarationButton(navigation, "Open target declaration", targetDeclaration);
                if (navigation.childElementCount) metadata.appendChild(navigation);
                setSelectedEvidence({
                    kind: "relationship",
                    sourceFileKey: activeFileKey,
                    line: occurrence.line,
                    relationshipLineCounts: occurrenceLineCounts(allOccurrences, activeFileKey),
                    reason: (state.cycleStepEdgeId === edge.id ? "Cycle relationship occurrence " :
                        "Relationship occurrence ") + (activeIndex + 1) + " of " + allOccurrences.length +
                        ": " + occurrence.file + ":" + occurrence.line,
                });
                renderActiveEvidence();
                pageStatus.textContent = "Displayed relationship record " + (activeIndex + 1) + " of " +
                    allOccurrences.length + " / " + occurrence.file + ":" + occurrence.line + " / " +
                    fileGroups.length + (fileGroups.length === 1 ? " source file / " : " source files / ") +
                    occurrenceDistinctLineCount(allOccurrences) + " distinct source lines";
                previous.disabled = activeIndex === 0;
                next.disabled = activeIndex + 1 === allOccurrences.length;
                fileButtons.forEach(function (button) {
                    if (button.dataset.srcxOccurrenceFile === activeFileKey) button.setAttribute("aria-current", "true");
                    else button.removeAttribute("aria-current");
                });
            }
        }

        function occurrenceFileGroups(occurrences) {
            var groups = new Map();
            occurrences.forEach(function (occurrence, index) {
                var key = occurrenceSourceFileKey(occurrence);
                var group = groups.get(key);
                if (!group) {
                    group = { key: key, label: occurrence.file, count: 0, firstIndex: index };
                    groups.set(key, group);
                }
                group.count += 1;
            });
            return Array.from(groups.values());
        }

        function occurrenceDistinctLineCount(occurrences) {
            return new Set(occurrences.map(function (occurrence) {
                return occurrenceSourceFileKey(occurrence) + "\u0000" + occurrence.line;
            })).size;
        }

        function occurrenceLineCounts(occurrences, sourceFileKey) {
            var counts = new Map();
            occurrences.forEach(function (occurrence) {
                if (occurrenceSourceFileKey(occurrence) !== sourceFileKey) return;
                counts.set(occurrence.line, (counts.get(occurrence.line) || 0) + 1);
            });
            return counts;
        }

        function appendFileRelationshipEvidenceBrowser(parent, node, sourceFile) {
            var section = detailSection("Relationship evidence from this file");
            section.dataset.srcxFileRelationshipEvidence = "";
            var items = fileRelationshipEvidenceItems(node, sourceFile);
            var typedCount = items.filter(function (item) { return Boolean(item.occurrence); }).length;
            var lineOnlyCount = items.length - typedCount;
            section.appendChild(factList([
                ["Catalog evidence items", String(items.length)],
                ["Typed relationship records", String(typedCount)],
                ["Line-only omitted-target evidence", String(lineOnlyCount)],
            ]));
            var introduction = document.createElement("p");
            introduction.className = "srcx-dashboard__architecture-muted";
            introduction.textContent = items.length ?
                "Nothing is marked just because the file is open. Activate this browser to lightly mark every " +
                    "catalog relationship-evidence line with its item count; only the selected item is highlighted " +
                    "strongly. Line-only items retain a source line but never invent an omitted target." :
                "No originating relationship evidence was retained for this source file.";
            section.appendChild(introduction);
            if (!items.length) {
                parent.appendChild(section);
                return;
            }
            var controls = document.createElement("div");
            controls.className = "srcx-dashboard__architecture-occurrence-controls";
            controls.setAttribute("role", "group");
            controls.setAttribute("aria-label", "Browse relationship evidence originating in this file");
            var activate = occurrenceButton(
                "Browse evidence",
                "Activate relationship evidence browser; " + items.length +
                    (items.length === 1 ? " item" : " items"),
            );
            activate.dataset.srcxFileEvidenceActivate = "";
            activate.setAttribute("aria-pressed", "false");
            var activation = document.createElement("div");
            activation.className = "srcx-dashboard__architecture-declaration-navigation";
            activation.appendChild(activate);
            var previous = occurrenceButton("Previous", "Previous relationship evidence item from this file");
            var pageStatus = document.createElement("span");
            pageStatus.className = "srcx-dashboard__architecture-occurrence-status";
            pageStatus.setAttribute("role", "status");
            pageStatus.setAttribute("aria-live", "polite");
            pageStatus.setAttribute("aria-atomic", "true");
            var next = occurrenceButton("Next", "Next relationship evidence item from this file");
            var metadata = document.createElement("div");
            metadata.className = "srcx-dashboard__architecture-occurrence-metadata";
            controls.append(previous, pageStatus, next);
            section.append(activation, controls, metadata);
            parent.appendChild(section);
            var activeIndex = null;
            var restingEvidence = state.selectedEvidence;
            updateInactiveState();
            activate.addEventListener("click", function () {
                if (activeIndex !== null) {
                    activeIndex = null;
                    metadata.replaceChildren();
                    setSelectedEvidence(restingEvidence);
                    updateInactiveState();
                    return;
                }
                activeIndex = 0;
                renderActiveItem();
            });
            previous.addEventListener("click", function () {
                if (activeIndex !== null && activeIndex > 0) activeIndex -= 1;
                renderActiveItem();
            });
            next.addEventListener("click", function () {
                if (activeIndex !== null && activeIndex + 1 < items.length) activeIndex += 1;
                renderActiveItem();
            });

            function updateInactiveState() {
                activate.textContent = "Browse evidence";
                activate.setAttribute(
                    "aria-label",
                    "Activate relationship evidence browser; " + items.length +
                        (items.length === 1 ? " item" : " items"),
                );
                activate.setAttribute("aria-pressed", "false");
                previous.disabled = true;
                next.disabled = true;
                pageStatus.textContent = "Not active / " + items.length +
                    (items.length === 1 ? " catalog evidence item / " : " catalog evidence items / ") +
                    fileEvidenceDistinctLineCount(items) + " distinct source " +
                    (fileEvidenceDistinctLineCount(items) === 1 ? "line" : "lines");
            }

            function renderActiveItem() {
                if (activeIndex === null) return;
                var item = items[activeIndex];
                metadata.replaceChildren(fileRelationshipEvidenceMetadata(item, sourceFile));
                activate.textContent = "Stop browsing";
                activate.setAttribute("aria-label", "Stop browsing relationship evidence from this file");
                activate.setAttribute("aria-pressed", "true");
                previous.disabled = activeIndex === 0;
                next.disabled = activeIndex + 1 === items.length;
                setSelectedEvidence({
                    kind: "relationship",
                    sourceFileKey: sourceFileKey(sourceFile),
                    line: item.line,
                    relationshipLineCounts: fileEvidenceLineCounts(items),
                    relationshipCountSingular: "catalog relationship evidence item",
                    relationshipCountPlural: "catalog relationship evidence items",
                    reason: "File relationship evidence item " + (activeIndex + 1) + " of " + items.length +
                        ": " + sourceFile.path + ":" + item.line + " / " + fileEvidenceTypeLabel(item),
                });
                pageStatus.textContent = "File relationship evidence item " + (activeIndex + 1) + " of " +
                    items.length + " / " + sourceFile.path + ":" + item.line + " / " +
                    fileEvidenceTypeLabel(item) + " / " + fileEvidenceDistinctLineCount(items) +
                    " distinct source " + (fileEvidenceDistinctLineCount(items) === 1 ? "line" : "lines");
            }
        }

        function fileRelationshipEvidenceItems(node, sourceFile) {
            if (!sourceFile) return [];
            var activeFileKey = sourceFileKey(sourceFile);
            var items = [];
            var ordinal = 0;
            (node.internalOccurrences || []).forEach(function (occurrence) {
                if (occurrenceSourceFileKey(occurrence) !== activeFileKey) return;
                items.push({
                    type: "internal",
                    line: occurrence.line,
                    occurrence: occurrence,
                    edge: null,
                    ordinal: ordinal++,
                });
            });
            availableFileEdges.forEach(function (edge) {
                if (endpointId(edge.source) !== node.id) return;
                (edge.occurrences || []).forEach(function (occurrence) {
                    if (occurrenceSourceFileKey(occurrence) !== activeFileKey) return;
                    items.push({
                        type: "outgoing",
                        line: occurrence.line,
                        occurrence: occurrence,
                        edge: edge,
                        ordinal: ordinal++,
                    });
                });
            });
            var typedLines = new Set(items.map(function (item) { return item.line; }));
            (sourceFile.relationshipLines || []).forEach(function (line) {
                if (typedLines.has(line)) return;
                items.push({
                    type: "line-only",
                    line: line,
                    occurrence: null,
                    edge: null,
                    ordinal: ordinal++,
                });
            });
            return items.sort(function (left, right) {
                return left.line - right.line || fileEvidenceTypeOrder(left) - fileEvidenceTypeOrder(right) ||
                    left.ordinal - right.ordinal;
            });
        }

        function fileRelationshipEvidenceMetadata(item, sourceFile) {
            if (!item.occurrence) {
                return factList([
                    ["Evidence type", "Catalog source evidence; target omitted/unavailable"],
                    ["Location", sourceFile.path + ":" + item.line],
                    ["Endpoint", "Target omitted/unavailable; no endpoint inferred"],
                    ["Available detail", "The source line was retained, but typed occurrence metadata was not " +
                        "serialized for this omitted or unavailable target."],
                ]);
            }
            var occurrence = item.occurrence;
            var sourceDeclaration = declarationById.get(occurrence.sourceSymbolId);
            var targetDeclaration = declarationById.get(occurrence.targetSymbolId);
            return factList([
                ["Evidence type", fileEvidenceTypeLabel(item)],
                ["Relationship kind", occurrence.kindLabel || occurrence.kind],
                ["Evidence", occurrence.evidence],
                ["Source declaration", declarationIdentity(sourceDeclaration, occurrence.sourceSymbolId)],
                ["Target declaration", declarationIdentity(targetDeclaration, occurrence.targetSymbolId)],
                ["Location", occurrence.file + ":" + occurrence.line],
                ["Context", occurrence.context || "No source context was serialized for this record"],
            ]);
        }

        function fileEvidenceTypeLabel(item) {
            if (item.type === "internal") return "Typed internal relationship record";
            if (item.type === "outgoing") return "Typed catalog outgoing relationship record";
            return "Catalog source evidence; target omitted/unavailable";
        }

        function fileEvidenceTypeOrder(item) {
            if (item.type === "internal") return 0;
            if (item.type === "outgoing") return 1;
            return 2;
        }

        function fileEvidenceLineCounts(items) {
            var counts = new Map();
            items.forEach(function (item) {
                counts.set(item.line, (counts.get(item.line) || 0) + 1);
            });
            return counts;
        }

        function fileEvidenceDistinctLineCount(items) {
            return new Set(items.map(function (item) { return item.line; })).size;
        }

        function compactOccurrenceFileLabel(path) {
            var parts = String(path).split("/");
            return parts[parts.length - 1] || path;
        }

        function occurrenceButton(label, accessibleLabel) {
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = label;
            button.setAttribute("aria-label", accessibleLabel);
            return button;
        }

        function appendDeclarationButton(parent, label, declaration) {
            if (!declaration) return;
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = label;
            button.addEventListener("click", function () { navigateToDeclaration(declaration); });
            parent.appendChild(button);
        }

        function navigateToDeclaration(declaration) {
            state.selectedId = null;
            state.selectedType = null;
            state.selectedEdge = null;
            state.cycleStepEdgeId = null;
            resetGraphHighlight();
            openDetail("Declaration source", declaration.qualifiedName);
            detailFields.appendChild(factList([
                ["Declaration", declaration.file + ":" + declaration.line],
                ["Scope", declaration.build + " / " + declaration.project + " / " + declaration.sourceSet],
                ["Kind", declaration.kind],
            ]));
            var sourceFile = sourceFileForDeclaration(declaration);
            setSelectedEvidence({
                kind: "declaration",
                sourceFileKey: sourceFileKey(sourceFile),
                line: declaration.line,
                focusViewer: true,
                reason: "Declaration: " + declaration.file + ":" + declaration.line,
            });
            appendActiveEvidenceSection(detailFields, "Source evidence");
        }

        function fileRecordSummary(node) {
            var section = detailSection("Relationship record counts");
            var counts = document.createElement("dl");
            counts.className = "srcx-dashboard__architecture-record-counts";
            [
                ["Inbound", node.totalIncomingRecordCount],
                ["Outbound", node.totalOutgoingRecordCount],
                ["Internal", node.totalInternalRecordCount],
            ].forEach(function (item) {
                var row = document.createElement("div");
                var term = document.createElement("dt");
                var value = document.createElement("dd");
                term.textContent = item[0];
                value.textContent = String(item[1]);
                row.append(term, value);
                counts.appendChild(row);
            });
            var linkedInFrame = links.filter(function (edge) {
                if (isAnalysisCycleEdge(edge)) return false;
                return endpointId(edge.source) === node.id || endpointId(edge.target) === node.id;
            }).reduce(function (sum, edge) { return sum + edgeRecordCount(edge); }, 0);
            var internalInFrame = scopedInternalRecordCount(node, state.selectedRelationshipKind);
            var displayed = linkedInFrame + internalInFrame;
            var total = (node.totalIncomingRecordCount || 0) + (node.totalOutgoingRecordCount || 0) +
                (node.totalInternalRecordCount || 0);
            var explanation = document.createElement("p");
            explanation.className = "srcx-dashboard__architecture-muted";
            explanation.textContent = "Outbound records start in this file, inbound records start elsewhere and " +
                "end at a declaration here, and internal records stay in this file. " + displayed + " of " + total +
                " attached records are represented in this frame; same-file records have evidence but no arrow. " +
                "Counts are resolved, deduplicated, non-import relationship records, not unique callers, IDE " +
                "usages, runtime calls, or execution traces.";
            section.append(counts, explanation);
            return section;
        }

        function appendActiveEvidenceSection(parent, title) {
            var section = detailSection(title);
            parent.appendChild(section);
            bindEvidenceHost(section);
            return section;
        }

        function bindEvidenceHost(parent) {
            var evidenceStatus = document.createElement("p");
            evidenceStatus.className = "srcx-dashboard__architecture-source-evidence-status";
            evidenceStatus.dataset.srcxSourceEvidenceStatus = "";
            evidenceStatus.setAttribute("role", "status");
            evidenceStatus.setAttribute("aria-live", "polite");
            evidenceStatus.setAttribute("aria-atomic", "true");
            var evidenceViewer = document.createElement("div");
            evidenceViewer.className = "srcx-dashboard__architecture-source-evidence-viewer";
            evidenceViewer.dataset.srcxSourceEvidenceViewer = "";
            parent.append(evidenceStatus, evidenceViewer);
            activeEvidenceStatus = evidenceStatus;
            activeEvidenceHost = evidenceViewer;
            renderActiveEvidence();
            return evidenceViewer;
        }

        function setSelectedEvidence(evidence) {
            state.previewEvidence = null;
            state.selectedEvidence = evidence || null;
            renderActiveEvidence();
        }

        function previewRelationshipEvidence(edge, interaction) {
            if (detail.hidden || !detail.classList.contains("is-open") || !activeEvidenceHost) return;
            var visibleEdge = links.find(function (candidate) { return candidate.id === edge.id; });
            var evidenceEdge = visibleEdge || edge;
            var occurrence = (evidenceEdge.occurrences || [])[0];
            if (!occurrence) return;
            state.previewEvidence = {
                kind: "relationship",
                sourceFileKey: occurrenceSourceFileKey(occurrence),
                line: occurrence.line,
                reason: interaction + ": " + occurrence.file + ":" + occurrence.line,
            };
            renderActiveEvidence();
        }

        function clearRelationshipPreview() {
            if (!state.previewEvidence) return;
            state.previewEvidence = null;
            renderActiveEvidence();
        }

        function restoreRelationshipPreview(edge) {
            if (focusedRelationshipPreview) {
                previewRelationshipEvidence(focusedRelationshipPreview, "Focused relationship");
                return;
            }
            if (hoveredRelationshipPreview) {
                previewRelationshipEvidence(hoveredRelationshipPreview, "Hovered relationship");
                return;
            }
            clearRelationshipPreview();
        }

        function renderActiveEvidence() {
            if (!activeEvidenceHost || !activeEvidenceStatus || !activeEvidenceHost.isConnected) return;
            var previewing = Boolean(state.previewEvidence);
            var evidence = state.previewEvidence || state.selectedEvidence;
            activeEvidenceHost.replaceChildren();
            if (!evidence) {
                activeEvidenceStatus.textContent = "No source evidence is active.";
                var empty = document.createElement("p");
                empty.className = "srcx-dashboard__architecture-muted";
                empty.textContent = "Select a relationship occurrence, declaration, finding, or file source.";
                activeEvidenceHost.appendChild(empty);
                return;
            }
            activeEvidenceStatus.textContent = (previewing ? "Preview evidence / " : "Selected evidence / ") +
                evidence.reason;
            appendSourceViewer(activeEvidenceHost, sourceFileForEvidence(evidence), {
                activeLine: evidence.line,
                activeType: evidence.kind,
                focusViewer: Boolean(evidence.focusViewer),
                relationshipLineCounts: evidence.relationshipLineCounts,
                relationshipCountSingular: evidence.relationshipCountSingular,
                relationshipCountPlural: evidence.relationshipCountPlural,
            });
            if (evidence.focusViewer) evidence.focusViewer = false;
        }

        function appendSourceViewer(parent, sourceFile, options) {
            var section = detailSection("Complete source file");
            section.classList.add("srcx-dashboard__architecture-source-section");
            if (!sourceFile) {
                var unavailable = document.createElement("p");
                unavailable.className = "srcx-dashboard__architecture-muted";
                unavailable.textContent = "Source text was not included for this bounded file.";
                section.appendChild(unavailable);
                parent.appendChild(section);
                return;
            }
            var identity = document.createElement("p");
            identity.className = "srcx-dashboard__architecture-source-identity";
            identity.textContent = sourceFile.build + " / " + sourceFile.project + " / " + sourceFile.sourceSet +
                " / " + sourceFile.path;
            var key = document.createElement("p");
            key.className = "srcx-dashboard__architecture-source-key";
            key.textContent = "Declaration lines are marked. For a selected relationship, every distinct " +
                "occurrence line in this file is lightly marked with its record count; the active record is " +
                "highlighted strongly.";
            var viewer = document.createElement("div");
            viewer.className = "srcx-dashboard__architecture-source-viewer";
            viewer.setAttribute("role", "region");
            viewer.setAttribute("tabindex", "0");
            viewer.setAttribute("aria-label", "Complete source for " + sourceFile.path);
            viewer.addEventListener("wheel", routeSourceWheelToDrawer, { passive: false });
            var declarationLines = new Set(sourceFile.declarationLines || []);
            var relationshipLineCounts = options.relationshipLineCounts instanceof Map ?
                options.relationshipLineCounts : new Map();
            var syntax = sourceSyntaxForPath(sourceFile.path);
            var syntaxState = sourceSyntaxState(syntax);
            var activeRow = null;
            splitSourceLines(sourceFile.content).forEach(function (line, index) {
                var lineNumber = index + 1;
                var row = document.createElement("div");
                row.className = "srcx-dashboard__architecture-source-line";
                row.dataset.srcxSourceLine = String(lineNumber);
                if (declarationLines.has(lineNumber)) row.classList.add("is-declaration-line");
                if (relationshipLineCounts.has(lineNumber)) {
                    row.classList.add("is-relationship-line");
                }
                if (lineNumber === options.activeLine) {
                    row.classList.add("is-active-line");
                    if (options.activeType === "declaration") row.classList.add("is-active-declaration");
                    if (options.activeType === "relationship") row.classList.add("is-active-relationship");
                    if (options.activeType === "finding") row.classList.add("is-active-finding");
                    row.setAttribute("aria-current", "true");
                    activeRow = row;
                }
                var number = document.createElement("span");
                number.className = "srcx-dashboard__architecture-source-line-number";
                number.setAttribute("aria-hidden", "true");
                number.textContent = String(lineNumber);
                var code = document.createElement("code");
                appendHighlightedSourceLine(code, line, syntax, syntaxState);
                row.append(number, code);
                if (relationshipLineCounts.has(lineNumber)) {
                    var countBadge = document.createElement("span");
                    countBadge.className = "srcx-dashboard__architecture-source-record-count";
                    var count = relationshipLineCounts.get(lineNumber);
                    countBadge.setAttribute(
                        "aria-label",
                        count + " " + (count === 1 ?
                            options.relationshipCountSingular || "displayed relationship record" :
                            options.relationshipCountPlural || "displayed relationship records") +
                        " on line " + lineNumber,
                    );
                    var countText = document.createElement("span");
                    countText.setAttribute("aria-hidden", "true");
                    countText.textContent = "×" + count;
                    countBadge.appendChild(countText);
                    row.appendChild(countBadge);
                }
                viewer.appendChild(row);
            });
            section.append(identity, key, viewer);
            parent.appendChild(section);
            if (activeRow) scrollSourceLine(viewer, activeRow, options.focusViewer);
        }

        function splitSourceLines(content) {
            return content.split(/\r\n|\r|\n/);
        }

        function sourceSyntaxForPath(path) {
            var value = String(path || "").toLowerCase();
            if (value.endsWith(".java")) return "java";
            if (value.endsWith(".kt") || value.endsWith(".kts")) return "kotlin";
            if (value.endsWith(".groovy") || value.endsWith(".gradle")) return "groovy";
            if (value.endsWith(".json")) return "json";
            if (value.endsWith(".yaml") || value.endsWith(".yml")) return "yaml";
            if (value.endsWith(".toml")) return "toml";
            if (value.endsWith(".md") || value.endsWith(".markdown")) return "markdown";
            return "plain";
        }

        function sourceSyntaxState(syntax) {
            return { syntax: syntax, blockComment: false, tripleQuote: null, markdownFence: false };
        }

        function appendHighlightedSourceLine(code, line, syntax, state) {
            try {
                if (syntax === "plain") return code.appendChild(document.createTextNode(line));
                if (syntax === "markdown") return highlightMarkdownLine(code, line, state);
                if (syntax === "json") return highlightDataLine(code, line, "json");
                if (syntax === "yaml") return highlightDataLine(code, line, "yaml");
                if (syntax === "toml") return highlightDataLine(code, line, "toml");
                return highlightJvmLine(code, line, syntax, state);
            } catch (ignored) {
                code.replaceChildren(document.createTextNode(line));
            }
        }

        function appendSyntaxToken(parent, text, type) {
            if (!text) return;
            var classes = {
                keyword: "srcx-syntax-keyword",
                string: "srcx-syntax-string",
                number: "srcx-syntax-number",
                comment: "srcx-syntax-comment",
                property: "srcx-syntax-property",
                annotation: "srcx-syntax-annotation",
                literal: "srcx-syntax-literal",
                punctuation: "srcx-syntax-punctuation",
                heading: "srcx-syntax-heading",
                emphasis: "srcx-syntax-emphasis",
            };
            var className = classes[type];
            if (!className) {
                parent.appendChild(document.createTextNode(text));
                return;
            }
            var span = document.createElement("span");
            span.className = className;
            span.textContent = text;
            parent.appendChild(span);
        }

        function highlightJvmLine(code, line, syntax, state) {
            var keywords = sourceKeywordSet(syntax);
            var index = 0;
            while (index < line.length) {
                if (state.blockComment) {
                    var commentEnd = line.indexOf("*/", index);
                    if (commentEnd < 0) {
                        appendSyntaxToken(code, line.slice(index), "comment");
                        return;
                    }
                    appendSyntaxToken(code, line.slice(index, commentEnd + 2), "comment");
                    state.blockComment = false;
                    index = commentEnd + 2;
                    continue;
                }
                if (state.tripleQuote) {
                    var tripleEnd = line.indexOf(state.tripleQuote, index);
                    if (tripleEnd < 0) {
                        appendSyntaxToken(code, line.slice(index), "string");
                        return;
                    }
                    appendSyntaxToken(code, line.slice(index, tripleEnd + 3), "string");
                    state.tripleQuote = null;
                    index = tripleEnd + 3;
                    continue;
                }
                if (line.startsWith("//", index)) {
                    appendSyntaxToken(code, line.slice(index), "comment");
                    return;
                }
                if (line.startsWith("/*", index)) {
                    state.blockComment = true;
                    continue;
                }
                if ((syntax === "kotlin" || syntax === "groovy") && line.startsWith('"""', index)) {
                    state.tripleQuote = '"""';
                    appendSyntaxToken(code, '"""', "string");
                    index += 3;
                    continue;
                }
                var character = line[index];
                if (character === '"' || character === "'") {
                    var stringEnd = scanQuotedToken(line, index, character);
                    appendSyntaxToken(code, line.slice(index, stringEnd), "string");
                    index = stringEnd;
                    continue;
                }
                if (character === "@") {
                    var annotationEnd = scanWhile(line, index + 1, /[A-Za-z0-9_.]/);
                    appendSyntaxToken(code, line.slice(index, annotationEnd), "annotation");
                    index = annotationEnd;
                    continue;
                }
                if (/[A-Za-z_$]/.test(character)) {
                    var wordEnd = scanWhile(line, index + 1, /[A-Za-z0-9_$]/);
                    var word = line.slice(index, wordEnd);
                    appendSyntaxToken(code, word, keywords.has(word) ? "keyword" :
                        /^(true|false|null)$/.test(word) ? "literal" : null);
                    index = wordEnd;
                    continue;
                }
                if (/\d/.test(character)) {
                    var numberEnd = scanWhile(line, index + 1, /[0-9A-Fa-f_xXbB.eE+-]/);
                    appendSyntaxToken(code, line.slice(index, numberEnd), "number");
                    index = numberEnd;
                    continue;
                }
                appendSyntaxToken(code, character, /[{}()[\],.;:]/.test(character) ? "punctuation" : null);
                index += 1;
            }
        }

        function sourceKeywordSet(syntax) {
            var common = "class interface enum extends implements public private protected static final abstract " +
                "return if else for while switch case break continue try catch finally throw throws new this super " +
                "package import void boolean byte char short int long float double instanceof synchronized";
            var kotlin = " fun val var object data sealed open override internal companion when is in as typealias " +
                "suspend inline reified tailrec operator infix constructor init by where actual expect";
            var groovy = " def trait closure in as with assert each collect find inject delegate owner";
            return new Set((common + (syntax === "kotlin" ? kotlin : syntax === "groovy" ? groovy : ""))
                .trim().split(/\s+/));
        }

        function highlightDataLine(code, line, syntax) {
            var index = 0;
            var keyBoundary = true;
            while (index < line.length) {
                var character = line[index];
                if ((syntax === "yaml" || syntax === "toml") && character === "#") {
                    appendSyntaxToken(code, line.slice(index), "comment");
                    return;
                }
                if (character === '"' || character === "'") {
                    var stringEnd = scanQuotedToken(line, index, character);
                    var after = line.slice(stringEnd).match(/^\s*[:=]/);
                    appendSyntaxToken(code, line.slice(index, stringEnd), after ? "property" : "string");
                    index = stringEnd;
                    keyBoundary = false;
                    continue;
                }
                if (/[A-Za-z_-]/.test(character)) {
                    var wordEnd = scanWhile(line, index + 1, /[A-Za-z0-9_.-]/);
                    var word = line.slice(index, wordEnd);
                    var remainder = line.slice(wordEnd);
                    var property = keyBoundary && /^\s*[:=]/.test(remainder);
                    appendSyntaxToken(code, word, property ? "property" :
                        /^(true|false|null|yes|no|on|off)$/.test(word) ? "literal" : null);
                    index = wordEnd;
                    keyBoundary = false;
                    continue;
                }
                if (/[-\d]/.test(character) && /\d/.test(line[index + Number(character === "-")] || "")) {
                    var numberEnd = scanWhile(line, index + 1, /[0-9.eE+-]/);
                    appendSyntaxToken(code, line.slice(index, numberEnd), "number");
                    index = numberEnd;
                    keyBoundary = false;
                    continue;
                }
                appendSyntaxToken(code, character, /[{}[\],:=]/.test(character) ? "punctuation" : null);
                if (character === "," || character === "{" || character === "\n") keyBoundary = true;
                index += 1;
            }
        }

        function highlightMarkdownLine(code, line, state) {
            var fence = line.match(/^\s*(```+|~~~+)/);
            if (fence) {
                appendSyntaxToken(code, line, "keyword");
                state.markdownFence = !state.markdownFence;
                return;
            }
            if (state.markdownFence) {
                appendSyntaxToken(code, line, "string");
                return;
            }
            var heading = line.match(/^(\s{0,3}#{1,6}\s+)(.*)$/);
            if (heading) {
                appendSyntaxToken(code, heading[1], "punctuation");
                appendSyntaxToken(code, heading[2], "heading");
                return;
            }
            var index = 0;
            var tokenPattern = /(`+[^`]*`+|\*\*[^*]+\*\*|__[^_]+__|^\s*(?:[-*+] |\d+\. |> ))/g;
            var match;
            while ((match = tokenPattern.exec(line)) !== null) {
                code.appendChild(document.createTextNode(line.slice(index, match.index)));
                appendSyntaxToken(code, match[0], match[0][0] === "`" ? "string" : "emphasis");
                index = match.index + match[0].length;
                if (match[0].length === 0) tokenPattern.lastIndex += 1;
            }
            code.appendChild(document.createTextNode(line.slice(index)));
        }

        function scanQuotedToken(line, start, quote) {
            var index = start + 1;
            while (index < line.length) {
                if (line[index] === "\\") index += 2;
                else if (line[index] === quote) return index + 1;
                else index += 1;
            }
            return line.length;
        }

        function scanWhile(line, start, pattern) {
            var index = start;
            while (index < line.length && pattern.test(line[index])) index += 1;
            return index;
        }

        function routeSourceWheelToDrawer(event) {
            if (Math.abs(event.deltaY) <= Math.abs(event.deltaX) || detail.scrollHeight <= detail.clientHeight) return;
            var previousScrollTop = detail.scrollTop;
            detail.scrollTop += event.deltaY;
            if (detail.scrollTop !== previousScrollTop) event.preventDefault();
        }

        function scrollSourceLine(viewer, row, focusViewer) {
            window.requestAnimationFrame(function () {
                var drawer = viewer.closest("[data-srcx-detail]");
                if (drawer) {
                    var rowBox = row.getBoundingClientRect();
                    var drawerBox = drawer.getBoundingClientRect();
                    var top = Math.max(
                        0,
                        drawer.scrollTop + rowBox.top - drawerBox.top - (drawer.clientHeight - rowBox.height) / 2,
                    );
                    if (typeof drawer.scrollTo === "function") {
                        drawer.scrollTo({ top: top, behavior: reducedMotion() ? "auto" : "smooth" });
                    } else drawer.scrollTop = top;
                }
                if (focusViewer) viewer.focus({ preventScroll: true });
            });
        }

        function sourceFileForNode(node) {
            if (node.entityType === "file") return sourceFileById.get(node.id) || sourceFileByScopePath.get(
                scopePathKey(node.build, node.project, node.sourceSet, node.path),
            );
            return sourceFileByScopePath.get(scopePathKey(node.build, node.project, node.sourceSet, node.file));
        }

        function fileEntityForSymbol(node) {
            return fileEntityByScopePath.get(scopePathKey(
                node.build,
                node.project,
                node.sourceSet,
                node.file,
            ));
        }

        function sourceFileFindingsForSymbol(node) {
            var file = fileEntityForSymbol(node);
            if (!file) return [];
            var findingIds = new Set(file.findingIds || []);
            return data.findings.filter(function (finding) { return findingIds.has(finding.id); });
        }

        function sourceFileForOccurrence(occurrence) {
            return sourceFileByScopePath.get(scopePathKey(
                occurrence.build,
                occurrence.project,
                occurrence.sourceSet,
                occurrence.file,
            ));
        }

        function sourceFileForDeclaration(declaration) {
            return sourceFileByScopePath.get(scopePathKey(
                declaration.build,
                declaration.project,
                declaration.sourceSet,
                declaration.file,
            ));
        }

        function sourceFileKey(sourceFile) {
            if (!sourceFile) return null;
            return scopePathKey(sourceFile.build, sourceFile.project, sourceFile.sourceSet, sourceFile.path);
        }

        function occurrenceSourceFileKey(occurrence) {
            return scopePathKey(occurrence.build, occurrence.project, occurrence.sourceSet, occurrence.file);
        }

        function sourceFileForEvidence(evidence) {
            if (!evidence || !evidence.sourceFileKey) return null;
            return sourceFileByScopePath.get(evidence.sourceFileKey) || sourceFileById.get(evidence.sourceFileKey);
        }

        function findingEvidence(finding, node) {
            var path = finding.filePath || node.path || node.file;
            var sourceFile = sourceFileByScopePath.get(scopePathKey(
                finding.build,
                finding.project,
                node.sourceSet,
                path,
            )) || data.sourceFiles.find(function (candidate) {
                return candidate.build === finding.build && candidate.project === finding.project &&
                    candidate.path === path;
            });
            return {
                kind: "finding",
                sourceFileKey: sourceFileKey(sourceFile),
                line: Number.isInteger(finding.line) && finding.line > 0 ? finding.line : null,
                findingId: finding.id,
                message: finding.message,
                reason: "Finding " + finding.id + ": " + path +
                    (finding.line ? ":" + finding.line : " / file-level evidence; no exact line") +
                    " / " + finding.message,
            };
        }

        function edgeKinds(edge) {
            return edge.kindCounts ? edge.kindCounts.map(countLabel).join(", ") : "Relationship";
        }

        function edgeEvidence(edge) {
            return edge.evidenceCounts ? edge.evidenceCounts.map(countLabel).join(", ") : "Unspecified";
        }

        function detailSection(title) {
            var section = document.createElement("section");
            section.className = "srcx-dashboard__architecture-detail-section";
            var heading = document.createElement("h4");
            heading.textContent = title;
            section.appendChild(heading);
            return section;
        }

        function openDetail(kicker, title) {
            if (detailCloseTimer !== null) {
                window.clearTimeout(detailCloseTimer);
                detailCloseTimer = null;
            }
            detail.hidden = false;
            detailResize.hidden = false;
            detailKicker.textContent = kicker;
            detailTitle.textContent = title;
            detailFields.replaceChildren();
            activeEvidenceHost = null;
            activeEvidenceStatus = null;
            state.selectedEvidence = null;
            state.previewEvidence = null;
            detail.scrollTop = 0;
            window.requestAnimationFrame(function () {
                if (!detail.hidden && detailCloseTimer === null) detail.classList.add("is-open");
            });
        }

        function closeDetail() {
            detail.classList.remove("is-open");
            detailResize.hidden = true;
            activeEvidenceHost = null;
            activeEvidenceStatus = null;
            state.previewEvidence = null;
            if (detailCloseTimer !== null) window.clearTimeout(detailCloseTimer);
            if (reducedMotion()) {
                detail.hidden = true;
                detailCloseTimer = null;
                return;
            }
            detailCloseTimer = window.setTimeout(function () {
                detail.hidden = true;
                detailCloseTimer = null;
            }, 190);
        }

        function appendNodeBadges(parent, node) {
            var badges = document.createElement("div");
            badges.className = "srcx-dashboard__architecture-node-badges";
            badges.setAttribute("aria-label", "Why this node looks this way");
            badges.appendChild(nodeBadge("Fill: " + node.build + " ownership", "is-build", buildColor(
                node.build,
                buildByName,
            )));
            if (node.entityType !== "file" && declarationSemantic(node) !== "OTHER") {
                badges.appendChild(nodeBadge(
                    "Declaration tag: " + declarationSemanticDetail(node),
                    "is-semantic is-" + declarationSemantic(node).toLowerCase().replace(/_/g, "-"),
                ));
            }
            if (node.important) {
                badges.appendChild(nodeBadge("Dark ring: report-priority / ranking signal", "is-important"));
            }
            if (node.fileFindingCount > 0) {
                badges.appendChild(nodeBadge(
                    "Red ring: " + node.fileFindingCount + " exact file findings",
                    "is-finding",
                ));
            }
            if (node.hasCycle) {
                badges.appendChild(nodeBadge("Red dashed ring: observed file-cycle member", "is-cycle"));
            }
            if (node.hasAnalysisCycle) {
                badges.appendChild(nodeBadge(
                    "Violet dotted ring: analyzer-inferred component-cycle participant",
                    "is-analysis-cycle",
                ));
            }
            if (node.hasAnalyzerFinding && (node.fileFindingCount || 0) === 0) {
                badges.appendChild(nodeBadge(
                    "Violet mark: analyzer finding identifies this component",
                    "is-analyzer-finding",
                ));
            }
            badges.appendChild(nodeBadge(
                "Size: " + nodeRelationshipRecordCount(node) + " relationship records",
                "is-size",
            ));
            parent.appendChild(badges);
        }

        function declarationSemanticDetail(node) {
            if (node.declarationSemanticDetail) return node.declarationSemanticDetail;
            var semantic = declarationSemantic(node);
            if (semantic === "INTERFACE") return "Interface declaration; this is descriptive, not a problem signal";
            if (semantic === "ABSTRACT_CLASS") return "Abstract or sealed class declaration; no intent is inferred";
            if (semantic === "CONCRETE_CLASS") return "Concrete or data class declaration; no intent is inferred";
            if (semantic === "SINGLETON_OBJECT") {
                return "Kotlin object declaration (language-level singleton instance); no architectural intent is inferred";
            }
            if (semantic === "ENUM") return "Enum declaration; this is descriptive, not a problem signal";
            return node.declarationSemanticLabel || node.kind || "Other declaration";
        }

        function semanticImplementationFact(node) {
            var semantic = declarationSemantic(node);
            if (semantic !== "INTERFACE" && semantic !== "ABSTRACT_CLASS") return "Not applicable to this declaration type";
            var acceptedKinds = semantic === "INTERFACE" ? new Set(["IMPLEMENTS"]) :
                new Set(["EXTENDS", "IMPLEMENTS"]);
            var records = [];
            availableSymbolEdges.filter(function (edge) {
                return endpointId(edge.target) === node.id;
            }).forEach(function (edge) {
                (edge.occurrences || []).forEach(function (occurrence) {
                    if (!acceptedKinds.has(occurrence.kind || edge.kind)) return;
                    records.push({
                        occurrence: occurrence,
                        sourceId: occurrence.sourceSymbolId || endpointId(edge.source),
                    });
                });
            });
            var declarations = new Set(records.map(function (record) {
                return record.sourceId ||
                    occurrenceSourceFileKey(record.occurrence) + ":" + record.occurrence.line;
            }));
            var displayedIds = new Set(nodes.map(function (candidate) { return candidate.id; }));
            var displayedDeclarations = new Set(records.map(function (record) {
                return record.sourceId;
            }).filter(function (id) { return id && displayedIds.has(id); }));
            if (!records.length) {
                return "No resolved implementation record is present in the typed symbol catalog; " +
                    "SRCX does not infer why this declaration exists";
            }
            return "Observed implementation evidence: " + declarations.size + " catalog " +
                (declarations.size === 1 ? "declaration" : "declarations") + " / " + records.length + " " +
                (records.length === 1 ? "record" : "records") + "; " + displayedDeclarations.size +
                " source " + (displayedDeclarations.size === 1 ? "declaration is" : "declarations are") +
                " displayed in the current lens";
        }

        function nodeBadge(label, className, color) {
            var badge = document.createElement("span");
            badge.className = "srcx-dashboard__architecture-node-badge " + className;
            if (color) badge.style.setProperty("--srcx-build-color", color);
            var mark = document.createElement("i");
            mark.setAttribute("aria-hidden", "true");
            var text = document.createElement("span");
            text.textContent = label;
            badge.append(mark, text);
            return badge;
        }

        function appendFindingSection(
            parent,
            findings,
            explainCycleOnly,
            node,
            selectedFindingId,
            title,
            analyzerInferred,
            introduction,
        ) {
            var section = document.createElement("section");
            section.className = "srcx-dashboard__architecture-detail-section is-findings";
            if (analyzerInferred) section.classList.add("is-analysis-findings");
            var heading = document.createElement("h4");
            heading.textContent = title || "Exact file findings";
            section.appendChild(heading);
            if (introduction) {
                var note = document.createElement("p");
                note.className = "srcx-dashboard__architecture-muted";
                note.textContent = introduction;
                section.appendChild(note);
            }
            if (!findings.length) {
                var empty = document.createElement("p");
                empty.className = "srcx-dashboard__architecture-muted";
                empty.textContent = explainCycleOnly ?
                    "No exact file findings. This participant is in Problems because of cycle evidence." :
                    "No exact file findings.";
                section.appendChild(empty);
            }
            findings.forEach(function (finding) {
                var row = document.createElement("article");
                row.className = "srcx-dashboard__architecture-finding is-" + finding.severity.toLowerCase();
                row.dataset.srcxArchitectureFindingId = finding.id;
                if (selectedFindingId === finding.id) {
                    row.classList.add("is-selected-evidence");
                    row.setAttribute("aria-current", "true");
                }
                var select = document.createElement("button");
                select.type = "button";
                select.className = "srcx-dashboard__architecture-finding-evidence";
                select.dataset.srcxFindingEvidence = finding.id;
                select.setAttribute("aria-pressed", String(row.classList.contains("is-selected-evidence")));
                select.setAttribute("aria-label", "Show source evidence for finding " + finding.id);
                var severity = document.createElement("span");
                severity.className = "srcx-dashboard__architecture-finding-severity";
                severity.textContent = finding.severity;
                var message = document.createElement("strong");
                message.textContent = finding.message;
                var suggestion = document.createElement("span");
                suggestion.className = "srcx-dashboard__architecture-finding-suggestion";
                suggestion.textContent = finding.suggestion;
                select.append(severity, message, suggestion);
                select.addEventListener("click", function () {
                    section.querySelectorAll("[data-srcx-architecture-finding-id]").forEach(function (candidate) {
                        var selected = candidate === row;
                        candidate.classList.toggle("is-selected-evidence", selected);
                        if (selected) candidate.setAttribute("aria-current", "true");
                        else candidate.removeAttribute("aria-current");
                        var candidateButton = candidate.querySelector("[data-srcx-finding-evidence]");
                        if (candidateButton) candidateButton.setAttribute("aria-pressed", String(selected));
                    });
                    if (finding.analysisCycleId) {
                        openArchitectureEvidence({ findingId: finding.id }, select);
                    } else {
                        setSelectedEvidence(findingEvidence(finding, node));
                    }
                });
                row.appendChild(select);
                section.appendChild(row);
            });
            parent.appendChild(section);
        }

        function appendCycleParticipationSection(parent, node, cycles) {
            var section = detailSection("Cycle participation");
            section.classList.add("srcx-dashboard__architecture-cycle-participation");
            if (!cycles.length) {
                var empty = document.createElement("p");
                empty.className = "srcx-dashboard__architecture-muted";
                empty.textContent = "Not a member of a resolved directed file cycle.";
                section.appendChild(empty);
                parent.appendChild(section);
                return;
            }
            var introduction = document.createElement("p");
            introduction.className = "srcx-dashboard__architecture-muted";
            introduction.textContent = cycles.length + " resolved cycle group" + (cycles.length === 1 ? "" : "s") +
                " contain this file. Open the Cycles lens to step through exact directed edges.";
            var routes = document.createElement("ul");
            cycleRoutesForNode(node, cycles).forEach(function (route) {
                var item = document.createElement("li");
                item.textContent = route.cycleId + ": " + cycleRouteLabel(route);
                routes.appendChild(item);
            });
            section.append(introduction, routes);
            parent.appendChild(section);
        }

        function analysisCyclesForNode(node) {
            return data.analysisCycles.filter(function (cycle) {
                return node.entityType === "file" ? cycle.memberFileIds.includes(node.id) :
                    cycle.memberSymbolIds.includes(node.id);
            });
        }

        function observedCyclesForCurrentProjection() {
            return scopedFileRefillActive(state.selectedRelationshipKind) ? availableObservedCycles : data.cycles;
        }

        function appendAnalysisCycleParticipationSection(parent, node, analysisCycles) {
            var section = detailSection("Analyzer-inferred component-cycle participation");
            section.classList.add("srcx-dashboard__architecture-analysis-cycle-participation");
            var introduction = document.createElement("p");
            introduction.className = "srcx-dashboard__architecture-muted";
            introduction.textContent = analysisCycles.length + " analyzer-inferred qualified component route" +
                (analysisCycles.length === 1 ? " includes" : "s include") +
                " this declaration. These are directional analysis signals, not resolved relationship records.";
            var routes = document.createElement("ul");
            analysisCycles.forEach(function (cycle) {
                var item = document.createElement("li");
                item.textContent = cycle.id + ": " + analysisCycleRouteLabel(cycle);
                routes.appendChild(item);
            });
            section.append(introduction, routes);
            parent.appendChild(section);
        }

        function appendAnalysisCycleSection(parent, node, analysisCycles) {
            var section = detailSection("Analyzer-inferred component cycle");
            section.classList.add("srcx-dashboard__architecture-analysis-cycle-routes");
            var evidenceRequest = state.externalEvidenceRequest;
            var selectedCycle = analysisCycles.find(function (cycle) {
                return evidenceRequest && cycle.id === evidenceRequest.analysisCycleId;
            }) || analysisCycles[0];
            var description = document.createElement("p");
            description.className = "srcx-dashboard__architecture-muted";
            description.textContent = "Qualified analyzer route; directional component evidence only. Its hops " +
                "are not resolved relationship records and do not add to relationship counts.";
            var route = document.createElement("p");
            route.className = "srcx-dashboard__architecture-analysis-cycle-route-label";
            route.textContent = analysisCycleRouteLabel(selectedCycle);
            var related = document.createElement("p");
            related.className = "srcx-dashboard__architecture-muted";
            related.textContent = "Analyzer cycle " + selectedCycle.id + " / " + selectedCycle.build + " / " +
                projectDisplayName(selectedCycle.project, selectedCycle.build) + " / " +
                selectedCycle.findingIds.length + " linked finding" +
                (selectedCycle.findingIds.length === 1 ? "" : "s");
            section.append(description, route, related);
            if (analysisCycles.length > 1) appendAnalysisCycleChooser(section, node, analysisCycles, selectedCycle);
            appendAnalysisCycleParticipants(section, selectedCycle);
            parent.appendChild(section);
            focusRequestedAnalysisParticipant(selectedCycle, node, section);
        }

        function appendAnalysisCycleChooser(parent, node, analysisCycles, selectedCycle) {
            var choices = document.createElement("div");
            choices.className = "srcx-dashboard__architecture-analysis-cycle-choices";
            choices.setAttribute("role", "group");
            choices.setAttribute("aria-label", "Analyzer-inferred cycle routes for this participant");
            analysisCycles.forEach(function (cycle, index) {
                var button = document.createElement("button");
                button.type = "button";
                button.textContent = "Route " + (index + 1);
                button.setAttribute("aria-pressed", String(cycle === selectedCycle));
                button.addEventListener("click", function () {
                    state.externalEvidenceRequest = { analysisCycleId: cycle.id };
                    renderNodeDetail(node);
                    state.externalEvidenceRequest = null;
                });
                choices.appendChild(button);
            });
            parent.appendChild(choices);
        }

        function appendAnalysisCycleParticipants(parent, cycle) {
            var list = document.createElement("ol");
            list.className = "srcx-dashboard__architecture-analysis-cycle-participants";
            cycle.route.slice(0, -1).forEach(function (component, index) {
                var item = document.createElement("li");
                var copy = document.createElement("div");
                var title = document.createElement("strong");
                title.textContent = (index + 1) + ". " + component.name;
                var identity = document.createElement("code");
                identity.textContent = component.componentId;
                var location = document.createElement("span");
                location.textContent = analysisComponentLocation(component);
                copy.append(title, identity, location);
                item.appendChild(copy);
                var navigation = document.createElement("div");
                navigation.className = "srcx-dashboard__architecture-declaration-navigation";
                appendAnalysisParticipantButton(navigation, component, cycle, index);
                if (navigation.childElementCount) item.appendChild(navigation);
                list.appendChild(item);
            });
            parent.appendChild(list);
        }

        function appendAnalysisParticipantButton(parent, component, cycle, index) {
            if (!analysisParticipantSource(component)) return;
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = "Open participant source";
            button.dataset.srcxAnalysisParticipant = component.componentId;
            button.addEventListener("click", function () {
                showAnalysisParticipantEvidence(component, cycle, index, true);
            });
            parent.appendChild(button);
        }

        function focusRequestedAnalysisParticipant(cycle, node, section) {
            var request = state.externalEvidenceRequest;
            var requestedIndex = request && Number.isInteger(request.analysisParticipantIndex) ?
                request.analysisParticipantIndex : cycle.route.slice(0, -1).findIndex(function (component) {
                    return component.fileId === node.id || component.symbolId === node.id;
                });
            var index = requestedIndex >= 0 ? requestedIndex : firstAvailableAnalysisParticipantIndex(cycle);
            if (index < 0) {
                setSelectedEvidence(null);
                var missing = document.createElement("p");
                missing.className = "srcx-dashboard__architecture-cycle-filter-note";
                missing.textContent = "No participant declaration source is available in this bounded report. " +
                    "The complete qualified analyzer route remains listed above.";
                section.appendChild(missing);
                return;
            }
            showAnalysisParticipantEvidence(cycle.route[index], cycle, index, false, section);
        }

        function showAnalysisParticipantEvidence(component, cycle, index, focusViewer, parent) {
            var source = analysisParticipantSource(component);
            setSelectedEvidence({
                kind: "declaration",
                sourceFileKey: sourceFileKey(source),
                line: component.line,
                focusViewer: focusViewer,
                reason: "Analyzer cycle participant " + (index + 1) + " of " + (cycle.route.length - 1) +
                    ": " + component.componentId + " / " + analysisComponentLocation(component),
            });
            if (parent) appendActiveEvidenceSection(parent, "Participant source evidence");
            else renderActiveEvidence();
        }

        function firstAvailableAnalysisParticipantIndex(cycle) {
            return cycle.route.slice(0, -1).findIndex(function (component) {
                return Boolean(analysisParticipantSource(component));
            });
        }

        function analysisParticipantSource(component) {
            if (component.fileId && sourceFileById.has(component.fileId)) return sourceFileById.get(component.fileId);
            if (!component.filePath || !component.sourceSet) return null;
            var cycle = analysisCycleForComponent(component);
            return cycle ? sourceFileByScopePath.get(scopePathKey(
                cycle.build,
                cycle.project,
                component.sourceSet,
                component.filePath,
            )) : null;
        }

        function analysisCycleForComponent(component) {
            return data.analysisCycles.find(function (cycle) { return cycle.route.includes(component); }) || null;
        }

        function analysisComponentLocation(component) {
            if (!component.filePath) return "No declaration location resolved; no typed file location was supplied";
            var location = (component.sourceSet ? component.sourceSet + " / " : "") + component.filePath +
                (component.line ? ":" + component.line : " / declaration line unavailable");
            if (!analysisParticipantSource(component)) location += " / source absent from typed source payload";
            else if (!analysisParticipantProjectionEntity(component)) location += " / outside current map projection";
            return location;
        }

        function analysisParticipantProjectionEntity(component) {
            var entity = component.fileId && fileEntityById.get(component.fileId) ||
                component.symbolId && symbolEntityById.get(component.symbolId) || null;
            if (!entity) return null;
            return nodes.some(function (node) { return node.id === entity.id; }) ? entity : null;
        }

        function analysisCycleRouteLabel(cycle) {
            return cycle.route.map(function (component) { return component.name; }).join(" \u2192 ");
        }

        function renderAnalysisCycleEdgeDetail(edge) {
            var cycle = analysisCycleById.get(edge.analysisCycleId);
            openDetail(
                "Analyzer-inferred cycle hop",
                edge.sourceComponent.name + " \u2192 " + edge.targetComponent.name,
            );
            detailFields.appendChild(factList([
                ["Direction", edge.sourceComponent.componentId + " \u2192 " + edge.targetComponent.componentId],
                ["Evidence", "ANALYZER_INFERRED / directional component-analysis signal"],
                ["Relationship records", "Not applicable; this hop is not a resolved relationship record"],
                ["Analyzer route", cycle ? analysisCycleRouteLabel(cycle) : edge.analysisCycleId],
            ]));
            var section = detailSection("Available participant declarations");
            [edge.sourceComponent, edge.targetComponent].forEach(function (component, index) {
                var row = document.createElement("div");
                row.className = "srcx-dashboard__architecture-analysis-cycle-edge-participant";
                var copy = document.createElement("span");
                copy.textContent = component.componentId + " / " + analysisComponentLocation(component);
                row.appendChild(copy);
                if (cycle) appendAnalysisParticipantButton(row, component, cycle, edge.analysisStepIndex + index);
                section.appendChild(row);
            });
            detailFields.appendChild(section);
            var preferred = analysisParticipantSource(edge.sourceComponent) ? edge.sourceComponent : edge.targetComponent;
            if (cycle && analysisParticipantSource(preferred)) {
                showAnalysisParticipantEvidence(
                    preferred,
                    cycle,
                    preferred === edge.sourceComponent ? edge.analysisStepIndex : edge.analysisStepIndex + 1,
                    false,
                    detailFields,
                );
            }
        }

        function appendCycleRoutes(parent, node, cycles) {
            var routes = cycleRoutesForNode(node, cycles);
            var section = detailSection("Directed cycle routes");
            section.classList.add("srcx-dashboard__architecture-cycle-routes");
            var introduction = document.createElement("p");
            introduction.className = "srcx-dashboard__architecture-muted";
            introduction.textContent = "Routes containing this file are rotated to start and end here; related " +
                "routes complete the compound cycle group. Step through each distinct directed edge once to " +
                "inspect every exact relationship occurrence and source line.";
            var routeList = document.createElement("ol");
            routes.forEach(function (route, index) {
                var item = document.createElement("li");
                item.textContent = "Route " + (index + 1) + " of " + routes.length +
                    (route.containsSelectedFile ? " / selected file: " : " / related route: ") +
                    cycleRouteLabel(route);
                routeList.appendChild(item);
            });
            section.append(introduction, routeList);
            var seenCycleEdgeIds = new Set();
            var steps = [];
            routes.forEach(function (route, routeIndex) {
                route.edgeIds.forEach(function (edgeId, stepIndex) {
                    if (seenCycleEdgeIds.has(edgeId)) return;
                    seenCycleEdgeIds.add(edgeId);
                    steps.push({ edgeId: edgeId, route: route, routeIndex: routeIndex, stepIndex: stepIndex });
                });
            });
            if (!steps.length) {
                var unavailable = document.createElement("p");
                unavailable.className = "srcx-dashboard__architecture-muted";
                unavailable.textContent = "No exact directed cycle edge was included.";
                section.appendChild(unavailable);
                parent.appendChild(section);
                return;
            }
            var evidenceRequest = state.externalEvidenceRequest;
            var requestedStepIndex = evidenceRequest ? steps.findIndex(function (step) {
                if (evidenceRequest.edgeId) return step.edgeId === evidenceRequest.edgeId;
                if (evidenceRequest.routeId) return step.route.routeId === evidenceRequest.routeId;
                return evidenceRequest.cycleId && step.route.cycleId === evidenceRequest.cycleId;
            }) : -1;
            var activeIndex = requestedStepIndex >= 0 ? requestedStepIndex : 0;
            var controls = document.createElement("div");
            controls.className = "srcx-dashboard__architecture-cycle-controls";
            var previous = occurrenceButton("Previous relationship", "Previous directed cycle relationship");
            previous.dataset.srcxCyclePrevious = "";
            var pageStatus = document.createElement("span");
            pageStatus.className = "srcx-dashboard__architecture-cycle-status";
            pageStatus.setAttribute("role", "status");
            pageStatus.setAttribute("aria-live", "polite");
            pageStatus.setAttribute("aria-atomic", "true");
            var next = occurrenceButton("Next relationship", "Next directed cycle relationship");
            next.dataset.srcxCycleNext = "";
            var evidenceHost = document.createElement("div");
            evidenceHost.className = "srcx-dashboard__architecture-cycle-evidence";
            controls.append(previous, pageStatus, next);
            section.append(controls, evidenceHost);
            parent.appendChild(section);
            previous.addEventListener("click", function () {
                if (activeIndex > 0) activeIndex -= 1;
                renderActiveCycleStep();
            });
            next.addEventListener("click", function () {
                if (activeIndex + 1 < steps.length) activeIndex += 1;
                renderActiveCycleStep();
            });
            renderActiveCycleStep();

            function renderActiveCycleStep() {
                var step = steps[activeIndex];
                var edge = fileEdgeById.get(step.edgeId);
                var visibleEdge = links.find(function (candidate) { return candidate.id === step.edgeId; });
                evidenceHost.replaceChildren();
                pageStatus.textContent = "Cycle edge " + (activeIndex + 1) + " of " + steps.length +
                    " / route " + (step.routeIndex + 1) + " of " + routes.length;
                previous.disabled = activeIndex === 0;
                next.disabled = activeIndex + 1 === steps.length;
                if (!edge) return;
                state.cycleStepEdgeId = visibleEdge ? visibleEdge.id : null;
                if (visibleEdge) {
                    highlightEdge(visibleEdge);
                } else {
                    highlight(node.id);
                    var filterNote = document.createElement("p");
                    filterNote.className = "srcx-dashboard__architecture-cycle-filter-note";
                    filterNote.textContent = "This directed edge is outside the current map filter; exact " +
                        "relationship evidence remains available below.";
                    evidenceHost.appendChild(filterNote);
                }
                var source = fileEntityById.get(edge.source);
                var target = fileEntityById.get(edge.target);
                var evidenceEdge = visibleEdge || edge;
                renderOccurrencePager(
                    evidenceEdge,
                    evidenceEdge.occurrences || [],
                    source,
                    target,
                    evidenceHost,
                );
            }
        }

        function cycleRoutesForNode(node, cycles) {
            var evidenceRequest = state.externalEvidenceRequest;
            return cycles.flatMap(function (cycle) {
                return cycle.routes.map(function (route, routeIndex) {
                    return rotateCycleRoute(cycle.id, route, routeIndex, node.id);
                });
            }).sort(function (left, right) {
                var leftRequested = evidenceRequest && (left.routeId === evidenceRequest.routeId ||
                    left.cycleId === evidenceRequest.cycleId);
                var rightRequested = evidenceRequest && (right.routeId === evidenceRequest.routeId ||
                    right.cycleId === evidenceRequest.cycleId);
                return Number(rightRequested) - Number(leftRequested) ||
                    Number(right.containsSelectedFile) - Number(left.containsSelectedFile) ||
                    left.cycleId.localeCompare(right.cycleId) || left.routeIndex - right.routeIndex;
            });
        }

        function rotateCycleRoute(cycleId, route, routeIndex, nodeId) {
            var closedNodes = route.nodeIds.slice();
            var uniqueNodes = closedNodes.slice(0, -1);
            var pivot = uniqueNodes.indexOf(nodeId);
            if (pivot < 0) {
                return {
                    cycleId: cycleId,
                    routeId: route.id || null,
                    routeIndex: routeIndex,
                    nodeIds: closedNodes,
                    edgeIds: route.edgeIds.slice(),
                    containsSelectedFile: false,
                };
            }
            var rotatedNodes = uniqueNodes.slice(pivot).concat(uniqueNodes.slice(0, pivot));
            var rotatedEdges = route.edgeIds.slice(pivot).concat(route.edgeIds.slice(0, pivot));
            rotatedNodes.push(rotatedNodes[0]);
            return {
                cycleId: cycleId,
                routeId: route.id || null,
                routeIndex: routeIndex,
                nodeIds: rotatedNodes,
                edgeIds: rotatedEdges,
                containsSelectedFile: true,
            };
        }

        function cycleRouteLabel(route) {
            return route.nodeIds.map(function (nodeId) {
                var file = fileEntityById.get(nodeId);
                return file ? file.name : nodeId;
            }).join(" \u2192 ");
        }

        function appendSection(parent, title, rows) {
            var section = document.createElement("section");
            section.className = "srcx-dashboard__architecture-detail-section";
            var heading = document.createElement("h4");
            heading.textContent = title;
            section.appendChild(heading);
            var list = document.createElement("ul");
            rows.forEach(function (value) {
                var item = document.createElement("li");
                item.textContent = value;
                list.appendChild(item);
            });
            section.appendChild(list);
            parent.appendChild(section);
        }

        function factList(rows) {
            var list = document.createElement("dl");
            list.className = "srcx-dashboard__architecture-detail-list";
            rows.forEach(function (values) {
                var row = document.createElement("div");
                var term = document.createElement("dt");
                var description = document.createElement("dd");
                term.textContent = values[0];
                description.textContent = values[1];
                row.append(term, description);
                list.appendChild(row);
            });
            return list;
        }

        function renderRelationshipKindFilter(focusKind) {
            relationshipKindOptions.replaceChildren();
            var categoryStats = relationshipCategoryStats();
            [{ id: "all", label: "All relationships" }].concat(availableRelationshipCategories)
                .forEach(function (category) {
                    var stats = categoryStats.get(category.id) || { records: 0, links: 0, internalRecords: 0 };
                    var button = document.createElement("button");
                    button.type = "button";
                    button.className = "srcx-dashboard__architecture-kind-filter-button is-kind-" + category.id;
                    button.dataset.srcxRelationshipKind = category.id;
                    button.setAttribute("role", "radio");
                    button.setAttribute("aria-checked", String(state.selectedRelationshipKind === category.id));
                    button.tabIndex = state.selectedRelationshipKind === category.id ? 0 : -1;
                    var swatch = document.createElement("i");
                    swatch.setAttribute("aria-hidden", "true");
                    var label = document.createElement("span");
                    label.textContent = category.label;
                    var count = document.createElement("strong");
                    count.className = "srcx-dashboard__architecture-kind-filter-count";
                    count.textContent = String(stats.records);
                    count.setAttribute("aria-label", stats.records + " relationship records available in this frame; " +
                        stats.links + " resolved links; " + stats.internalRecords + " same-file records");
                    button.title = category.label + ": " + stats.records + " records available in this frame (" + stats.links +
                        " resolved links, " + stats.internalRecords + " same-file records without arrows)";
                    button.append(swatch, label, count);
                    button.addEventListener("click", function () {
                        applyRelationshipKindFilter(category.id, true);
                    });
                    button.addEventListener("keydown", function (event) {
                        moveRelationshipKindFocus(event, button);
                    });
                    relationshipKindOptions.appendChild(button);
                });
            relationshipKindFilter.hidden = availableRelationshipCategories.length === 0;
            if (focusKind) {
                var target = relationshipKindOptions.querySelector(
                    "[data-srcx-relationship-kind='" + focusKind + "']",
                );
                if (target) target.focus({ preventScroll: true });
            }
        }

        function relationshipCategoryStats() {
            var stats = new Map();
            ["all"].concat(availableRelationshipCategories.map(function (category) { return category.id; }))
                .forEach(function (categoryId) {
                    var projection = projectionForView(categoryId);
                    var links = projection.links.filter(function (edge) { return !isAnalysisCycleEdge(edge); });
                    var internalRecords = projection.availableInternalRecordCount;
                    stats.set(categoryId, {
                        records: links.reduce(function (sum, edge) { return sum + edgeRecordCount(edge); }, 0) +
                            internalRecords,
                        links: links.length,
                        internalRecords: internalRecords,
                    });
                });
            return stats;
        }

        function applyRelationshipKindFilter(categoryId, moveFocus) {
            var allowed = new Set(["all"].concat(availableRelationshipCategories.map(function (item) {
                return item.id;
            })));
            if (!allowed.has(categoryId)) return;
            clearSelection(false);
            state.selectedRelationshipKind = categoryId;
            renderRelationshipKindFilter(moveFocus ? categoryId : null);
            draw();
        }

        function moveRelationshipKindFocus(event, current) {
            var buttons = Array.from(relationshipKindOptions.querySelectorAll("[data-srcx-relationship-kind]"));
            var index = buttons.indexOf(current);
            if (index < 0 || !buttons.length) return;
            var nextIndex;
            if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
                nextIndex = (index - 1 + buttons.length) % buttons.length;
            } else if (event.key === "ArrowRight" || event.key === "ArrowDown") {
                nextIndex = (index + 1) % buttons.length;
            } else if (event.key === "Home") nextIndex = 0;
            else if (event.key === "End") nextIndex = buttons.length - 1;
            else return;
            event.preventDefault();
            applyRelationshipKindFilter(buttons[nextIndex].dataset.srcxRelationshipKind, true);
        }

        function renderNavigator(focusKind) {
            var builds = data.builds.slice();
            var totalFiles = builds.reduce(function (sum, build) { return sum + build.fileNodeCount; }, 0);
            var totalSymbols = builds.reduce(function (sum, build) { return sum + build.symbolNodeCount; }, 0);
            var totalIndexedFiles = builds.reduce(function (sum, build) { return sum + build.indexedFileCount; }, 0);
            var totalIndexedSymbols = builds.reduce(function (sum, build) {
                return sum + build.indexedSymbolCount;
            }, 0);
            var totalProjects = builds.reduce(function (sum, build) { return sum + build.projectCount; }, 0);
            var buildButtons = [filterButton({
                kind: "build",
                value: null,
                title: "All builds",
                summary: builds.length + " builds / " + totalProjects + " projects / " + totalIndexedFiles +
                    " indexed files / " + totalFiles + " shown in the All overview / " + totalIndexedSymbols +
                    " indexed symbols / " + totalSymbols + " shown in the bounded All overview; " +
                    "select a build or project for one complete scoped frame",
                selected: state.selectedBuild === null,
                select: function () { clearGraphContext("build"); },
            })];
            builds.forEach(function (build) {
                buildButtons.push(filterButton({
                    kind: "build",
                    value: build.name,
                    title: build.name,
                    summary: build.context + " / " + build.projectCount + " projects / " +
                        indexedOverviewSummary(build),
                    color: build.color,
                    selected: state.selectedBuild === build.name,
                    select: function () { applyGraphFilters(build.name, null, "build"); },
                }));
            });
            buildFilter.replaceChildren.apply(buildFilter, buildButtons);

            var selectedBuild = data.builds.find(function (build) { return build.name === state.selectedBuild; });
            var projects = projectsForSelectedBuild();
            var projectButtons = [filterButton({
                kind: "project",
                value: null,
                title: "All projects",
                summary: selectedBuild ? projects.length + " projects in " + selectedBuild.name + " / " +
                    indexedOverviewSummary(selectedBuild) :
                    "Choose a build to browse its projects",
                color: selectedBuild ? selectedBuild.color : null,
                selected: state.selectedProject === null,
                select: function () { applyGraphFilters(state.selectedBuild, null, "project"); },
            })];
            projects.forEach(function (project) {
                projectButtons.push(filterButton({
                    kind: "project",
                    value: project.name,
                    title: projectDisplayName(project.name, selectedBuild.name),
                    summary: indexedOverviewSummary(project),
                    color: selectedBuild.color,
                    selected: state.selectedProject === project.name,
                    select: function () { applyGraphFilters(state.selectedBuild, project.name, "project"); },
                }));
            });
            projectFilter.replaceChildren.apply(projectFilter, projectButtons);
            projectFilter.setAttribute("aria-label", selectedBuild ?
                "Filter " + selectedBuild.name + " by project" : "Filter atlas by project; choose a build first");
            var sourceSets = sourceSetsForGraphContext();
            var sourceSetButtons = [filterButton({
                kind: "source-set",
                value: null,
                title: "All source sets",
                summary: sourceSets.length + " source sets in the selected build/project scope",
                selected: state.selectedSourceSet === null,
                select: function () { applySourceSetFilter(null); },
            })];
            sourceSets.forEach(function (sourceSet) {
                var scopedNodes = graphEntitiesForSourceSet(sourceSet);
                sourceSetButtons.push(filterButton({
                    kind: "source-set",
                    value: sourceSet,
                    title: sourceSet,
                    summary: scopedNodes.length + " bounded " +
                        (scopedNodes.length === 1 ? "node" : "nodes") + " in this source set",
                    selected: state.selectedSourceSet === sourceSet,
                    select: function () { applySourceSetFilter(sourceSet); },
                }));
            });
            sourceSetFilter.replaceChildren.apply(sourceSetFilter, sourceSetButtons);
            sourceSetFilter.setAttribute(
                "aria-label",
                "Filter " + graphContextOwnershipLabel() + " by source set",
            );
            wireFilterRail(buildFilter, "build");
            wireFilterRail(projectFilter, "project");
            wireFilterRail(sourceSetFilter, "source-set");
            if (pathFilter) renderPathFilter();
            updateFilterContext(selectedBuild);
            if (focusKind) focusFilterButton(focusKind);
        }

        function wirePathFilter() {
            pathUp.addEventListener("click", openParentPathDirectory);
            pathAll.addEventListener("click", function () {
                applyPathSelection("all", new Set(), "Showing every bounded Atlas path");
            });
            pathClear.addEventListener("click", function () {
                applyPathSelection("include", new Set(), "Path selection cleared; no nodes are shown");
            });
            pathPrevious.addEventListener("click", function () { changePathPage(-1); });
            pathNext.addEventListener("click", function () { changePathPage(1); });
            pathChipPrevious.addEventListener("click", function () {
                state.pathChipPage = Math.max(0, state.pathChipPage - 1);
                renderPathFilter();
            });
            pathChipNext.addEventListener("click", function () {
                state.pathChipPage += 1;
                renderPathFilter();
            });
            wireScrollableFilterRail(pathBreadcrumb);
            wireScrollableFilterRail(pathChips);
        }

        function renderPathFilter(shouldFocusTree, announcement) {
            var records = contextualBoundedPathRecords();
            reconcilePathDirectory(records);
            renderPathBreadcrumb();
            renderPathChips(records);
            var entries = boundedPathEntries(records, state.pathDirectoryParts);
            var rovingIndex = entries.findIndex(function (entry) {
                return entry.id === state.pathRovingEntryId;
            });
            if (rovingIndex >= 0) state.pathPage = Math.floor(rovingIndex / pathPageSize);
            var pageCount = Math.max(1, Math.ceil(entries.length / pathPageSize));
            state.pathPage = Math.max(0, Math.min(state.pathPage, pageCount - 1));
            var start = state.pathPage * pathPageSize;
            var visible = entries.slice(start, start + pathPageSize);
            if (!visible.some(function (entry) { return entry.id === state.pathRovingEntryId; })) {
                state.pathRovingEntryId = visible.length ? visible[0].id : null;
            }
            renderPathTreePage(visible, entries.length, start, shouldFocusTree);
            pathPrevious.disabled = state.pathPage === 0;
            pathNext.disabled = state.pathPage + 1 >= pageCount;
            pathPageStatus.textContent = entries.length ? "Items " + (start + 1) + "–" +
                (start + visible.length) + " of " + entries.length + " / Page " + (state.pathPage + 1) +
                " of " + pageCount : "No bounded child paths / Page 1 of 1";
            pathAll.setAttribute("aria-pressed", String(state.pathSelectionMode === "all"));
            pathClear.setAttribute("aria-pressed", String(
                state.pathSelectionMode === "include" && state.selectedPathKeys.size === 0,
            ));
            var summary = pathContextLabel() + ". " + records.length +
                (records.length === 1 ? " bounded file is" : " bounded files are") +
                " available in the higher-level scope.";
            var message = announcement || pendingPathAnnouncement;
            pendingPathAnnouncement = null;
            pathStatus.textContent = message ? message + ". " + summary : summary;
        }

        function renderPathBreadcrumb() {
            pathBreadcrumb.replaceChildren();
            var rootButton = pathBreadcrumbButton("Bounded paths", 0);
            if (!state.pathDirectoryParts.length) rootButton.setAttribute("aria-current", "location");
            pathBreadcrumb.appendChild(rootButton);
            var firstVisiblePart = Math.max(0, state.pathDirectoryParts.length - 4);
            if (firstVisiblePart > 0) {
                var omitted = document.createElement("span");
                omitted.setAttribute("aria-hidden", "true");
                omitted.textContent = "/ … /";
                pathBreadcrumb.appendChild(omitted);
            }
            state.pathDirectoryParts.slice(firstVisiblePart).forEach(function (part, visibleIndex) {
                var index = firstVisiblePart + visibleIndex;
                var separator = document.createElement("span");
                separator.setAttribute("aria-hidden", "true");
                separator.textContent = "/";
                var button = pathBreadcrumbButton(part, index + 1);
                if (index + 1 === state.pathDirectoryParts.length) {
                    button.setAttribute("aria-current", "location");
                }
                pathBreadcrumb.append(separator, button);
            });
            pathUp.disabled = state.pathDirectoryParts.length === 0;
        }

        function pathBreadcrumbButton(label, depth) {
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = label;
            button.addEventListener("click", function () {
                state.pathDirectoryParts = state.pathDirectoryParts.slice(0, depth);
                resetPathBrowserPage();
                renderPathFilter(true);
            });
            return button;
        }

        function renderPathChips(records) {
            var recordByKey = new Map(records.map(function (record) { return [record.key, record]; }));
            var selected = Array.from(state.selectedPathKeys).map(function (key) {
                return recordByKey.get(key);
            }).filter(Boolean).sort(compareBoundedPathRecords);
            var pageCount = Math.max(1, Math.ceil(selected.length / pathChipPageSize));
            state.pathChipPage = Math.max(0, Math.min(state.pathChipPage, pageCount - 1));
            var start = state.pathChipPage * pathChipPageSize;
            pathChips.replaceChildren();
            selected.slice(start, start + pathChipPageSize).forEach(function (record) {
                var chip = document.createElement("button");
                chip.type = "button";
                chip.className = "srcx-dashboard__architecture-path-chip";
                chip.dataset.srcxPathChip = record.key;
                chip.textContent = record.path + " · " + record.build + " / " + record.project + " / " +
                    record.sourceSet + " ×";
                chip.setAttribute("aria-label", "Remove " + record.path + " from " +
                    record.build + " / " + record.project + " / " + record.sourceSet);
                chip.addEventListener("click", function () { removeSelectedPath(record.key); });
                pathChips.appendChild(chip);
            });
            if (!selected.length) {
                var empty = document.createElement("span");
                empty.textContent = state.pathSelectionMode === "all" ?
                    "All bounded paths are active" : "No bounded paths selected";
                pathChips.appendChild(empty);
            }
            pathChipPrevious.disabled = state.pathChipPage === 0;
            pathChipNext.disabled = state.pathChipPage + 1 >= pageCount;
            pathChipStatus.textContent = selected.length ? "Selected paths " + (start + 1) + "–" +
                (start + Math.min(pathChipPageSize, selected.length - start)) + " of " + selected.length +
                " / Page " + (state.pathChipPage + 1) + " of " + pageCount : "No selected-path chips";
        }

        function renderPathTreePage(entries, totalCount, start, shouldFocusTree) {
            pathTree.replaceChildren();
            if (!entries.length) {
                var empty = document.createElement("p");
                empty.textContent = "No bounded files are available inside this directory.";
                pathTree.appendChild(empty);
                return;
            }
            entries.forEach(function (entry, index) {
                var row = document.createElement("div");
                row.className = "srcx-dashboard__architecture-path-entry is-" + entry.type;
                row.dataset.srcxPathEntry = entry.id;
                row.setAttribute("role", "treeitem");
                row.setAttribute("aria-level", String(state.pathDirectoryParts.length + 1));
                row.setAttribute("aria-posinset", String(start + index + 1));
                row.setAttribute("aria-setsize", String(totalCount));
                var checkedState = pathEntryCheckedState(entry);
                row.setAttribute("aria-checked", checkedState);
                row.setAttribute("aria-label", (entry.type === "dir" ? "Directory " : "File ") + entry.name +
                    "; " + entry.fileKeys.size + " bounded " + (entry.fileKeys.size === 1 ? "file" : "files") +
                    "; " + (checkedState === "true" ? "checked" : checkedState === "mixed" ?
                        "partly checked" : "not checked"));
                row.tabIndex = entry.id === state.pathRovingEntryId ? 0 : -1;
                var toggle = document.createElement("span");
                toggle.className = "srcx-dashboard__architecture-path-toggle";
                toggle.setAttribute("aria-hidden", "true");
                toggle.textContent = checkedState === "true" ? "✓" : checkedState === "mixed" ? "−" : "";
                toggle.addEventListener("click", function (event) {
                    event.stopPropagation();
                    state.pathRovingEntryId = entry.id;
                    toggleBoundedPathEntry(entry);
                });
                var label = document.createElement("span");
                label.className = "srcx-dashboard__architecture-path-entry-label";
                label.textContent = (entry.type === "dir" ? "Directory " : "File ") + entry.name;
                label.addEventListener("click", function () {
                    state.pathRovingEntryId = entry.id;
                    if (entry.type === "dir") openPathDirectory(entry);
                    else toggleBoundedPathEntry(entry);
                });
                var facts = document.createElement("small");
                facts.textContent = entry.fileKeys.size + " bounded " +
                    (entry.fileKeys.size === 1 ? "file" : "files") + " / " + entry.scopeKeys.size +
                    (entry.scopeKeys.size === 1 ? " source scope" : " source scopes");
                row.append(toggle, label, facts);
                row.addEventListener("keydown", function (event) { handlePathTreeKey(event, entry); });
                row.addEventListener("focus", function () { state.pathRovingEntryId = entry.id; });
                pathTree.appendChild(row);
            });
            if (shouldFocusTree) focusCurrentPathEntry();
        }

        function handlePathTreeKey(event, entry) {
            var rows = Array.from(pathTree.querySelectorAll("[data-srcx-path-entry]"));
            var index = rows.indexOf(event.currentTarget);
            var nextIndex = null;
            if (event.key === "ArrowDown") nextIndex = Math.min(rows.length - 1, index + 1);
            else if (event.key === "ArrowUp") nextIndex = Math.max(0, index - 1);
            else if (event.key === "Home") nextIndex = 0;
            else if (event.key === "End") nextIndex = rows.length - 1;
            else if (event.key === "PageDown") return changePathPage(1, event);
            else if (event.key === "PageUp") return changePathPage(-1, event);
            else if (event.key === " " || event.key === "Spacebar") {
                event.preventDefault();
                toggleBoundedPathEntry(entry);
                return;
            } else if (event.key === "Enter") {
                event.preventDefault();
                if (entry.type === "dir") openPathDirectory(entry);
                else toggleBoundedPathEntry(entry);
                return;
            } else if (event.key === "ArrowRight" && entry.type === "dir") {
                event.preventDefault();
                openPathDirectory(entry);
                return;
            } else if ((event.key === "ArrowLeft" || event.key === "Backspace") &&
                state.pathDirectoryParts.length) {
                event.preventDefault();
                openParentPathDirectory();
                return;
            }
            if (nextIndex === null) return;
            event.preventDefault();
            movePathRovingFocus(rows, nextIndex);
        }

        function movePathRovingFocus(rows, index) {
            rows.forEach(function (row, rowIndex) { row.tabIndex = rowIndex === index ? 0 : -1; });
            var target = rows[index];
            if (!target) return;
            state.pathRovingEntryId = target.dataset.srcxPathEntry;
            target.focus({ preventScroll: true });
        }

        function focusCurrentPathEntry() {
            window.requestAnimationFrame(function () {
                var target = Array.from(pathTree.querySelectorAll("[data-srcx-path-entry]")).find(function (row) {
                    return row.dataset.srcxPathEntry === state.pathRovingEntryId;
                });
                if (target) target.focus({ preventScroll: true });
            });
        }

        function changePathPage(delta, event) {
            if (event) event.preventDefault();
            state.pathPage = Math.max(0, state.pathPage + delta);
            state.pathRovingEntryId = null;
            renderPathFilter(true);
        }

        function openPathDirectory(entry) {
            state.pathDirectoryParts = entry.path === pathRootSentinel ? [] : entry.path.split("/");
            resetPathBrowserPage();
            renderPathFilter(true, "Opened directory " + entry.name);
        }

        function openParentPathDirectory() {
            if (!state.pathDirectoryParts.length) return;
            var exitedPath = state.pathDirectoryParts.join("/");
            state.pathDirectoryParts = state.pathDirectoryParts.slice(0, -1);
            state.pathPage = 0;
            var parentEntry = boundedPathEntries(
                contextualBoundedPathRecords(),
                state.pathDirectoryParts,
            ).find(function (entry) { return entry.type === "dir" && entry.path === exitedPath; });
            state.pathRovingEntryId = parentEntry ? parentEntry.id : null;
            renderPathFilter(true, "Moved up one bounded directory level");
        }

        function resetPathBrowserPage() {
            state.pathPage = 0;
            state.pathRovingEntryId = null;
        }

        function toggleBoundedPathEntry(entry) {
            var next = state.pathSelectionMode === "all" ? new Set(contextualBoundedPathRecords().map(function (record) {
                return record.key;
            })) : new Set(state.selectedPathKeys);
            var selectedCount = Array.from(entry.fileKeys).filter(function (key) { return next.has(key); }).length;
            if (selectedCount === entry.fileKeys.size) {
                entry.fileKeys.forEach(function (key) { next.delete(key); });
            } else entry.fileKeys.forEach(function (key) { next.add(key); });
            applyPathSelection("include", next, "Updated bounded path selection");
        }

        function removeSelectedPath(key) {
            var chipIndex = Array.from(pathChips.querySelectorAll("[data-srcx-path-chip]")).findIndex(function (chip) {
                return chip.dataset.srcxPathChip === key;
            });
            var restoreChipFocus = pathChips.contains(document.activeElement);
            var next = new Set(state.selectedPathKeys);
            next.delete(key);
            applyPathSelection("include", next, "Removed one bounded path", true);
            if (restoreChipFocus) focusPathChipAfterRemoval(chipIndex);
        }

        function focusPathChipAfterRemoval(previousIndex) {
            window.requestAnimationFrame(function () {
                var chips = Array.from(pathChips.querySelectorAll("[data-srcx-path-chip]"));
                var target = chips[Math.min(Math.max(0, previousIndex), chips.length - 1)];
                if (!target && !pathChipNext.disabled) target = pathChipNext;
                if (!target && !pathChipPrevious.disabled) target = pathChipPrevious;
                (target || pathClear).focus({ preventScroll: true });
            });
        }

        function applyPathSelection(mode, keys, announcement, preserveChipPage) {
            var restoreTreeFocus = pathTree.contains(document.activeElement);
            clearSelection(false);
            detailReturnFocus = null;
            state.pathSelectionMode = mode;
            state.selectedPathKeys = new Set(keys);
            if (!preserveChipPage) state.pathChipPage = 0;
            state.rovingNodeId = null;
            state.rovingEdgeId = null;
            renderPathFilter(restoreTreeFocus, announcement);
            updateFilterContext(data.builds.find(function (build) { return build.name === state.selectedBuild; }));
            renderRelationshipKindFilter();
            draw();
        }

        function reconcilePathSelection(announcement) {
            var records = contextualBoundedPathRecords();
            var available = new Set(records.map(function (record) { return record.key; }));
            var previouslySelected = state.selectedPathKeys.size;
            state.selectedPathKeys = new Set(Array.from(state.selectedPathKeys).filter(function (key) {
                return available.has(key);
            }));
            if (state.pathSelectionMode === "include" && previouslySelected > 0 && !state.selectedPathKeys.size) {
                state.pathSelectionMode = "all";
                pendingPathAnnouncement = announcement +
                    "; invalid path selections were removed and All bounded paths is active";
            }
            state.pathPage = 0;
            state.pathChipPage = 0;
            state.pathRovingEntryId = null;
            reconcilePathDirectory(records);
        }

        function reconcilePathDirectory(records) {
            while (state.pathDirectoryParts.length && !records.some(function (record) {
                return pathIsDirectoryDescendant(record.path, state.pathDirectoryParts.join("/"));
            })) state.pathDirectoryParts.pop();
        }

        function contextualBoundedPathRecords() {
            return data.sourceFiles.filter(function (sourceFile) {
                if (state.selectedBuild && sourceFile.build !== state.selectedBuild) return false;
                if (state.selectedProject && sourceFile.project !== state.selectedProject) return false;
                if (state.selectedSourceSet && sourceFile.sourceSet !== state.selectedSourceSet) return false;
                return true;
            }).map(function (sourceFile) {
                var path = normalizeBoundedPath(sourceFile.path);
                return {
                    build: sourceFile.build,
                    project: sourceFile.project,
                    sourceSet: sourceFile.sourceSet,
                    path: path,
                    key: boundedPathLeafKey(sourceFile.build, sourceFile.project, sourceFile.sourceSet, path),
                };
            }).sort(compareBoundedPathRecords);
        }

        function boundedPathEntries(records, directoryParts) {
            var entries = new Map();
            records.forEach(function (record) {
                var parts = record.path.split("/").filter(Boolean);
                if (!directoryParts.every(function (part, index) { return parts[index] === part; })) return;
                if (parts.length <= directoryParts.length) return;
                var type = parts.length === directoryParts.length + 1 ? "file" : "dir";
                var name = parts[directoryParts.length];
                var path = directoryParts.concat(name).join("/") || pathRootSentinel;
                var aggregateKey = type + "\u0000" + name;
                var entry = entries.get(aggregateKey);
                if (!entry) {
                    entry = { type: type, name: name, path: path, fileKeys: new Set(), scopeKeys: new Set() };
                    entries.set(aggregateKey, entry);
                }
                entry.fileKeys.add(record.key);
                entry.scopeKeys.add(scopePathKey(record.build, record.project, record.sourceSet, ""));
            });
            return Array.from(entries.values()).map(function (entry) {
                entry.id = typedPathEntryKey(entry.type, entry.path, entry.fileKeys);
                return entry;
            }).sort(compareBoundedPathEntries);
        }

        function typedPathEntryKey(type, path, fileKeys) {
            return type + "\u0000" + path + "\u0000" + Array.from(fileKeys).sort().join("\u0001");
        }

        function pathEntryCheckedState(entry) {
            if (state.pathSelectionMode === "all") return "true";
            var count = Array.from(entry.fileKeys).filter(function (key) {
                return state.selectedPathKeys.has(key);
            }).length;
            if (count === 0) return "false";
            return count === entry.fileKeys.size ? "true" : "mixed";
        }

        function compareBoundedPathEntries(left, right) {
            if (left.type !== right.type) return left.type === "dir" ? -1 : 1;
            return left.name.toLowerCase().localeCompare(right.name.toLowerCase()) ||
                left.name.localeCompare(right.name) || left.id.localeCompare(right.id);
        }

        function compareBoundedPathRecords(left, right) {
            return left.path.toLowerCase().localeCompare(right.path.toLowerCase()) ||
                left.path.localeCompare(right.path) || left.key.localeCompare(right.key);
        }

        function pathContextLabel() {
            if (state.pathSelectionMode === "all") return "all bounded paths";
            if (!state.selectedPathKeys.size) return "no bounded paths";
            return state.selectedPathKeys.size + (state.selectedPathKeys.size === 1 ?
                " selected bounded file" : " selected bounded files");
        }

        function selectExternalEvidencePath(entity) {
            state.pathSelectionMode = "all";
            state.selectedPathKeys = new Set();
            state.pathDirectoryParts = [];
            state.pathPage = 0;
            state.pathChipPage = 0;
            state.pathRovingEntryId = null;
            var path = entityProjectRelativePath(entity);
            if (!entity || !path) return;
            var key = boundedPathLeafKey(entity.build, entity.project, entity.sourceSet, path);
            var available = data.sourceFiles.some(function (sourceFile) {
                return boundedPathLeafKey(
                    sourceFile.build,
                    sourceFile.project,
                    sourceFile.sourceSet,
                    sourceFile.path,
                ) === key;
            });
            if (!available) return;
            state.pathSelectionMode = "include";
            state.selectedPathKeys.add(key);
            state.pathDirectoryParts = path.split("/").slice(0, -1);
        }

        function projectsForSelectedBuild() {
            if (!state.selectedBuild) return [];
            var build = data.builds.find(function (candidate) { return candidate.name === state.selectedBuild; });
            return build ? build.projects.slice().sort(function (left, right) {
                return left.name.localeCompare(right.name);
            }) : [];
        }

        function sourceSetsForGraphContext() {
            if (state.selectedBuild) {
                var build = buildByName.get(state.selectedBuild);
                if (!build) return [];
                if (state.selectedProject) {
                    var project = build.projects.find(function (candidate) {
                        return candidate.name === state.selectedProject;
                    });
                    return project ? project.sourceSets.slice().sort(sourceSetOrder) : [];
                }
                return build.sourceSets.slice().sort(sourceSetOrder);
            }
            return Array.from(new Set(data.builds.flatMap(function (build) {
                return build.sourceSets;
            }))).sort(sourceSetOrder);
        }

        function graphEntitiesForSourceSet(sourceSet) {
            return availableFileNodes.concat(availableSymbolNodes).filter(function (node) {
                if (node.sourceSet !== sourceSet) return false;
                if (state.selectedBuild && node.build !== state.selectedBuild) return false;
                if (state.selectedProject && node.project !== state.selectedProject) return false;
                return true;
            });
        }

        function indexedOverviewSummary(scope) {
            return scope.indexedFileCount + " indexed files / " + scope.fileNodeCount +
                " shown in the All overview / " + scope.indexedSymbolCount + " indexed symbols / " +
                scope.symbolNodeCount + " shown in the All overview; selecting this scope opens one complete frame";
        }

        function filterButton(options) {
            var button = document.createElement("button");
            button.type = "button";
            button.className = "srcx-dashboard__architecture-filter-button";
            var key = options.value === null ? "all" : options.value;
            if (options.kind === "build") button.dataset.srcxFilterBuild = key;
            else if (options.kind === "project") button.dataset.srcxFilterProject = key;
            else button.dataset.srcxFilterSourceSet = key;
            button.setAttribute("aria-pressed", String(options.selected));
            button.setAttribute("aria-label", options.title + "; " + options.summary);
            button.tabIndex = options.selected ? 0 : -1;
            if (options.color) button.style.setProperty("--srcx-build-color", options.color);
            var swatch = document.createElement("i");
            swatch.className = "srcx-dashboard__architecture-filter-swatch" +
                (options.color ? "" : " is-all");
            swatch.setAttribute("aria-hidden", "true");
            var copy = document.createElement("span");
            var title = document.createElement("strong");
            var summary = document.createElement("small");
            title.textContent = options.title;
            summary.textContent = options.summary;
            copy.append(title, summary);
            button.append(swatch, copy);
            button.addEventListener("click", options.select);
            return button;
        }

        function applyGraphFilters(selectedBuild, selectedProject, focusKind) {
            clearSelection(false);
            detailReturnFocus = null;
            state.selectedBuild = selectedBuild || null;
            state.selectedProject = state.selectedBuild && selectedProject ? selectedProject : null;
            state.selectedSourceSet = null;
            reconcilePathSelection("Build or project scope changed");
            state.rovingNodeId = null;
            state.rovingEdgeId = null;
            renderNavigator(focusKind);
            renderRelationshipKindFilter();
            draw();
        }

        function applySourceSetFilter(sourceSet) {
            clearSelection(false);
            detailReturnFocus = null;
            state.selectedSourceSet = sourceSet || null;
            reconcilePathSelection("Source-set scope changed");
            state.rovingNodeId = null;
            state.rovingEdgeId = null;
            renderNavigator("source-set");
            renderRelationshipKindFilter();
            draw();
        }

        function clearGraphContext(focusKind) {
            applyGraphFilters(null, null, focusKind);
        }

        function updateFilterContext(selectedBuild) {
            var buildLabel = selectedBuild ? selectedBuild.context + " " + selectedBuild.name : "All builds";
            var projectLabel = state.selectedProject ?
                "project " + projectDisplayName(state.selectedProject, selectedBuild && selectedBuild.name) :
                "all projects";
            var sourceSetLabel = state.selectedSourceSet ?
                "source set " + state.selectedSourceSet : "all source sets";
            filterContext.textContent = buildLabel + " / " + projectLabel + " / " + sourceSetLabel;
            root.dataset.srcxGraphBuild = state.selectedBuild || "all";
            root.dataset.srcxGraphProject = state.selectedProject || "all";
            root.dataset.srcxGraphSourceSet = state.selectedSourceSet || "all";
            root.dataset.srcxGraphPathMode = state.pathSelectionMode;
            root.dataset.srcxGraphPathCount = String(state.selectedPathKeys.size);
        }

        function graphContextOwnershipLabel() {
            if (!state.selectedBuild) return "all builds and projects";
            if (!state.selectedProject) return state.selectedBuild + " and all of its projects";
            return projectDisplayName(state.selectedProject, state.selectedBuild);
        }

        function wireFilterRail(rail, kind) {
            rail.setAttribute("aria-orientation", "horizontal");
            wireScrollableFilterRail(rail);
            var buttons = Array.from(rail.querySelectorAll("button"));
            buttons.forEach(function (button, index) {
                button.addEventListener("keydown", function (event) {
                    var targetIndex = null;
                    if (event.key === "ArrowRight" || event.key === "ArrowDown") targetIndex = index + 1;
                    if (event.key === "ArrowLeft" || event.key === "ArrowUp") targetIndex = index - 1;
                    if (event.key === "Home") targetIndex = 0;
                    if (event.key === "End") targetIndex = buttons.length - 1;
                    if (targetIndex === null) return;
                    event.preventDefault();
                    moveFilterFocus(buttons, targetIndex, kind);
                });
            });
        }

        function wireScrollableFilterRail(rail) {
            if (rail.dataset.srcxPointerScroll === "true") return;
            rail.dataset.srcxPointerScroll = "true";
            var gesture = null;
            var suppressClickUntil = 0;
            var movementThreshold = 6;

            function pointerDown(event) {
                if (event.pointerType === "touch" || event.button !== 0) return;
                gesture = {
                    pointerId: event.pointerId,
                    startX: event.clientX,
                    startY: event.clientY,
                    startScrollLeft: rail.scrollLeft,
                    moved: false,
                };
            }

            function pointerMove(event) {
                if (!gesture || event.pointerId !== gesture.pointerId) return;
                var distanceX = event.clientX - gesture.startX;
                var distanceY = event.clientY - gesture.startY;
                if (!gesture.moved &&
                    (Math.abs(distanceX) < movementThreshold || Math.abs(distanceX) < Math.abs(distanceY))) return;
                gesture.moved = true;
                rail.setPointerCapture(event.pointerId);
                rail.classList.add("is-dragging");
                rail.scrollLeft = gesture.startScrollLeft - distanceX;
                event.preventDefault();
            }

            function pointerEnd(event) {
                if (!gesture || event.pointerId !== gesture.pointerId) return;
                if (gesture.moved) suppressClickUntil = Date.now() + 300;
                gesture = null;
                rail.classList.remove("is-dragging");
                if (rail.hasPointerCapture(event.pointerId)) rail.releasePointerCapture(event.pointerId);
            }

            function suppressDraggedClick(event) {
                if (Date.now() >= suppressClickUntil) return;
                event.preventDefault();
                event.stopImmediatePropagation();
            }

            function horizontalWheel(event) {
                if (rail.scrollWidth <= rail.clientWidth) return;
                var delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
                if (!delta) return;
                var previous = rail.scrollLeft;
                rail.scrollLeft += delta;
                if (rail.scrollLeft !== previous) event.preventDefault();
            }

            rail.addEventListener("pointerdown", pointerDown);
            rail.addEventListener("pointermove", pointerMove);
            rail.addEventListener("pointerup", pointerEnd);
            rail.addEventListener("pointercancel", pointerEnd);
            rail.addEventListener("click", suppressDraggedClick, true);
            rail.addEventListener("wheel", horizontalWheel, { passive: false });
            filterRailCleanups.push(function () {
                rail.removeEventListener("pointerdown", pointerDown);
                rail.removeEventListener("pointermove", pointerMove);
                rail.removeEventListener("pointerup", pointerEnd);
                rail.removeEventListener("pointercancel", pointerEnd);
                rail.removeEventListener("click", suppressDraggedClick, true);
                rail.removeEventListener("wheel", horizontalWheel);
            });
        }

        function moveFilterFocus(buttons, targetIndex, kind) {
            var wrappedIndex = (targetIndex + buttons.length) % buttons.length;
            buttons.forEach(function (button, index) { button.tabIndex = index === wrappedIndex ? 0 : -1; });
            var target = buttons[wrappedIndex];
            target.focus({ preventScroll: true });
            target.scrollIntoView({
                behavior: reducedMotion() ? "auto" : "smooth",
                block: "nearest",
                inline: "nearest",
            });
            root.dataset.srcxFilterFocus = kind;
        }

        function focusFilterButton(kind) {
            var selector = kind === "build" ? "[data-srcx-filter-build]" :
                kind === "project" ? "[data-srcx-filter-project]" : "[data-srcx-filter-source-set]";
            var value = kind === "build" ? state.selectedBuild :
                kind === "project" ? state.selectedProject : state.selectedSourceSet;
            var key = value || "all";
            var rail = kind === "build" ? buildFilter : kind === "project" ? projectFilter : sourceSetFilter;
            var button = Array.from(rail.querySelectorAll(selector)).find(function (candidate) {
                var candidateValue = kind === "build" ? candidate.dataset.srcxFilterBuild :
                    kind === "project" ? candidate.dataset.srcxFilterProject :
                        candidate.dataset.srcxFilterSourceSet;
                return candidateValue === key;
            });
            if (button) button.focus({ preventScroll: true });
        }

        function ensureSelectionToolbar() {
            if (selectionToolbar) return;
            selectionToolbar = document.createElement("div");
            selectionToolbar.className = "srcx-dashboard__architecture-selection-toolbar";
            selectionToolbar.setAttribute("role", "toolbar");
            selectionToolbar.setAttribute("aria-label", "Arrange atlas nodes");
            boxSelectButton = selectionActionButton("Box select", "box-select", function () {
                setBoxSelectMode(!state.boxSelectMode);
            });
            boxSelectButton.setAttribute("aria-pressed", "false");
            selectProjectButton = selectionActionButton("Select project", "select-project", function () {
                selectScopeNodes("project");
            });
            selectBuildButton = selectionActionButton("Select build", "select-build", function () {
                selectScopeNodes("build");
            });
            frameSelectionButton = selectionActionButton("Frame selection", "frame-selection", frameSelection);
            frameProjectButton = selectionActionButton("Frame project", "frame-project", function () {
                frameScope("project");
            });
            frameBuildButton = selectionActionButton("Frame build", "frame-build", function () {
                frameScope("build");
            });
            clearNodeSelectionButton = selectionActionButton("Clear", "clear-selection", clearNodeSelection);
            selectionStatus = document.createElement("span");
            selectionStatus.className = "srcx-dashboard__architecture-selection-status";
            selectionStatus.setAttribute("role", "status");
            selectionStatus.setAttribute("aria-live", "polite");
            selectionStatus.setAttribute("aria-atomic", "true");
            selectionToolbar.append(
                boxSelectButton,
                selectProjectButton,
                selectBuildButton,
                frameSelectionButton,
                frameProjectButton,
                frameBuildButton,
                clearNodeSelectionButton,
                selectionStatus,
            );
            navigatorElement.insertBefore(selectionToolbar, filterContext);
            updateSelectionToolbar();
        }

        function selectionActionButton(label, action, activate) {
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = label;
            button.dataset.srcxGraphSelectionAction = action;
            button.addEventListener("click", activate);
            return button;
        }

        function updateSelectionToolbar(announcement) {
            if (!selectionToolbar) return;
            var count = state.selectedNodeIds.size;
            var anchor = selectionAnchorNode();
            boxSelectButton.setAttribute("aria-pressed", String(state.boxSelectMode));
            boxSelectButton.textContent = state.boxSelectMode ? "Cancel box select" : "Box select";
            boxSelectButton.disabled = nodes.length === 0;
            selectProjectButton.disabled = !anchor;
            selectBuildButton.disabled = !anchor;
            frameSelectionButton.disabled = count === 0;
            frameProjectButton.disabled = !anchor;
            frameBuildButton.disabled = !anchor;
            clearNodeSelectionButton.disabled = count === 0;
            if (anchor) {
                selectProjectButton.setAttribute(
                    "aria-label",
                    "Select all visible nodes in project " + projectDisplayName(anchor.project, anchor.build),
                );
                selectBuildButton.setAttribute("aria-label", "Select all visible nodes in build " + anchor.build);
            }
            root.dataset.srcxSelectionCount = String(count);
            selectionStatus.textContent = announcement || (count ? count + (count === 1 ? " node selected" :
                " nodes selected") + "; drag any selected node to move the group" :
                "No nodes selected; Shift-drag empty canvas or choose Box select");
        }

        function setBoxSelectMode(enabled) {
            if (enabled) clearSelection(false);
            state.boxSelectMode = Boolean(enabled);
            root.classList.toggle("is-box-select-mode", state.boxSelectMode);
            updateSelectionToolbar(state.boxSelectMode ?
                "Box select ready; drag across nodes on empty canvas" : "Box select canceled");
        }

        function wireLassoSelection() {
            svgElement.addEventListener("pointerdown", beginLassoSelection, true);
            window.addEventListener("pointermove", moveLassoSelection);
            window.addEventListener("pointerup", endLassoSelection);
            window.addEventListener("pointercancel", cancelLassoSelection);
        }

        function beginLassoSelection(event) {
            if (event.pointerType === "touch" || event.button !== 0) return;
            if (!state.boxSelectMode && !event.shiftKey) return;
            if (event.target.closest && event.target.closest(
                ".srcx-dashboard__architecture-svg-node, .srcx-dashboard__architecture-svg-edge",
            )) return;
            clearSelection(false);
            var point = graphPointFromPointer(event);
            lassoGesture = {
                pointerId: event.pointerId,
                start: point,
                current: point,
                additive: event.ctrlKey || event.metaKey,
                initialIds: new Set(state.selectedNodeIds),
                moved: false,
            };
            markUserNavigation();
            root.classList.add("is-box-selecting");
            if (svgElement.setPointerCapture) svgElement.setPointerCapture(event.pointerId);
            event.preventDefault();
            event.stopImmediatePropagation();
        }

        function moveLassoSelection(event) {
            if (!lassoGesture || event.pointerId !== lassoGesture.pointerId) return;
            lassoGesture.current = graphPointFromPointer(event);
            var box = selectionRectangle(lassoGesture.start, lassoGesture.current);
            if (!lassoGesture.moved && box.width < 4 && box.height < 4) return;
            lassoGesture.moved = true;
            lassoRectangle.style("display", null)
                .attr("x", box.x)
                .attr("y", box.y)
                .attr("width", box.width)
                .attr("height", box.height);
            var ids = lassoGesture.additive ? new Set(lassoGesture.initialIds) : new Set();
            nodes.forEach(function (node) {
                if (rectanglesOverlap(boxToBounds(box), nodeCircleObstacleBounds(node, 3))) ids.add(node.id);
            });
            setNodeSelection(ids);
            event.preventDefault();
        }

        function endLassoSelection(event) {
            if (!lassoGesture || event.pointerId !== lassoGesture.pointerId) return;
            var selectedCount = state.selectedNodeIds.size;
            var moved = lassoGesture.moved;
            finishLassoGesture(event.pointerId);
            if (state.boxSelectMode) setBoxSelectMode(false);
            if (moved) {
                suppressCanvasClickUntil = Date.now() + 300;
                updateSelectionToolbar("Box selected " + selectedCount +
                    (selectedCount === 1 ? " node" : " nodes"));
            }
            event.preventDefault();
        }

        function cancelLassoSelection(event) {
            if (!lassoGesture || (event && event.pointerId !== lassoGesture.pointerId)) return;
            setNodeSelection(lassoGesture.initialIds, "Box selection canceled");
            finishLassoGesture(lassoGesture.pointerId);
            setBoxSelectMode(false);
        }

        function finishLassoGesture(pointerId) {
            lassoGesture = null;
            root.classList.remove("is-box-selecting");
            if (lassoRectangle) lassoRectangle.style("display", "none");
            if (svgElement.hasPointerCapture && svgElement.hasPointerCapture(pointerId)) {
                svgElement.releasePointerCapture(pointerId);
            }
        }

        function graphPointFromPointer(event) {
            return d3.zoomTransform(svgElement).invert(d3.pointer(event, svgElement));
        }

        function selectionRectangle(start, end) {
            return {
                x: Math.min(start[0], end[0]),
                y: Math.min(start[1], end[1]),
                width: Math.abs(end[0] - start[0]),
                height: Math.abs(end[1] - start[1]),
            };
        }

        function boxToBounds(box) {
            return { minX: box.x, minY: box.y, maxX: box.x + box.width, maxY: box.y + box.height };
        }

        function wireControls() {
            controls.querySelectorAll("[data-srcx-graph-view]").forEach(function (button) {
                button.addEventListener("click", function () {
                    clearSelection(false);
                    detailReturnFocus = null;
                    state.view = button.dataset.srcxGraphView;
                    controls.querySelectorAll("[data-srcx-graph-view]").forEach(function (candidate) {
                        candidate.setAttribute("aria-pressed", String(candidate === button));
                    });
                    renderRelationshipKindFilter();
                    draw();
                });
            });
            search.addEventListener("input", function () {
                state.query = search.value;
                renderRelationshipKindFilter();
                draw();
            });
            searchRecoveryAction.addEventListener("click", function () {
                if (searchRecoveryAction.dataset.srcxSearchRecoveryAction === "show-scope") {
                    state.selectedBuild = null;
                    state.selectedProject = null;
                    state.selectedSourceSet = null;
                    state.pathSelectionMode = "all";
                    state.selectedPathKeys = new Set();
                    state.pathDirectoryParts = [];
                    state.pathPage = 0;
                    state.pathChipPage = 0;
                    state.pathRovingEntryId = null;
                    state.selectedRelationshipKind = "all";
                    renderNavigator();
                } else {
                    state.query = "";
                    search.value = "";
                }
                renderRelationshipKindFilter();
                draw();
                search.focus({ preventScroll: true });
            });
            controls.querySelectorAll("[data-srcx-graph-action]").forEach(function (button) {
                button.addEventListener("click", function () {
                    var action = button.dataset.srcxGraphAction;
                    if (action === "zoom-in") zoomBy(1.28);
                    if (action === "zoom-out") zoomBy(1 / 1.28);
                    if (action === "fit") fitGraph();
                    if (action === "reset") resetGraphLayout();
                });
            });
            fullscreenButton.addEventListener("click", toggleArchitectureFullscreen);
            fullscreenChromeToggle.addEventListener("click", toggleFullscreenChrome);
            document.addEventListener("fullscreenchange", handleFullscreenChange);
            document.addEventListener("webkitfullscreenchange", handleFullscreenChange);
            document.addEventListener("keydown", handleFullscreenEscape);
            detailClose.addEventListener("click", function () { clearSelection(true); });
            root.addEventListener("keydown", function (event) {
                if (event.key !== "Escape") return;
                if (lassoGesture) {
                    event.preventDefault();
                    cancelLassoSelection();
                    return;
                }
                if (state.boxSelectMode) {
                    event.preventDefault();
                    setBoxSelectMode(false);
                    return;
                }
                if (detail.hidden || architectureFullscreenActive()) return;
                event.preventDefault();
                clearSelection(true);
            });
        }

        function wireArchitectureEvidenceApi() {
            architectureEvidenceEventHandler = function (event) {
                openArchitectureEvidence(event.detail, event.target);
            };
            dashboard.addEventListener("srcx:open-architecture-evidence", architectureEvidenceEventHandler);
            dashboard.addEventListener("srcx:open-finding", architectureEvidenceEventHandler);
            root.srcxOpenArchitectureEvidence = openArchitectureEvidence;
        }

        function openArchitectureEvidence(request, origin) {
            if (!request || typeof request !== "object") return false;
            var requestedFinding = request.findingId ? findingById.get(request.findingId) : null;
            var fileNode = request.fileNodeId ? fileEntityById.get(request.fileNodeId) : null;
            var symbolNode = request.symbolNodeId ? symbolEntityById.get(request.symbolNodeId) : null;
            var requestedCycle = request.cycleId ? availableObservedCycles.find(function (cycle) {
                return cycle.id === request.cycleId;
            }) : null;
            var analysisCycleId = typeof request.analysisCycleId === "string" ? request.analysisCycleId :
                requestedFinding && requestedFinding.analysisCycleId;
            var requestedAnalysisCycle = analysisCycleId ? analysisCycleById.get(analysisCycleId) : null;
            if (!fileNode && requestedFinding) {
                fileNode = availableFileNodes.find(function (node) {
                    var isAnalysisMember = !requestedAnalysisCycle ||
                        requestedAnalysisCycle.memberFileIds.includes(node.id);
                    return Array.isArray(node.findingIds) && node.findingIds.includes(requestedFinding.id) &&
                        isAnalysisMember;
                });
            }
            if (!fileNode && requestedFinding) {
                fileNode = firstFileNodeByIds(requestedFinding.componentFileIds);
            }
            if (!fileNode && requestedAnalysisCycle) {
                fileNode = firstFileNodeByIds(requestedAnalysisCycle.memberFileIds);
            }
            if (!fileNode && request.edgeId) {
                var requestedEdge = fileEdgeById.get(request.edgeId);
                if (requestedEdge) fileNode = fileEntityById.get(requestedEdge.source);
            }
            if (!fileNode && requestedCycle) {
                var requestedRoute = request.routeId ? requestedCycle.routes.find(function (route) {
                    return route.id === request.routeId;
                }) : null;
                var routeNodeId = requestedRoute && requestedRoute.nodeIds[0];
                fileNode = fileEntityById.get(routeNodeId || requestedCycle.memberIds[0]);
            }
            if (!fileNode && requestedFinding) {
                symbolNode = firstSymbolNodeByIds(requestedFinding.componentSymbolIds);
            }
            if (!fileNode && !symbolNode && requestedAnalysisCycle) {
                symbolNode = firstSymbolNodeByIds(requestedAnalysisCycle.memberSymbolIds);
            }
            var selectedEntity = symbolNode || fileNode;
            var evidenceScope = selectedEntity || requestedAnalysisCycle || requestedFinding;
            if (!evidenceScope) {
                status.textContent = "Finding evidence has no linked file, declaration, or analyzer cycle in the " +
                    "typed Atlas catalog.";
                return false;
            }
            var requestedView = request.view;
            if (!requestedView) {
                requestedView = request.cycleId || requestedAnalysisCycle ? "cycles" :
                    request.findingId ? "problems" : symbolNode ? "symbols" : "files";
            }
            if (!["files", "symbols", "problems", "cycles"].includes(requestedView)) return false;
            if (requestedView === "symbols" && selectedEntity === fileNode) requestedView = "files";
            state.externalEvidenceRequest = {
                findingId: typeof request.findingId === "string" ? request.findingId : null,
                cycleId: typeof request.cycleId === "string" ? request.cycleId : null,
                analysisCycleId: requestedAnalysisCycle ? requestedAnalysisCycle.id : null,
                analysisParticipantIndex: requestedAnalysisParticipantIndex(
                    requestedAnalysisCycle,
                    selectedEntity,
                ),
                routeId: typeof request.routeId === "string" ? request.routeId : null,
                edgeId: typeof request.edgeId === "string" ? request.edgeId : null,
            };
            state.view = requestedView;
            state.query = "";
            search.value = "";
            state.selectedBuild = evidenceScope.build;
            state.selectedProject = evidenceScope.project;
            var cycleEvidence = Boolean(requestedCycle || requestedAnalysisCycle);
            state.selectedSourceSet = selectedEntity && !cycleEvidence ? selectedEntity.sourceSet : null;
            selectExternalEvidencePath(cycleEvidence ? null : selectedEntity);
            state.selectedRelationshipKind = "all";
            controls.querySelectorAll("[data-srcx-graph-view]").forEach(function (button) {
                button.setAttribute("aria-pressed", String(button.dataset.srcxGraphView === requestedView));
            });
            renderNavigator();
            renderRelationshipKindFilter();
            draw();
            if (!selectedEntity) {
                renderUnprojectedAnalysisEvidence(requestedFinding, requestedAnalysisCycle, origin);
                state.externalEvidenceRequest = null;
                return true;
            }
            var selectedNode = nodes.find(function (node) { return node.id === selectedEntity.id; });
            if (!selectedNode) {
                state.externalEvidenceRequest = null;
                return false;
            }
            selectNode(selectedNode, origin);
            state.externalEvidenceRequest = null;
            return true;
        }

        function renderUnprojectedAnalysisEvidence(finding, cycle, origin) {
            prepareDetailFocus(origin);
            openDetail(
                cycle ? "Analyzer-inferred component cycle" : "Analyzer finding",
                finding ? finding.message : cycle.id,
            );
            if (finding) {
                detailFields.appendChild(factList([
                    ["Severity", finding.severity],
                    ["Scope", finding.build + " / " + projectDisplayName(finding.project, finding.build)],
                    ["Finding", finding.message],
                    ["Suggestion", finding.suggestion],
                    ["Source availability", "No linked participant is present in the typed Atlas catalog"],
                ]));
            }
            if (cycle) {
                appendAnalysisCycleSection(detailFields, { id: null, entityType: "file" }, [cycle]);
            } else if (finding && finding.componentIds.length) {
                appendSection(detailFields, "Analyzer component IDs", finding.componentIds);
            }
            status.textContent = "Opened analyzer evidence; no linked catalog participant can be drawn.";
            focusOpenedDetail();
        }

        function firstFileNodeByIds(ids) {
            return (ids || []).map(function (id) { return fileEntityById.get(id); }).find(Boolean) || null;
        }

        function firstSymbolNodeByIds(ids) {
            return (ids || []).map(function (id) { return symbolEntityById.get(id); }).find(Boolean) || null;
        }

        function requestedAnalysisParticipantIndex(cycle, entity) {
            if (!cycle || !entity) return null;
            var index = cycle.route.slice(0, -1).findIndex(function (component) {
                return component.fileId === entity.id || component.symbolId === entity.id;
            });
            return index >= 0 ? index : null;
        }

        function preferredSourceWidth() {
            var canvas = document.createElement("canvas");
            var context = typeof canvas.getContext === "function" ? canvas.getContext("2d") : null;
            var detailStyle = window.getComputedStyle ? window.getComputedStyle(detail) : null;
            var monoFamily = detailStyle && detailStyle.getPropertyValue("--srcx-font-mono").trim();
            if (context && detailStyle) context.font = "10px " + (monoFamily || detailStyle.fontFamily);
            var characterWidth = context ? context.measureText("0").width : 6.2;
            return Math.ceil(characterWidth * 140 + 96);
        }

        function detailWidthBounds() {
            var availableWidth = viewport.clientWidth || root.clientWidth || window.innerWidth || 1;
            var maximum = Math.max(1, Math.floor(availableWidth * 0.5));
            var practicalMinimum = Math.max(280, Math.min(480, Math.floor(availableWidth * 0.3)));
            return {
                min: Math.min(maximum, practicalMinimum),
                max: maximum,
                availableWidth: availableWidth,
            };
        }

        function clampDetailWidth(value, bounds) {
            bounds = bounds || detailWidthBounds();
            var requested = Number.isFinite(value) ? value : preferredSourceWidth();
            return Math.max(bounds.min, Math.min(bounds.max, Math.round(requested)));
        }

        function applyDetailWidth(value, shouldPersist) {
            var bounds = detailWidthBounds();
            var nextWidth = clampDetailWidth(value, bounds);
            state.detailWidth = nextWidth;
            root.style.setProperty("--srcx-detail-width", nextWidth + "px");
            var percent = Math.round(nextWidth / bounds.availableWidth * 100);
            detailResize.setAttribute("aria-valuemin", String(Math.round(bounds.min / bounds.availableWidth * 100)));
            detailResize.setAttribute("aria-valuemax", "50");
            detailResize.setAttribute("aria-valuenow", String(percent));
            detailResize.setAttribute("aria-valuetext", nextWidth + " pixels, " + percent +
                " percent of map width");
            if (shouldPersist) persistDetailWidth();
            return nextWidth;
        }

        function persistDetailWidth() {
            if (!Number.isFinite(state.detailWidth)) return;
            try {
                window.localStorage.setItem(detailWidthStorageKey, String(state.detailWidth));
            } catch (ignored) {
                // A file report or privacy mode may disallow storage; the in-memory width still works.
            }
        }

        function restoreDetailWidth() {
            var storedWidth = null;
            try {
                var stored = window.localStorage.getItem(detailWidthStorageKey);
                if (stored !== null && stored.trim() !== "") storedWidth = Number(stored);
            } catch (ignored) {
                storedWidth = null;
            }
            applyDetailWidth(Number.isFinite(storedWidth) ? storedWidth : preferredSourceWidth(), false);
        }

        function reclampDetailWidth() {
            return applyDetailWidth(state.detailWidth, false);
        }

        function wireDetailResize() {
            detailResize.addEventListener("pointerdown", handleDetailResizePointerDown);
            window.addEventListener("pointermove", handleDetailResizePointerMove);
            window.addEventListener("pointerup", handleDetailResizePointerEnd);
            window.addEventListener("pointercancel", handleDetailResizePointerEnd);
            detailResize.addEventListener("keydown", handleDetailResizeKeydown);
            detailResize.addEventListener("dblclick", resetDetailWidth);
        }

        function handleDetailResizePointerDown(event) {
            if (event.button !== 0) return;
            detailResizePointerId = event.pointerId;
            detailResizeStartX = event.clientX;
            detailResizeStartWidth = state.detailWidth || detail.getBoundingClientRect().width;
            root.classList.add("is-resizing-detail");
            if (typeof detailResize.setPointerCapture === "function") {
                detailResize.setPointerCapture(event.pointerId);
            }
            event.preventDefault();
        }

        function handleDetailResizePointerMove(event) {
            if (detailResizePointerId === null || event.pointerId !== detailResizePointerId) return;
            applyDetailWidth(detailResizeStartWidth + detailResizeStartX - event.clientX, false);
            event.preventDefault();
        }

        function handleDetailResizePointerEnd(event) {
            if (detailResizePointerId === null || event.pointerId !== detailResizePointerId) return;
            if (typeof detailResize.releasePointerCapture === "function" &&
                detailResize.hasPointerCapture && detailResize.hasPointerCapture(event.pointerId)) {
                detailResize.releasePointerCapture(event.pointerId);
            }
            detailResizePointerId = null;
            root.classList.remove("is-resizing-detail");
            persistDetailWidth();
        }

        function handleDetailResizeKeydown(event) {
            var bounds = detailWidthBounds();
            var step = event.shiftKey ? 80 : 24;
            var nextWidth = state.detailWidth;
            if (event.key === "ArrowLeft") nextWidth += step;
            else if (event.key === "ArrowRight") nextWidth -= step;
            else if (event.key === "Home") nextWidth = bounds.min;
            else if (event.key === "End") nextWidth = bounds.max;
            else return;
            event.preventDefault();
            applyDetailWidth(nextWidth, true);
        }

        function resetDetailWidth() {
            applyDetailWidth(preferredSourceWidth(), true);
        }

        function toggleArchitectureFullscreen() {
            if (architectureFullscreenActive()) exitArchitectureFullscreen();
            else enterArchitectureFullscreen();
        }

        function toggleFullscreenChrome() {
            var collapsed = root.dataset.srcxFullscreenChrome !== "collapsed";
            setFullscreenChromeCollapsed(collapsed);
        }

        function setFullscreenChromeCollapsed(collapsed) {
            svg.interrupt();
            var preservedTransform = d3.zoomTransform(svgElement);
            markUserNavigation();
            if (fullscreenFitTimer !== null) {
                window.clearTimeout(fullscreenFitTimer);
                fullscreenFitTimer = null;
            }
            var active = architectureFullscreenActive();
            var nextCollapsed = active && collapsed;
            root.dataset.srcxFullscreenChrome = nextCollapsed ? "collapsed" : "expanded";
            fullscreenChromeToggle.hidden = !active;
            fullscreenChromeToggle.textContent = nextCollapsed ? "Show map controls" : "Hide map controls";
            fullscreenChromeToggle.setAttribute("aria-expanded", String(!nextCollapsed));
            fullscreenChromeToggle.setAttribute(
                "aria-label",
                nextCollapsed ? "Show map controls and filters" : "Hide map controls and filters",
            );
            chromeDisclosureResizePending = true;
            requestResize();
            scheduleChromeDisclosureResizeSettlement();
            window.requestAnimationFrame(function () {
                if (destroyed) return;
                svg.call(zoom.transform, preservedTransform);
            });
        }

        function scheduleChromeDisclosureResizeSettlement() {
            chromeDisclosureResizeRevision += 1;
            var revision = chromeDisclosureResizeRevision;
            if (chromeDisclosureResizeStableFrame !== null) {
                window.cancelAnimationFrame(chromeDisclosureResizeStableFrame);
            }
            chromeDisclosureResizeStableFrame = window.requestAnimationFrame(function () {
                chromeDisclosureResizeStableFrame = window.requestAnimationFrame(function () {
                    if (revision !== chromeDisclosureResizeRevision) return;
                    chromeDisclosureResizeStableFrame = null;
                    chromeDisclosureResizePending = false;
                });
            });
        }

        function enterArchitectureFullscreen() {
            captureFullscreenContext();
            var requestFullscreen = root.requestFullscreen || root.webkitRequestFullscreen;
            if (!requestFullscreen) {
                activateFallbackFullscreen();
                return;
            }
            var request;
            try {
                request = requestFullscreen.call(root);
            } catch (ignored) {
                activateFallbackFullscreen();
                return;
            }
            if (!request || typeof request.then !== "function") {
                window.setTimeout(function () {
                    if (destroyed) return;
                    if (nativeArchitectureFullscreen()) synchronizeFullscreenState();
                    else activateFallbackFullscreen();
                }, 0);
                return;
            }
            request.then(function () {
                if (!destroyed) synchronizeFullscreenState();
            }).catch(function () {
                if (!destroyed && !nativeArchitectureFullscreen()) activateFallbackFullscreen();
            });
        }

        function exitArchitectureFullscreen() {
            if (fallbackFullscreen) {
                fallbackFullscreen = false;
                root.classList.remove("is-fullscreen-fallback");
                synchronizeFullscreenState();
                return;
            }
            var exitFullscreen = document.exitFullscreen || document.webkitExitFullscreen;
            if (nativeArchitectureFullscreen() && exitFullscreen) {
                var exitResult = exitFullscreen.call(document);
                if (exitResult && typeof exitResult.catch === "function") {
                    exitResult.catch(synchronizeFullscreenState);
                }
                return;
            }
            synchronizeFullscreenState();
        }

        function activateFallbackFullscreen() {
            captureFullscreenContext();
            fallbackFullscreen = true;
            root.classList.add("is-fullscreen-fallback");
            synchronizeFullscreenState();
        }

        function handleFullscreenChange() {
            if (nativeArchitectureFullscreen()) fallbackFullscreen = false;
            if (!fallbackFullscreen) root.classList.remove("is-fullscreen-fallback");
            synchronizeFullscreenState();
        }

        function handleFullscreenEscape(event) {
            if (event.key !== "Escape" || !architectureFullscreenActive()) return;
            event.preventDefault();
            exitArchitectureFullscreen();
        }

        function nativeArchitectureFullscreen() {
            return document.fullscreenElement === root || document.webkitFullscreenElement === root;
        }

        function architectureFullscreenActive() {
            return fallbackFullscreen || nativeArchitectureFullscreen();
        }

        function synchronizeFullscreenState() {
            var active = architectureFullscreenActive();
            fullscreenButton.textContent = active ? "Exit full screen" : "Full screen";
            fullscreenButton.setAttribute("aria-pressed", String(active));
            root.dataset.srcxFullscreen = String(active);
            setFullscreenChromeCollapsed(active && root.dataset.srcxFullscreenChrome === "collapsed");
            if (active) lockDocumentScroll();
            else {
                restoreDocumentScroll();
                restoreFullscreenContext();
            }
            if (active !== fullscreenUiActive) {
                fullscreenUiActive = active;
                scheduleFullscreenFit();
            }
        }

        function lockDocumentScroll() {
            if (savedDocumentOverflow) return;
            savedDocumentOverflow = {
                body: document.body.style.overflow,
                documentElement: document.documentElement.style.overflow,
            };
            document.body.classList.add("srcx-dashboard--architecture-fullscreen");
            document.body.style.overflow = "hidden";
            document.documentElement.style.overflow = "hidden";
        }

        function captureFullscreenContext() {
            if (!savedViewportPosition) {
                savedViewportPosition = { x: window.scrollX || 0, y: window.scrollY || 0 };
            }
            if (!fullscreenReturnFocus) fullscreenReturnFocus = document.activeElement;
        }

        function restoreDocumentScroll() {
            if (!savedDocumentOverflow) return;
            document.body.style.overflow = savedDocumentOverflow.body;
            document.documentElement.style.overflow = savedDocumentOverflow.documentElement;
            document.body.classList.remove("srcx-dashboard--architecture-fullscreen");
            savedDocumentOverflow = null;
        }

        function restoreFullscreenContext() {
            if (!savedViewportPosition && !fullscreenReturnFocus) return;
            var position = savedViewportPosition;
            var focusTarget = fullscreenReturnFocus;
            savedViewportPosition = null;
            fullscreenReturnFocus = null;
            window.requestAnimationFrame(function () {
                if (position) window.scrollTo(position.x, position.y);
                if (focusTarget && focusTarget.isConnected && typeof focusTarget.focus === "function") {
                    focusTarget.focus({ preventScroll: true });
                } else if (fullscreenButton.isConnected) {
                    fullscreenButton.focus({ preventScroll: true });
                }
            });
        }

        function scheduleFullscreenFit() {
            if (fullscreenFitTimer !== null) window.clearTimeout(fullscreenFitTimer);
            requestResize();
            fullscreenFitTimer = window.setTimeout(function () {
                fullscreenFitTimer = null;
                reclampDetailWidth();
                resize();
                fitGraph();
            }, reducedMotion() ? 0 : 180);
        }

        function updateStatus(projection) {
            var label = state.view.charAt(0).toUpperCase() + state.view.slice(1);
            var category = state.selectedRelationshipKind === "all" ?
                { label: "All relationship kinds" } : relationshipCategoryDefinition(state.selectedRelationshipKind);
            var relationshipLinks = projection.links.filter(function (edge) { return !isAnalysisCycleEdge(edge); });
            var analysisLinks = projection.links.filter(isAnalysisCycleEdge);
            var linkedRecords = relationshipLinks.reduce(function (sum, edge) {
                return sum + edgeRecordCount(edge);
            }, 0);
            var internalRecords = projection.nodes.reduce(function (sum, node) {
                return sum + scopedInternalRecordCount(node, state.selectedRelationshipKind);
            }, 0);
            var records = linkedRecords + internalRecords;
            var boundedLensRecords = projection.availableRelationshipRecordCount +
                (projection.availableInternalRecordCount || 0);
            var lensExplanation = state.view === "files" ?
                "Files show the complete selected build or project scope in one frame and exclude imports" :
                state.view === "symbols" ?
                    "Symbols show every declaration in the selected build or project scope in one frame; " +
                        "the unrestricted All view remains bounded and imports are excluded" :
                    "Imports are excluded from Atlas relationship edges and counts";
            status.textContent = label + " / " + graphContextLabel() + " / " + category.label + " / " +
                projection.nodes.length + " of " +
                projection.candidateNodeCount + " available nodes shown / " + relationshipLinks.length +
                " resolved links / " + internalRecords + " same-file records without arrows / " +
                analysisLinks.length + " analyzer-inferred route hops / " + records + " available in this frame / " +
                boundedLensRecords + " of " + data.totalRelationshipRecordCount +
                " workspace relationship records available to this scope / " + lensExplanation;
        }

        function renderSearchRecovery(projection) {
            var query = state.query.trim();
            var hasVisibleMatch = projection.nodes.length > 0;
            if (!query || hasVisibleMatch) {
                searchRecovery.hidden = true;
                searchRecoveryStatus.textContent = "";
                searchRecoveryAction.textContent = "";
                delete searchRecoveryAction.dataset.srcxSearchRecoveryAction;
                return;
            }
            searchRecovery.hidden = false;
            if (projection.boundedQueryMatchCount === 0) {
                searchRecoveryStatus.textContent = "No bounded match for “" + query + "” in this map lens.";
                searchRecoveryAction.textContent = "Clear search";
                searchRecoveryAction.dataset.srcxSearchRecoveryAction = "clear-search";
                return;
            }
            searchRecoveryStatus.textContent = projection.hiddenQueryMatchCount + " bounded " +
                (projection.hiddenQueryMatchCount === 1 ? "match is" : "matches are") +
                " hidden by the current build, project, source-set, or relationship-kind filters.";
            searchRecoveryAction.textContent = "Show all bounded matches";
            searchRecoveryAction.dataset.srcxSearchRecoveryAction = "show-scope";
        }

        function graphContextLabel() {
            var ownership;
            if (!state.selectedBuild) ownership = "all builds / all projects";
            else if (state.selectedProject) {
                ownership = projectDisplayName(state.selectedProject, state.selectedBuild);
            } else ownership = state.selectedBuild + " / all projects";
            return ownership + " / " + (state.selectedSourceSet || "all source sets");
        }

        function emptyViewMessage(projection) {
            if (state.query.trim() && projection.boundedQueryMatchCount === 0) return "No bounded search match";
            if (state.query.trim() && projection.hiddenQueryMatchCount > 0) {
                return "Search matches are hidden by current scope or relationship-kind filters";
            }
            if (state.selectedRelationshipKind !== "all" &&
                (state.view === "files" || state.view === "symbols")) {
                return "No " + relationshipCategoryDefinition(state.selectedRelationshipKind).label.toLowerCase() +
                    " relationship records in " + graphContextLabel();
            }
            if (state.view === "cycles") {
                return "No observed file cycles or analyzer-inferred component cycles in " + graphContextLabel();
            }
            if (state.view === "problems") {
                return "No file findings, observed file cycles, or analyzer-inferred component cycles in " +
                    graphContextLabel();
            }
            return "No nodes match this view in " + graphContextLabel();
        }

        function fitGraph(animate) {
            if (!zoomLayer || !zoomLayer.node()) return;
            cancelScheduledFit();
            var renderedBounds = renderedGraphBounds(zoomLayer.node());
            var measuredBounds = graphBounds(nodes, data.builds, links);
            var bounds = unionGraphBounds(renderedBounds, measuredBounds);
            if (!bounds.width || !bounds.height) return;
            var measuredBoundaryPadding = 48;
            var fitRect = graphFitRect(measuredBoundaryPadding);
            var fittedScale = Math.min(fitRect.width / bounds.width, fitRect.height / bounds.height);
            if (!Number.isFinite(fittedScale) || fittedScale <= 0) return;
            var scale = Math.max(minimumZoomScale, Math.min(maximumZoomScale, fittedScale));
            var currentScaleExtent = zoom.scaleExtent();
            zoom.scaleExtent([
                Math.min(minimumZoomScale, scale / 2),
                Math.max(currentScaleExtent[1], maximumZoomScale, scale * 2),
            ]);
            var x = fitRect.x + fitRect.width / 2 - scale * (bounds.x + bounds.width / 2);
            var y = fitRect.y + fitRect.height / 2 - scale * (bounds.y + bounds.height / 2);
            var transform = d3.zoomIdentity.translate(x, y).scale(scale);
            if (animate === false || reducedMotion()) svg.call(zoom.transform, transform);
            else svg.transition().duration(180).call(zoom.transform, transform);
            if (fittedScale < minReadableScale) {
                status.textContent = "Fit shows the complete frame; zoom or hover to read labels on small nodes.";
            }
        }

        function frameSelection() {
            frameNodes(selectedVisibleNodes(), "selection");
        }

        function frameScope(scope) {
            var anchor = selectionAnchorNode();
            if (!anchor) return;
            var framedNodes = graphLayoutNodes().filter(function (node) {
                return node.build === anchor.build && (scope === "build" || node.project === anchor.project);
            });
            frameNodes(framedNodes, scope);
        }

        function frameNodes(framedNodes, label, framedLinks) {
            if (!framedNodes.length) return;
            var scopedLinks = framedLinks || links.filter(function (edge) {
                return framedNodes.includes(edge.source) && framedNodes.includes(edge.target);
            });
            var bounds = graphBounds(framedNodes, data.builds, scopedLinks);
            var fitRect = graphFitRect(48, label === "direct neighborhood");
            var fittedScale = Math.min(fitRect.width / bounds.width, fitRect.height / bounds.height);
            var scale = Math.max(minReadableScale, Math.min(maximumZoomScale, fittedScale));
            var x = fitRect.x + fitRect.width / 2 - scale * (bounds.x + bounds.width / 2);
            var y = fitRect.y + fitRect.height / 2 - scale * (bounds.y + bounds.height / 2);
            markUserNavigation();
            svg.transition().duration(reducedMotion() ? 0 : 180)
                .call(zoom.transform, d3.zoomIdentity.translate(x, y).scale(scale));
            updateSelectionToolbar("Framed " + framedNodes.length + " nodes in " + label);
        }

        function graphFitRect(padding, includeOpeningDetail) {
            var detailOcclusion = 0;
            if (!detail.hidden && (includeOpeningDetail || detail.classList.contains("is-open"))) {
                detailOcclusion = Math.min(width - 1, detail.getBoundingClientRect().width + 18);
            }
            return {
                x: padding,
                y: padding,
                width: Math.max(1, width - detailOcclusion - padding * 2),
                height: Math.max(1, height - padding * 2),
            };
        }

        function requestResize() {
            if (resizeFrame !== null || destroyed) return;
            resizeFrame = window.requestAnimationFrame(function () {
                resizeFrame = null;
                resize();
            });
        }

        function resize() {
            reclampDetailWidth();
            var size = viewportSize(viewport);
            var nextWidth = size.width;
            var nextHeight = size.height;
            var sizeChanged = nextWidth !== width || nextHeight !== height;
            if (chromeDisclosureResizePending) {
                scheduleChromeDisclosureResizeSettlement();
                if (!sizeChanged) return;
                width = nextWidth;
                height = nextHeight;
                svg.attr("viewBox", "0 0 " + width + " " + height);
                ticked();
                return;
            }
            if (!sizeChanged) return;
            width = nextWidth;
            height = nextHeight;
            svg.attr("viewBox", "0 0 " + width + " " + height);
            draw();
        }

        function dragStarted(event, node) {
            markUserNavigation();
            if (neighborhoodFocusContext && !neighborhoodFocusContext.nodeIds.has(node.id)) {
                exitNodeNeighborhoodFocus(true);
            }
            if (!state.selectedNodeIds.has(node.id)) setNodeSelection([node.id]);
            var draggedNodes = selectedVisibleNodes();
            nodeDragContext = {
                startX: event.x,
                startY: event.y,
                moved: false,
                positions: new Map(draggedNodes.map(function (selected) {
                    return [selected.id, captureNodeDragState(selected)];
                })),
            };
            if (!reducedMotion() && !event.active) simulation.alphaTarget(0.18).restart();
            draggedNodes.forEach(function (selected) {
                selected.isDirectlyDragged = true;
                selected.fx = selected.x;
                selected.fy = selected.y;
            });
        }

        function restorePinnedNodePositions(nodes) {
            nodes.forEach(function (node) {
                var position = manualNodePositions.get(node.id);
                if (!position) return;
                var offset = combinedScopeOffset(node.build, node.project);
                node.x = position.x + offset.x;
                node.y = position.y + offset.y;
                node.fx = node.x;
                node.fy = node.y;
                node.isManuallyPinned = true;
            });
        }

        function storeManualNodePosition(node) {
            var offset = combinedScopeOffset(node.build, node.project);
            manualNodePositions.set(node.id, { x: node.x - offset.x, y: node.y - offset.y });
        }

        function applyManualScopeOffsets(nodes, currentLayout) {
            currentLayout.cells.forEach(function (cell, build) {
                translateLayoutRectangle(cell, manualBuildOffsets.get(build));
            });
            currentLayout.centers.forEach(function (center, build) {
                translateLayoutPoint(center, manualBuildOffsets.get(build));
            });
            var scopes = new Map(nodes.map(function (node) {
                return [projectKey(node.build, node.project), { build: node.build, project: node.project }];
            }));
            currentLayout.projectCells.forEach(function (cell, key) {
                var scope = scopes.get(key);
                if (!scope) return;
                translateLayoutRectangle(cell, combinedScopeOffset(scope.build, scope.project));
            });
            currentLayout.subgroupCells.forEach(function (cell, key) {
                var subgroup = currentLayout.subgroupByKey.get(key);
                if (!subgroup) return;
                translateLayoutRectangle(cell, combinedScopeOffset(subgroup.build, subgroup.project));
            });
            nodes.forEach(function (node) {
                translateLayoutPoint(currentLayout.homes.get(node.id), combinedScopeOffset(node.build, node.project));
            });
        }

        function combinedScopeOffset(build, project) {
            var buildOffset = manualBuildOffsets.get(build) || { x: 0, y: 0 };
            var projectOffset = manualProjectOffsets.get(projectKey(build, project)) || { x: 0, y: 0 };
            return { x: buildOffset.x + projectOffset.x, y: buildOffset.y + projectOffset.y };
        }

        function translateLayoutPoint(point, offset) {
            if (!point || !offset) return;
            point.x += offset.x;
            point.y += offset.y;
        }

        function translateLayoutRectangle(rectangle, offset) {
            if (!rectangle || !offset) return;
            rectangle.minX += offset.x;
            rectangle.maxX += offset.x;
            rectangle.minY += offset.y;
            rectangle.maxY += offset.y;
        }

        function resetGraphLayout() {
            clearSelection(false);
            manualNodePositions.clear();
            manualBuildOffsets.clear();
            manualProjectOffsets.clear();
            state.selectedNodeIds = new Set();
            state.boxSelectMode = false;
            root.classList.remove("is-box-select-mode", "is-box-selecting");
            draw();
            updateSelectionToolbar("Layout reset; builds and projects were repacked and all node pins released");
        }

        function dragged(event, node) {
            if (!nodeDragContext) return;
            var delta = constrainedNodeDragDelta(
                nodeDragContext,
                event.x - nodeDragContext.startX,
                event.y - nodeDragContext.startY,
            );
            var dx = delta.x;
            var dy = delta.y;
            if (Math.sqrt(dx * dx + dy * dy) >= 4) nodeDragContext.moved = true;
            selectedVisibleNodes().forEach(function (selected) {
                var start = nodeDragContext.positions.get(selected.id);
                if (!start) return;
                selected.fx = start.x + dx;
                selected.fy = start.y + dy;
                selected.x = selected.fx;
                selected.y = selected.fy;
            });
            if (reducedMotion()) {
                ticked();
            }
        }

        function constrainedNodeDragDelta(context, dx, dy) {
            var minimumX = -Infinity;
            var maximumX = Infinity;
            var minimumY = -Infinity;
            var maximumY = Infinity;
            context.positions.forEach(function (start, id) {
                var selected = entityById(id);
                if (!selected || !start.bounds) return;
                var cell = layout.subgroupCells.get(selected.subgroupKey) ||
                    layout.projectCells.get(projectKey(selected.build, selected.project));
                if (!cell) return;
                minimumX = Math.max(minimumX, cell.minX + 12 - start.bounds.minX);
                maximumX = Math.min(maximumX, cell.maxX - 12 - start.bounds.maxX);
                minimumY = Math.max(minimumY, cell.minY + 28 - start.bounds.minY);
                maximumY = Math.min(maximumY, cell.maxY - 12 - start.bounds.maxY);
            });
            return {
                x: Math.max(minimumX, Math.min(maximumX, dx)),
                y: Math.max(minimumY, Math.min(maximumY, dy)),
            };
        }

        function dragEnded(event, node) {
            if (!reducedMotion() && !event.active) simulation.alphaTarget(0);
            var moved = nodeDragContext && nodeDragContext.moved;
            selectedVisibleNodes().forEach(function (selected) {
                var start = nodeDragContext && nodeDragContext.positions.get(selected.id);
                selected.isDirectlyDragged = false;
                if (!moved && start) restoreNodeDragState(selected, start);
                else if (moved) {
                    selected.isManuallyPinned = true;
                    selected.fx = selected.x;
                    selected.fy = selected.y;
                    storeManualNodePosition(selected);
                }
            });
            nodeDragContext = null;
            if (moved) {
                suppressNodeClickUntil = Date.now() + 300;
                suppressCanvasClickUntil = suppressNodeClickUntil;
                updateSelectionToolbar("Moved and pinned " + state.selectedNodeIds.size +
                    (state.selectedNodeIds.size === 1 ? " node" : " nodes") + "; Reset releases layout pins");
            }
        }

        function consumeSuppressedNodeClick(event) {
            if (Date.now() >= suppressNodeClickUntil) return false;
            event.preventDefault();
            event.stopPropagation();
            return true;
        }

        function selectRegionNodes(scope, region) {
            clearSelection(false);
            var selected = graphLayoutNodes().filter(function (node) {
                return node.build === region.build && (scope === "build" || node.project === region.project);
            }).map(function (node) { return node.id; });
            setNodeSelection(selected, "Selected " + selected.length + " nodes in " + scope + " region");
        }

        function scopeDragStarted(event, scope, region) {
            markUserNavigation();
            clearSelection(false);
            var draggedNodes = graphLayoutNodes().filter(function (node) {
                return node.build === region.build && (scope === "build" || node.project === region.project);
            });
            var scopeCell = scope === "build" ? layout.cells.get(region.build) :
                layout.projectCells.get(projectKey(region.build, region.project));
            setNodeSelection(draggedNodes.map(function (node) { return node.id; }));
            scopeDragContext = {
                scope: scope,
                region: region,
                startX: event.x,
                startY: event.y,
                moved: false,
                appliedX: 0,
                appliedY: 0,
                cell: copyRectangle(scopeCell),
                siblingCells: scopeSiblingCells(scope, region),
                projectKeys: new Set(draggedNodes.map(function (node) {
                    return projectKey(node.build, node.project);
                })),
                positions: new Map(draggedNodes.map(function (node) {
                    return [node.id, captureNodeDragState(node)];
                })),
            };
            draggedNodes.forEach(function (node) {
                node.isDirectlyDragged = true;
                node.fx = node.x;
                node.fy = node.y;
            });
            if (!reducedMotion() && !event.active) simulation.alphaTarget(0.12).restart();
        }

        function scopeDragged(event) {
            if (!scopeDragContext) return;
            var delta = constrainedScopeDragDelta(
                scopeDragContext,
                event.x - scopeDragContext.startX,
                event.y - scopeDragContext.startY,
            );
            var dx = delta.x;
            var dy = delta.y;
            if (Math.sqrt(dx * dx + dy * dy) >= 4) scopeDragContext.moved = true;
            translateDraggedScopeLayout(scopeDragContext, dx, dy);
            scopeDragContext.positions.forEach(function (start, id) {
                var node = entityById(id);
                if (!node) return;
                node.fx = start.x + dx;
                node.fy = start.y + dy;
                node.x = node.fx;
                node.y = node.fy;
            });
            ticked();
        }

        function constrainedScopeDragDelta(context, dx, dy) {
            if (!context.cell) return { x: dx, y: dy };
            if (context.scope === "project") {
                var buildCell = layout.cells.get(context.region.build);
                if (buildCell) {
                    dx = Math.max(buildCell.minX + 16 - context.cell.minX,
                        Math.min(buildCell.maxX - 16 - context.cell.maxX, dx));
                    dy = Math.max(buildCell.minY + 46 - context.cell.minY,
                        Math.min(buildCell.maxY - 16 - context.cell.maxY, dy));
                }
            }
            var candidate = translatedRectangle(context.cell, dx, dy);
            var gap = context.scope === "build" ? 24 : 14;
            var collides = context.siblingCells.some(function (sibling) {
                return rectanglesOverlap(expandRectangle(candidate, gap), sibling);
            });
            if (collides) return { x: context.appliedX, y: context.appliedY };
            return { x: dx, y: dy };
        }

        function scopeSiblingCells(scope, region) {
            if (scope === "build") {
                return Array.from(layout.cells.entries()).filter(function (entry) {
                    return entry[0] !== region.build;
                }).map(function (entry) { return copyRectangle(entry[1]); });
            }
            return Array.from(layout.projectCells.entries()).filter(function (entry) {
                if (entry[0] === projectKey(region.build, region.project)) return false;
                return nodes.some(function (node) {
                    return node.build === region.build && projectKey(node.build, node.project) === entry[0];
                });
            }).map(function (entry) { return copyRectangle(entry[1]); });
        }

        function translateDraggedScopeLayout(context, dx, dy) {
            var translateX = dx - context.appliedX;
            var translateY = dy - context.appliedY;
            if (!translateX && !translateY) return;
            var offset = { x: translateX, y: translateY };
            if (context.scope === "build") {
                translateLayoutRectangle(layout.cells.get(context.region.build), offset);
                translateLayoutPoint(layout.centers.get(context.region.build), offset);
                layout.projectCells.forEach(function (cell, key) {
                    if (context.projectKeys.has(key)) translateLayoutRectangle(cell, offset);
                });
                layout.subgroupCells.forEach(function (cell, key) {
                    var subgroup = layout.subgroupByKey.get(key);
                    if (subgroup && subgroup.build === context.region.build) {
                        translateLayoutRectangle(cell, offset);
                    }
                });
                nodes.filter(function (node) { return node.build === context.region.build; })
                    .forEach(function (node) { translateLayoutPoint(layout.homes.get(node.id), offset); });
            } else {
                var key = projectKey(context.region.build, context.region.project);
                translateLayoutRectangle(layout.projectCells.get(key), offset);
                layout.subgroupCells.forEach(function (cell, subgroupKey) {
                    var subgroup = layout.subgroupByKey.get(subgroupKey);
                    if (subgroup && subgroup.build === context.region.build &&
                        subgroup.project === context.region.project) translateLayoutRectangle(cell, offset);
                });
                nodes.filter(function (node) {
                    return node.build === context.region.build && node.project === context.region.project;
                }).forEach(function (node) { translateLayoutPoint(layout.homes.get(node.id), offset); });
            }
            context.appliedX = dx;
            context.appliedY = dy;
        }

        function scopeDragEnded(event) {
            if (!scopeDragContext) return;
            if (!reducedMotion() && !event.active) simulation.alphaTarget(0);
            var context = scopeDragContext;
            var dx = context.appliedX;
            var dy = context.appliedY;
            if (!context.moved) translateDraggedScopeLayout(context, 0, 0);
            context.positions.forEach(function (start, id) {
                var node = entityById(id);
                if (!node) return;
                node.isDirectlyDragged = false;
                if (!context.moved) restoreNodeDragState(node, start);
                else {
                    node.isManuallyPinned = true;
                    node.fx = node.x;
                    node.fy = node.y;
                }
            });
            if (context.moved && context.scope === "build") {
                accumulateScopeOffset(manualBuildOffsets, context.region.build, dx, dy);
            } else if (context.moved) {
                accumulateScopeOffset(
                    manualProjectOffsets,
                    projectKey(context.region.build, context.region.project),
                    dx,
                    dy,
                );
            }
            if (context.moved) {
                context.positions.forEach(function (start, id) {
                    var node = entityById(id);
                    if (node) storeManualNodePosition(node);
                });
            }
            scopeDragContext = null;
            if (context.moved) {
                suppressScopeClickUntil = Date.now() + 300;
                suppressCanvasClickUntil = suppressScopeClickUntil;
                updateSelectionToolbar("Moved and pinned the entire " + context.scope + "; Reset repacks it");
            }
        }

        function entityById(id) {
            return nodes.find(function (node) { return node.id === id; });
        }

        function accumulateScopeOffset(offsets, key, dx, dy) {
            var previous = offsets.get(key) || { x: 0, y: 0 };
            offsets.set(key, { x: previous.x + dx, y: previous.y + dy });
        }

        function captureNodeDragState(node) {
            var manualPosition = manualNodePositions.get(node.id);
            return {
                x: node.x,
                y: node.y,
                fx: node.fx,
                fy: node.fy,
                bounds: copyRectangle(nodeVisualBounds(node)),
                isManuallyPinned: Boolean(node.isManuallyPinned),
                manualPosition: manualPosition ? { x: manualPosition.x, y: manualPosition.y } : null,
            };
        }

        function copyRectangle(rectangle) {
            if (!rectangle) return null;
            return {
                minX: rectangle.minX,
                minY: rectangle.minY,
                maxX: rectangle.maxX,
                maxY: rectangle.maxY,
            };
        }

        function translatedRectangle(rectangle, dx, dy) {
            return {
                minX: rectangle.minX + dx,
                minY: rectangle.minY + dy,
                maxX: rectangle.maxX + dx,
                maxY: rectangle.maxY + dy,
            };
        }

        function restoreNodeDragState(node, stateBeforeDrag) {
            node.x = stateBeforeDrag.x;
            node.y = stateBeforeDrag.y;
            node.fx = stateBeforeDrag.fx;
            node.fy = stateBeforeDrag.fy;
            node.isManuallyPinned = stateBeforeDrag.isManuallyPinned;
            if (stateBeforeDrag.manualPosition) manualNodePositions.set(node.id, stateBeforeDrag.manualPosition);
            else manualNodePositions.delete(node.id);
        }

        function reconcileSelection() {
            if (!state.selectedId) return null;
            var collection = state.selectedType && state.selectedType.includes("edge") ? links : nodes;
            var selected = collection.find(function (item) { return item.id === state.selectedId; });
            if (!selected && state.selectedEdge && state.selectedEdge.constituentEdgeIds) {
                var visibleEdgeIds = new Set(links.map(function (edge) { return edge.id; }));
                var aggregateVisible = state.selectedEdge.constituentEdgeIds.some(function (id) {
                    return visibleEdgeIds.has(id);
                });
                if (aggregateVisible) selected = state.selectedEdge;
            }
            if (selected) return selected;
            state.selectedId = null;
            state.selectedType = null;
            state.selectedEdge = null;
            state.cycleStepEdgeId = null;
            state.selectedEvidence = null;
            activeEvidenceHost = null;
            activeEvidenceStatus = null;
            detail.classList.remove("is-open");
            detail.hidden = true;
            detailResize.hidden = true;
            return null;
        }

        function restoreSelection(selected) {
            var cycleStepEdge = state.cycleStepEdgeId && links.find(function (edge) {
                return edge.id === state.cycleStepEdgeId;
            });
            if (cycleStepEdge) highlightEdge(cycleStepEdge);
            else if (state.selectedType && state.selectedType.includes("edge")) highlightEdge(selected);
            else enterNodeNeighborhoodFocus(selected.id);
        }

        function ensureRovingNode() {
            if (nodes.some(function (node) { return node.id === state.rovingNodeId; })) return;
            state.rovingNodeId = nodes.slice().sort(function (left, right) {
                return left.id.localeCompare(right.id);
            })[0].id;
        }

        function ensureRovingEdge() {
            if (links.some(function (edge) { return edge.id === state.rovingEdgeId; })) return;
            state.rovingEdgeId = links.length ? links.slice().sort(function (left, right) {
                return left.id.localeCompare(right.id);
            })[0].id : null;
        }

        function setRovingNode(node, moveFocus) {
            state.rovingNodeId = node.id;
            if (!nodeGroups) return;
            nodeGroups.attr("tabindex", function (candidate) { return candidate.id === node.id ? 0 : -1; });
            if (!moveFocus) return;
            var element = graphNodeElement(node.id);
            if (element) element.focus();
        }

        function focusAdjacentNode(node, key) {
            var next = adjacentNode(node, key, graphLayoutNodes());
            if (next) setRovingNode(next, true);
        }

        function setRovingEdge(edge, moveFocus) {
            state.rovingEdgeId = edge.id;
            if (!edgeGroups) return;
            edgeGroups.attr("tabindex", function (candidate) { return candidate.id === edge.id ? 0 : -1; });
            if (!moveFocus) return;
            var element = graphEdgeElement(edge.id);
            if (element) element.focus();
        }

        function focusIncidentEdge(node) {
            var allowedIds = neighborhoodFocusContext && neighborhoodFocusContext.nodeIds;
            var incident = (incidentLinksByNode.get(node.id) || []).filter(function (edge) {
                return !allowedIds ||
                    allowedIds.has(endpointId(edge.source)) && allowedIds.has(endpointId(edge.target));
            }).sort(function (left, right) {
                return left.id.localeCompare(right.id);
            });
            if (!incident.length) return;
            edgeNavigationLinks = incident;
            state.rovingEdgeId = incident[0].id;
            renderEdgeSubset(incident);
            setRovingEdge(incident[0], true);
        }

        function focusAdjacentEdge(edge, direction) {
            var ordered = edgeNavigationLinks.slice().sort(function (left, right) {
                return left.id.localeCompare(right.id);
            });
            var index = ordered.findIndex(function (candidate) { return candidate.id === edge.id; });
            if (index < 0 || !ordered.length) return;
            var nextIndex = (index + direction + ordered.length) % ordered.length;
            setRovingEdge(ordered[nextIndex], true);
        }

        function focusEdgeEndpoint(edge, endpoint) {
            var nodeId = endpointId(edge[endpoint]);
            var node = nodes.find(function (candidate) { return candidate.id === nodeId; });
            if (node) setRovingNode(node, true);
        }

        function graphNodeElement(nodeId) {
            if (!nodeGroups) return null;
            return nodeGroups.filter(function (node) { return node.id === nodeId; }).node();
        }

        function graphEdgeElement(edgeId) {
            if (!edgeGroups) return null;
            return edgeGroups.filter(function (edge) { return edge.id === edgeId; }).node();
        }

        function prepareDetailFocus(origin) {
            var candidate = origin || document.activeElement;
            if (!candidate || (!root.contains(candidate) && !dashboard.contains(candidate))) return;
            if (detail.contains(candidate)) return;
            detailReturnFocus = candidate;
        }

        function focusOpenedDetail() {
            detailClose.focus({ preventScroll: true });
        }

        function restoreDetailFocus() {
            var target = detailReturnFocus;
            detailReturnFocus = null;
            if (target && target.isConnected && typeof target.focus === "function") {
                target.focus({ preventScroll: true });
                return;
            }
            var roving = graphNodeElement(state.rovingNodeId);
            if (roving) roving.focus({ preventScroll: true });
        }

        function scheduleFit(generation, delay) {
            cancelScheduledFit();
            fitTimer = window.setTimeout(function () {
                fitTimer = null;
                if (generation === drawGeneration && !userNavigated && !destroyed) fitGraph(false);
            }, delay);
        }

        function cancelScheduledFit() {
            if (fitTimer === null) return;
            window.clearTimeout(fitTimer);
            fitTimer = null;
        }

        function markUserNavigation() {
            userNavigated = true;
            cancelScheduledFit();
        }

        function zoomBy(factor) {
            markUserNavigation();
            svg.transition().duration(reducedMotion() ? 0 : 130).call(zoom.scaleBy, factor);
        }

        function cleanup() {
            destroyed = true;
            cancelScheduledFit();
            if (fullscreenFitTimer !== null) window.clearTimeout(fullscreenFitTimer);
            if (detailCloseTimer !== null) window.clearTimeout(detailCloseTimer);
            cancelHighlightRestore();
            if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame);
            if (chromeDisclosureResizeStableFrame !== null) {
                window.cancelAnimationFrame(chromeDisclosureResizeStableFrame);
            }
            if (resizeObserver) resizeObserver.disconnect();
            filterRailCleanups.splice(0).forEach(function (removeListeners) { removeListeners(); });
            window.removeEventListener("resize", requestResize);
            window.removeEventListener("pointermove", moveLassoSelection);
            window.removeEventListener("pointerup", endLassoSelection);
            window.removeEventListener("pointercancel", cancelLassoSelection);
            svgElement.removeEventListener("pointerdown", beginLassoSelection, true);
            window.removeEventListener("pointermove", handleDetailResizePointerMove);
            window.removeEventListener("pointerup", handleDetailResizePointerEnd);
            window.removeEventListener("pointercancel", handleDetailResizePointerEnd);
            detailResize.removeEventListener("pointerdown", handleDetailResizePointerDown);
            detailResize.removeEventListener("keydown", handleDetailResizeKeydown);
            detailResize.removeEventListener("dblclick", resetDetailWidth);
            if (architectureEvidenceEventHandler) {
                dashboard.removeEventListener("srcx:open-architecture-evidence", architectureEvidenceEventHandler);
                dashboard.removeEventListener("srcx:open-finding", architectureEvidenceEventHandler);
            }
            if (root.srcxOpenArchitectureEvidence === openArchitectureEvidence) {
                delete root.srcxOpenArchitectureEvidence;
            }
            document.removeEventListener("fullscreenchange", handleFullscreenChange);
            document.removeEventListener("webkitfullscreenchange", handleFullscreenChange);
            document.removeEventListener("keydown", handleFullscreenEscape);
            fallbackFullscreen = false;
            root.classList.remove("is-fullscreen-fallback");
            if (nativeArchitectureFullscreen()) {
                var exitFullscreen = document.exitFullscreen || document.webkitExitFullscreen;
                if (exitFullscreen) {
                    var exitResult = exitFullscreen.call(document);
                    if (exitResult && typeof exitResult.catch === "function") exitResult.catch(function () {});
                }
            }
            fullscreenButton.removeEventListener("click", toggleArchitectureFullscreen);
            fullscreenChromeToggle.removeEventListener("click", toggleFullscreenChrome);
            root.classList.remove("is-resizing-detail");
            fullscreenButton.textContent = "Full screen";
            fullscreenButton.setAttribute("aria-pressed", "false");
            fullscreenChromeToggle.hidden = true;
            delete root.dataset.srcxFullscreenChrome;
            root.dataset.srcxFullscreen = "false";
            restoreDocumentScroll();
            if (simulation) simulation.stop();
            svg.interrupt().on(".zoom", null);
        }
    }

    function buildCellLayout(nodes, width, height, buildByName) {
        var viewportAspect = Math.max(0.75, width / Math.max(1, height));
        var subgroupGap = 14;
        var projectGap = 24;
        var buildGap = 40;
        var projects = Array.from(d3.group(nodes, function (node) {
            return projectKey(node.build, node.project);
        }), function (entry) {
            var members = entry[1].slice().sort(function (left, right) { return left.id.localeCompare(right.id); });
            var nodeRectangles = members.map(function (node) {
                var bounds = labelBounds(node);
                var labelWidth = node.labelPinned ? bounds.width + 10 : 0;
                var labelHeight = node.labelPinned ? bounds.height : 0;
                return {
                    key: node.id,
                    width: outerNodeRadius(node) * 2 + labelWidth + 24,
                    height: Math.max(outerNodeRadius(node) * 2, labelHeight) + 24,
                    nodeRadius: outerNodeRadius(node),
                };
            }).sort(function (left, right) {
                return right.width * right.height - left.width * left.height || left.key.localeCompare(right.key);
            });
            var rectangleByNode = new Map(nodeRectangles.map(function (rectangle) {
                return [rectangle.key, rectangle];
            }));
            var subgroups = automaticNodeSubgroups(members).map(function (subgroup) {
                var subgroupRectangles = subgroup.members.map(function (node) {
                    return rectangleByNode.get(node.id);
                }).filter(Boolean);
                var nodePack = packVariableRectangles(subgroupRectangles, 1.35, 14);
                return Object.assign({}, subgroup, {
                    nodePack: nodePack,
                    width: Math.max(132, subgroupRegionLabel(subgroup).length * 5.2 + 28, nodePack.width + 28),
                    height: Math.max(86, nodePack.height + 46),
                });
            }).sort(function (left, right) { return left.key.localeCompare(right.key); });
            var subgroupPack = packVariableRectangles(subgroups, 1.35, subgroupGap);
            return {
                key: entry[0],
                build: members[0].build,
                project: members[0].project,
                members: members,
                nodeRectangles: rectangleByNode,
                subgroups: subgroups,
                subgroupPack: subgroupPack,
                width: Math.max(
                    190,
                    projectRegionSummaryWidth(members[0], members) + 28,
                    subgroupPack.width + 36,
                ),
                height: Math.max(130, subgroupPack.height + 60),
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var projectsByBuild = d3.group(projects, function (project) { return project.build; });
        var buildRects = Array.from(projectsByBuild, function (entry) {
            var buildName = entry[0];
            var packed = packVariableRectangles(entry[1], 1.35, projectGap);
            var build = buildByName.get(buildName);
            var headingWidth = build && build.labelWidth ? build.labelWidth : buildName.length * 6;
            return {
                key: buildName,
                build: buildName,
                projects: entry[1],
                projectPack: packed,
                width: Math.max(260, headingWidth + 54, packed.width + 48),
                height: Math.max(190, packed.height + 82),
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var buildPack = packVariableRectangles(buildRects, viewportAspect, buildGap);
        var originX = (width - buildPack.width) / 2;
        var originY = (height - buildPack.height) / 2;
        var cells = new Map();
        var centers = new Map();
        var projectCells = new Map();
        var subgroupCells = new Map();
        var subgroupByKey = new Map();
        var subgroups = [];
        var homes = new Map();
        buildRects.forEach(function (buildRect) {
            var buildPlacement = buildPack.placements.get(buildRect.key);
            var buildX = originX + buildPlacement.x;
            var buildY = originY + buildPlacement.y;
            var buildCell = {
                minX: buildX,
                minY: buildY,
                maxX: buildX + buildRect.width,
                maxY: buildY + buildRect.height,
            };
            cells.set(buildRect.build, buildCell);
            centers.set(buildRect.build, rectangleCenter(buildCell));
            buildRect.projects.forEach(function (project) {
                var projectPlacement = buildRect.projectPack.placements.get(project.key);
                var minX = buildX + 24 + projectPlacement.x;
                var minY = buildY + 52 + projectPlacement.y;
                var projectCell = {
                    minX: minX,
                    minY: minY,
                    maxX: minX + project.width,
                    maxY: minY + project.height,
                };
                projectCells.set(project.key, projectCell);
                project.subgroups.forEach(function (subgroup) {
                    var subgroupPlacement = project.subgroupPack.placements.get(subgroup.key);
                    var subgroupCell = {
                        minX: projectCell.minX + 18 + subgroupPlacement.x,
                        minY: projectCell.minY + 38 + subgroupPlacement.y,
                        maxX: projectCell.minX + 18 + subgroupPlacement.x + subgroup.width,
                        maxY: projectCell.minY + 38 + subgroupPlacement.y + subgroup.height,
                    };
                    subgroupCells.set(subgroup.key, subgroupCell);
                    subgroupByKey.set(subgroup.key, subgroup);
                    subgroups.push(subgroup);
                    subgroup.members.forEach(function (node) {
                        var placement = subgroup.nodePack.placements.get(node.id);
                        var rectangle = project.nodeRectangles.get(node.id);
                        node.subgroupKey = subgroup.key;
                        node.labelDirection = "right";
                        homes.set(node.id, {
                            x: subgroupCell.minX + 14 + placement.x + rectangle.nodeRadius + 10,
                            y: subgroupCell.minY + 34 + placement.y + rectangle.height / 2,
                        });
                    });
                });
            });
        });
        return {
            cells: cells,
            centers: centers,
            projectCells: projectCells,
            subgroupCells: subgroupCells,
            subgroupByKey: subgroupByKey,
            subgroups: subgroups,
            homes: homes,
        };
    }

    function initializeNodes(nodes, layout) {
        nodes.forEach(function (node) {
            var home = layout.homes.get(node.id) || layout.centers.get(node.build);
            node.x = home.x;
            node.y = home.y;
        });
    }

    function packVariableRectangles(rectangles, targetAspect, gap) {
        if (!rectangles.length) return { width: 0, height: 0, placements: new Map() };
        var best = null;
        var idealColumns = Math.max(1, Math.round(Math.sqrt(rectangles.length * targetAspect)));
        var columnCandidates = rectangles.length <= 64 ?
            Array.from({ length: rectangles.length }, function (_, index) { return index + 1; }) :
            Array.from(new Set([-3, -2, -1, 0, 1, 2, 3].map(function (offset) {
                return Math.max(1, Math.min(rectangles.length, idealColumns + offset));
            }).concat([1, rectangles.length])));
        columnCandidates.forEach(function (columns) {
            var placements = new Map();
            var y = 0;
            var totalWidth = 0;
            for (var start = 0; start < rectangles.length; start += columns) {
                var row = rectangles.slice(start, start + columns);
                var x = 0;
                var rowHeight = Math.max.apply(null, row.map(function (rectangle) { return rectangle.height; }));
                row.forEach(function (rectangle) {
                    placements.set(rectangle.key, { x: x, y: y });
                    x += rectangle.width + gap;
                });
                totalWidth = Math.max(totalWidth, x - gap);
                y += rowHeight + gap;
            }
            var totalHeight = y - gap;
            var aspectPenalty = Math.abs(Math.log(Math.max(0.01, totalWidth / totalHeight) / targetAspect));
            var score = aspectPenalty * totalWidth * totalHeight + totalWidth + totalHeight;
            if (!best || score < best.score) {
                best = { width: totalWidth, height: totalHeight, placements: placements, score: score };
            }
        });
        return best;
    }

    function automaticNodeSubgroups(members) {
        var project = members[0];
        var result = [];
        d3.group(members, function (node) { return node.sourceSet; }).forEach(function (sourceMembers, sourceSet) {
            var directories = sourceMembers.map(nodeDirectorySegments);
            var commonPrefix = commonDirectoryPrefix(directories);
            d3.group(sourceMembers, function (node) {
                return nodeDirectorySegments(node)[commonPrefix.length] || "root";
            }).forEach(function (areaMembers, area) {
                if (areaMembers[0].entityType === "symbol" && areaMembers.length > 90) {
                    adaptiveSymbolSubgroups(project, sourceSet, area, areaMembers).forEach(function (subgroup) {
                        result.push(subgroup);
                    });
                } else {
                    result.push(nodeSubgroup(project, sourceSet, area, areaMembers, area));
                }
            });
        });
        return result.sort(function (left, right) { return left.key.localeCompare(right.key); });
    }

    function adaptiveSymbolSubgroups(project, sourceSet, area, members) {
        var files = Array.from(d3.group(members, function (node) { return normalizeBoundedPath(node.file); }),
            function (entry) { return { path: entry[0], members: entry[1] }; })
            .sort(function (left, right) {
                return right.members.length - left.members.length || left.path.localeCompare(right.path);
            });
        var dedicated = files.filter(function (file) { return file.members.length >= 12; }).slice(0, 10);
        var dedicatedPaths = new Set(dedicated.map(function (file) { return file.path; }));
        var remaining = files.filter(function (file) { return !dedicatedPaths.has(file.path); });
        var buckets = [];
        remaining.forEach(function (file) {
            var bucket = buckets[buckets.length - 1];
            if (!bucket || bucket.members.length + file.members.length > 64 || bucket.fileCount >= 12) {
                bucket = { members: [], fileCount: 0 };
                buckets.push(bucket);
            }
            bucket.members = bucket.members.concat(file.members);
            bucket.fileCount += 1;
        });
        var subgroups = dedicated.map(function (file) {
            return nodeSubgroup(
                project,
                sourceSet,
                area,
                file.members,
                compactFileStem(file.path),
                file.path,
            );
        });
        buckets.forEach(function (bucket, index) {
            var suffix = bucket.fileCount + " smaller " + (bucket.fileCount === 1 ? "file" : "files");
            if (buckets.length > 1) suffix += " " + (index + 1) + "/" + buckets.length;
            subgroups.push(nodeSubgroup(project, sourceSet, area, bucket.members, suffix));
        });
        return subgroups;
    }

    function nodeSubgroup(project, sourceSet, area, members, suffix, keySuffix) {
        var subgroupMembers = members.slice().sort(comparePersistentLabelPriority);
        var entityType = subgroupMembers.every(function (node) { return node.entityType === "file"; }) ? "file" :
            subgroupMembers.every(function (node) { return node.entityType === "symbol"; }) ? "symbol" : "node";
        var label = sourceSet + " / " + area + (suffix === area ? "" : " / " + suffix);
        return {
            key: projectKey(project.build, project.project) + "\u0000" + sourceSet + "\u0000" + area +
                "\u0000" + (keySuffix || suffix),
            build: project.build,
            project: project.project,
            sourceSet: sourceSet,
            area: area,
            label: label,
            members: subgroupMembers,
            entityType: entityType,
            flaggedCount: subgroupMembers.filter(function (node) { return nodeReviewPriority(node) > 0; }).length,
        };
    }

    function compactFileStem(path) {
        var name = String(path).split("/").pop() || "file";
        return name.replace(/\.(kt|kts|java|groovy|gradle)$/i, "");
    }

    function nodeDirectorySegments(node) {
        var path = normalizeBoundedPath(node.entityType === "file" ? node.path : node.file);
        var parts = path.split("/").filter(Boolean);
        if (parts.length) parts.pop();
        var sourceSetIndex = parts.indexOf(node.sourceSet);
        if (sourceSetIndex >= 0) parts = parts.slice(sourceSetIndex + 1);
        while (parts.length && ["kotlin", "java", "groovy", "resources"].includes(parts[0])) parts.shift();
        return parts;
    }

    function commonDirectoryPrefix(directories) {
        if (!directories.length) return [];
        var limit = Math.min.apply(null, directories.map(function (parts) { return parts.length; }));
        var prefix = [];
        for (var index = 0; index < limit; index += 1) {
            var segment = directories[0][index];
            if (!directories.every(function (parts) { return parts[index] === segment; })) break;
            prefix.push(segment);
        }
        return prefix;
    }

    function projectDisplayWidth(project) {
        return String(project || ":").length * 7;
    }

    function projectDisplayName(projectName, buildName) {
        return projectName === ":" ? String(buildName || "Build").toUpperCase() + " · : (root project)" :
            projectName;
    }

    function projectRegionSummaryWidth(node, members) {
        var entityType = members.every(function (candidate) { return candidate.entityType === "file"; }) ? "file" :
            members.every(function (candidate) { return candidate.entityType === "symbol"; }) ? "symbol" : "node";
        var noun = members.length === 1 ? entityType : entityType + "s";
        var flagged = members.filter(function (candidate) { return nodeReviewPriority(candidate) > 0; }).length;
        var label = projectDisplayName(node.project, node.build) + " / " + members.length + " " + noun +
            (flagged ? " / " + flagged + " flagged" : "");
        return label.length * 6;
    }

    function rectangleCenter(rectangle) {
        return { x: (rectangle.minX + rectangle.maxX) / 2, y: (rectangle.minY + rectangle.maxY) / 2 };
    }

    function projectKey(build, project) {
        return build + "\u0000" + project;
    }

    function validGraphData(data) {
        var views = new Set(["files", "symbols", "problems", "cycles"]);
        var arrays = [
            "builds",
            "fileNodes",
            "fileEdges",
            "availableFileNodes",
            "availableFileEdges",
            "nodes",
            "edges",
            "availableNodes",
            "availableEdges",
            "cycles",
            "availableCycles",
            "analysisCycles",
            "findings",
            "sourceFiles",
        ];
        var counts = [
            "omittedNodeCount",
            "omittedFileNodeCount",
            "totalRelationshipRecordCount",
            "shownRelationshipRecordCount",
            "shownSymbolRelationshipRecordCount",
        ];
        if (!data || typeof data !== "object" || !views.has(data.defaultView)) return false;
        if (!arrays.every(function (field) { return Array.isArray(data[field]); })) return false;
        if (!counts.every(function (field) { return Number.isInteger(data[field]) && data[field] >= 0; })) return false;
        var fileNodeIds = new Set(data.fileNodes.map(function (node) { return node.id; }));
        var fileEdgeIds = new Set(data.fileEdges.map(function (edge) { return edge.id; }));
        var availableFileNodeIds = new Set(data.availableFileNodes.map(function (node) { return node.id; }));
        var availableFileEdgeIds = new Set(data.availableFileEdges.map(function (edge) { return edge.id; }));
        var sourceFileIds = new Set();
        var sourceScopePaths = new Set();
        var buildsValid = data.builds.every(function (build) {
            if (!build || typeof build.name !== "string" || typeof build.color !== "string" ||
                !Number.isInteger(build.projectCount) || build.projectCount < 0 ||
                !Array.isArray(build.projects) || build.projectCount !== build.projects.length ||
                !Number.isInteger(build.fileNodeCount) || build.fileNodeCount < 0 ||
                !Number.isInteger(build.symbolNodeCount) || build.symbolNodeCount < 0 ||
                !Number.isInteger(build.indexedFileCount) || build.indexedFileCount < 0 ||
                !Number.isInteger(build.indexedSymbolCount) || build.indexedSymbolCount < 0 ||
                !uniqueStringArray(build.sourceSets)) return false;
            var projectNames = new Set();
            return build.projects.every(function (project) {
                return project && typeof project.name === "string" && project.name.length > 0 &&
                    !projectNames.has(project.name) && projectNames.add(project.name) &&
                    Number.isInteger(project.fileNodeCount) && project.fileNodeCount >= 0 &&
                    Number.isInteger(project.symbolNodeCount) && project.symbolNodeCount >= 0 &&
                    Number.isInteger(project.indexedFileCount) && project.indexedFileCount >= 0 &&
                    Number.isInteger(project.indexedSymbolCount) && project.indexedSymbolCount >= 0 &&
                    uniqueStringArray(project.sourceSets) &&
                    project.sourceSets.every(function (sourceSet) { return build.sourceSets.includes(sourceSet); });
            });
        });
        if (!buildsValid) return false;
        var buildNames = new Set(data.builds.map(function (build) { return build.name; }));
        if (buildNames.size !== data.builds.length) return false;
        var projectsByBuild = new Map(data.builds.map(function (build) {
            return [build.name, new Set(build.projects.map(function (project) { return project.name; }))];
        }));
        var declarationCategories = new Set([null, "INTERFACE", "ABSTRACT", "CONCRETE", "ENUM"]);
        var ownershipValid = data.availableFileNodes.concat(data.availableNodes).every(function (node) {
            return node && buildNames.has(node.build) && projectsByBuild.get(node.build).has(node.project);
        });
        var declarationCategoriesValid = data.availableNodes.every(function (node) {
            return declarationCategories.has(node.declarationCategory);
        });
        var fileNodeEvidenceValid = data.availableFileNodes.every(function (node) {
            return Array.isArray(node.internalOccurrences) &&
                node.internalOccurrences.every(function (occurrence) {
                    return validFileOccurrence(occurrence) && occurrence.build === node.build &&
                        occurrence.project === node.project && occurrence.sourceSet === node.sourceSet &&
                        occurrence.file === node.path;
                }) && node.shownInternalRecordCount === node.internalOccurrences.length;
        });
        var sourcesValid = data.sourceFiles.every(function (sourceFile) {
            if (!sourceFile || typeof sourceFile.id !== "string" || !sourceFile.id.length ||
                sourceFileIds.has(sourceFile.id) || typeof sourceFile.build !== "string" ||
                typeof sourceFile.project !== "string" || typeof sourceFile.sourceSet !== "string" ||
                typeof sourceFile.path !== "string" || typeof sourceFile.content !== "string" ||
                !validLineNumbers(sourceFile.declarationLines) || !validLineNumbers(sourceFile.relationshipLines)) {
                return false;
            }
            var key = scopePathKey(sourceFile.build, sourceFile.project, sourceFile.sourceSet, sourceFile.path);
            if (sourceScopePaths.has(key)) return false;
            sourceFileIds.add(sourceFile.id);
            sourceScopePaths.add(key);
            return true;
        });
        var fileSourcesPresent = data.availableFileNodes.every(function (node) { return sourceFileIds.has(node.id); });
        var symbolSourcesPresent = data.availableNodes.every(function (node) {
            return sourceScopePaths.has(scopePathKey(node.build, node.project, node.sourceSet, node.file));
        });
        var analysisEvidenceValid = validAnalysisEvidence(data, buildNames, projectsByBuild);
        var observedCycleIds = new Set(data.cycles.map(function (cycle) { return cycle.id; }));
        var cycleIdsValid = observedCycleIds.size === data.cycles.length && data.analysisCycles.every(function (cycle) {
            return !observedCycleIds.has(cycle.id);
        });
        var projectionsValid = validProjection(data.fileNodes, data.fileEdges) &&
            validProjection(data.nodes, data.edges) &&
            validProjection(data.availableFileNodes, data.availableFileEdges) &&
            validProjection(data.availableNodes, data.availableEdges);
        var shownSymbolRecords = data.edges.reduce(function (sum, edge) { return sum + edge.recordCount; }, 0);
        var shownCountsValid = data.shownRelationshipRecordCount <= data.totalRelationshipRecordCount &&
            data.shownSymbolRelationshipRecordCount <= data.totalRelationshipRecordCount &&
            data.shownSymbolRelationshipRecordCount === shownSymbolRecords;
        return buildsValid && ownershipValid && declarationCategoriesValid && fileNodeEvidenceValid &&
            projectionsValid && shownCountsValid && validEdgeEvidenceArrays(data) && sourcesValid &&
            fileSourcesPresent && symbolSourcesPresent &&
            data.cycles.every(function (cycle) {
                return validCycle(cycle, fileNodeIds, fileEdgeIds, data.fileEdges);
            }) && data.availableCycles.every(function (cycle) {
                return validCycle(cycle, availableFileNodeIds, availableFileEdgeIds, data.availableFileEdges);
            }) && analysisEvidenceValid && cycleIdsValid;
    }

    function validAnalysisEvidence(data, buildNames, projectsByBuild) {
        var findingIds = new Set();
        var cycleIds = new Set();
        var cyclesById = new Map();
        var findingsValid = data.findings.every(function (finding) {
            if (!finding || typeof finding.id !== "string" || !finding.id.length || findingIds.has(finding.id) ||
                !validAnalysisScope(finding, buildNames, projectsByBuild) ||
                typeof finding.severity !== "string" || typeof finding.message !== "string" ||
                typeof finding.suggestion !== "string" || !nullableString(finding.filePath) ||
                !nullablePositiveInteger(finding.line) || (finding.line !== null && finding.filePath === null) ||
                !uniqueStringArray(finding.componentIds) || !uniqueStringArray(finding.componentSymbolIds) ||
                !uniqueStringArray(finding.componentFileIds) ||
                !nullableString(finding.analysisCycleId)) return false;
            findingIds.add(finding.id);
            return true;
        });
        if (!findingsValid) return false;
        var cyclesValid = data.analysisCycles.every(function (cycle) {
            if (!cycle || typeof cycle.id !== "string" || !cycle.id.length || cycleIds.has(cycle.id) ||
                !validAnalysisScope(cycle, buildNames, projectsByBuild) || cycle.evidence !== "ANALYZER_INFERRED" ||
                !Array.isArray(cycle.route) || cycle.route.length < 3 ||
                !uniqueStringArray(cycle.findingIds, findingIds) || !uniqueStringArray(cycle.memberSymbolIds) ||
                !uniqueStringArray(cycle.memberFileIds) ||
                !Array.isArray(cycle.componentIds) || cycle.componentIds.length !== cycle.route.length) return false;
            var routeValid = cycle.route.every(function (component, index) {
                return validAnalysisComponent(component) &&
                    cycle.componentIds[index] === component.componentId;
            });
            if (!routeValid || cycle.componentIds[0] !== cycle.componentIds[cycle.componentIds.length - 1] ||
                new Set(cycle.componentIds.slice(0, -1)).size !== cycle.componentIds.length - 1) return false;
            var routeSymbolIds = distinctDefined(cycle.route.slice(0, -1).map(function (item) {
                return item.symbolId;
            }));
            var routeFileIds = distinctDefined(cycle.route.slice(0, -1).map(function (item) {
                return item.fileId;
            }));
            if (!sameMembers(routeSymbolIds, cycle.memberSymbolIds) ||
                !sameMembers(routeFileIds, cycle.memberFileIds)) return false;
            cycleIds.add(cycle.id);
            cyclesById.set(cycle.id, cycle);
            return true;
        });
        if (!cyclesValid) return false;
        return data.findings.every(function (finding) {
            if (finding.analysisCycleId === null) return true;
            var cycle = cyclesById.get(finding.analysisCycleId);
            return Boolean(cycle) && cycle.build === finding.build && cycle.project === finding.project &&
                cycle.findingIds.includes(finding.id) &&
                sameMembers(finding.componentIds, cycle.componentIds.slice(0, -1)) &&
                finding.componentSymbolIds.every(function (id) { return cycle.memberSymbolIds.includes(id); }) &&
                finding.componentFileIds.every(function (id) { return cycle.memberFileIds.includes(id); });
        }) && data.analysisCycles.every(function (cycle) {
            return cycle.findingIds.every(function (findingId) {
                var finding = data.findings.find(function (candidate) { return candidate.id === findingId; });
                return finding && finding.analysisCycleId === cycle.id;
            });
        });
    }

    function validAnalysisScope(item, buildNames, projectsByBuild) {
        return typeof item.build === "string" && buildNames.has(item.build) &&
            typeof item.project === "string" && projectsByBuild.get(item.build).has(item.project);
    }

    function validAnalysisComponent(component) {
        return component && typeof component.componentId === "string" && component.componentId.length > 0 &&
            typeof component.name === "string" && component.name.length > 0 && nullableString(component.symbolId) &&
            nullableString(component.fileId) && nullableString(component.filePath) &&
            nullableString(component.sourceSet) && nullablePositiveInteger(component.line);
    }

    function uniqueStringArray(values, allowed) {
        if (!Array.isArray(values) || values.some(function (value) {
            return typeof value !== "string" || !value.length || (allowed && !allowed.has(value));
        })) return false;
        return new Set(values).size === values.length;
    }

    function nullableString(value) {
        return value === null || (typeof value === "string" && value.length > 0);
    }

    function nullablePositiveInteger(value) {
        return value === null || (Number.isInteger(value) && value > 0);
    }

    function distinctDefined(values) {
        return Array.from(new Set(values.filter(function (value) { return value !== null; })));
    }

    function sameMembers(left, right) {
        return left.length === right.length && left.every(function (value) { return right.includes(value); });
    }

    function validCycle(cycle, fileNodeIds, fileEdgeIds, fileEdges) {
        if (!cycle || typeof cycle.id !== "string" || !Array.isArray(cycle.memberIds) ||
            !Array.isArray(cycle.edgeIds) || !Array.isArray(cycle.routes) || !cycle.routes.length ||
            !cycle.memberIds.every(function (id) { return fileNodeIds.has(id); }) ||
            !cycle.edgeIds.every(function (id) { return fileEdgeIds.has(id); })) return false;
        var edgeById = new Map(fileEdges.map(function (edge) { return [edge.id, edge]; }));
        var routesValid = cycle.routes.every(function (route) {
            if (!route || !Array.isArray(route.nodeIds) || !Array.isArray(route.edgeIds) ||
                route.nodeIds.length < 3 || route.edgeIds.length !== route.nodeIds.length - 1 ||
                route.nodeIds[0] !== route.nodeIds[route.nodeIds.length - 1]) return false;
            return route.nodeIds.every(function (id) { return cycle.memberIds.includes(id); }) &&
                route.edgeIds.every(function (edgeId, index) {
                    var edge = edgeById.get(edgeId);
                    return edge && cycle.edgeIds.includes(edgeId) && edge.source === route.nodeIds[index] &&
                        edge.target === route.nodeIds[index + 1];
                });
        });
        return routesValid && cycle.memberIds.every(function (memberId) {
            return cycle.routes.some(function (route) { return route.nodeIds.includes(memberId); });
        }) && cycle.edgeIds.every(function (edgeId) {
            return cycle.routes.some(function (route) { return route.edgeIds.includes(edgeId); });
        });
    }

    function validLineNumbers(lines) {
        return Array.isArray(lines) && lines.every(function (line) {
            return Number.isInteger(line) && line > 0;
        });
    }

    function validProjection(nodes, edges) {
        var ids = new Set();
        var validNodes = nodes.every(function (node) {
            return node && typeof node.id === "string" && node.id.length > 0 && !ids.has(node.id) && ids.add(node.id);
        });
        if (!validNodes) return false;
        var edgeIds = new Set();
        return edges.every(function (edge) {
            return edge && typeof edge.id === "string" && edge.id.length > 0 && !edgeIds.has(edge.id) &&
                edgeIds.add(edge.id) && typeof edge.source === "string" && typeof edge.target === "string" &&
                ids.has(edge.source) && ids.has(edge.target);
        });
    }

    function validEdgeEvidenceArrays(data) {
        return data.availableFileEdges.every(function (edge) {
            if (!Array.isArray(edge.occurrences) || edge.occurrences.length === 0 ||
                !edge.occurrences.every(validFileOccurrence) || edge.recordCount !== edge.occurrences.length) {
                return false;
            }
            return countArrayMatchesOccurrences(edge.kindCounts, "kind", edge.occurrences) &&
                countArrayMatchesOccurrences(edge.evidenceCounts, "evidence", edge.occurrences);
        }) && data.availableEdges.every(function (edge) {
            return Array.isArray(edge.occurrences) && edge.occurrences.length > 0 &&
                edge.occurrences.every(validRelationshipOccurrence) && edge.recordCount === edge.occurrences.length &&
                nonEmptyString(edge.kind) && nonEmptyString(edge.kindLabel) && nonEmptyString(edge.label) &&
                nonEmptyString(edge.evidence);
        });
    }

    function validFileOccurrence(occurrence) {
        return validRelationshipOccurrence(occurrence) && nonEmptyString(occurrence.sourceSymbolId) &&
            nonEmptyString(occurrence.targetSymbolId) && nonEmptyString(occurrence.kind) &&
            nonEmptyString(occurrence.kindLabel) && nonEmptyString(occurrence.evidence);
    }

    function validRelationshipOccurrence(occurrence) {
        return occurrence && nonEmptyString(occurrence.build) && nonEmptyString(occurrence.project) &&
            nonEmptyString(occurrence.sourceSet) && nonEmptyString(occurrence.file) &&
            Number.isInteger(occurrence.line) && occurrence.line > 0 && typeof occurrence.context === "string";
    }

    function countArrayMatchesOccurrences(items, field, occurrences) {
        if (!Array.isArray(items) || items.length === 0) return false;
        var values = new Set();
        var itemsValid = items.every(function (item) {
            return item && nonEmptyString(item[field]) && !values.has(item[field]) && values.add(item[field]) &&
                nonEmptyString(item.label) && Number.isInteger(item.count) && item.count > 0;
        });
        if (!itemsValid) return false;
        var actualCounts = new Map();
        occurrences.forEach(function (occurrence) {
            actualCounts.set(occurrence[field], (actualCounts.get(occurrence[field]) || 0) + 1);
        });
        return actualCounts.size === items.length && items.every(function (item) {
            return actualCounts.get(item[field]) === item.count;
        });
    }

    function nonEmptyString(value) {
        return typeof value === "string" && value.length > 0;
    }

    function relationshipCategory(kindOrEdge) {
        if (kindOrEdge && typeof kindOrEdge === "object") {
            var categories = edgeRelationshipCategories(kindOrEdge);
            return categories.length ? categories[0].id : null;
        }
        var kind = String(kindOrEdge || "").toUpperCase();
        if (kind === "EXTENDS" || kind === "IMPLEMENTS") return "inheritance";
        if (kind === "CALL" || kind === "CONSTRUCTOR") return "calls";
        if (kind === "NAME_REFERENCE" || kind === "TYPE_REFERENCE" || kind === "PROPERTY_TYPE" ||
            kind === "PARAMETER_TYPE" || kind === "RETURN_TYPE") return "references";
        if (kind === "IMPORT") return "imports";
        return null;
    }

    function relationshipCategoryDefinition(categoryId) {
        return {
            inheritance: { id: "inheritance", label: "Inheritance & implementation" },
            calls: { id: "calls", label: "Call / construct records" },
            references: { id: "references", label: "Type & member references" },
            imports: { id: "imports", label: "Imports" },
        }[categoryId] || null;
    }

    function edgeRelationshipKindCounts(edge) {
        if (Array.isArray(edge.kindCounts)) return edge.kindCounts;
        if (!edge.kind) return [];
        return [{ kind: edge.kind, label: edge.kindLabel || edge.label || edge.kind, count: edgeRecordCount(edge) }];
    }

    function edgeRelationshipCategories(edge) {
        var totals = new Map();
        edgeRelationshipKindCounts(edge).forEach(function (item) {
            var categoryId = relationshipCategory(item.kind);
            if (!categoryId) return;
            totals.set(categoryId, (totals.get(categoryId) || 0) + (item.count || 0));
        });
        return Array.from(totals.entries()).map(function (entry) {
            var definition = relationshipCategoryDefinition(entry[0]);
            return Object.assign({}, definition, { count: entry[1] });
        }).sort(function (left, right) {
            return right.count - left.count || relationshipCategoryOrder(left.id) - relationshipCategoryOrder(right.id);
        });
    }

    function relationshipCategoryOrder(categoryId) {
        return ["inheritance", "calls", "references", "imports"].indexOf(categoryId);
    }

    function relationshipCategories(data) {
        var ids = new Set();
        (data.availableFileEdges || data.fileEdges).concat(data.availableEdges || data.edges).forEach(function (edge) {
            edgeRelationshipCategories(edge).forEach(function (category) { ids.add(category.id); });
        });
        return ["inheritance", "calls", "references", "imports"].filter(function (categoryId) {
            return ids.has(categoryId);
        }).map(relationshipCategoryDefinition);
    }

    function edgeMatchesRelationshipKind(edge, categoryId) {
        if (!categoryId || categoryId === "all") return true;
        return edgeRelationshipCategories(edge).some(function (category) { return category.id === categoryId; });
    }

    function filterEdgeToRelationshipKind(edge, categoryId) {
        if (!edgeMatchesRelationshipKind(edge, categoryId)) return null;
        var activeCategory = categoryId === "all" ? relationshipCategory(edge) : categoryId;
        if (categoryId === "all") {
            return Object.assign({}, edge, { displayRelationshipCategory: activeCategory });
        }
        if (!Array.isArray(edge.kindCounts)) {
            return Object.assign({}, edge, { displayRelationshipCategory: activeCategory });
        }
        var kinds = new Set(edge.kindCounts.filter(function (item) {
            return relationshipCategory(item.kind) === categoryId;
        }).map(function (item) { return item.kind; }));
        var occurrences = (edge.occurrences || []).filter(function (occurrence) { return kinds.has(occurrence.kind); });
        return Object.assign({}, edge, {
            displayRelationshipCategory: activeCategory,
            recordCount: occurrences.length,
            kindCounts: edge.kindCounts.filter(function (item) { return kinds.has(item.kind); }),
            evidenceCounts: occurrenceEvidenceCounts(occurrences),
            occurrences: occurrences,
        });
    }

    function occurrenceEvidenceCounts(occurrences) {
        var counts = new Map();
        occurrences.forEach(function (occurrence) {
            counts.set(occurrence.evidence, (counts.get(occurrence.evidence) || 0) + 1);
        });
        return Array.from(counts.entries()).sort(function (left, right) {
            return left[0].localeCompare(right[0]);
        }).map(function (entry) { return { evidence: entry[0], label: entry[0], count: entry[1] }; });
    }

    function assignNodeVisualHierarchy(nodes) {
        var recordCeiling = percentileValue(nodes.map(nodeRelationshipRecordCount), 0.9);
        var importanceCeiling = percentileValue(nodes.map(function (node) {
            return Number(node.importanceScore || node.importance || 0);
        }), 0.9);
        var ranked = nodes.slice().sort(comparePersistentLabelPriority);
        var rankById = new Map(ranked.map(function (node, index) { return [node.id, index]; }));
        var highSignalLimit = Math.max(8, Math.ceil(nodes.length * 0.08));
        var mediumSignalLimit = Math.max(24, Math.ceil(nodes.length * 0.3));
        nodes.forEach(function (node) {
            var records = nodeRelationshipRecordCount(node);
            var recordSignal = Math.min(1, Math.log2(records + 1) / Math.log2(recordCeiling + 1));
            var importanceSignal = Math.min(
                1,
                Number(node.importanceScore || node.importance || 0) / importanceCeiling,
            );
            var reviewSignal = nodeReviewPriority(node) / 5;
            var rank = rankById.get(node.id) || 0;
            node.visualSignal = Math.max(recordSignal, importanceSignal * 0.9, reviewSignal);
            node.visualRank = rank + 1;
            node.visualPopulation = nodes.length;
            node.visualTier = nodeReviewPriority(node) > 0 ||
                node.visualSignal > 0 && rank < highSignalLimit ? "high" :
                node.visualSignal > 0 && (rank < mediumSignalLimit || node.visualSignal >= 0.24) ?
                    "medium" : "low";
        });
        d3.group(nodes, function (node) { return projectKey(node.build, node.project); }).forEach(function (members) {
            automaticNodeSubgroups(members).forEach(function (subgroup) {
                subgroup.members.forEach(function (node) { node.visualSubgroupKey = subgroup.key; });
            });
        });
    }

    function percentileValue(values, percentile) {
        var sorted = values.map(Number).filter(Number.isFinite).sort(function (left, right) { return left - right; });
        if (!sorted.length) return 1;
        var index = Math.max(0, Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * percentile)));
        return Math.max(1, sorted[index]);
    }

    function assignLinkGeometry(links) {
        var groups = d3.group(links, function (edge) {
            var source = endpointId(edge.source);
            var target = endpointId(edge.target);
            return source < target ? source + "\u0000" + target : target + "\u0000" + source;
        });
        groups.forEach(function (members) {
            members.sort(function (left, right) { return left.id.localeCompare(right.id); });
            members.forEach(function (edge, index) {
                edge.parallelIndex = index;
                edge.parallelCount = members.length;
            });
        });
        d3.group(links, function (edge) { return endpointId(edge.source); }).forEach(function (members) {
            members.sort(function (left, right) {
                return endpointId(left.target).localeCompare(endpointId(right.target)) || left.id.localeCompare(right.id);
            });
            members.forEach(function (edge, index) {
                edge.sourceFanIndex = index;
                edge.sourceFanCount = members.length;
            });
        });
    }

    function indexIncidentLinks(links) {
        var index = new Map();
        links.forEach(function (edge) {
            [endpointId(edge.source), endpointId(edge.target)].forEach(function (nodeId) {
                if (!index.has(nodeId)) index.set(nodeId, []);
                index.get(nodeId).push(edge);
            });
        });
        index.forEach(function (incident) {
            incident.sort(function (left, right) { return left.id.localeCompare(right.id); });
        });
        return index;
    }

    function edgePath(edge) {
        var route = edge.visualRoute || curveDirectEdge(
            edge,
            edge.route || edgeRoute(edge, [edge.source, edge.target]),
        );
        return roundedEdgePath(route);
    }

    function curveDirectEdge(edge, route) {
        if (route.length !== 2 || endpointId(edge.source) === endpointId(edge.target)) return route;
        var start = route[0];
        var end = route[1];
        var dx = end.x - start.x;
        var dy = end.y - start.y;
        var length = Math.sqrt(dx * dx + dy * dy);
        if (length < 40) return route;
        var checksum = String(edge.id).split("").reduce(function (sum, character) {
            return sum + character.charCodeAt(0);
        }, 0);
        var direction = checksum % 2 === 0 ? 1 : -1;
        var centeredFan = (edge.sourceFanIndex || 0) - ((edge.sourceFanCount || 1) - 1) / 2;
        var centeredParallel = (edge.parallelIndex || 0) - ((edge.parallelCount || 1) - 1) / 2;
        var bend = Math.max(
            -34,
            Math.min(34, Math.max(7, length * 0.045) * direction + centeredFan * 3 + centeredParallel * 10),
        );
        return [start, {
            x: (start.x + end.x) / 2 - dy / length * bend,
            y: (start.y + end.y) / 2 + dx / length * bend,
        }, end];
    }

    function roundedEdgePath(route) {
        if (!route.length) return "";
        if (route.length === 3) {
            return "M" + roundedPoint(route[0]) + " Q" + roundedPoint(route[1]) + " " + roundedPoint(route[2]);
        }
        var commands = ["M" + roundedPoint(route[0])];
        for (var index = 1; index + 1 < route.length; index += 1) {
            var previous = route[index - 1];
            var corner = route[index];
            var next = route[index + 1];
            var radius = Math.min(10, pointDistance(previous, corner) / 2, pointDistance(corner, next) / 2);
            var entry = pointToward(corner, previous, radius);
            var exit = pointToward(corner, next, radius);
            commands.push("L" + roundedPoint(entry));
            commands.push("Q" + roundedPoint(corner) + " " + roundedPoint(exit));
        }
        if (route.length > 1) commands.push("L" + roundedPoint(route[route.length - 1]));
        return commands.join(" ");
    }

    function roundedPoint(point) {
        return roundCoordinate(point.x) + "," + roundCoordinate(point.y);
    }

    function pointDistance(left, right) {
        return Math.sqrt(Math.pow(right.x - left.x, 2) + Math.pow(right.y - left.y, 2));
    }

    function pointToward(origin, target, distance) {
        var length = pointDistance(origin, target);
        if (!length) return { x: origin.x, y: origin.y };
        return {
            x: origin.x + (target.x - origin.x) / length * distance,
            y: origin.y + (target.y - origin.y) / length * distance,
        };
    }

    function routeEdgeAroundLabels(edge, nodes) {
        return edgeRoute(edge, nodes);
    }

    function edgeRoute(edge, nodes) {
        if (edge.source.id === edge.target.id) {
            return selfEdgeRoute(edge);
        }
        return clipEdgeRoute([
            { x: edge.source.x, y: edge.source.y },
            { x: edge.target.x, y: edge.target.y },
        ], edge);
    }

    function selfEdgeRoute(edge) {
        var sourceRadius = outerNodeRadius(edge.source) + 2;
        var targetRadius = outerNodeRadius(edge.target) + 2;
        var direction = edge.source.labelDirection === "right" ? -1 : 1;
        var loop = Math.max(sourceRadius, targetRadius) + 24 + (edge.parallelIndex || 0) * 12;
        var x = edge.source.x;
        var y = edge.source.y;
        return [
            { x: x + direction * sourceRadius * 0.7, y: y - sourceRadius * 0.7 },
            { x: x + direction * loop, y: y - loop * 0.72 },
            { x: x + direction * loop, y: y + loop * 0.72 },
            { x: x + direction * targetRadius * 0.7, y: y + targetRadius * 0.7 },
        ];
    }

    function nodeObstacleBounds(node, padding) {
        padding = Number.isFinite(padding) ? padding : 0;
        var bounds = labelBounds(node);
        var gap = outerNodeRadius(node) + 6;
        var minX = node.labelDirection === "left" ? node.x - gap - bounds.width : node.x + gap;
        var maxX = node.labelDirection === "left" ? node.x - gap : node.x + gap + bounds.width;
        return {
            id: "label:" + node.id,
            minX: minX - padding,
            minY: node.y - bounds.height / 2 - padding,
            maxX: maxX + padding,
            maxY: node.y + bounds.height / 2 + padding,
        };
    }

    function nodeCircleObstacleBounds(node, padding) {
        var radius = outerNodeRadius(node) + (padding || 0);
        return {
            id: "node:" + node.id,
            minX: node.x - radius,
            minY: node.y - radius,
            maxX: node.x + radius,
            maxY: node.y + radius,
        };
    }

    function routeAroundObstacles(initialRoute, obstacles) {
        var route = initialRoute.slice();
        var iterationLimit = Math.max(12, Math.min(72, obstacles.length * 4));
        for (var iteration = 0; iteration < iterationLimit; iteration += 1) {
            var collision = firstRouteCollision(route, obstacles);
            if (!collision) return simplifyRoute(route);
            var start = route[collision.segmentIndex];
            var end = route[collision.segmentIndex + 1];
            var detour = bestRectangleDetour(start, end, collision.obstacle, obstacles);
            if (!detour) break;
            route.splice.apply(route, [collision.segmentIndex + 1, 0].concat(detour));
            route = simplifyRoute(route);
        }
        return simplifyRoute(bestOuterRoute(route[0], route[route.length - 1], obstacles));
    }

    function firstRouteCollision(route, obstacles) {
        for (var segmentIndex = 0; segmentIndex + 1 < route.length; segmentIndex += 1) {
            for (var obstacleIndex = 0; obstacleIndex < obstacles.length; obstacleIndex += 1) {
                if (segmentIntersectsRectangle(route[segmentIndex], route[segmentIndex + 1], obstacles[obstacleIndex])) {
                    return { segmentIndex: segmentIndex, obstacle: obstacles[obstacleIndex] };
                }
            }
        }
        return null;
    }

    function bestRectangleDetour(start, end, obstacle, obstacles) {
        var corners = {
            topLeft: { x: obstacle.minX, y: obstacle.minY },
            topRight: { x: obstacle.maxX, y: obstacle.minY },
            bottomLeft: { x: obstacle.minX, y: obstacle.maxY },
            bottomRight: { x: obstacle.maxX, y: obstacle.maxY },
        };
        var sides = [
            [corners.topLeft, corners.topRight],
            [corners.bottomLeft, corners.bottomRight],
            [corners.topLeft, corners.bottomLeft],
            [corners.topRight, corners.bottomRight],
        ];
        var best = sides.flatMap(function (side) { return [side, [side[1], side[0]]]; })
            .filter(function (side) {
                return !segmentIntersectsRectangle(start, side[0], obstacle) &&
                    !segmentIntersectsRectangle(side[0], side[1], obstacle) &&
                    !segmentIntersectsRectangle(side[1], end, obstacle);
            }).map(function (side) {
                var candidate = [start, side[0], side[1], end];
                return {
                    points: side,
                    score: routeCollisionCount(candidate, obstacles) * 100000 + routeLength(candidate),
                };
            }).sort(function (left, right) { return left.score - right.score; })[0];
        return best ? best.points : null;
    }

    function bestOuterRoute(start, end, obstacles) {
        if (!obstacles.length) return [start, end];
        var clearance = 28;
        var minX = Math.min.apply(null, obstacles.map(function (item) { return item.minX; })) - clearance;
        var minY = Math.min.apply(null, obstacles.map(function (item) { return item.minY; })) - clearance;
        var maxX = Math.max.apply(null, obstacles.map(function (item) { return item.maxX; })) + clearance;
        var maxY = Math.max.apply(null, obstacles.map(function (item) { return item.maxY; })) + clearance;
        return [
            [start, { x: minX, y: start.y }, { x: minX, y: end.y }, end],
            [start, { x: maxX, y: start.y }, { x: maxX, y: end.y }, end],
            [start, { x: start.x, y: minY }, { x: end.x, y: minY }, end],
            [start, { x: start.x, y: maxY }, { x: end.x, y: maxY }, end],
        ].map(function (route) {
            return { route: route, collisions: routeCollisionCount(route, obstacles), length: routeLength(route) };
        }).sort(function (left, right) {
            return left.collisions - right.collisions || left.length - right.length;
        })[0].route;
    }

    function segmentIntersectsRectangle(start, end, rectangle) {
        var epsilon = 0.2;
        var minX = rectangle.minX + epsilon;
        var minY = rectangle.minY + epsilon;
        var maxX = rectangle.maxX - epsilon;
        var maxY = rectangle.maxY - epsilon;
        if (maxX <= minX || maxY <= minY) return false;
        var dx = end.x - start.x;
        var dy = end.y - start.y;
        var horizontalRange = segmentAxisIntersection(start.x, dx, minX, maxX);
        var verticalRange = segmentAxisIntersection(start.y, dy, minY, maxY);
        if (!horizontalRange || !verticalRange) return false;
        var lower = Math.max(horizontalRange.min, verticalRange.min, 0);
        var upper = Math.min(horizontalRange.max, verticalRange.max, 1);
        return lower <= upper;
    }

    function segmentAxisIntersection(origin, delta, minimum, maximum) {
        if (Math.abs(delta) < 0.00001) {
            return origin >= minimum && origin <= maximum ? { min: -Infinity, max: Infinity } : null;
        }
        var first = (minimum - origin) / delta;
        var second = (maximum - origin) / delta;
        return { min: Math.min(first, second), max: Math.max(first, second) };
    }

    function routeCollisionCount(route, obstacles) {
        var count = 0;
        for (var index = 0; index + 1 < route.length; index += 1) {
            obstacles.forEach(function (obstacle) {
                if (segmentIntersectsRectangle(route[index], route[index + 1], obstacle)) count += 1;
            });
        }
        return count;
    }

    function routeLength(route) {
        var length = 0;
        for (var index = 0; index + 1 < route.length; index += 1) {
            length += pointDistance(route[index], route[index + 1]);
        }
        return length;
    }

    function simplifyRoute(route) {
        return route.filter(function (point, index) {
            return index === 0 || pointDistance(point, route[index - 1]) > 0.5;
        }).filter(function (point, index, points) {
            if (index === 0 || index === points.length - 1) return true;
            var previous = points[index - 1];
            var next = points[index + 1];
            var cross = (point.x - previous.x) * (next.y - point.y) -
                (point.y - previous.y) * (next.x - point.x);
            return Math.abs(cross) > 0.5;
        });
    }

    function clipEdgeRoute(route, edge) {
        if (route.length < 2) return route;
        var clipped = route.slice();
        clipped[0] = pointFromCenter(edge.source, clipped[1], outerNodeRadius(edge.source) + 2);
        clipped[clipped.length - 1] = pointFromCenter(
            edge.target,
            clipped[clipped.length - 2],
            outerNodeRadius(edge.target) + 2,
        );
        return clipped;
    }

    function pointFromCenter(center, toward, radius) {
        var dx = toward.x - center.x;
        var dy = toward.y - center.y;
        var length = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        return { x: center.x + dx / length * radius, y: center.y + dy / length * radius };
    }

    function pointDistance(left, right) {
        var dx = right.x - left.x;
        var dy = right.y - left.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    function roundCoordinate(value) {
        return Math.round(value * 10) / 10;
    }

    function positionEdgeCountBadges(edgeGroups, links, nodes) {
        var occupied = [];
        edgeGroups.select(".srcx-dashboard__architecture-svg-edge-count").each(function (edge) {
            var label = edgeRecordCountText(edge);
            var width = Math.max(38, label.length * 5.3 + 13);
            var height = 16;
            var point = edgeLabelPoint(edge, nodes, occupied, width, height);
            edge.countBadgeBounds = {
                minX: point.x - width / 2,
                minY: point.y - height / 2,
                maxX: point.x + width / 2,
                maxY: point.y + height / 2,
            };
            occupied.push(edge.countBadgeBounds);
            var badge = d3.select(this).attr("transform", "translate(" + point.x + "," + point.y + ")");
            badge.select("rect")
                .attr("x", -width / 2)
                .attr("y", -height / 2)
                .attr("width", width)
                .attr("height", height);
            badge.select("text").text(label);
        });
    }

    function edgeLabelPoint(edge, nodes, occupied, badgeWidth, badgeHeight) {
        var route = edge.visualRoute || curveDirectEdge(edge, edge.route || edgeRoute(edge, nodes));
        occupied = occupied || [];
        badgeWidth = badgeWidth || 48;
        badgeHeight = badgeHeight || 16;
        var endpointIds = new Set([endpointId(edge.source), endpointId(edge.target)]);
        var obstacles = nodes.filter(function (node) { return !endpointIds.has(node.id); })
            .map(function (node) { return expandRectangle(nodeVisualBounds(node), 4); });
        var fractions = [0.5, 0.38, 0.62];
        var offsets = [12, -12, 24, -24, 0];
        var fallback = edgeRouteSample(route, 0.5).point;
        for (var fractionIndex = 0; fractionIndex < fractions.length; fractionIndex += 1) {
            var sample = edgeRouteSample(route, fractions[fractionIndex]);
            for (var offsetIndex = 0; offsetIndex < offsets.length; offsetIndex += 1) {
                var point = {
                    x: sample.point.x + sample.normal.x * offsets[offsetIndex],
                    y: sample.point.y + sample.normal.y * offsets[offsetIndex],
                };
                var bounds = {
                    minX: point.x - badgeWidth / 2,
                    minY: point.y - badgeHeight / 2,
                    maxX: point.x + badgeWidth / 2,
                    maxY: point.y + badgeHeight / 2,
                };
                if (!obstacles.some(function (obstacle) { return rectanglesOverlap(bounds, obstacle); }) &&
                    !occupied.some(function (other) { return rectanglesOverlap(bounds, other); })) return point;
            }
        }
        return fallback;
    }

    function edgeRouteSample(route, fraction) {
        if (route.length === 3) {
            var inverse = 1 - fraction;
            var point = {
                x: inverse * inverse * route[0].x + 2 * inverse * fraction * route[1].x +
                    fraction * fraction * route[2].x,
                y: inverse * inverse * route[0].y + 2 * inverse * fraction * route[1].y +
                    fraction * fraction * route[2].y,
            };
            var tangent = {
                x: 2 * inverse * (route[1].x - route[0].x) + 2 * fraction * (route[2].x - route[1].x),
                y: 2 * inverse * (route[1].y - route[0].y) + 2 * fraction * (route[2].y - route[1].y),
            };
            return { point: point, normal: normalizedNormal(tangent) };
        }
        var segmentIndex = Math.max(0, Math.min(route.length - 2, Math.floor(fraction * (route.length - 1))));
        var start = route[segmentIndex] || { x: 0, y: 0 };
        var end = route[segmentIndex + 1] || start;
        var localFraction = fraction * Math.max(1, route.length - 1) - segmentIndex;
        return {
            point: {
                x: start.x + (end.x - start.x) * localFraction,
                y: start.y + (end.y - start.y) * localFraction,
            },
            normal: normalizedNormal({ x: end.x - start.x, y: end.y - start.y }),
        };
    }

    function normalizedNormal(vector) {
        var length = Math.max(1, Math.sqrt(vector.x * vector.x + vector.y * vector.y));
        return { x: -vector.y / length, y: vector.x / length };
    }

    function expandRectangle(rectangle, padding) {
        return {
            minX: rectangle.minX - padding,
            minY: rectangle.minY - padding,
            maxX: rectangle.maxX + padding,
            maxY: rectangle.maxY + padding,
        };
    }

    function rectanglesOverlap(left, right) {
        return left.minX < right.maxX && left.maxX > right.minX &&
            left.minY < right.maxY && left.maxY > right.minY;
    }

    function parallelOffset(edge) {
        var count = edge.parallelCount || 1;
        if (count === 1) return 0;
        var centered = (edge.parallelIndex || 0) - (count - 1) / 2;
        var direction = endpointId(edge.source) < endpointId(edge.target) ? 1 : -1;
        return centered * 22 * direction;
    }

    function persistentLabelIds(nodes) {
        var budget = Math.max(10, Math.min(36, Math.ceil(Math.sqrt(nodes.length) * 2.2)));
        var ranked = nodes.slice().sort(comparePersistentLabelPriority);
        var selected = new Set();
        ranked.filter(function (node) { return nodeReviewPriority(node) >= 3; })
            .forEach(function (node) { selected.add(node.id); });
        ranked.some(function (node) {
            if (selected.size >= budget) return true;
            selected.add(node.id);
            return false;
        });
        return selected;
    }

    function comparePersistentLabelPriority(left, right) {
        return nodeReviewPriority(right) - nodeReviewPriority(left) ||
            Number(right.importanceScore || right.importance || 0) -
                Number(left.importanceScore || left.importance || 0) ||
            nodeRelationshipRecordCount(right) - nodeRelationshipRecordCount(left) ||
            String(left.id).localeCompare(String(right.id));
    }

    function nodeReviewPriority(node) {
        if (node.fileFindingCount > 0) return 5;
        if (node.hasCycle) return 4;
        if (node.hasAnalysisCycle || node.hasAnalyzerFinding) return 3;
        return node.important ? 2 : 0;
    }

    function collisionRadius(node, labelIds) {
        if (!labelIds.has(node.id)) return outerNodeRadius(node) + 13;
        var bounds = labelBounds(node);
        var labelRadius = Math.sqrt(bounds.width * bounds.width + bounds.height * bounds.height) / 2;
        return outerNodeRadius(node) + 16 + labelRadius;
    }

    function measureNodeLabels(nodeGroups) {
        nodeGroups.select("text").each(function (node) {
            var fallbackLines = wrappedNodeLabel(node);
            var bounds = node.labelPinned ? safeSvgBounds(this) : null;
            node.labelLines = fallbackLines;
            node.labelBounds = {
                width: bounds ? bounds.width : longestTextLength(fallbackLines) * 5.2,
                height: bounds ? bounds.height : Math.max(10, fallbackLines.length * 10),
            };
            node.labelWidth = node.labelBounds.width;
            node.labelHeight = node.labelBounds.height;
        });
    }

    function labelBounds(node) {
        return node.labelBounds || {
            width: node.labelWidth || longestTextLength(wrappedNodeLabel(node)) * 5.2,
            height: node.labelHeight || Math.max(10, wrappedNodeLabel(node).length * 10),
        };
    }

    function measureBuildLabels(buildGroups) {
        buildGroups.select("text").each(function (build) {
            var context = build.context + " / " + build.fileNodeCount + " bounded files";
            var bounds = safeSvgBounds(this);
            build.labelWidth = bounds ? bounds.width : (build.name.length * 5.2 + context.length * 4 + 12);
            build.labelHeight = bounds ? bounds.height : 10;
        });
    }

    function positionLabels(nodeGroups) {
        nodeGroups.select("text").each(function (node) {
            var toLeft = node.labelDirection === "left";
            var x = toLeft ? -(outerNodeRadius(node) + 6) : outerNodeRadius(node) + 6;
            var label = d3.select(this);
            var lineCount = (node.labelLines || wrappedNodeLabel(node)).length;
            label.attr("x", x).attr("y", -((lineCount - 1) * 5))
                .style("text-anchor", toLeft ? "end" : "start");
            label.selectAll("tspan").attr("x", x);
        });
    }

    function assignLabelDirections(nodes, cells, subgroupCells) {
        nodes.forEach(function (node) {
            var cell = subgroupCells && subgroupCells.get(node.subgroupKey) || cells.get(node.build);
            if (node.labelPinned && node.labelDirection) return;
            if ((node.isDirectlyDragged || node.isManuallyPinned) && node.labelDirection) return;
            node.labelDirection = cell && node.x > (cell.minX + cell.maxX) / 2 ? "left" : "right";
        });
    }

    function nodeVisualBounds(node) {
        var radius = outerNodeRadius(node) + 2;
        var labelGap = outerNodeRadius(node) + 6;
        var bounds = labelBounds(node);
        var labelWidth = node.labelPinned ? bounds.width : 0;
        var labelHeight = node.labelPinned ? bounds.height : 0;
        var left = node.x - radius;
        var right = node.x + radius;
        if (node.labelDirection === "left") left = Math.min(left, node.x - labelGap - labelWidth);
        else right = Math.max(right, node.x + labelGap + labelWidth);
        return {
            minX: left,
            minY: node.y - Math.max(radius, labelHeight / 2),
            maxX: right,
            maxY: node.y + Math.max(radius, labelHeight / 2),
        };
    }

    function constrainNodesToBuildCells(nodes, cells, projectCells, subgroupCells) {
        nodes.forEach(function (node) {
            if (node.isDirectlyDragged) return;
            var subgroupCell = subgroupCells && subgroupCells.get(node.subgroupKey);
            var cell = subgroupCell || projectCells && projectCells.get(projectKey(node.build, node.project)) ||
                cells.get(node.build);
            if (!cell) return;
            var boundaryPadding = subgroupCell ? 10 : projectCells ? 14 : 36;
            var radius = outerNodeRadius(node) + 4;
            var bounds = labelBounds(node);
            var labelWidth = node.labelPinned ? bounds.width : 0;
            var labelHeight = node.labelPinned ? bounds.height : 0;
            var leftInset = radius + (node.labelDirection === "left" ? labelWidth + 8 : 0);
            var rightInset = radius + (node.labelDirection === "right" ? labelWidth + 8 : 0);
            var minX = cell.minX + boundaryPadding + leftInset;
            var maxX = cell.maxX - boundaryPadding - rightInset;
            var minY = cell.minY + boundaryPadding + (subgroupCell ? 18 : projectCells ? 22 : 28) +
                Math.max(radius, labelHeight / 2);
            var maxY = cell.maxY - boundaryPadding - Math.max(radius, labelHeight / 2);
            if (minX > maxX) minX = maxX = (cell.minX + cell.maxX) / 2;
            if (minY > maxY) minY = maxY = (cell.minY + cell.maxY) / 2;
            node.x = Math.max(minX, Math.min(maxX, node.x));
            node.y = Math.max(minY, Math.min(maxY, node.y));
            if (node.fx != null) node.fx = node.x;
            if (node.fy != null) node.fy = node.y;
        });
    }

    function graphBounds(nodes, builds, links) {
        if (!nodes.length) return { x: 0, y: 0, width: 0, height: 0 };
        var minX = Infinity;
        var minY = Infinity;
        var maxX = -Infinity;
        var maxY = -Infinity;
        nodes.forEach(function (node) {
            var bounds = nodeVisualBounds(node);
            var build = builds.find(function (candidate) { return candidate.name === node.build; });
            var headingWidth = build && build.labelWidth ? build.labelWidth + 28 : 0;
            minX = Math.min(minX, bounds.minX - 36);
            maxX = Math.max(maxX, bounds.maxX + 36, bounds.minX + headingWidth);
            minY = Math.min(minY, bounds.minY - 56);
            maxY = Math.max(maxY, bounds.maxY + 36);
        });
        (links || []).forEach(function (edge) {
            var bounds = edgeVisualBounds(edge);
            if (!bounds) return;
            minX = Math.min(minX, bounds.minX - 16);
            minY = Math.min(minY, bounds.minY - 16);
            maxX = Math.max(maxX, bounds.maxX + 16);
            maxY = Math.max(maxY, bounds.maxY + 16);
        });
        return { x: minX, y: minY, width: Math.max(1, maxX - minX), height: Math.max(1, maxY - minY) };
    }

    function edgeVisualBounds(edge) {
        var route = edge.visualRoute || edge.route;
        if (!route || !route.length) return null;
        var minX = Math.min.apply(null, route.map(function (point) { return point.x; }));
        var minY = Math.min.apply(null, route.map(function (point) { return point.y; }));
        var maxX = Math.max.apply(null, route.map(function (point) { return point.x; }));
        var maxY = Math.max.apply(null, route.map(function (point) { return point.y; }));
        if (edge.countBadgeBounds) {
            minX = Math.min(minX, edge.countBadgeBounds.minX);
            minY = Math.min(minY, edge.countBadgeBounds.minY);
            maxX = Math.max(maxX, edge.countBadgeBounds.maxX);
            maxY = Math.max(maxY, edge.countBadgeBounds.maxY);
        }
        return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
    }

    function unionGraphBounds(left, right) {
        if (!left) return right;
        if (!right) return left;
        var minX = Math.min(left.x, right.x);
        var minY = Math.min(left.y, right.y);
        var maxX = Math.max(left.x + left.width, right.x + right.width);
        var maxY = Math.max(left.y + left.height, right.y + right.height);
        return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
    }

    function renderedGraphBounds(element) {
        var bounds = safeSvgBounds(element);
        if (!bounds || !Number.isFinite(bounds.x) || !Number.isFinite(bounds.y)) return null;
        if (bounds.width <= 0 || bounds.height <= 0) return null;
        return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
    }

    function safeSvgBounds(element) {
        if (!element || typeof element.getBBox !== "function") return null;
        try {
            var bounds = element.getBBox();
            if (!Number.isFinite(bounds.width) || !Number.isFinite(bounds.height)) return null;
            if (bounds.width <= 0 || bounds.height <= 0) return null;
            return bounds;
        } catch (ignored) {
            return null;
        }
    }

    function longestTextLength(lines) {
        return lines.reduce(function (longest, line) { return Math.max(longest, line.length); }, 0);
    }

    function viewportSize(viewport) {
        return {
            width: Math.max(1, Math.round(viewport.clientWidth || 1000)),
            height: Math.max(1, Math.round(viewport.clientHeight || graphHeight())),
        };
    }

    function relationshipLabel(edge, nodeById) {
        if (isAnalysisCycleEdge(edge)) {
            return "Analyzer-inferred component-cycle hop " + edge.sourceComponent.name + " to " +
                edge.targetComponent.name + "; directional analyzer evidence; not a resolved relationship record";
        }
        var categories = edgeRelationshipCategories(edge).map(function (category) {
            return category.label;
        }).join(", ");
        return accessibleNodeName(nodeById.get(endpointId(edge.source))) + " to " +
            accessibleNodeName(nodeById.get(endpointId(edge.target))) + "; source depends on target; " +
            (categories || "Other relationship kind") + "; " + edgeRecordCountLabel(edge);
    }

    function activationKey(event) {
        return event.key === "Enter" || event.key === " ";
    }

    function arrowKey(event) {
        return event.key === "ArrowLeft" || event.key === "ArrowRight" || event.key === "ArrowUp" ||
            event.key === "ArrowDown";
    }

    function adjacentNode(current, key, nodes) {
        var horizontal = key === "ArrowLeft" || key === "ArrowRight";
        var direction = key === "ArrowLeft" || key === "ArrowUp" ? -1 : 1;
        var candidates = nodes.filter(function (node) {
            var primary = horizontal ? node.x - current.x : node.y - current.y;
            return node.id !== current.id && primary * direction > 1;
        });
        candidates.sort(function (left, right) {
            var leftPrimary = Math.abs((horizontal ? left.x : left.y) - (horizontal ? current.x : current.y));
            var rightPrimary = Math.abs((horizontal ? right.x : right.y) - (horizontal ? current.x : current.y));
            var leftSecondary = Math.abs((horizontal ? left.y : left.x) - (horizontal ? current.y : current.x));
            var rightSecondary = Math.abs((horizontal ? right.y : right.x) - (horizontal ? current.y : current.x));
            return leftPrimary + leftSecondary * 1.5 - (rightPrimary + rightSecondary * 1.5) ||
                left.id.localeCompare(right.id);
        });
        return candidates[0] || null;
    }

    function nodeRadius(node) {
        var signal = Math.max(0, Math.min(1, Number(node.visualSignal || 0)));
        if (node.entityType === "file") return 4.5 + Math.pow(signal, 0.72) * 15.5;
        return 4 + Math.pow(signal, 0.72) * 14;
    }

    function outerNodeRadius(node) {
        var radius = nodeRadius(node);
        if (node && node.important) radius = Math.max(radius, nodeRadius(node) + 4);
        if (node && node.fileFindingCount > 0) radius = Math.max(radius, nodeRadius(node) + 7);
        if (node && node.hasCycle) radius = Math.max(radius, nodeRadius(node) + 10);
        if (node && node.hasAnalysisCycle) radius = Math.max(radius, nodeRadius(node) + 13);
        return radius;
    }

    function nodeRelationshipRecordCount(node) {
        if (!node) return 0;
        if (node.entityType === "file" || Number.isFinite(node.relationshipRecordCount)) {
            return node.relationshipRecordCount || 0;
        }
        return (node.workspaceInbound || 0) + (node.outgoingRecordCount || 0);
    }

    function nodeClass(node) {
        var classes = "srcx-dashboard__architecture-svg-node";
        classes += " is-signal-" + (node.visualTier || "low");
        if (node.important) classes += " is-important";
        if (node.fileFindingCount > 0) classes += " has-problem has-finding";
        if (node.hasCycle) classes += " has-problem has-cycle";
        if (node.hasAnalysisCycle) classes += " has-problem has-analysis-cycle";
        if (node.hasAnalyzerFinding) classes += " has-problem has-analyzer-finding";
        if (node.sourceFileFindingCount > 0) classes += " has-source-file-finding";
        return classes;
    }

    function declarationSemantic(node) {
        if (node.declarationSemantic) return node.declarationSemantic;
        if (String(node.kind || "").toLowerCase() === "object") return "SINGLETON_OBJECT";
        if (node.declarationCategory === "INTERFACE") return "INTERFACE";
        if (node.declarationCategory === "ABSTRACT") return "ABSTRACT_CLASS";
        if (node.declarationCategory === "CONCRETE") return "CONCRETE_CLASS";
        if (node.declarationCategory === "ENUM") return "ENUM";
        return "OTHER";
    }

    function semanticBadgeLabel(node) {
        var semantic = declarationSemantic(node);
        if (semantic === "INTERFACE") return "interface";
        if (semantic === "ABSTRACT_CLASS") return "abstract";
        if (semantic === "CONCRETE_CLASS") return String(node.kind || "").toLowerCase().includes("data") ?
            "data" : "concrete";
        if (semantic === "SINGLETON_OBJECT") return "object";
        if (semantic === "ENUM") return "enum";
        return "declaration";
    }

    function edgeClass(edge) {
        var classes = "srcx-dashboard__architecture-svg-edge";
        if (edge.crossBuild) classes += " is-cross-build";
        if (edge.cycleEdge) classes += " is-cycle-edge";
        if (isAnalysisCycleEdge(edge)) classes += " is-analysis-cycle-edge";
        var evidence = edge.evidenceCounts || [];
        if (edge.evidence === "HEURISTIC" || evidence.some(function (item) { return item.evidence === "HEURISTIC"; })) {
            classes += " is-heuristic";
        }
        var category = edge.displayRelationshipCategory || relationshipCategory(edge);
        if (category) classes += " is-kind-" + category;
        return classes;
    }

    function nodeLabel(node) {
        return node ? (node.name || node.qualifiedName || node.id) : "Unknown";
    }

    function nodeTitle(node) {
        var details = [
            accessibleNodeName(node),
            "fill shows " + node.build + " ownership",
            "size ranks this node " + node.visualRank + " of " + node.visualPopulation +
                " in the current frame using relationship volume and review signals",
            nodeRelationshipRecordCount(node) + " relationship records contribute to that size",
        ];
        if (node.entityType !== "file" && declarationSemantic(node) !== "OTHER") {
            details.push("colored square before the name shows " + semanticBadgeLabel(node) + " declaration type");
        }
        if (node.important) details.push("dark solid ring shows a report-priority / ranking signal");
        if (node.fileFindingCount > 0) details.push("red solid ring shows exact file findings");
        if (node.hasCycle) details.push("red dashed ring shows observed file-cycle membership");
        if (node.hasAnalysisCycle) {
            details.push("violet dotted ring shows analyzer-inferred component-cycle participation");
        }
        if (node.hasAnalyzerFinding) details.push("analyzer finding identifies this component");
        if (node.sourceFileFindingCount > 0) {
            details.push(
                "yellow diamond shows " + node.sourceFileFindingCount + " exact " +
                    (node.sourceFileFindingCount === 1 ? "finding" : "findings") +
                    " on the declaring file, not necessarily this symbol",
            );
        }
        if (node.entityType !== "file") details.push(node.kind + " at " + node.file + ":" + node.line);
        return details.join("; ");
    }

    function mapSignalFact(node) {
        var tier = String(node.visualTier || "low");
        return tier.charAt(0).toUpperCase() + tier.slice(1) + " signal / rank " + node.visualRank + " of " +
            node.visualPopulation + " in this frame / " + nodeRelationshipRecordCount(node) +
            " relationship records";
    }

    function endpointId(endpoint) {
        return typeof endpoint === "string" ? endpoint : endpoint.id;
    }

    function searchable(node) {
        var symbols = (node.symbols || []).map(function (symbol) { return symbol.name + " " + symbol.qualifiedName; }).join(" ");
        return [node.name, node.path, node.qualifiedName, node.file, node.build, node.project, symbols].filter(Boolean).join(" ");
    }

    function countLabel(item) {
        return (item.label || item.evidence || item.kind) + " x" + item.count;
    }

    function compactEdgeLabel(edge) {
        if (edge.kindCounts) return edge.kindCounts.map(countLabel).join(", ");
        return edge.label + " / " + edge.evidence.toLowerCase();
    }

    function representedBuilds(nodes, buildByName) {
        return Array.from(d3.group(nodes, function (node) { return node.build; }), function (entry) {
            var build = buildByName.get(entry[0]);
            if (!build) return null;
            var entityType = entry[1].every(function (node) { return node.entityType === "file"; }) ? "file" :
                entry[1].every(function (node) { return node.entityType === "symbol"; }) ? "symbol" : "node";
            return Object.assign({}, build, {
                visibleNodeCount: entry[1].length,
                visibleEntityType: entityType,
            });
        }).filter(Boolean).sort(function (left, right) { return left.name.localeCompare(right.name); });
    }

    function representedProjects(nodes) {
        return Array.from(d3.group(nodes, function (node) {
            return projectKey(node.build, node.project);
        }), function (entry) {
            var members = entry[1];
            var node = members[0];
            var entityType = members.every(function (candidate) { return candidate.entityType === "file"; }) ?
                "file" : members.every(function (candidate) { return candidate.entityType === "symbol"; }) ?
                    "symbol" : "node";
            return {
                key: entry[0],
                build: node.build,
                project: node.project,
                visibleNodeCount: members.length,
                visibleEntityType: entityType,
                flaggedCount: members.filter(function (candidate) {
                    return nodeReviewPriority(candidate) > 0;
                }).length,
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
    }

    function updateProjectRegions(projectGroups, projectCells, nodes) {
        if (!projectGroups) return;
        projectGroups.each(function (project) {
            var cell = projectCells.get(project.key);
            if (!cell) return;
            var group = d3.select(this);
            group.select(".srcx-dashboard__architecture-project-region-body")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", Math.max(1, cell.maxY - cell.minY));
            group.select("text").attr("x", cell.minX + 10).attr("y", cell.minY + 18);
            group.select(".srcx-dashboard__architecture-project-drag-handle")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", 28);
        });
    }

    function renderSubgroupRegions(layer, subgroups, subgroupCells) {
        var groups = layer.selectAll("g")
            .data(subgroups, function (subgroup) { return subgroup.key; })
            .join("g")
            .attr("class", "srcx-dashboard__architecture-subgroup-region");
        groups.append("rect");
        groups.append("text").text(subgroupRegionLabel);
        updateSubgroupRegions(groups, subgroupCells);
        return groups;
    }

    function updateSubgroupRegions(groups, subgroupCells) {
        if (!groups) return;
        groups.each(function (subgroup) {
            var cell = subgroupCells.get(subgroup.key);
            if (!cell) return;
            var group = d3.select(this);
            group.select("rect")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", Math.max(1, cell.maxY - cell.minY));
            group.select("text").attr("x", cell.minX + 8).attr("y", cell.minY + 17);
        });
    }

    function subgroupRegionLabel(subgroup) {
        var noun = subgroup.members.length === 1 ? subgroup.entityType : subgroup.entityType + "s";
        var flagged = subgroup.flaggedCount ? " / " + subgroup.flaggedCount + " flagged" : "";
        return subgroup.label + " / " + subgroup.members.length + " " + noun + flagged;
    }

    function projectRegionLabel(project) {
        var noun = project.visibleNodeCount === 1 ? project.visibleEntityType : project.visibleEntityType + "s";
        var flagged = project.flaggedCount ? " / " + project.flaggedCount + " flagged" : "";
        return projectDisplayName(project.project, project.build) + " / " + project.visibleNodeCount + " " + noun +
            flagged;
    }

    function assignLayoutDegrees(nodes, links) {
        var degrees = new Map(nodes.map(function (node) { return [node.id, 0]; }));
        links.forEach(function (edge) {
            var source = endpointId(edge.source);
            var target = endpointId(edge.target);
            degrees.set(source, (degrees.get(source) || 0) + 1);
            degrees.set(target, (degrees.get(target) || 0) + 1);
        });
        nodes.forEach(function (node) { node.layoutDegree = degrees.get(node.id) || 0; });
    }

    function homeForceStrength(node) {
        return node.layoutDegree === 0 ? 0.92 : node.layoutDegree === 1 ? 0.48 : 0.26;
    }

    function updateBuildRegions(nodes, buildGroups, cells) {
        if (!buildGroups) return;
        buildGroups.each(function (build) {
            var cell = cells.get(build.name);
            if (!cell) return;
            var headingHeight = build.labelHeight || 10;
            var group = d3.select(this);
            group.select(".srcx-dashboard__architecture-build-region-body")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", Math.max(1, cell.maxY - cell.minY));
            group.select("text")
                .attr("x", cell.minX + 10)
                .attr("y", cell.minY + headingHeight + 10);
            group.select(".srcx-dashboard__architecture-build-drag-handle")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", 40);
        });
    }

    function appendBuildRegionLabel(text, build) {
        text.append("tspan").text(build.name);
        var count = Number.isInteger(build.visibleNodeCount) ? build.visibleNodeCount : build.fileNodeCount;
        var entityType = build.visibleEntityType || "file";
        var noun = count === 1 ? entityType : entityType + "s";
        var context = build.context + " / " + count + " " + noun + " in this frame";
        text.append("tspan").attr("dx", 7).attr("class", "srcx-dashboard__architecture-build-context")
            .text(context);
    }

    function appendNodeLabel(text, node) {
        var lines = wrappedNodeLabel(node);
        lines.forEach(function (line, index) {
            var semantic = declarationSemantic(node);
            if (index === 0 && node.entityType !== "file" && semantic !== "OTHER") {
                text.append("tspan")
                    .attr("x", 0)
                    .attr("dy", 0)
                    .attr("aria-hidden", "true")
                    .attr("class", "srcx-dashboard__architecture-semantic-marker is-" +
                        semantic.toLowerCase().replace(/_/g, "-"))
                    .text("■");
                text.append("tspan").attr("dx", 4).text(line);
            } else {
                text.append("tspan").attr("x", 0).attr("dy", index === 0 ? 0 : 10).text(line);
            }
        });
    }

    function wrappedNodeLabel(node) {
        return wrapVisibleLabel(nodeLabel(node), node.entityType === "file" ? 20 : 22);
    }

    function wrapVisibleLabel(value, limit) {
        var parts = semanticLabelParts(value);
        if (!parts.length) return [value];
        var lines = [];
        var line = "";
        parts.forEach(function (part) {
            if (!line || line.length + part.length <= limit) {
                line += part;
                return;
            }
            lines.push(line);
            line = part;
        });
        if (line) lines.push(line);
        return lines;
    }

    function semanticLabelParts(value) {
        return String(value)
            .replace(/([a-z0-9])([A-Z])/g, "$1\u0000$2")
            .replace(/([/_.-]+)/g, "$1\u0000")
            .split("\u0000")
            .filter(function (part) { return part.length > 0; });
    }

    function buildColor(buildName, buildByName) {
        var build = buildByName.get(buildName);
        return build ? build.color : "#a8b0b7";
    }

    function edgeRecordCount(edge) {
        if (Number.isInteger(edge.recordCount) && edge.recordCount >= 0) return edge.recordCount;
        return Array.isArray(edge.occurrences) ? edge.occurrences.length : 0;
    }

    function edgeRecordCountText(edge) {
        var count = edgeRecordCount(edge);
        return count + " " + (count === 1 ? "record" : "records");
    }

    function edgeRecordCountLabel(edge) {
        var count = edgeRecordCount(edge);
        return count + " displayed relationship " + (count === 1 ? "record" : "records");
    }

    function isAnalysisCycleEdge(edge) {
        return Boolean(edge && edge.entityType === "analysis-cycle-edge");
    }

    function accessibleNodeName(node) {
        if (!node) return "Unknown";
        if (node.entityType === "file" || node.path) {
            return node.name + " / " + node.build + " / " + node.project + " / " + node.sourceSet + " / " + node.path;
        }
        return node.qualifiedName + " / " + node.build + " / " + node.project + " / " + node.file + ":" + node.line;
    }

    function scopeLabel(node) {
        return node.build + " / " + node.project + " / " + node.sourceSet + " / " + node.path;
    }

    function scopePathKey(build, project, sourceSet, path) {
        return [build, project, sourceSet, path].join("\u0000");
    }

    function normalizeBoundedPath(path) {
        return String(path || "").replace(/\\/g, "/").replace(/^\.\/+/, "").replace(/\/+/g, "/");
    }

    function boundedPathLeafKey(build, project, sourceSet, path) {
        return scopePathKey(build, project, sourceSet, normalizeBoundedPath(path));
    }

    function pathHasDirectoryPrefix(candidate, directory) {
        var path = normalizeBoundedPath(candidate);
        var prefix = normalizeBoundedPath(directory);
        return !prefix || path === prefix || path.startsWith(prefix + "/");
    }

    function pathIsDirectoryDescendant(candidate, directory) {
        var path = normalizeBoundedPath(candidate);
        var prefix = normalizeBoundedPath(directory);
        return !prefix || path.startsWith(prefix + "/");
    }

    function entityProjectRelativePath(node) {
        if (!node) return null;
        return normalizeBoundedPath(node.entityType === "file" || node.path ? node.path : node.file);
    }

    function declarationIndex(data) {
        var index = new Map();
        data.availableFileNodes.forEach(function (node) {
            (node.symbols || []).forEach(function (symbol) {
                index.set(symbol.id, Object.assign({}, symbol, {
                    build: node.build,
                    project: node.project,
                    sourceSet: node.sourceSet,
                    file: node.path,
                }));
            });
        });
        data.availableNodes.forEach(function (symbol) { index.set(symbol.id, symbol); });
        return index;
    }

    function declarationIdentity(declaration, fallbackId) {
        if (!declaration) return fallbackId || "Unknown declaration";
        return declaration.kind + " " + declaration.qualifiedName + " at " + declaration.file + ":" +
            declaration.line + " / " + declaration.build + " / " + declaration.project + " / " +
            declaration.sourceSet;
    }

    function linkDistance(edge) {
        if (isAnalysisCycleEdge(edge)) return 155;
        return edge.crossBuild ? 190 : 115;
    }

    function graphHeight() {
        return Math.max(420, Math.min(600, Math.round((window.innerHeight || 800) * 0.58)));
    }

    function wireFindingFilters() {
        document.querySelectorAll(".srcx-dashboard").forEach(function (dashboard) {
            var controls = dashboard.querySelector("[data-srcx-finding-controls]");
            if (!controls) return;
            var filters = ["build", "project", "severity"];
            var state = { build: "all", project: "all", severity: "all" };
            var reset = controls.querySelector("[data-srcx-finding-filter-reset]");
            var status = controls.querySelector("[data-srcx-finding-filter-status]");
            var buttonsByFilter = new Map(filters.map(function (filter) {
                return [filter, Array.from(controls.querySelectorAll(
                    "[data-srcx-finding-filter='" + filter + "']",
                ))];
            }));
            if (!reset || !status || filters.some(function (filter) { return !buttonsByFilter.get(filter).length; })) {
                return;
            }
            buttonsByFilter.forEach(function (buttons, filter) {
                var selected = buttons.find(function (button) {
                    return button.getAttribute("aria-pressed") === "true";
                }) || buttons[0];
                updateToolbarTabStop(buttons, selected);
                buttons.forEach(function (button) {
                    button.addEventListener("click", function () {
                        state[filter] = button.dataset.srcxFindingFilterValue;
                        if (filter === "build") syncAvailableProjects();
                        syncFindingFilterButtons();
                        applyFindingFilters();
                    });
                    button.addEventListener("keydown", function (event) {
                        moveToolbarFocus(event, availableFilterButtons(filter), button);
                    });
                });
            });
            reset.addEventListener("click", function () {
                state = { build: "all", project: "all", severity: "all" };
                syncAvailableProjects();
                syncFindingFilterButtons();
                applyFindingFilters();
            });
            syncAvailableProjects();
            syncFindingFilterButtons();
            applyFindingFilters();

            function availableFilterButtons(filter) {
                return buttonsByFilter.get(filter).filter(function (button) { return !button.hidden; });
            }

            function syncAvailableProjects() {
                var projectButtons = buttonsByFilter.get("project");
                projectButtons.forEach(function (button) {
                    var build = button.dataset.srcxFindingFilterBuild;
                    button.hidden = Boolean(build && state.build !== "all" && build !== state.build);
                });
                var selectedProject = projectButtons.find(function (button) {
                    return !button.hidden && button.dataset.srcxFindingFilterValue === state.project &&
                        (!button.dataset.srcxFindingFilterBuild || state.build === "all" ||
                            button.dataset.srcxFindingFilterBuild === state.build);
                });
                if (!selectedProject) state.project = "all";
            }

            function syncFindingFilterButtons() {
                buttonsByFilter.forEach(function (buttons, filter) {
                    var target = buttons.find(function (button) {
                        return !button.hidden && button.dataset.srcxFindingFilterValue === state[filter] &&
                            (filter !== "project" || state.build === "all" ||
                                !button.dataset.srcxFindingFilterBuild ||
                                button.dataset.srcxFindingFilterBuild === state.build);
                    }) || buttons.find(function (button) {
                        return !button.hidden && button.dataset.srcxFindingFilterValue === "all";
                    });
                    buttons.forEach(function (button) {
                        button.setAttribute("aria-pressed", String(button === target));
                    });
                    updateToolbarTabStop(availableFilterButtons(filter), target);
                });
                reset.disabled = filters.every(function (filter) { return state[filter] === "all"; });
            }

            function applyFindingFilters() {
                var visible = 0;
                dashboard.querySelectorAll("[data-srcx-finding-scope]").forEach(function (scope) {
                    var scopeMatches = (state.build === "all" || scope.dataset.srcxFindingBuild === state.build) &&
                        (state.project === "all" || scope.dataset.srcxFindingProject === state.project);
                    var scopeCount = 0;
                    scope.querySelectorAll("[data-srcx-finding-row]").forEach(function (finding) {
                        var matches = scopeMatches && (state.severity === "all" ||
                            finding.dataset.srcxFindingSeverity === state.severity);
                        finding.hidden = !matches;
                        if (matches) scopeCount += 1;
                    });
                    scope.hidden = scopeCount === 0;
                    visible += scopeCount;
                });
                status.textContent = visible + (visible === 1 ? " finding shown" : " findings shown");
            }
        });
    }

    function wireArchitectureEvidenceActions() {
        document.querySelectorAll(".srcx-dashboard").forEach(function (dashboard) {
            dashboard.addEventListener("click", function (event) {
                var action = event.target.closest && event.target.closest("[data-srcx-open-finding]");
                if (!action || !dashboard.contains(action)) return;
                var findingId = action.dataset.srcxFindingId;
                if (!findingId) return;
                var atlas = dashboard.querySelector("[data-srcx-architecture-graph]");
                if (!atlas) return;
                var opened = typeof atlas.srcxOpenArchitectureEvidence === "function" &&
                    atlas.srcxOpenArchitectureEvidence({ findingId: findingId }, action);
                if (opened) {
                    atlas.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth", block: "start" });
                } else {
                    scrollAndFocus(atlas);
                }
            });
        });
    }

    function sourceSetOrder(left, right) {
        var preferred = ["main", "test", "slopTest", "androidMain", "androidTest"];
        var leftIndex = preferred.indexOf(left);
        var rightIndex = preferred.indexOf(right);
        if (leftIndex < 0) leftIndex = preferred.length;
        if (rightIndex < 0) rightIndex = preferred.length;
        return leftIndex - rightIndex || left.localeCompare(right);
    }

    function moveToolbarFocus(event, buttons, current) {
        var direction = event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 :
            event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 0;
        var targetIndex = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 :
            direction ? buttons.indexOf(current) + direction : null;
        if (targetIndex === null) return;
        event.preventDefault();
        var target = buttons[(targetIndex + buttons.length) % buttons.length];
        buttons.forEach(function (button) { button.tabIndex = button === target ? 0 : -1; });
        target.focus({ preventScroll: true });
        target.scrollIntoView({
            behavior: reducedMotion() ? "auto" : "smooth",
            block: "nearest",
            inline: "nearest",
        });
    }

    function updateToolbarTabStop(buttons, target) {
        if (!target) return;
        buttons.forEach(function (button) { button.tabIndex = button === target ? 0 : -1; });
    }

    function scrollAndFocus(target) {
        target.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth", block: "start" });
        window.setTimeout(function () {
            if (typeof target.focus === "function") target.focus({ preventScroll: true });
        }, reducedMotion() ? 0 : 220);
    }

    function wireDocumentNavigation() {
        document.querySelectorAll(".srcx-dashboard").forEach(function (dashboard) {
            if (dashboard.dataset.srcxSmoothNavigation === "true") return;
            dashboard.dataset.srcxSmoothNavigation = "true";
            dashboard.querySelectorAll("a[href^='#']").forEach(function (link) {
                link.addEventListener("click", function (event) {
                    var hash = link.getAttribute("href");
                    if (!hash || hash.charAt(0) !== "#") return;
                    var targetId = hash.slice(1);
                    var target = Array.from(dashboard.querySelectorAll("[id]")).find(function (candidate) {
                        return candidate.id === targetId;
                    });
                    if (!target) return;
                    event.preventDefault();
                    target.scrollIntoView({
                        behavior: reducedMotion() ? "auto" : "smooth",
                        block: "start",
                    });
                    if (window.history && typeof window.history.pushState === "function") {
                        window.history.pushState(null, "", hash);
                    } else if (window.location.hash !== hash) {
                        window.location.hash = hash;
                    }
                });
            });
        });
    }

    function reducedMotion() {
        return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    }

})();
