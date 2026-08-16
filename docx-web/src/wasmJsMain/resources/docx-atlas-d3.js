(function installDocxAtlasD3(global) {
    "use strict";

    var SVG_NS = "http://www.w3.org/2000/svg";
    var MIN_SCALE = 0.05;
    var MAX_SCALE = 4;
    var STATIC_LAYOUT_NODE_THRESHOLD = 240;
    var states = new WeakMap();

    function requireD3() {
        var d3 = global.d3;
        if (!d3 || typeof d3.select !== "function" || typeof d3.forceSimulation !== "function") {
            throw new Error("DOCX Atlas requires the bundled D3 7.9 renderer");
        }
        var version = String(d3.version || "7.9.0");
        if (version !== "7.9.0") {
            throw new Error("DOCX Atlas requires D3 7.9; found " + version);
        }
        return d3;
    }

    function requireHost(host) {
        if (!host || host.nodeType !== 1) throw new TypeError("DOCX Atlas host must be a DOM element");
        return host;
    }

    function parseFrame(frameJson) {
        if (typeof frameJson !== "string") throw new TypeError("AtlasFrame must be kotlinx-serialized JSON text");
        var frame = JSON.parse(frameJson);
        if (!frame || frame.schemaVersion !== 1 || typeof frame.frameId !== "string") {
            throw new Error("Unsupported AtlasFrame envelope");
        }
        if (!Array.isArray(frame.nodes) || !Array.isArray(frame.edges) || !Array.isArray(frame.builds)) {
            throw new Error("AtlasFrame graph collections are missing");
        }
        var nodeIds = new Set();
        frame.nodes.forEach(function validateNode(node) {
            if (!node || typeof node.id !== "string" || nodeIds.has(node.id)) {
                throw new Error("AtlasFrame nodes require unique IDs");
            }
            nodeIds.add(node.id);
        });
        var edgeIds = new Set();
        frame.edges.forEach(function validateEdge(edge) {
            if (!edge || typeof edge.id !== "string" || edgeIds.has(edge.id)) {
                throw new Error("AtlasFrame edges require unique IDs");
            }
            if (!nodeIds.has(edge.sourceId) || !nodeIds.has(edge.targetId)) {
                throw new Error("AtlasFrame edge endpoint closure is incomplete");
            }
            edgeIds.add(edge.id);
        });
        frame.edges = frame.edges.filter(function withoutImports(edge) {
            var counts = Array.isArray(edge.kindCounts) ? edge.kindCounts : [];
            return String(edge.category || "").toLowerCase() !== "imports" &&
                !counts.some(function (count) { return String(count.key).toUpperCase() === "IMPORT"; });
        });
        return frame;
    }

    function mount(host, frameJson) {
        host = requireHost(host);
        if (states.has(host)) destroy(host);
        var d3 = requireD3();
        var frame = parseFrame(frameJson);
        var graphRoot = host.closest(".srcx-dashboard__architecture-graph") || host;
        var svgElement = host.querySelector(
            "[data-docx-atlas-svg], [data-srcx-graph-svg], svg.srcx-dashboard__architecture-svg",
        );
        var createdSvg = false;
        if (!svgElement) {
            svgElement = global.document.createElementNS(SVG_NS, "svg");
            host.appendChild(svgElement);
            createdSvg = true;
        }
        svgElement.classList.add("srcx-dashboard__architecture-svg");
        svgElement.setAttribute("data-docx-atlas-svg", "");
        svgElement.setAttribute("data-srcx-graph-svg", "");
        svgElement.removeAttribute("hidden");
        svgElement.setAttribute("role", "group");
        svgElement.setAttribute(
            "aria-label",
            "Interactive workspace relationship map. Tab focuses scopes and Enter selects them; " +
                "arrow keys move between nodes; R focuses an incident relationship.",
        );
        var state = {
            host: host,
            graphRoot: graphRoot,
            svgElement: svgElement,
            svg: d3.select(svgElement),
            d3: d3,
            d3Version: String(d3.version || "7.9.0"),
            frame: frame,
            createdSvg: createdSvg,
            destroyed: false,
            mode: "normal",
            viewportMode: "fit",
            userNavigated: false,
            selectedNodeIds: new Set(frame.selectedNodeIds || []),
            selectedEdgeId: frame.selectedEdgeId || null,
            selectedScope: null,
            hoveredNodeId: null,
            hoveredEdgeId: null,
            manualPositions: new Map(),
            manualBuildOffsets: new Map(),
            manualProjectOffsets: new Map(),
            transform: d3.zoomIdentity,
            resizeObserver: null,
            resizeFrame: null,
            suppressScopeClickUntil: 0,
            visibleEdgeIds: new Set(),
            lassoCleanup: null,
        };
        states.set(host, state);
        installZoom(state);
        draw(state, false);
        installResizeObserver(state);
        syncHost(state, "ready");
        emit(host, "docx-atlas-ready", snapshotValue(state));
        return host;
    }

    function update(host, frameJson) {
        var state = requireState(host);
        var previousScope = frameScopeKey(state.frame);
        var oldPositions = new Map(state.nodes.map(function (node) {
            return [node.id, { x: node.x, y: node.y, fx: node.fx, fy: node.fy }];
        }));
        state.frame = parseFrame(frameJson);
        var preserveViewport = previousScope === frameScopeKey(state.frame);
        if (!preserveViewport) {
            state.userNavigated = false;
            state.transform = state.d3.zoomIdentity;
            state.selectedScope = null;
        }
        state.selectedNodeIds = new Set(state.frame.selectedNodeIds || []);
        state.selectedEdgeId = state.frame.selectedEdgeId || null;
        if (state.selectedNodeIds.size || state.selectedEdgeId) state.selectedScope = null;
        state.hoveredNodeId = null;
        state.hoveredEdgeId = null;
        state.previousPositions = oldPositions;
        draw(state, preserveViewport);
        state.previousPositions = null;
        syncHost(state, "ready");
        emit(state.host, "docx-atlas-ready", snapshotValue(state));
        return host;
    }

    function command(host, action) {
        var state = requireState(host);
        action = String(action || "");
        if (action === "zoom-in") zoomBy(state, 1.25);
        else if (action === "zoom-out") zoomBy(state, 0.8);
        else if (action === "fit") fit(state, true);
        else if (action === "reset") reset(state);
        else if (action === "clear-selection") clearSelection(state, true);
        else if (action.indexOf("pan:") === 0) pan(state, action.substring(4));
        else if (action.indexOf("select-node:") === 0) selectNodeById(state, action.substring(12), true);
        else if (action.indexOf("select-edge:") === 0) selectEdgeById(state, action.substring(12), true);
        else if (action.indexOf("select-scope:") === 0) selectScopeByCommand(state, action.substring(13), true);
        else throw new Error("Unknown DOCX Atlas command: " + action);
        syncHost(state, "ready");
        return snapshot(host);
    }

    function snapshot(host) {
        return JSON.stringify(snapshotValue(requireState(host)));
    }

    function destroy(host) {
        host = requireHost(host);
        var state = states.get(host);
        if (!state) {
            host.setAttribute("data-docx-atlas-state", "destroyed");
            return host;
        }
        state.destroyed = true;
        if (state.simulation) state.simulation.stop();
        if (state.resizeObserver) state.resizeObserver.disconnect();
        if (state.resizeFrame !== null && global.cancelAnimationFrame) {
            global.cancelAnimationFrame(state.resizeFrame);
        }
        if (state.lassoCleanup) state.lassoCleanup();
        state.graphRoot.classList.remove("is-box-selecting");
        state.svg.on(".zoom", null).on("click", null);
        state.svg.selectAll("*").remove();
        if (state.createdSvg && state.svgElement.parentNode === host) state.svgElement.remove();
        states.delete(host);
        host.setAttribute("data-docx-atlas-state", "destroyed");
        host.setAttribute("data-docx-atlas-node-count", "0");
        host.setAttribute("data-docx-atlas-edge-count", "0");
        return host;
    }

    function requireState(host) {
        host = requireHost(host);
        var state = states.get(host);
        if (!state || state.destroyed) throw new Error("DOCX Atlas host is not mounted");
        return state;
    }

    function installZoom(state) {
        state.zoom = state.d3.zoom()
            .scaleExtent([MIN_SCALE, MAX_SCALE])
            .filter(function zoomFilter(event) {
                return !event.shiftKey && event.button !== 2;
            })
            .on("start", function zoomStarted(event) {
                if (event.sourceEvent) {
                    state.mode = "panning";
                    state.viewportMode = "manual";
                }
                syncHost(state, "ready");
            })
            .on("zoom", function zoomed(event) {
                state.transform = event.transform;
                if (state.zoomLayer) state.zoomLayer.attr("transform", event.transform);
                if (event.sourceEvent) state.userNavigated = true;
                syncHost(state, "ready");
                emitViewport(state);
            })
            .on("end", function zoomEnded() {
                if (state.mode === "panning") state.mode = "normal";
                syncHost(state, "ready");
                emitViewport(state);
            });
        state.svg.call(state.zoom).on("dblclick.zoom", null);
    }

    function installResizeObserver(state) {
        if (!global.ResizeObserver) return;
        state.resizeObserver = new global.ResizeObserver(function resized() {
            if (state.destroyed || state.resizeFrame !== null) return;
            state.resizeFrame = global.requestAnimationFrame(function relayoutAfterResize() {
                state.resizeFrame = null;
                if (state.destroyed) return;
                var previousWidth = state.width;
                var previousHeight = state.height;
                updateViewportSize(state);
                if (previousWidth === state.width && previousHeight === state.height) return;
                draw(state, state.userNavigated);
            });
        });
        state.resizeObserver.observe(state.host);
    }

    function updateViewportSize(state) {
        var bounds = state.host.getBoundingClientRect();
        state.width = Math.max(320, Math.round(state.host.clientWidth || bounds.width || 1200));
        state.height = Math.max(360, Math.round(state.host.clientHeight || bounds.height || 640));
        state.svg.attr("viewBox", "0 0 " + state.width + " " + state.height);
    }

    function draw(state, preserveTransform) {
        if (state.simulation) state.simulation.stop();
        updateViewportSize(state);
        state.svg.selectAll("*").remove();
        if (state.lassoCleanup) state.lassoCleanup();
        state.lassoCleanup = null;
        state.graphRoot.classList.remove("is-box-selecting");
        state.nodes = state.frame.nodes.map(function copyNode(node) { return Object.assign({}, node); });
        assignVisualTiers(state.nodes);
        state.nodeById = new Map(state.nodes.map(function (node) { return [node.id, node]; }));
        state.edges = state.frame.edges.map(function copyEdge(edge) {
            return Object.assign({}, edge, { source: edge.sourceId, target: edge.targetId });
        });
        state.edgeById = new Map(state.edges.map(function (edge) { return [edge.id, edge]; }));
        state.buildById = new Map(state.frame.builds.map(function (build) { return [build.id, build]; }));
        setCanonicalGraphState(state);
        if (!state.nodes.length) {
            state.svg.append("text")
                .attr("class", "srcx-dashboard__architecture-empty-label")
                .attr("x", state.width / 2)
                .attr("y", state.height / 2)
                .attr("text-anchor", "middle")
                .text("No graph records match this frame.");
            state.transform = state.d3.zoomIdentity;
            syncHost(state, "ready");
            return;
        }
        appendArrowMarker(state);
        state.zoomLayer = state.svg.append("g").attr("class", "srcx-dashboard__architecture-zoom-layer");
        measureNodeLabels(state);
        measureBuildLabels(state);
        state.layout = layoutFrame(state);
        applyManualScopeOffsets(state);
        appendRegions(state);
        appendScopeRelationshipLayer(state);
        appendEdges(state);
        appendNodes(state);
        installLasso(state);
        initializeNodePositions(state);
        runDeterministicSimulation(state);
        ticked(state);
        restoreSelectionHighlight(state);
        if (preserveTransform) {
            state.svg.call(state.zoom.transform, state.transform);
        } else {
            fit(state, false);
        }
        state.svg.on("click", function canvasClicked(event) {
            if (Date.now() < state.suppressScopeClickUntil || event.defaultPrevented) return;
            clearSelection(state, true);
        });
        syncHost(state, "ready");
    }

    function setCanonicalGraphState(state) {
        var lens = String(state.frame.lens || "files").toLowerCase();
        [state.graphRoot, state.host].forEach(function setDataset(element) {
            element.setAttribute("data-srcx-graph-lens", lens);
            element.setAttribute("data-srcx-graph-density", state.nodes.length > 36 ? "dense" : "normal");
            element.setAttribute("data-srcx-graph-focus", "full");
            element.setAttribute("data-srcx-empty", state.nodes.length ? "false" : "true");
        });
    }

    function appendArrowMarker(state) {
        var markerId = "docx-atlas-arrow-" + safeDomId(state.frame.frameId);
        state.markerId = markerId;
        state.svg.append("defs").append("marker")
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
    }

    function measureNodeLabels(state) {
        var layer = state.zoomLayer.append("g")
            .attr("class", "srcx-dashboard__architecture-node-layer")
            .attr("aria-hidden", "true")
            .style("visibility", "hidden")
            .style("pointer-events", "none");
        var groups = layer.selectAll("g").data(state.nodes, function (node) { return node.id; })
            .join("g")
            .attr("class", nodeClass)
            .classed("is-label-pinned", function (node) { return shouldPinLabel(node); });
        groups.append("text")
            .attr("class", "srcx-dashboard__architecture-svg-node-name srcx-dashboard__architecture-svg-node-label")
            .each(function appendMeasuredLabel(node) { appendNodeLabel(state.d3.select(this), node); });
        groups.select("text").each(function recordLabelBounds(node) {
            var bounds = safeSvgBounds(this);
            node.labelPinned = shouldPinLabel(node);
            node.labelBounds = {
                width: bounds ? bounds.width : estimatedTextWidth(nodeLabel(node)),
                height: bounds ? bounds.height : 10,
            };
        });
        layer.remove();
    }

    function measureBuildLabels(state) {
        state.buildLabelWidths = new Map();
        var layer = state.zoomLayer.append("g")
            .attr("class", "srcx-dashboard__architecture-build-regions")
            .attr("aria-hidden", "true")
            .style("visibility", "hidden")
            .style("pointer-events", "none");
        var groups = layer.selectAll("g").data(state.frame.builds, function (build) { return build.id; })
            .join("g")
            .attr("class", "srcx-dashboard__architecture-build-region");
        groups.append("text").each(function appendMeasuredBuildLabel(build) {
            var text = state.d3.select(this);
            var count = state.nodes.filter(function (node) { return nodeBuildId(node) === build.id; }).length;
            text.append("tspan").text(build.name);
            text.append("tspan").attr("dx", 7)
                .attr("class", "srcx-dashboard__architecture-build-context")
                .text(build.context + " / " + count + (count === 1 ? " node" : " nodes") + " in this frame");
        });
        groups.select("text").each(function recordBuildLabelWidth(build) {
            var bounds = safeSvgBounds(this);
            var fallback = String(build.name) + " " + String(build.context);
            state.buildLabelWidths.set(build.id, bounds ? bounds.width : estimatedTextWidth(fallback));
        });
        layer.remove();
    }

    function layoutFrame(state) {
        var viewportAspect = Math.max(0.75, state.width / Math.max(1, state.height));
        var projects = Array.from(state.d3.group(state.nodes, function (node) {
            return nodeProjectKey(node);
        }), function buildProject(entry) {
            var members = entry[1].slice().sort(byId);
            var buildId = nodeBuildId(members[0]);
            var projectId = nodeProjectId(members[0]);
            var nodeRectangles = members.map(function nodeRectangle(node) {
                var bounds = labelBounds(node);
                var labelWidth = node.labelPinned ? bounds.width + 10 : 0;
                var labelHeight = node.labelPinned ? bounds.height : 0;
                return {
                    key: node.id,
                    width: outerNodeRadius(node) * 2 + labelWidth + 24,
                    height: Math.max(outerNodeRadius(node) * 2, labelHeight) + 24,
                    nodeRadius: outerNodeRadius(node),
                };
            }).sort(function largestFirst(left, right) {
                return right.width * right.height - left.width * left.height || left.key.localeCompare(right.key);
            });
            var rectangleByNode = new Map(nodeRectangles.map(function indexRectangle(rectangleValue) {
                return [rectangleValue.key, rectangleValue];
            }));
            var subgroups = Array.from(groupSorted(members, function subgroupName(node) {
                return node.sourceSet || node.type || "nodes";
            }), function buildSubgroup(subgroupEntry) {
                var label = String(subgroupEntry[0]);
                var subgroupMembers = subgroupEntry[1];
                var rectangles = subgroupMembers.map(function rectangleForNode(node) {
                    return rectangleByNode.get(node.id);
                });
                var nodePack = packVariableRectangles(rectangles, 1.35, 14);
                var summary = label + " / " + subgroupMembers.length +
                    (subgroupMembers.length === 1 ? " node" : " nodes");
                return {
                    key: entry[0] + "\u0000" + label,
                    label: label,
                    members: subgroupMembers,
                    buildId: buildId,
                    projectId: projectId,
                    nodePack: nodePack,
                    width: Math.max(132, estimatedTextWidth(summary) + 28, nodePack.width + 28),
                    height: Math.max(86, nodePack.height + 46),
                };
            }).sort(function subgroupOrder(left, right) { return left.key.localeCompare(right.key); });
            var subgroupPack = packVariableRectangles(subgroups, 1.35, 14);
            var projectLabel = String(members[0].projectPath || members[0].projectId || "build-owned nodes") +
                " / " + members.length + (members.length === 1 ? " node" : " nodes");
            return {
                key: entry[0],
                buildId: buildId,
                projectId: projectId,
                members: members,
                nodeRectangles: rectangleByNode,
                subgroups: subgroups,
                subgroupPack: subgroupPack,
                width: Math.max(190, estimatedTextWidth(projectLabel) + 28, subgroupPack.width + 36),
                height: Math.max(130, subgroupPack.height + 60),
            };
        }).sort(function projectOrder(left, right) { return left.key.localeCompare(right.key); });
        var projectsByBuild = state.d3.group(projects, function (project) { return project.buildId; });
        var buildRectangles = Array.from(projectsByBuild, function buildRectangle(entry) {
            var buildId = entry[0];
            var projectPack = packVariableRectangles(entry[1], 1.35, 24);
            return {
                key: buildId,
                buildId: buildId,
                projects: entry[1],
                projectPack: projectPack,
                width: Math.max(260, (state.buildLabelWidths.get(buildId) || estimatedTextWidth(buildId)) + 54,
                    projectPack.width + 48),
                height: Math.max(190, projectPack.height + 82),
            };
        }).sort(function buildOrder(left, right) {
            return compareBuild(state.buildById.get(left.buildId), state.buildById.get(right.buildId)) ||
                left.buildId.localeCompare(right.buildId);
        });
        var buildPack = packVariableRectangles(buildRectangles, viewportAspect, 40);
        var originX = (state.width - buildPack.width) / 2;
        var originY = (state.height - buildPack.height) / 2;
        var buildCells = new Map();
        var projectCells = new Map();
        var subgroupCells = new Map();
        var homes = new Map();
        buildRectangles.forEach(function placeBuild(buildRectangleValue) {
            var buildPlacement = buildPack.placements.get(buildRectangleValue.key);
            var buildX = originX + buildPlacement.x;
            var buildY = originY + buildPlacement.y;
            var buildCell = rectangle(buildX, buildY, buildRectangleValue.width, buildRectangleValue.height);
            buildCells.set(buildRectangleValue.buildId, buildCell);
            buildRectangleValue.projects.forEach(function placeProject(project) {
                var projectPlacement = buildRectangleValue.projectPack.placements.get(project.key);
                var projectCell = rectangle(
                    buildX + 24 + projectPlacement.x,
                    buildY + 52 + projectPlacement.y,
                    project.width,
                    project.height,
                );
                projectCells.set(project.key, projectCell);
                project.subgroups.forEach(function placeSubgroup(subgroup) {
                    var subgroupPlacement = project.subgroupPack.placements.get(subgroup.key);
                    var subgroupCell = rectangle(
                        projectCell.minX + 18 + subgroupPlacement.x,
                        projectCell.minY + 38 + subgroupPlacement.y,
                        subgroup.width,
                        subgroup.height,
                    );
                    subgroup.cell = subgroupCell;
                    subgroupCells.set(subgroup.key, subgroup);
                    subgroup.members.forEach(function placeNode(node) {
                        var placement = subgroup.nodePack.placements.get(node.id);
                        var nodeRectangleValue = project.nodeRectangles.get(node.id);
                        node.subgroupKey = subgroup.key;
                        node.labelDirection = "right";
                        homes.set(node.id, {
                            x: subgroupCell.minX + 14 + placement.x + nodeRectangleValue.nodeRadius + 10,
                            y: subgroupCell.minY + 34 + placement.y + nodeRectangleValue.height / 2,
                        });
                    });
                });
            });
        });
        return { buildCells: buildCells, projectCells: projectCells, subgroupCells: subgroupCells, homes: homes };
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
        columnCandidates.forEach(function testColumns(columns) {
            var placements = new Map();
            var y = 0;
            var totalWidth = 0;
            for (var start = 0; start < rectangles.length; start += columns) {
                var row = rectangles.slice(start, start + columns);
                var x = 0;
                var rowHeight = Math.max.apply(null, row.map(function (rectangleValue) {
                    return rectangleValue.height;
                }));
                row.forEach(function placeRectangle(rectangleValue) {
                    placements.set(rectangleValue.key, { x: x, y: y });
                    x += rectangleValue.width + gap;
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

    function applyManualScopeOffsets(state) {
        state.layout.buildCells.forEach(function offsetBuildCell(cell, buildId) {
            translateLayoutRectangle(cell, state.manualBuildOffsets.get(buildId));
        });
        state.layout.projectCells.forEach(function offsetProjectCell(cell, key) {
            var node = state.nodes.find(function inProject(candidate) { return nodeProjectKey(candidate) === key; });
            if (node) translateLayoutRectangle(cell, combinedScopeOffset(state, node));
        });
        state.layout.subgroupCells.forEach(function offsetSubgroup(subgroup) {
            translateLayoutRectangle(subgroup.cell, combinedScopeOffsetFor(
                state,
                subgroup.buildId,
                subgroup.projectId,
            ));
        });
        state.nodes.forEach(function offsetNodeHome(node) {
            translateLayoutPoint(state.layout.homes.get(node.id), combinedScopeOffset(state, node));
        });
    }

    function combinedScopeOffset(state, node) {
        return combinedScopeOffsetFor(state, nodeBuildId(node), nodeProjectId(node));
    }

    function combinedScopeOffsetFor(state, buildId, projectId) {
        var buildOffset = state.manualBuildOffsets.get(buildId) || { x: 0, y: 0 };
        var projectOffset = state.manualProjectOffsets.get(scopeKey(buildId, projectId)) || { x: 0, y: 0 };
        return { x: buildOffset.x + projectOffset.x, y: buildOffset.y + projectOffset.y };
    }

    function translateLayoutPoint(point, offset) {
        if (!point || !offset) return;
        point.x += offset.x;
        point.y += offset.y;
    }

    function translateLayoutRectangle(rectangleValue, offset) {
        if (!rectangleValue || !offset) return;
        rectangleValue.minX += offset.x;
        rectangleValue.maxX += offset.x;
        rectangleValue.minY += offset.y;
        rectangleValue.maxY += offset.y;
    }

    function manualPositionFor(state, node) {
        var position = state.manualPositions.get(node.id);
        if (!position) return null;
        var offset = combinedScopeOffset(state, node);
        return { x: position.x + offset.x, y: position.y + offset.y };
    }

    function storeManualPosition(state, node) {
        var offset = combinedScopeOffset(state, node);
        state.manualPositions.set(node.id, { x: node.x - offset.x, y: node.y - offset.y });
    }

    function accumulateScopeOffset(offsets, key, dx, dy) {
        var previous = offsets.get(key) || { x: 0, y: 0 };
        offsets.set(key, { x: previous.x + dx, y: previous.y + dy });
    }

    function appendRegions(state) {
        var buildLayer = state.zoomLayer.append("g").attr("class", "srcx-dashboard__architecture-build-regions");
        var representedBuilds = state.frame.builds.filter(function (build) {
            return state.layout.buildCells.has(build.id);
        }).sort(compareBuild);
        state.buildGroups = buildLayer.selectAll("g").data(representedBuilds, function (build) { return build.id; })
            .join("g")
            .attr("class", "srcx-dashboard__architecture-build-region")
            .attr("data-docx-atlas-build-id", function (build) { return build.id; })
            .attr("role", "button")
            .attr("tabindex", "0")
            .attr("aria-pressed", "false")
            .attr("aria-label", function (build) { return "Select build " + build.name; })
            .on("click", function buildClicked(event, build) {
                if (Date.now() < state.suppressScopeClickUntil) return;
                event.stopPropagation();
                selectScope(state, buildScope(build.id), true);
            })
            .on("keydown", function buildKeydown(event, build) {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                selectScope(state, buildScope(build.id), true);
            });
        state.buildGroups.append("rect")
            .attr("class", "srcx-dashboard__architecture-build-region-body")
            .attr("fill", function (build) { return build.color; })
            .attr("stroke", function (build) { return build.color; });
        state.buildGroups.append("text").each(function appendLabel(build) {
            var text = state.d3.select(this);
            var count = state.nodes.filter(function (node) { return nodeBuildId(node) === build.id; }).length;
            text.append("tspan").text(build.name);
            text.append("tspan").attr("dx", 7)
                .attr("class", "srcx-dashboard__architecture-build-context")
                .text(build.context + " / " + count + (count === 1 ? " node" : " nodes") + " in this frame");
        });
        state.buildGroups.append("rect")
            .attr("class", "srcx-dashboard__architecture-build-drag-handle")
            .attr("aria-hidden", "true");
        var projectData = representedProjects(state);
        var projectLayer = state.zoomLayer.append("g").attr("class", "srcx-dashboard__architecture-project-regions");
        state.projectGroups = projectLayer.selectAll("g").data(projectData, function (project) { return project.key; })
            .join("g")
            .attr("class", "srcx-dashboard__architecture-project-region")
            .attr("data-docx-atlas-build-id", function (project) { return project.buildId; })
            .attr("data-docx-atlas-project-id", function (project) { return project.projectId; })
            .attr("role", "button")
            .attr("tabindex", "0")
            .attr("aria-pressed", "false")
            .attr("aria-label", function (project) { return "Select project " + project.projectPath; })
            .on("click", function projectClicked(event, project) {
                if (Date.now() < state.suppressScopeClickUntil) return;
                event.stopPropagation();
                selectScope(state, projectScope(project.buildId, project.projectId), true);
            })
            .on("keydown", function projectKeydown(event, project) {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                selectScope(state, projectScope(project.buildId, project.projectId), true);
            });
        state.projectGroups.append("rect").attr("class", "srcx-dashboard__architecture-project-region-body");
        state.projectGroups.append("text").text(function (project) {
            return project.projectPath + " / " + project.count + (project.count === 1 ? " node" : " nodes");
        });
        state.projectGroups.append("rect")
            .attr("class", "srcx-dashboard__architecture-project-drag-handle")
            .attr("aria-hidden", "true");
        var subgroupLayer = state.zoomLayer.append("g")
            .attr("class", "srcx-dashboard__architecture-subgroup-regions");
        state.subgroupGroups = subgroupLayer.selectAll("g")
            .data(Array.from(state.layout.subgroupCells.values()), function (subgroup) { return subgroup.key; })
            .join("g")
            .attr("class", "srcx-dashboard__architecture-subgroup-region")
            .attr("data-docx-atlas-build-id", function (subgroup) { return subgroup.buildId; })
            .attr("data-docx-atlas-project-id", function (subgroup) {
                return subgroup.projectId === "__build__" ? "" : subgroup.projectId;
            })
            .attr("data-docx-atlas-source-set", function (subgroup) { return subgroup.label; })
            .attr("role", "button")
            .attr("tabindex", "0")
            .attr("aria-pressed", "false")
            .attr("aria-label", function (subgroup) {
                return "Select source set " + subgroup.label;
            })
            .on("click", function subgroupClicked(event, subgroup) {
                event.stopPropagation();
                selectScope(
                    state,
                    sourceSetScope(subgroup.buildId, subgroup.projectId, subgroup.label),
                    true,
                );
            })
            .on("keydown", function subgroupKeydown(event, subgroup) {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                selectScope(
                    state,
                    sourceSetScope(subgroup.buildId, subgroup.projectId, subgroup.label),
                    true,
                );
            });
        state.subgroupGroups.append("rect");
        state.subgroupGroups.append("text").text(function (subgroup) {
            return subgroup.label + " / " + subgroup.members.length +
                (subgroup.members.length === 1 ? " node" : " nodes");
        });
        positionRegions(state);
        state.buildGroups.call(scopeDrag(state, "build"));
        state.projectGroups.call(scopeDrag(state, "project"));
    }

    function appendScopeRelationshipLayer(state) {
        state.scopeEdgeLayer = state.zoomLayer.append("g")
            .attr("class", "srcx-dashboard__architecture-scope-edge-layer")
            .attr("aria-hidden", "true");
        state.scopeEdgeGroups = state.scopeEdgeLayer.selectAll("g.srcx-dashboard__architecture-scope-edge");
        state.visibleScopeRelationships = [];
    }

    function representedProjects(state) {
        var seen = new Map();
        state.nodes.forEach(function collect(node) {
            if (!node.projectId) return;
            var key = nodeProjectKey(node);
            if (!seen.has(key)) {
                seen.set(key, {
                    key: key,
                    buildId: nodeBuildId(node),
                    projectId: node.projectId,
                    projectPath: node.projectPath || node.projectId,
                    count: 0,
                });
            }
            seen.get(key).count += 1;
        });
        return Array.from(seen.values()).sort(function (left, right) { return left.key.localeCompare(right.key); });
    }

    function positionRegions(state) {
        state.buildGroups.each(function positionBuild(build) {
            positionRegion(state.d3.select(this), state.layout.buildCells.get(build.id), 42, true);
        });
        state.projectGroups.each(function positionProject(project) {
            positionRegion(state.d3.select(this), state.layout.projectCells.get(project.key), 27, false);
        });
        state.subgroupGroups.each(function positionSubgroup(subgroup) {
            var group = state.d3.select(this);
            var cell = subgroup.cell;
            group.select("rect").attr("x", cell.minX).attr("y", cell.minY)
                .attr("width", rectWidth(cell)).attr("height", rectHeight(cell));
            group.select("text").attr("x", cell.minX + 8).attr("y", cell.minY + 17);
        });
        positionScopeRelationships(state);
    }

    function positionRegion(group, cell, handleHeight, build) {
        if (!cell) return;
        group.select(build ? ".srcx-dashboard__architecture-build-region-body" :
            ".srcx-dashboard__architecture-project-region-body")
            .attr("x", cell.minX).attr("y", cell.minY)
            .attr("width", rectWidth(cell)).attr("height", rectHeight(cell));
        group.select("text").attr("x", cell.minX + 10).attr("y", cell.minY + (build ? 20 : 18));
        group.select(build ? ".srcx-dashboard__architecture-build-drag-handle" :
            ".srcx-dashboard__architecture-project-drag-handle")
            .attr("x", cell.minX).attr("y", cell.minY)
            .attr("width", rectWidth(cell)).attr("height", handleHeight);
    }

    function buildScope(buildId) {
        return {
            level: "build",
            key: String(buildId),
            buildId: String(buildId),
            projectId: null,
            sourceSet: null,
        };
    }

    function projectScope(buildId, projectId) {
        return {
            level: "project",
            key: scopeKey(buildId, projectId),
            buildId: String(buildId),
            projectId: String(projectId),
            sourceSet: null,
        };
    }

    function sourceSetScope(buildId, projectId, sourceSet) {
        return {
            level: "source-set",
            key: sourceSetScopeKey(buildId, projectId, sourceSet),
            buildId: String(buildId),
            projectId: String(projectId),
            sourceSet: String(sourceSet),
        };
    }

    function sourceSetScopeKey(buildId, projectId, sourceSet) {
        return scopeKey(buildId, projectId) + "\u0000" + String(sourceSet);
    }

    function scopeForNode(node, level) {
        if (level === "build") return buildScope(nodeBuildId(node));
        if (level === "project") return projectScope(nodeBuildId(node), nodeProjectId(node));
        if (level === "source-set") {
            return sourceSetScope(
                nodeBuildId(node),
                nodeProjectId(node),
                node.sourceSet || node.type || "nodes",
            );
        }
        throw new Error("Unknown Atlas relationship level: " + level);
    }

    function scopeCell(state, scope) {
        if (scope.level === "build") return state.layout.buildCells.get(scope.buildId);
        if (scope.level === "project") return state.layout.projectCells.get(scope.key);
        if (scope.level === "source-set") {
            var subgroup = state.layout.subgroupCells.get(scope.key);
            return subgroup ? subgroup.cell : null;
        }
        return null;
    }

    function aggregateScopeRelationships(state, level) {
        var aggregated = new Map();
        state.edges.forEach(function aggregateVisibleEdge(edge) {
            var sourceNode = state.nodeById.get(edge.sourceId);
            var targetNode = state.nodeById.get(edge.targetId);
            if (!sourceNode || !targetNode) return;
            var sourceScope = scopeForNode(sourceNode, level);
            var targetScope = scopeForNode(targetNode, level);
            if (sourceScope.key === targetScope.key) return;
            var key = sourceScope.key + "\u0001" + targetScope.key;
            var relationship = aggregated.get(key);
            if (!relationship) {
                relationship = {
                    id: level + "\u0001" + key,
                    level: level,
                    sourceScope: sourceScope,
                    targetScope: targetScope,
                    routeCount: 0,
                    recordCount: 0,
                    relationshipIds: [],
                    categories: new Set(),
                    hasHeuristic: false,
                    isObservedCycleEdge: false,
                    isAnalysisCycleEdge: false,
                };
                aggregated.set(key, relationship);
            }
            relationship.routeCount += 1;
            relationship.recordCount += Number(edge.recordCount || 0);
            relationship.relationshipIds = relationship.relationshipIds.concat(edge.relationshipIds || []);
            relationship.categories.add(String(edge.category || "references").toLowerCase());
            relationship.hasHeuristic = relationship.hasHeuristic || Boolean(edge.hasHeuristic);
            relationship.isObservedCycleEdge =
                relationship.isObservedCycleEdge || Boolean(edge.isObservedCycleEdge);
            relationship.isAnalysisCycleEdge =
                relationship.isAnalysisCycleEdge || Boolean(edge.isAnalysisCycleEdge);
        });
        return Array.from(aggregated.values()).map(function finalizeRelationship(relationship) {
            relationship.relationshipIds = Array.from(new Set(relationship.relationshipIds)).sort();
            relationship.category = relationship.categories.size === 1 ?
                Array.from(relationship.categories)[0] : "mixed";
            delete relationship.categories;
            return relationship;
        }).sort(function relationshipOrder(left, right) { return left.id.localeCompare(right.id); });
    }

    function scopeRelationshipClass(relationship) {
        var classes = "srcx-dashboard__architecture-scope-edge is-level-" + relationship.level +
            " is-kind-" + relationship.category;
        if (relationship.hasHeuristic) classes += " is-heuristic";
        if (relationship.isObservedCycleEdge) classes += " is-cycle-edge";
        if (relationship.isAnalysisCycleEdge) classes += " is-analysis-cycle-edge";
        return classes;
    }

    function renderScopeRelationshipOverlay(state) {
        if (!state.scopeEdgeLayer) return;
        var selected = state.selectedScope;
        var relationships = selected ? aggregateScopeRelationships(state, selected.level) : [];
        var visible = relationships.filter(function incident(relationship) {
            return relationship.sourceScope.key === selected.key || relationship.targetScope.key === selected.key;
        });
        state.visibleScopeRelationships = visible;
        state.scopeEdgeGroups = state.scopeEdgeLayer.selectAll("g.srcx-dashboard__architecture-scope-edge")
            .data(visible, function (relationship) { return relationship.id; })
            .join(function enterScopeRelationship(enter) {
                var group = enter.append("g")
                    .attr("class", scopeRelationshipClass)
                    .attr("aria-hidden", "true");
                group.append("path")
                    .attr("class", "srcx-dashboard__architecture-scope-edge-line")
                    .attr("marker-end", "url(#" + state.markerId + ")");
                var badge = group.append("g")
                    .attr("class", "srcx-dashboard__architecture-scope-edge-count");
                badge.append("rect").attr("rx", 5).attr("ry", 5);
                badge.append("text")
                    .attr("text-anchor", "middle")
                    .attr("dominant-baseline", "central");
                group.append("title");
                return group;
            })
            .attr("class", scopeRelationshipClass)
            .attr("data-docx-atlas-scope-level", function (relationship) { return relationship.level; })
            .attr("data-docx-atlas-visible-route-count", function (relationship) {
                return relationship.routeCount;
            })
            .attr("data-docx-atlas-visible-record-count", function (relationship) {
                return relationship.recordCount;
            });
        state.scopeEdgeGroups.select(".srcx-dashboard__architecture-scope-edge-count text")
            .text(function (relationship) { return String(relationship.recordCount); });
        state.scopeEdgeGroups.select("title").text(scopeRelationshipTitle);
        positionScopeRelationships(state);
    }

    function scopeRelationshipTitle(relationship) {
        var level = relationship.level === "source-set" ? "Source-set" :
            relationship.level.charAt(0).toUpperCase() + relationship.level.substring(1);
        return level + " relationship: " + relationship.recordCount + " visible relationship " +
            (relationship.recordCount === 1 ? "record" : "records") + " across " +
            relationship.routeCount + " visible node " +
            (relationship.routeCount === 1 ? "route" : "routes") +
            ". Aggregated only from the current Atlas frame.";
    }

    function positionScopeRelationships(state) {
        if (!state.scopeEdgeGroups) return;
        state.scopeEdgeGroups.each(function positionScopeRelationship(relationship) {
            var geometry = scopeRelationshipGeometry(state, relationship);
            if (!geometry) return;
            var group = state.d3.select(this);
            group.select(".srcx-dashboard__architecture-scope-edge-line").attr("d", geometry.path);
            var count = String(relationship.recordCount);
            var badgeWidth = Math.max(18, count.length * 6 + 10);
            group.select(".srcx-dashboard__architecture-scope-edge-count")
                .attr("transform", "translate(" + geometry.badge.x + "," + geometry.badge.y + ")")
                .select("rect")
                .attr("x", -badgeWidth / 2)
                .attr("y", -8)
                .attr("width", badgeWidth)
                .attr("height", 16);
        });
    }

    function scopeRelationshipGeometry(state, relationship) {
        var sourceCell = scopeCell(state, relationship.sourceScope);
        var targetCell = scopeCell(state, relationship.targetScope);
        if (!sourceCell || !targetCell) return null;
        var sourceCenter = rectangleCenter(sourceCell);
        var targetCenter = rectangleCenter(targetCell);
        var start = rectangleBoundaryPoint(sourceCell, targetCenter);
        var end = rectangleBoundaryPoint(targetCell, sourceCenter);
        var dx = end.x - start.x;
        var dy = end.y - start.y;
        var distance = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        var curve = Math.min(72, 20 + distance * 0.06);
        var control = {
            x: (start.x + end.x) / 2 - dy / distance * curve,
            y: (start.y + end.y) / 2 + dx / distance * curve,
        };
        return {
            path: "M" + start.x + "," + start.y + " Q" + control.x + "," + control.y +
                " " + end.x + "," + end.y,
            badge: {
                x: (start.x + 2 * control.x + end.x) / 4,
                y: (start.y + 2 * control.y + end.y) / 4,
            },
        };
    }

    function rectangleCenter(cell) {
        return { x: (cell.minX + cell.maxX) / 2, y: (cell.minY + cell.maxY) / 2 };
    }

    function rectangleBoundaryPoint(cell, toward) {
        var center = rectangleCenter(cell);
        var dx = toward.x - center.x;
        var dy = toward.y - center.y;
        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return center;
        var scaleX = Math.abs(dx) < 0.001 ? Infinity : rectWidth(cell) / 2 / Math.abs(dx);
        var scaleY = Math.abs(dy) < 0.001 ? Infinity : rectHeight(cell) / 2 / Math.abs(dy);
        var scale = Math.min(scaleX, scaleY);
        return { x: center.x + dx * scale, y: center.y + dy * scale };
    }

    function appendEdges(state) {
        state.edgeLayer = state.zoomLayer.append("g").attr("class", "srcx-dashboard__architecture-edge-layer");
        state.edgeGroups = state.edgeLayer.selectAll("g").data(state.edges, function (edge) { return edge.id; })
            .join("g")
            .attr("class", edgeClass)
            .attr("data-docx-atlas-edge-id", function (edge) { return edge.id; })
            .attr("data-docx-atlas-relationship-ids", function (edge) {
                return (edge.relationshipIds || []).join("\u001f");
            })
            .attr("data-docx-atlas-reference-ids", function (edge) {
                return (edge.referenceIds || []).join("\u001f");
            })
            .attr("role", "button")
            .attr("tabindex", "-1")
            .attr("focusable", "true")
            .on("mouseenter", function edgeEntered(event, edge) {
                state.hoveredEdgeId = edge.id;
                highlightEdge(state, edge);
                emitHover(state, "edge", edge);
            })
            .on("mouseleave", function edgeLeft() {
                state.hoveredEdgeId = null;
                restoreSelectionHighlight(state);
                emitHover(state, "clear", null);
            })
            .on("focus", function edgeFocused(event, edge) {
                state.hoveredEdgeId = edge.id;
                highlightEdge(state, edge);
                emitHover(state, "edge", edge);
            })
            .on("blur", function edgeBlurred() {
                state.hoveredEdgeId = null;
                restoreSelectionHighlight(state);
            })
            .on("click", function edgeClicked(event, edge) {
                event.stopPropagation();
                selectEdge(state, edge, true);
            })
            .on("keydown", function edgeKeydown(event, edge) {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    selectEdge(state, edge, true);
                } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
                    event.preventDefault();
                    focusNode(state, event.key === "ArrowUp" ? edge.sourceId : edge.targetId);
                }
            });
        state.edgeGroups.append("path")
            .attr("class", "srcx-dashboard__architecture-svg-edge-line")
            .attr("pointer-events", "none")
            .attr("marker-end", "url(#" + state.markerId + ")")
            .attr("stroke-width", function (edge) {
                return edge.isAnalysisCycleEdge ? 2.4 : Math.min(4, 0.8 + Math.log2(edge.recordCount + 1));
            });
        state.edgeGroups.append("path").attr("class", "srcx-dashboard__architecture-svg-edge-hit");
        var badges = state.edgeGroups.append("g")
            .attr("class", "srcx-dashboard__architecture-svg-edge-count")
            .attr("role", "img")
            .attr("pointer-events", "none");
        badges.append("rect").attr("rx", 5).attr("ry", 5);
        badges.append("text")
            .attr("text-anchor", "middle")
            .attr("dominant-baseline", "central")
            .text(function (edge) { return String(edge.recordCount); });
        state.edgeGroups.append("title").text(edgeTitle);
        state.edgeGroups.style("display", "none").style("opacity", 0);
    }

    function appendNodes(state) {
        var nodeLayer = state.zoomLayer.append("g").attr("class", "srcx-dashboard__architecture-node-layer");
        state.nodeGroups = nodeLayer.selectAll("g").data(state.nodes, function (node) { return node.id; })
            .join("g")
            .attr("class", nodeClass)
            .classed("is-label-pinned", function (node) { return shouldPinLabel(node); })
            .attr("data-docx-atlas-node-id", function (node) { return node.id; })
            .attr("data-docx-atlas-entity-type", function (node) { return node.type; })
            .attr("data-docx-atlas-build-id", function (node) { return node.buildId || ""; })
            .attr("data-docx-atlas-project-id", function (node) { return node.projectId || ""; })
            .attr("data-docx-atlas-internal-record-count", function (node) {
                return Number(node.internalRecordCount || 0);
            })
            .attr("role", "button")
            .attr("tabindex", function (node, index) { return index === 0 ? 0 : -1; })
            .attr("focusable", "true")
            .attr("aria-label", function (node) { return "Select " + nodeLabel(node); })
            .on("mouseenter", function nodeEntered(event, node) {
                state.hoveredNodeId = node.id;
                highlightNode(state, node.id);
                emitHover(state, "node", node);
            })
            .on("mouseleave", function nodeLeft() {
                state.hoveredNodeId = null;
                restoreSelectionHighlight(state);
                emitHover(state, "clear", null);
            })
            .on("focus", function nodeFocused(event, node) {
                state.hoveredNodeId = node.id;
                highlightNode(state, node.id);
                emitHover(state, "node", node);
            })
            .on("blur", function nodeBlurred() {
                state.hoveredNodeId = null;
                restoreSelectionHighlight(state);
            })
            .on("click", function nodeClicked(event, node) {
                event.stopPropagation();
                selectNode(state, node, event.ctrlKey || event.metaKey || event.shiftKey, true);
            })
            .on("keydown", function nodeKeydown(event, node) {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    selectNode(state, node, false, true);
                } else if (event.key.indexOf("Arrow") === 0) {
                    event.preventDefault();
                    focusAdjacentNode(state, node, event.key);
                } else if (event.key === "r" || event.key === "R") {
                    event.preventDefault();
                    focusIncidentEdge(state, node.id);
                }
            });
        state.nodeGroups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-hit")
            .attr("r", function (node) { return outerNodeRadius(node) + 6; });
        state.nodeGroups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-dot")
            .attr("r", nodeRadius)
            .attr("fill", function (node) { return buildColor(state, node.buildId); });
        [
            "is-importance-ring",
            "is-finding-ring",
            "is-cycle-ring",
            "is-analysis-cycle-ring",
        ].forEach(function appendRing(ringClass, index) {
            state.nodeGroups.append("circle")
                .attr("class", "srcx-dashboard__architecture-svg-node-ring " + ringClass)
                .attr("r", function (node) { return nodeRadius(node) + 4 + index * 3; });
        });
        state.nodeGroups.filter(function needsFileMarker(node) {
            return node.type === "symbol" && node.sourceFileFindingCount > 0;
        }).append("rect")
            .attr("class", "srcx-dashboard__architecture-symbol-file-finding-marker")
            .attr("width", 7).attr("height", 7).attr("x", -3.5).attr("y", -3.5)
            .attr("transform", function (node) {
                var distance = outerNodeRadius(node) * 0.72;
                return "translate(" + distance + "," + -distance + ") rotate(45)";
            });
        state.nodeGroups.append("text")
            .attr("class", "srcx-dashboard__architecture-svg-node-name srcx-dashboard__architecture-svg-node-label")
            .attr("x", function (node) { return outerNodeRadius(node) + 7; })
            .attr("y", 3)
            .each(function appendLabel(node) { appendNodeLabel(state.d3.select(this), node); });
        state.nodeGroups.append("title").text(nodeTitle);
        state.nodeGroups.call(nodeDrag(state));
    }

    function initializeNodePositions(state) {
        state.nodes.forEach(function initialize(node) {
            var previous = state.previousPositions && state.previousPositions.get(node.id);
            var manual = manualPositionFor(state, node);
            var home = state.layout.homes.get(node.id) || { x: state.width / 2, y: state.height / 2 };
            var position = manual || previous || home;
            node.x = position.x;
            node.y = position.y;
            if (manual) {
                node.fx = manual.x;
                node.fy = manual.y;
            }
        });
        constrainNodesToRegions(state);
    }

    function runDeterministicSimulation(state) {
        var d3 = state.d3;
        state.simulation = d3.forceSimulation(state.nodes)
            .randomSource(d3.randomLcg(0.42))
            .force("link", d3.forceLink(state.edges).id(function (node) { return node.id; })
                .distance(function (edge) { return edge.crossBuild ? 150 : 105; })
                .strength(function (edge) { return edge.crossBuild ? 0.04 : 0.14; }))
            .on("tick", function simulationTicked() { ticked(state); })
            .stop();
        if (state.nodes.length > STATIC_LAYOUT_NODE_THRESHOLD) {
            constrainNodesToRegions(state);
            return;
        }
        state.simulation
            .force("charge", d3.forceManyBody().strength(-240).distanceMax(520))
            .force("collision", d3.forceCollide().radius(collisionRadius).strength(0.92))
            .force("x", d3.forceX(function (node) { return state.layout.homes.get(node.id).x; })
                .strength(homeStrength))
            .force("y", d3.forceY(function (node) { return state.layout.homes.get(node.id).y; })
                .strength(homeStrength));
        for (var tick = 0; tick < 180; tick += 1) {
            state.simulation.tick();
            constrainNodesToRegions(state);
        }
    }

    function ticked(state) {
        if (!state.nodeGroups) return;
        constrainNodesToRegions(state);
        state.nodeGroups.attr("transform", function (node) { return "translate(" + node.x + "," + node.y + ")"; });
        state.edgeGroups.each(function positionEdge(edge) {
            var geometry = edgeGeometry(edge);
            var group = state.d3.select(this);
            group.selectAll("path").attr("d", geometry.path);
            var count = String(edge.recordCount);
            group.select(".srcx-dashboard__architecture-svg-edge-count")
                .attr("transform", "translate(" + geometry.badge.x + "," + geometry.badge.y + ")")
                .select("rect")
                .attr("x", -(Math.max(14, count.length * 5 + 8) / 2))
                .attr("y", -7)
                .attr("width", Math.max(14, count.length * 5 + 8))
                .attr("height", 14);
        });
    }

    function constrainNodesToRegions(state) {
        state.nodes.forEach(function constrainNode(node) {
            var subgroup = state.layout.subgroupCells.get(node.subgroupKey);
            var projectCell = state.layout.projectCells.get(nodeProjectKey(node));
            var cell = subgroup ? subgroup.cell : projectCell || state.layout.buildCells.get(nodeBuildId(node));
            if (!cell) return;
            var boundaryPadding = subgroup ? 10 : projectCell ? 14 : 36;
            var headerHeight = subgroup ? 18 : projectCell ? 22 : 28;
            var radius = outerNodeRadius(node) + 4;
            var bounds = labelBounds(node);
            var labelWidth = node.labelPinned ? bounds.width : 0;
            var labelHeight = node.labelPinned ? bounds.height : 0;
            var leftInset = radius + (node.labelDirection === "left" ? labelWidth + 8 : 0);
            var rightInset = radius + (node.labelDirection !== "left" ? labelWidth + 8 : 0);
            var minX = cell.minX + boundaryPadding + leftInset;
            var maxX = cell.maxX - boundaryPadding - rightInset;
            var verticalInset = Math.max(radius, labelHeight / 2);
            var minY = cell.minY + boundaryPadding + headerHeight + verticalInset;
            var maxY = cell.maxY - boundaryPadding - verticalInset;
            if (minX > maxX) minX = maxX = (cell.minX + cell.maxX) / 2;
            if (minY > maxY) minY = maxY = (cell.minY + cell.maxY) / 2;
            node.x = clamp(node.x, minX, maxX);
            node.y = clamp(node.y, minY, maxY);
            if (node.fx != null) node.fx = node.x;
            if (node.fy != null) node.fy = node.y;
        });
    }

    function edgeGeometry(edge) {
        var source = edge.source;
        var target = edge.target;
        var dx = target.x - source.x;
        var dy = target.y - source.y;
        var distance = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        var ux = dx / distance;
        var uy = dy / distance;
        var startRadius = outerNodeRadius(source) + 2;
        var endRadius = outerNodeRadius(target) + 8;
        var start = { x: source.x + ux * startRadius, y: source.y + uy * startRadius };
        var end = { x: target.x - ux * endRadius, y: target.y - uy * endRadius };
        var curve = Math.min(54, 16 + distance * 0.08);
        var control = {
            x: (start.x + end.x) / 2 - uy * curve,
            y: (start.y + end.y) / 2 + ux * curve,
        };
        return {
            path: "M" + start.x + "," + start.y + " Q" + control.x + "," + control.y + " " + end.x + "," + end.y,
            badge: {
                x: (start.x + 2 * control.x + end.x) / 4,
                y: (start.y + 2 * control.y + end.y) / 4,
            },
        };
    }

    function nodeDrag(state) {
        return state.d3.drag()
            .on("start", function dragStarted(event, node) {
                if (event.sourceEvent) event.sourceEvent.stopPropagation();
                state.mode = "dragging";
                state.userNavigated = true;
                if (state.nodes.length <= STATIC_LAYOUT_NODE_THRESHOLD && !event.active) {
                    state.simulation.alphaTarget(0.16).restart();
                }
                node.fx = node.x;
                node.fy = node.y;
                syncHost(state, "ready");
            })
            .on("drag", function dragged(event, node) {
                node.fx = event.x;
                node.fy = event.y;
                node.x = event.x;
                node.y = event.y;
                constrainNodesToRegions(state);
                ticked(state);
            })
            .on("end", function dragEnded(event, node) {
                if (state.nodes.length <= STATIC_LAYOUT_NODE_THRESHOLD && !event.active) {
                    state.simulation.alphaTarget(0).stop();
                }
                storeManualPosition(state, node);
                state.mode = "normal";
                syncHost(state, "ready");
                emitViewport(state);
            });
    }

    function scopeDrag(state, scope) {
        var context;
        return state.d3.drag()
            .subject(function (event) { return { x: event.x, y: event.y }; })
            .on("start", function started(event, region) {
                if (event.sourceEvent) event.sourceEvent.stopPropagation();
                state.mode = "dragging";
                state.userNavigated = true;
                var members = scopeNodes(state, scope, region);
                var cell = scope === "build" ? state.layout.buildCells.get(region.id) :
                    state.layout.projectCells.get(region.key);
                context = {
                    scope: scope,
                    region: region,
                    startX: event.x,
                    startY: event.y,
                    moved: false,
                    appliedX: 0,
                    appliedY: 0,
                    cell: copyRectangle(cell),
                    siblingCells: scopeSiblingCells(state, scope, region),
                    projectKeys: new Set(members.map(nodeProjectKey)),
                    positions: new Map(members.map(function capture(node) {
                        return [node.id, { x: node.x, y: node.y, fx: node.fx, fy: node.fy }];
                    })),
                };
                members.forEach(function pin(node) {
                    node.fx = node.x;
                    node.fy = node.y;
                });
            })
            .on("drag", function dragged(event, region) {
                if (!context) return;
                var delta = constrainedScopeDragDelta(
                    state,
                    context,
                    event.x - context.startX,
                    event.y - context.startY,
                );
                var dx = delta.x;
                var dy = delta.y;
                context.moved = context.moved || Math.sqrt(dx * dx + dy * dy) > 3;
                translateDraggedScopeLayout(state, context, dx, dy);
                scopeNodes(state, scope, region).forEach(function moveNode(node) {
                    var origin = context.positions.get(node.id);
                    node.x = origin.x + dx;
                    node.y = origin.y + dy;
                    node.fx = node.x;
                    node.fy = node.y;
                });
                ticked(state);
            })
            .on("end", function ended(event, region) {
                if (!context) return;
                if (!context.moved) {
                    translateDraggedScopeLayout(state, context, 0, 0);
                    scopeNodes(state, scope, region).forEach(function restoreNode(node) {
                        var origin = context.positions.get(node.id);
                        node.x = origin.x;
                        node.y = origin.y;
                        node.fx = origin.fx;
                        node.fy = origin.fy;
                    });
                } else {
                    var offsetMap = scope === "build" ? state.manualBuildOffsets : state.manualProjectOffsets;
                    var offsetKey = scope === "build" ? region.id : region.key;
                    accumulateScopeOffset(offsetMap, offsetKey, context.appliedX, context.appliedY);
                    scopeNodes(state, scope, region).forEach(function storeNode(node) {
                        storeManualPosition(state, node);
                    });
                    state.suppressScopeClickUntil = Date.now() + 300;
                }
                context = null;
                ticked(state);
                state.mode = "normal";
                syncHost(state, "ready");
                emitViewport(state);
            });
    }

    function scopeNodes(state, scope, region) {
        return state.nodes.filter(function (node) {
            return scope === "build" ? nodeBuildId(node) === region.id :
                nodeBuildId(node) === region.buildId && node.projectId === region.projectId;
        });
    }

    function constrainedScopeDragDelta(state, context, dx, dy) {
        if (!context.cell) return { x: dx, y: dy };
        if (context.scope === "project") {
            var buildCell = state.layout.buildCells.get(context.region.buildId);
            if (buildCell) {
                dx = clamp(dx, buildCell.minX + 16 - context.cell.minX,
                    buildCell.maxX - 16 - context.cell.maxX);
                dy = clamp(dy, buildCell.minY + 46 - context.cell.minY,
                    buildCell.maxY - 16 - context.cell.maxY);
            }
        }
        var candidate = translatedRectangle(context.cell, dx, dy);
        var gap = context.scope === "build" ? 24 : 14;
        var collides = context.siblingCells.some(function overlapsSibling(sibling) {
            return rectanglesOverlap(expandRectangle(candidate, gap), sibling);
        });
        return collides ? { x: context.appliedX, y: context.appliedY } : { x: dx, y: dy };
    }

    function scopeSiblingCells(state, scope, region) {
        if (scope === "build") {
            return Array.from(state.layout.buildCells.entries()).filter(function otherBuild(entry) {
                return entry[0] !== region.id;
            }).map(function copyEntry(entry) { return copyRectangle(entry[1]); });
        }
        return Array.from(state.layout.projectCells.entries()).filter(function siblingProject(entry) {
            if (entry[0] === region.key) return false;
            return state.nodes.some(function projectMember(node) {
                return nodeBuildId(node) === region.buildId && nodeProjectKey(node) === entry[0];
            });
        }).map(function copyEntry(entry) { return copyRectangle(entry[1]); });
    }

    function translateDraggedScopeLayout(state, context, dx, dy) {
        var translateX = dx - context.appliedX;
        var translateY = dy - context.appliedY;
        if (!translateX && !translateY) return;
        var offset = { x: translateX, y: translateY };
        if (context.scope === "build") {
            translateLayoutRectangle(state.layout.buildCells.get(context.region.id), offset);
            state.layout.projectCells.forEach(function moveProject(cell, key) {
                if (context.projectKeys.has(key)) translateLayoutRectangle(cell, offset);
            });
            state.layout.subgroupCells.forEach(function moveSubgroup(subgroup) {
                if (subgroup.buildId === context.region.id) translateLayoutRectangle(subgroup.cell, offset);
            });
            state.nodes.filter(function inBuild(node) { return nodeBuildId(node) === context.region.id; })
                .forEach(function moveHome(node) { translateLayoutPoint(state.layout.homes.get(node.id), offset); });
        } else {
            translateLayoutRectangle(state.layout.projectCells.get(context.region.key), offset);
            state.layout.subgroupCells.forEach(function moveSubgroup(subgroup) {
                if (subgroup.buildId === context.region.buildId && subgroup.projectId === context.region.projectId) {
                    translateLayoutRectangle(subgroup.cell, offset);
                }
            });
            scopeNodes(state, "project", context.region).forEach(function moveHome(node) {
                translateLayoutPoint(state.layout.homes.get(node.id), offset);
            });
        }
        context.appliedX = dx;
        context.appliedY = dy;
        positionRegions(state);
    }

    function installLasso(state) {
        state.selectionBox = state.zoomLayer.append("rect")
            .attr("class", "srcx-dashboard__architecture-selection-box")
            .attr("pointer-events", "none")
            .style("display", "none");
        state.svg.on("pointerdown.lasso", function lassoStarted(event) {
            if (!event.shiftKey || event.button !== 0) return;
            event.preventDefault();
            event.stopPropagation();
            var start = state.d3.pointer(event, state.zoomLayer.node());
            state.mode = "selecting";
            state.graphRoot.classList.add("is-box-selecting");
            state.selectionBox.style("display", null)
                .attr("x", start[0]).attr("y", start[1]).attr("width", 0).attr("height", 0);
            function moved(moveEvent) {
                var point = state.d3.pointer(moveEvent, state.zoomLayer.node());
                positionSelectionBox(state.selectionBox, start, point);
                syncHost(state, "ready");
            }
            function ended(upEvent) {
                var end = state.d3.pointer(upEvent, state.zoomLayer.node());
                var bounds = selectionBounds(start, end);
                var selected = state.nodes.filter(function (node) {
                    return node.x >= bounds.minX && node.x <= bounds.maxX &&
                        node.y >= bounds.minY && node.y <= bounds.maxY;
                }).map(function (node) { return node.id; }).sort();
                state.selectedNodeIds = new Set(selected);
                state.selectedEdgeId = null;
                state.selectedScope = null;
                state.mode = "normal";
                state.graphRoot.classList.remove("is-box-selecting");
                state.selectionBox.style("display", "none");
                state.suppressScopeClickUntil = Date.now() + 300;
                restoreSelectionHighlight(state);
                syncHost(state, "ready");
                emitSelectionEvent(state, { type: "group", id: null, nodeIds: selected });
                cleanup();
            }
            function cleanup() {
                global.removeEventListener("pointermove", moved);
                global.removeEventListener("pointerup", ended);
                global.removeEventListener("pointercancel", ended);
                if (state.lassoCleanup === cleanup) state.lassoCleanup = null;
            }
            state.lassoCleanup = cleanup;
            global.addEventListener("pointermove", moved, { passive: true });
            global.addEventListener("pointerup", ended, { passive: true });
            global.addEventListener("pointercancel", ended, { passive: true });
            syncHost(state, "ready");
        });
    }

    function positionSelectionBox(box, start, end) {
        var bounds = selectionBounds(start, end);
        box.attr("x", bounds.minX).attr("y", bounds.minY)
            .attr("width", bounds.maxX - bounds.minX)
            .attr("height", bounds.maxY - bounds.minY);
    }

    function selectionBounds(start, end) {
        return {
            minX: Math.min(start[0], end[0]),
            minY: Math.min(start[1], end[1]),
            maxX: Math.max(start[0], end[0]),
            maxY: Math.max(start[1], end[1]),
        };
    }

    function selectNode(state, node, toggle, notify) {
        if (toggle) {
            if (state.selectedNodeIds.has(node.id)) state.selectedNodeIds.delete(node.id);
            else state.selectedNodeIds.add(node.id);
        } else {
            state.selectedNodeIds = new Set([node.id]);
        }
        state.selectedEdgeId = null;
        state.selectedScope = null;
        restoreSelectionHighlight(state);
        syncHost(state, "ready");
        if (notify) emitSelection(state, "node", node);
    }

    function selectNodeById(state, id, notify) {
        var node = state.nodeById.get(id);
        if (!node) return false;
        selectNode(state, node, false, notify);
        return true;
    }

    function selectEdge(state, edge, notify) {
        state.selectedNodeIds.clear();
        state.selectedEdgeId = edge.id;
        state.selectedScope = null;
        clearScopeRegionHighlight(state);
        highlightEdge(state, edge);
        syncHost(state, "ready");
        if (notify) emitSelection(state, "edge", edge);
    }

    function selectEdgeById(state, id, notify) {
        var edge = state.edges.find(function (candidate) {
            return candidate.id === id || (candidate.relationshipIds || []).includes(id);
        });
        if (!edge) return false;
        state.selectedNodeIds.clear();
        state.selectedEdgeId = id;
        state.selectedScope = null;
        clearScopeRegionHighlight(state);
        highlightEdge(state, edge);
        syncHost(state, "ready");
        if (notify) emitSelection(state, "edge", edge, id);
        return true;
    }

    function clearSelection(state, notify) {
        state.selectedNodeIds.clear();
        state.selectedEdgeId = null;
        state.selectedScope = null;
        state.hoveredNodeId = null;
        state.hoveredEdgeId = null;
        restoreSelectionHighlight(state);
        syncHost(state, "ready");
        if (notify) emitSelection(state, "clear", null);
    }

    function selectScope(state, scope, notify) {
        if (!scopeCell(state, scope)) return false;
        if (sameScope(state.selectedScope, scope)) {
            clearSelection(state, notify);
            return true;
        }
        state.selectedScope = scope;
        state.selectedNodeIds.clear();
        state.selectedEdgeId = null;
        state.hoveredNodeId = null;
        state.hoveredEdgeId = null;
        restoreSelectionHighlight(state);
        syncHost(state, "ready");
        if (notify) emitScopeSelection(state, scope);
        return true;
    }

    function selectScopeByCommand(state, value, notify) {
        // Contract: select-scope:<level>:<encodeURIComponent(build)>[:<project>[:<source-set>]].
        var parts = String(value || "").split(":").map(function decodePart(part) {
            return decodeURIComponent(part);
        });
        var level = parts[0];
        if (level === "build" && parts.length === 2) {
            return selectScope(state, buildScope(parts[1]), notify);
        }
        if (level === "project" && parts.length === 3) {
            return selectScope(state, projectScope(parts[1], parts[2]), notify);
        }
        if (level === "source-set" && parts.length === 4) {
            return selectScope(state, sourceSetScope(parts[1], parts[2], parts[3]), notify);
        }
        throw new Error("Scope selection requires URL-encoded level and ownership IDs");
    }

    function sameScope(left, right) {
        return Boolean(left && right && left.level === right.level && left.key === right.key);
    }

    function emitScopeSelection(state, scope) {
        var relationships = state.visibleScopeRelationships || [];
        var selectedNodeIds = state.nodes.filter(function selectedScopeMember(node) {
            return scopeForNode(node, scope.level).key === scope.key;
        }).map(function selectedScopeNodeId(node) { return node.id; }).sort();
        emitSelectionEvent(state, {
            type: "scope",
            id: scope.key,
            level: scope.level,
            buildId: scope.buildId,
            projectId: scope.projectId,
            sourceSet: scope.sourceSet,
            relationshipBasis: "current-frame",
            nodeIds: selectedNodeIds,
            visibleScopeEdgeCount: relationships.length,
            visibleNodeRouteCount: relationships.reduce(function sumRoutes(total, relationship) {
                return total + relationship.routeCount;
            }, 0),
            visibleRelationshipRecordCount: relationships.reduce(function sumRecords(total, relationship) {
                return total + relationship.recordCount;
            }, 0),
        });
    }

    function highlightNode(state, nodeId) {
        var incident = state.edges.filter(function (edge) {
            return edge.sourceId === nodeId || edge.targetId === nodeId;
        });
        var neighbors = new Set([nodeId]);
        incident.forEach(function (edge) { neighbors.add(edge.sourceId); neighbors.add(edge.targetId); });
        state.nodeGroups.classed("is-hovered", function (node) { return node.id === nodeId; })
            .classed("is-selected", function (node) { return state.selectedNodeIds.has(node.id); })
            .classed("is-group-selected", function (node) {
                return state.selectedNodeIds.size > 1 && state.selectedNodeIds.has(node.id);
            })
            .classed("is-dimmed", function (node) { return !neighbors.has(node.id); });
        showEdges(state, incident, function () { return true; });
        syncHost(state, "ready");
    }

    function highlightEdge(state, edge) {
        state.nodeGroups.classed("is-hovered", false)
            .classed("is-selected", function (node) {
                return node.id === edge.sourceId || node.id === edge.targetId;
            })
            .classed("is-group-selected", false)
            .classed("is-dimmed", function (node) {
                return node.id !== edge.sourceId && node.id !== edge.targetId;
            });
        showEdges(state, [edge], function (candidate) { return candidate.id === edge.id; });
        syncHost(state, "ready");
    }

    function highlightScope(state, scope) {
        renderScopeRelationshipOverlay(state);
        var visibleRelationships = state.visibleScopeRelationships;
        var relatedScopeKeys = new Set([scope.key]);
        var emphasizedNodeIds = new Set();
        state.nodes.forEach(function collectSelectedScopeNodes(node) {
            if (scopeForNode(node, scope.level).key === scope.key) emphasizedNodeIds.add(node.id);
        });
        visibleRelationships.forEach(function collectRelatedScope(relationship) {
            relatedScopeKeys.add(relationship.sourceScope.key);
            relatedScopeKeys.add(relationship.targetScope.key);
        });
        state.edges.forEach(function collectVisibleEndpoints(edge) {
            var sourceNode = state.nodeById.get(edge.sourceId);
            var targetNode = state.nodeById.get(edge.targetId);
            if (!sourceNode || !targetNode) return;
            var sourceScope = scopeForNode(sourceNode, scope.level);
            var targetScope = scopeForNode(targetNode, scope.level);
            if (sourceScope.key === scope.key || targetScope.key === scope.key) {
                emphasizedNodeIds.add(sourceNode.id);
                emphasizedNodeIds.add(targetNode.id);
            }
        });
        state.nodeGroups
            .classed("is-hovered", false)
            .classed("is-selected", false)
            .classed("is-group-selected", function (node) {
                return scopeForNode(node, scope.level).key === scope.key;
            })
            .classed("is-dimmed", function (node) { return !emphasizedNodeIds.has(node.id); });
        applyScopeRegionHighlight(state, scope, relatedScopeKeys, emphasizedNodeIds);
        showEdges(state, [], function () { return false; });
        syncHost(state, "ready");
    }

    function applyScopeRegionHighlight(state, selected, relatedKeys, emphasizedNodeIds) {
        applyScopeGroupHighlight(
            state.buildGroups,
            function buildDescriptor(build) { return buildScope(build.id); },
            selected,
            relatedKeys,
            emphasizedNodeIds,
            state,
        );
        applyScopeGroupHighlight(
            state.projectGroups,
            function projectDescriptor(project) { return projectScope(project.buildId, project.projectId); },
            selected,
            relatedKeys,
            emphasizedNodeIds,
            state,
        );
        applyScopeGroupHighlight(
            state.subgroupGroups,
            function subgroupDescriptor(subgroup) {
                return sourceSetScope(subgroup.buildId, subgroup.projectId, subgroup.label);
            },
            selected,
            relatedKeys,
            emphasizedNodeIds,
            state,
        );
    }

    function applyScopeGroupHighlight(groups, descriptor, selected, relatedKeys, emphasizedNodeIds, state) {
        groups
            .classed("is-scope-selected", function (value) { return sameScope(descriptor(value), selected); })
            .attr("aria-pressed", function (value) { return String(sameScope(descriptor(value), selected)); })
            .classed("is-scope-related", function (value) {
                var scope = descriptor(value);
                var sameLevelRelationship =
                    scope.level === selected.level && scope.key !== selected.key && relatedKeys.has(scope.key);
                var nestedRelationship =
                    scope.level !== selected.level && !scopeContainsScope(scope, selected) &&
                    scopeContainsEmphasizedNode(state, scope, emphasizedNodeIds);
                return sameLevelRelationship || nestedRelationship;
            })
            .classed("is-scope-context", function (value) {
                return scopeContainsScope(descriptor(value), selected);
            })
            .classed("is-scope-dimmed", function (value) {
                var scope = descriptor(value);
                return !scopeContainsEmphasizedNode(state, scope, emphasizedNodeIds) &&
                    !sameScope(scope, selected) && !scopeContainsScope(scope, selected);
            });
    }

    function scopeContainsScope(container, nested) {
        if (container.level === nested.level) return false;
        if (container.level === "build") return container.buildId === nested.buildId;
        return container.level === "project" && nested.level === "source-set" &&
            container.buildId === nested.buildId && container.projectId === nested.projectId;
    }

    function scopeContainsEmphasizedNode(state, scope, emphasizedNodeIds) {
        return state.nodes.some(function emphasizedMember(node) {
            return emphasizedNodeIds.has(node.id) && nodeBelongsToScope(node, scope);
        });
    }

    function nodeBelongsToScope(node, scope) {
        return nodeBuildId(node) === scope.buildId &&
            (scope.projectId === null || nodeProjectId(node) === scope.projectId) &&
            (scope.sourceSet === null || String(node.sourceSet || node.type || "nodes") === scope.sourceSet);
    }

    function clearScopeRegionHighlight(state) {
        [state.buildGroups, state.projectGroups, state.subgroupGroups].forEach(function clearGroups(groups) {
            if (!groups) return;
            groups.classed("is-scope-selected", false)
                .attr("aria-pressed", "false")
                .classed("is-scope-related", false)
                .classed("is-scope-context", false)
                .classed("is-scope-dimmed", false);
        });
        renderScopeRelationshipOverlay(state);
    }

    function restoreSelectionHighlight(state) {
        if (!state.nodeGroups || !state.edgeGroups) return;
        if (state.hoveredNodeId && state.nodeById.has(state.hoveredNodeId)) {
            highlightNode(state, state.hoveredNodeId);
            return;
        }
        if (state.hoveredEdgeId && state.edgeById.has(state.hoveredEdgeId)) {
            highlightEdge(state, state.edgeById.get(state.hoveredEdgeId));
            return;
        }
        if (state.selectedEdgeId) {
            var edge = state.edges.find(function (candidate) {
                return candidate.id === state.selectedEdgeId ||
                    (candidate.relationshipIds || []).includes(state.selectedEdgeId);
            });
            if (edge) {
                highlightEdge(state, edge);
                return;
            }
        }
        if (state.selectedScope && scopeCell(state, state.selectedScope)) {
            highlightScope(state, state.selectedScope);
            return;
        }
        if (state.selectedScope) state.selectedScope = null;
        clearScopeRegionHighlight(state);
        if (state.selectedNodeIds.size) {
            var selectedEdges = state.edges.filter(function (edge) {
                return state.selectedNodeIds.has(edge.sourceId) || state.selectedNodeIds.has(edge.targetId);
            });
            state.nodeGroups.classed("is-hovered", false)
                .classed("is-selected", function (node) { return state.selectedNodeIds.has(node.id); })
                .classed("is-group-selected", function (node) {
                    return state.selectedNodeIds.size > 1 && state.selectedNodeIds.has(node.id);
                })
                .classed("is-dimmed", false);
            showEdges(state, selectedEdges, function () { return true; });
            return;
        }
        state.nodeGroups.classed("is-hovered", false).classed("is-selected", false)
            .classed("is-group-selected", false).classed("is-dimmed", false);
        showEdges(state, [], function () { return false; });
    }

    function showEdges(state, visible, active) {
        var visibleIds = new Set(visible.map(function (edge) { return edge.id; }));
        state.visibleEdgeIds = visibleIds;
        state.edgeGroups
            .style("display", function (edge) { return visibleIds.has(edge.id) ? null : "none"; })
            .style("opacity", function (edge) { return visibleIds.has(edge.id) ? 1 : 0; })
            .attr("tabindex", function (edge) { return visibleIds.has(edge.id) ? 0 : -1; })
            .classed("is-active", function (edge) { return visibleIds.has(edge.id) && active(edge); })
            .classed("is-selected", function (edge) {
                return edge.id === state.selectedEdgeId || (edge.relationshipIds || []).includes(state.selectedEdgeId);
            })
            .classed("is-hovered", function (edge) { return edge.id === state.hoveredEdgeId; })
            .classed("is-dimmed", false);
    }

    function focusAdjacentNode(state, node, key) {
        var direction = key === "ArrowLeft" ? [-1, 0] : key === "ArrowRight" ? [1, 0] :
            key === "ArrowUp" ? [0, -1] : [0, 1];
        var candidates = state.nodes.filter(function (candidate) {
            var dx = candidate.x - node.x;
            var dy = candidate.y - node.y;
            return candidate.id !== node.id && dx * direction[0] + dy * direction[1] > 0;
        }).sort(function (left, right) {
            return squaredDistance(node, left) - squaredDistance(node, right) || left.id.localeCompare(right.id);
        });
        if (candidates.length) focusNode(state, candidates[0].id);
    }

    function focusNode(state, id) {
        var element = state.host.querySelector('[data-docx-atlas-node-id="' + cssEscape(id) + '"]');
        if (element) element.focus();
    }

    function focusIncidentEdge(state, nodeId) {
        var edge = state.edges.find(function (candidate) {
            return candidate.sourceId === nodeId || candidate.targetId === nodeId;
        });
        if (!edge) return;
        highlightNode(state, nodeId);
        var element = state.host.querySelector('[data-docx-atlas-edge-id="' + cssEscape(edge.id) + '"]');
        if (element) element.focus();
    }

    function zoomBy(state, factor) {
        state.userNavigated = true;
        state.viewportMode = "manual";
        state.svg.interrupt().call(state.zoom.scaleBy, factor);
    }

    function pan(state, coordinates) {
        var values = coordinates.split(",").map(Number);
        if (values.length !== 2 || !values.every(Number.isFinite)) throw new Error("Pan requires pan:<x>,<y>");
        state.userNavigated = true;
        state.viewportMode = "manual";
        var next = state.d3.zoomIdentity
            .translate(state.transform.x + values[0], state.transform.y + values[1])
            .scale(state.transform.k);
        state.svg.call(state.zoom.transform, next);
    }

    function fit(state, animate) {
        state.userNavigated = false;
        state.viewportMode = "fit";
        if (!state.zoomLayer || !state.zoomLayer.node()) return;
        var bounds = state.zoomLayer.node().getBBox();
        if (!bounds.width || !bounds.height) return;
        var padding = 48;
        var scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
            Math.min((state.width - padding * 2) / bounds.width, (state.height - padding * 2) / bounds.height)));
        var x = state.width / 2 - scale * (bounds.x + bounds.width / 2);
        var y = state.height / 2 - scale * (bounds.y + bounds.height / 2);
        var transform = state.d3.zoomIdentity.translate(x, y).scale(scale);
        var target = animate ? state.svg.transition().duration(reducedMotion() ? 0 : 180) : state.svg;
        target.call(state.zoom.transform, transform);
    }

    function reset(state) {
        state.manualPositions.clear();
        state.manualBuildOffsets.clear();
        state.manualProjectOffsets.clear();
        state.selectedNodeIds.clear();
        state.selectedEdgeId = null;
        state.selectedScope = null;
        state.hoveredNodeId = null;
        state.hoveredEdgeId = null;
        state.userNavigated = false;
        state.transform = state.d3.zoomIdentity;
        state.viewportMode = "reset";
        draw(state, true);
        emitSelection(state, "clear", null);
    }

    function syncHost(state, status) {
        var value = snapshotValue(state);
        setAttribute(state.host, "data-docx-atlas-renderer", "d3");
        setAttribute(state.host, "data-docx-atlas-state", status);
        setAttribute(state.host, "data-docx-atlas-frame-id", value.frameId);
        setAttribute(state.host, "data-docx-atlas-lens", value.lens);
        setAttribute(state.host, "data-docx-atlas-mode", value.mode);
        setAttribute(state.host, "data-docx-atlas-viewport-mode", value.viewportMode);
        setAttribute(state.host, "data-docx-atlas-node-count", value.nodeCount);
        setAttribute(state.host, "data-docx-atlas-edge-count", value.edgeCount);
        setAttribute(state.host, "data-docx-atlas-visible-edge-count", value.visibleEdgeCount);
        setAttribute(state.host, "data-docx-atlas-total-node-count", value.totalNodeCount);
        setAttribute(state.host, "data-docx-atlas-matching-node-count", value.matchingNodeCount);
        setAttribute(state.host, "data-docx-atlas-shown-relationship-count", value.shownRelationshipRecordCount);
        setAttribute(state.host, "data-docx-atlas-total-relationship-count", value.totalRelationshipRecordCount);
        setAttribute(state.host, "data-docx-atlas-selected-node-id", value.selectedNodeId || "");
        setAttribute(state.host, "data-docx-atlas-selected-node-ids", value.selectedNodeIds.join("\u001f"));
        setAttribute(state.host, "data-docx-atlas-selected-edge-id", value.selectedEdgeId || "");
        setAttribute(state.host, "data-docx-atlas-selected-scope-level", value.selectedScopeLevel || "");
        setAttribute(state.host, "data-docx-atlas-selected-scope-build-id", value.selectedScopeBuildId || "");
        setAttribute(state.host, "data-docx-atlas-selected-scope-project-id", value.selectedScopeProjectId || "");
        setAttribute(state.host, "data-docx-atlas-selected-scope-source-set", value.selectedScopeSourceSet || "");
        setAttribute(state.host, "data-docx-atlas-scope-relationship-basis", "current-frame");
        setAttribute(state.host, "data-docx-atlas-visible-scope-edge-count", value.visibleScopeEdgeCount);
        setAttribute(
            state.host,
            "data-docx-atlas-visible-scope-node-route-count",
            value.visibleScopeNodeRouteCount,
        );
        setAttribute(
            state.host,
            "data-docx-atlas-visible-scope-relationship-record-count",
            value.visibleScopeRelationshipRecordCount,
        );
        setAttribute(state.host, "data-docx-atlas-hovered-node-id", value.hoveredNodeId || "");
        setAttribute(state.host, "data-docx-atlas-hovered-edge-id", value.hoveredEdgeId || "");
        setAttribute(state.host, "data-docx-atlas-scale", value.scale);
        setAttribute(state.host, "data-docx-atlas-pan-x", value.panX);
        setAttribute(state.host, "data-docx-atlas-pan-y", value.panY);
        setAttribute(state.host, "data-docx-atlas-d3-version", value.d3Version);
        setAttribute(
            state.host,
            "data-docx-atlas-capabilities",
            "seeded-force zoom pan fit reset node-drag scope-drag lasso keyboard " +
                "hidden-incident-edges aggregate-selection scope-selection scope-relationship-levels",
        );
    }

    function snapshotValue(state) {
        var transform = state.transform || state.d3.zoomIdentity;
        var selectedNodeIds = Array.from(state.selectedNodeIds || []).sort();
        var selectedScope = state.selectedScope;
        var visibleScopeRelationships = state.visibleScopeRelationships || [];
        return {
            state: state.destroyed ? "destroyed" : "ready",
            frameId: state.frame.frameId,
            lens: String(state.frame.lens || "files").toLowerCase(),
            mode: state.mode,
            viewportMode: state.viewportMode,
            nodeCount: state.nodes ? state.nodes.length : 0,
            edgeCount: state.edges ? state.edges.length : 0,
            visibleEdgeCount: state.visibleEdgeIds ? state.visibleEdgeIds.size : 0,
            totalNodeCount: state.frame.totalNodeCount,
            matchingNodeCount: state.frame.matchingNodeCount,
            shownRelationshipRecordCount: state.frame.shownRelationshipRecordCount,
            totalRelationshipRecordCount: state.frame.totalRelationshipRecordCount,
            selectedNodeIds: selectedNodeIds,
            selectedNodeId: selectedNodeIds.length === 1 ? selectedNodeIds[0] : null,
            selectedEdgeId: state.selectedEdgeId,
            selectedScopeLevel: selectedScope ? selectedScope.level : null,
            selectedScopeBuildId: selectedScope ? selectedScope.buildId : null,
            selectedScopeProjectId: selectedScope ? selectedScope.projectId : null,
            selectedScopeSourceSet: selectedScope ? selectedScope.sourceSet : null,
            visibleScopeEdgeCount: visibleScopeRelationships.length,
            visibleScopeNodeRouteCount: visibleScopeRelationships.reduce(function sumRoutes(total, relationship) {
                return total + relationship.routeCount;
            }, 0),
            visibleScopeRelationshipRecordCount:
                visibleScopeRelationships.reduce(function sumRecords(total, relationship) {
                    return total + relationship.recordCount;
                }, 0),
            hoveredNodeId: state.hoveredNodeId,
            hoveredEdgeId: state.hoveredEdgeId,
            scale: roundViewport(transform.k),
            panX: roundViewport(transform.x),
            panY: roundViewport(transform.y),
            d3Version: state.d3Version,
        };
    }

    function emitSelection(state, type, value, selectedRelationshipId) {
        var detail = { type: type, id: value ? value.id : null };
        if (type === "node") {
            detail.nodeId = value.id;
            detail.entityType = value.type;
            detail.sourceFileId = value.sourceFileId || null;
            detail.findingIds = (value.findingIds || []).slice();
        } else if (type === "edge") {
            detail.id = selectedRelationshipId || value.id;
            detail.edgeId = value.id;
            detail.relationshipIds = (value.relationshipIds || []).slice();
            detail.referenceIds = (value.referenceIds || []).slice();
        }
        emitSelectionEvent(state, detail);
    }

    function emitSelectionEvent(state, detail) {
        var surface = state.host.closest("[data-docx-atlas-surface]");
        var scrollTop = surface ? surface.scrollTop : null;
        if (surface) surface.setAttribute("data-docx-atlas-d3-selection-scroll", String(scrollTop));
        emit(state.host, "docx-atlas-selection", detail);
        if (!surface || scrollTop === null) return;
        surface.scrollTop = scrollTop;
        surface.setAttribute("data-docx-atlas-d3-restore-sync", String(surface.scrollTop));
        global.requestAnimationFrame(function restoreSelectionScrollFirstFrame() {
            surface.scrollTop = scrollTop;
            global.requestAnimationFrame(function restoreSelectionScrollSecondFrame() {
                surface.scrollTop = scrollTop;
                surface.setAttribute("data-docx-atlas-d3-restore-frame", String(surface.scrollTop));
            });
        });
    }

    function emitHover(state, type, value) {
        emit(state.host, "docx-atlas-hover", {
            type: type,
            id: value ? value.id : null,
            relationshipIds: value && value.relationshipIds ? value.relationshipIds.slice() : [],
            referenceIds: value && value.referenceIds ? value.referenceIds.slice() : [],
        });
    }

    function emitViewport(state) {
        emit(state.host, "docx-atlas-viewport", {
            scale: roundViewport(state.transform.k),
            panX: roundViewport(state.transform.x),
            panY: roundViewport(state.transform.y),
            mode: state.viewportMode,
        });
    }

    function frameScopeKey(frame) {
        var scope = frame && frame.scope ? frame.scope : {};
        return [
            scope.kind || "",
            scope.workspaceId || "",
            scope.buildId || "",
            scope.projectId || "",
        ].join("|");
    }

    function emit(host, name, detail) {
        host.dispatchEvent(new global.CustomEvent(name, { detail: detail, bubbles: true }));
    }

    function assignVisualTiers(nodes) {
        var ranked = nodes.slice().sort(function (left, right) {
            return reviewPriority(right) - reviewPriority(left) ||
                Number(right.relationshipRecordCount || 0) - Number(left.relationshipRecordCount || 0) ||
                left.id.localeCompare(right.id);
        });
        var high = Math.max(3, Math.ceil(nodes.length * 0.12));
        var medium = Math.max(8, Math.ceil(nodes.length * 0.35));
        ranked.forEach(function (node, index) {
            node.visualTier = reviewPriority(node) > 0 || index < high ? "high" : index < medium ? "medium" : "low";
            node.visualRank = index + 1;
        });
    }

    function nodeClass(node) {
        var classes = "srcx-dashboard__architecture-svg-node is-signal-" + node.visualTier;
        if (node.important) classes += " is-important";
        if (node.findingCount > 0) classes += " has-problem has-finding";
        if (node.hasObservedCycle) classes += " has-problem has-cycle";
        if (node.hasAnalysisCycle) classes += " has-problem has-analysis-cycle";
        if (node.hasAnalyzerFinding) classes += " has-problem has-analyzer-finding";
        if (node.sourceFileFindingCount > 0) classes += " has-source-file-finding";
        if (node.primary === false) classes += " is-context";
        return classes;
    }

    function edgeClass(edge) {
        var category = String(edge.category || "references").toLowerCase();
        var classes = "srcx-dashboard__architecture-svg-edge is-kind-" + category;
        if (edge.crossBuild) classes += " is-cross-build";
        if (edge.hasHeuristic) classes += " is-heuristic has-heuristic";
        if (edge.isObservedCycleEdge) classes += " is-cycle-edge";
        if (edge.isAnalysisCycleEdge) classes += " is-analysis-cycle-edge";
        return classes;
    }

    function nodeRadius(node) {
        return 5 + Math.min(6, Math.log2(Number(node.relationshipRecordCount || 0) + 1) * 1.35) +
            (node.visualTier === "high" ? 1.5 : node.visualTier === "medium" ? 0.7 : 0);
    }

    function outerNodeRadius(node) {
        var radius = nodeRadius(node);
        if (node.important) radius = Math.max(radius, nodeRadius(node) + 4);
        if (node.findingCount > 0) radius = Math.max(radius, nodeRadius(node) + 7);
        if (node.hasObservedCycle) radius = Math.max(radius, nodeRadius(node) + 10);
        if (node.hasAnalysisCycle) radius = Math.max(radius, nodeRadius(node) + 13);
        return radius;
    }

    function collisionRadius(node) {
        if (!node.labelPinned) return outerNodeRadius(node) + 14;
        var bounds = labelBounds(node);
        var labelRadius = Math.sqrt(bounds.width * bounds.width + bounds.height * bounds.height) / 2;
        return outerNodeRadius(node) + 10 + labelRadius;
    }

    function homeStrength(node) {
        return Number(node.relationshipRecordCount || 0) === 0 ? 0.92 : 0.34;
    }

    function reviewPriority(node) {
        return Number(Boolean(node.important)) + Number(Boolean(node.hasObservedCycle)) +
            Number(Boolean(node.hasAnalysisCycle)) + Number(Boolean(node.hasAnalyzerFinding)) +
            Math.min(2, Number(node.findingCount || 0));
    }

    function shouldPinLabel(node) {
        return node.visualTier === "high" || node.important || node.findingCount > 0 ||
            node.hasObservedCycle || node.hasAnalysisCycle;
    }

    function appendNodeLabel(text, node) {
        var semantic = String(node.semantic || "OTHER");
        if (node.type === "symbol" && semantic !== "OTHER") {
            text.append("tspan")
                .attr("aria-hidden", "true")
                .attr("class", "srcx-dashboard__architecture-semantic-marker is-" +
                    semantic.toLowerCase().replace(/_/g, "-"))
                .text("■");
            text.append("tspan").attr("dx", 4).text(nodeLabel(node));
        } else {
            text.append("tspan").text(nodeLabel(node));
        }
    }

    function nodeLabel(node) {
        return String(node.name || node.qualifiedName || node.path || node.id);
    }

    function labelBounds(node) {
        return node.labelBounds || {
            width: estimatedTextWidth(nodeLabel(node)),
            height: 10,
        };
    }

    function safeSvgBounds(element) {
        if (!element || typeof element.getBBox !== "function") return null;
        try {
            var bounds = element.getBBox();
            return bounds && Number.isFinite(bounds.width) && Number.isFinite(bounds.height) && bounds.width > 0 ?
                bounds : null;
        } catch (ignored) {
            return null;
        }
    }

    function estimatedTextWidth(value) {
        return Math.max(10, String(value || "").length * 5.2);
    }

    function nodeTitle(node) {
        var details = [nodeLabel(node)];
        if (node.buildName) details.push(node.buildName + " build ownership");
        if (node.projectPath) details.push(node.projectPath);
        if (node.sourceSet) details.push(node.sourceSet);
        if (node.path) details.push(node.path);
        details.push(Number(node.relationshipRecordCount || 0) + " relationship records");
        if (node.internalRecordCount) details.push(node.internalRecordCount + " internal records");
        return details.join("; ");
    }

    function edgeTitle(edge) {
        var kinds = (edge.kindCounts || []).map(function (count) {
            return count.label + " " + count.count;
        }).join(", ");
        return edge.recordCount + (edge.recordCount === 1 ? " relationship record" : " relationship records") +
            (kinds ? "; " + kinds : "");
    }

    function buildColor(state, buildId) {
        var build = state.buildById.get(buildId);
        return build ? build.color : "hsl(0 0% 68%)";
    }

    function compareBuild(left, right) {
        left = left || { id: "", name: "", context: "" };
        right = right || { id: "", name: "", context: "" };
        var leftRoot = String(left.context).toLowerCase().indexOf("root") >= 0;
        var rightRoot = String(right.context).toLowerCase().indexOf("root") >= 0;
        return Number(rightRoot) - Number(leftRoot) ||
            String(left.name).toLowerCase().localeCompare(String(right.name).toLowerCase()) ||
            String(left.id).localeCompare(String(right.id));
    }

    function groupSorted(values, selector) {
        var groups = new Map();
        values.slice().sort(byId).forEach(function (value) {
            var key = selector(value);
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(value);
        });
        return new Map(Array.from(groups.entries()).sort(function (left, right) {
            return String(left[0]).localeCompare(String(right[0]));
        }));
    }

    function rectangle(x, y, width, height) {
        return { minX: x, minY: y, maxX: x + width, maxY: y + height };
    }

    function copyRectangle(rectangleValue) {
        if (!rectangleValue) return null;
        return {
            minX: rectangleValue.minX,
            minY: rectangleValue.minY,
            maxX: rectangleValue.maxX,
            maxY: rectangleValue.maxY,
        };
    }

    function translatedRectangle(rectangleValue, dx, dy) {
        return {
            minX: rectangleValue.minX + dx,
            minY: rectangleValue.minY + dy,
            maxX: rectangleValue.maxX + dx,
            maxY: rectangleValue.maxY + dy,
        };
    }

    function expandRectangle(rectangleValue, padding) {
        return {
            minX: rectangleValue.minX - padding,
            minY: rectangleValue.minY - padding,
            maxX: rectangleValue.maxX + padding,
            maxY: rectangleValue.maxY + padding,
        };
    }

    function rectanglesOverlap(left, right) {
        return left.minX < right.maxX && left.maxX > right.minX &&
            left.minY < right.maxY && left.maxY > right.minY;
    }

    function rectWidth(rect) { return Math.max(1, rect.maxX - rect.minX); }
    function rectHeight(rect) { return Math.max(1, rect.maxY - rect.minY); }
    function nodeBuildId(node) { return node.buildId || "workspace"; }
    function nodeProjectId(node) { return node.projectId || "__build__"; }
    function nodeProjectKey(node) { return scopeKey(nodeBuildId(node), nodeProjectId(node)); }
    function scopeKey(buildId, projectId) { return String(buildId) + "\u0000" + String(projectId); }
    function clamp(value, minimum, maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    function byId(left, right) { return String(left.id).localeCompare(String(right.id)); }
    function squaredDistance(left, right) {
        var dx = right.x - left.x;
        var dy = right.y - left.y;
        return dx * dx + dy * dy;
    }
    function safeDomId(value) { return String(value).replace(/[^A-Za-z0-9_-]/g, "-"); }
    function cssEscape(value) {
        if (global.CSS && typeof global.CSS.escape === "function") return global.CSS.escape(value);
        return String(value).replace(/["\\]/g, "\\$&");
    }
    function reducedMotion() {
        return Boolean(global.matchMedia && global.matchMedia("(prefers-reduced-motion: reduce)").matches);
    }
    function roundViewport(value) { return Math.round(Number(value || 0) * 1000) / 1000; }
    function setAttribute(element, name, value) { element.setAttribute(name, String(value)); }

    global.docxAtlasD3 = Object.freeze({
        version: "1",
        mount: mount,
        update: update,
        command: command,
        snapshot: snapshot,
        destroy: destroy,
    });
})(window);
