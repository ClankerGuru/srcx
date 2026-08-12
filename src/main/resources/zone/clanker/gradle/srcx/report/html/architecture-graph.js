(function () {
    "use strict";

    if (!window.d3) return;

    document.querySelectorAll("[data-srcx-architecture-graph]").forEach(enhanceGraph);

    function enhanceGraph(root, graphIndex) {
        if (root.dataset.srcxEnhanced === "true") return;
        var dataElement = root.querySelector("[data-srcx-architecture-data]");
        var svgElement = root.querySelector("[data-srcx-graph-svg]");
        var fallback = root.querySelector("[data-srcx-graph-fallback]");
        var controls = root.querySelector("[data-srcx-graph-controls]");
        var viewport = root.querySelector(".srcx-dashboard__architecture-viewport");
        var detail = root.querySelector("[data-srcx-detail]");
        if (!dataElement || !svgElement || !fallback || !controls || !viewport || !detail) return;

        var data;
        try {
            data = JSON.parse(dataElement.textContent);
        } catch (ignored) {
            return;
        }
        if (!data.fileNodes || data.fileNodes.length === 0) return;

        var state = {
            view: data.defaultView || "files",
            query: "",
            selectedId: null,
            selectedType: null,
        };
        var svg = d3.select(svgElement);
        var width = viewport.clientWidth || 1000;
        var height = graphHeight();
        var zoomLayer;
        var nodeGroups;
        var edgeGroups;
        var nodes = [];
        var links = [];
        var simulation;
        var zoom = d3.zoom()
            .scaleExtent([0.25, 4])
            .filter(function (event) {
                return event.type !== "wheel" || event.ctrlKey || event.metaKey;
            })
            .on("zoom", function (event) {
                if (zoomLayer) zoomLayer.attr("transform", event.transform);
            });

        svg.attr("viewBox", "0 0 " + width + " " + height).call(zoom).on("dblclick.zoom", null);
        wireControls();
        draw();

        root.dataset.srcxEnhanced = "true";
        fallback.hidden = true;
        controls.hidden = false;
        svgElement.hidden = false;

        if (window.ResizeObserver) new ResizeObserver(resize).observe(viewport);

        function draw() {
            if (simulation) simulation.stop();
            svg.selectAll("*").remove();
            var projection = projectionForView();
            nodes = projection.nodes;
            links = projection.links;
            if (nodes.length === 0) {
                svg.append("text")
                    .attr("class", "srcx-dashboard__architecture-empty-label")
                    .attr("x", width / 2)
                    .attr("y", height / 2)
                    .text(emptyViewMessage());
                nodeGroups = svg.selectAll(".srcx-dashboard__architecture-svg-node");
                edgeGroups = svg.selectAll(".srcx-dashboard__architecture-svg-edge");
                updateStatus(projection);
                return;
            }
            var nodeById = new Map(nodes.map(function (node) { return [node.id, node]; }));
            links = links.filter(function (edge) {
                return nodeById.has(endpointId(edge.source)) && nodeById.has(endpointId(edge.target));
            });

            var markerId = "srcx-atlas-arrow-" + graphIndex;
            svg.append("defs").append("marker")
                .attr("id", markerId)
                .attr("viewBox", "0 0 8 8")
                .attr("refX", 8)
                .attr("refY", 4)
                .attr("markerWidth", 7)
                .attr("markerHeight", 7)
                .attr("orient", "auto")
                .append("path")
                .attr("d", "M0,0 L8,4 L0,8 Z");

            zoomLayer = svg.append("g").attr("class", "srcx-dashboard__architecture-zoom-layer");
            edgeGroups = zoomLayer.append("g")
                .selectAll("g")
                .data(links, function (edge) { return edge.id; })
                .join("g")
                .attr("class", edgeClass)
                .attr("role", "button")
                .attr("tabindex", -1)
                .attr("aria-label", edgeLabel)
                .on("click", function (event, edge) {
                    event.stopPropagation();
                    selectEdge(edge);
                });
            edgeGroups.append("path")
                .attr("marker-end", "url(#" + markerId + ")")
                .attr("stroke-width", function (edge) { return Math.min(4, 0.8 + Math.log2((edge.count || 1) + 1)); });

            nodeGroups = zoomLayer.append("g")
                .selectAll("g")
                .data(nodes, function (node) { return node.id; })
                .join("g")
                .attr("class", nodeClass)
                .attr("role", "button")
                .attr("tabindex", function (_, index) { return index === 0 ? 0 : -1; })
                .attr("aria-label", function (node) { return "Select " + nodeLabel(node); })
                .on("mouseenter focus", function (event, node) { highlight(node.id); })
                .on("mouseleave blur", restoreHighlight)
                .on("click", function (event, node) {
                    event.stopPropagation();
                    selectNode(node);
                })
                .on("keydown", function (event, node) {
                    if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        selectNode(node);
                    }
                });
            nodeGroups.append("circle")
                .attr("r", nodeRadius)
                .attr("fill", function (node) { return nodeColor(root, node); });
            nodeGroups.append("text")
                .attr("class", "srcx-dashboard__architecture-svg-node-name")
                .attr("x", function (node) { return nodeRadius(node) + 6; })
                .attr("y", 4)
                .text(function (node) { return shorten(nodeLabel(node), 30); });
            nodeGroups.append("title").text(function (node) { return nodeTitle(node); });

            var centers = groupCenters(nodes, width, height);
            initializeNodes(nodes, centers);
            simulation = d3.forceSimulation(nodes)
                .randomSource(d3.randomLcg(0.42))
                .force("link", d3.forceLink(links).id(function (node) { return node.id; }).distance(linkDistance).strength(0.18))
                .force("charge", d3.forceManyBody().strength(-210).distanceMax(480))
                .force("collision", d3.forceCollide().radius(function (node) { return nodeRadius(node) + 13; }).strength(0.92))
                .force("x", d3.forceX(function (node) { return centers.get(nodeGroup(node)).x; }).strength(0.11))
                .force("y", d3.forceY(function (node) { return centers.get(nodeGroup(node)).y; }).strength(0.11))
                .on("tick", ticked)
                .on("end", function () { window.setTimeout(fitGraph, 0); });
            nodeGroups.call(d3.drag().on("start", dragStarted).on("drag", dragged).on("end", dragEnded));
            svg.on("click", clearSelection).on("keydown", function (event) {
                if (event.key === "Escape") clearSelection();
            });
            updateStatus(projection);
            window.setTimeout(fitGraph, 260);
        }

        function projectionForView() {
            var cycleMembers = new Set(data.cycles.flatMap(function (cycle) { return cycle.memberIds; }));
            var cycleEdges = new Set(data.cycles.flatMap(function (cycle) { return cycle.edgeIds; }));
            var fileNodes = data.fileNodes.map(function (node) {
                return Object.assign({}, node, { entityType: "file", clusterId: node.build + "::" + node.project });
            });
            var fileLinks = data.fileEdges.map(function (edge) { return Object.assign({}, edge, { entityType: "file-edge" }); });
            if (state.view === "symbols") {
                return filteredProjection(
                    data.nodes.map(function (node) { return Object.assign({}, node, { entityType: "symbol" }); }),
                    data.edges.map(function (edge) { return Object.assign({}, edge, { entityType: "symbol-edge" }); }),
                );
            }
            if (state.view === "problems") {
                fileNodes = fileNodes.filter(function (node) {
                    return node.fileFindingCount > 0 || cycleMembers.has(node.id);
                });
                var problemIds = new Set(fileNodes.map(function (node) { return node.id; }));
                fileLinks = fileLinks.filter(function (edge) {
                    return problemIds.has(edge.source) && problemIds.has(edge.target);
                });
            }
            if (state.view === "cycles") {
                fileNodes = fileNodes.filter(function (node) { return cycleMembers.has(node.id); });
                fileLinks = fileLinks.filter(function (edge) { return cycleEdges.has(edge.id); });
            }
            return filteredProjection(fileNodes, fileLinks);
        }

        function filteredProjection(allNodes, allLinks) {
            var query = state.query.trim().toLowerCase();
            var filteredNodes = query ? allNodes.filter(function (node) {
                return searchable(node).toLowerCase().includes(query);
            }) : allNodes;
            var ids = new Set(filteredNodes.map(function (node) { return node.id; }));
            var filteredLinks = allLinks.filter(function (edge) {
                return ids.has(endpointId(edge.source)) && ids.has(endpointId(edge.target));
            });
            return { nodes: filteredNodes, links: filteredLinks };
        }

        function ticked() {
            nodeGroups.attr("transform", function (node) { return "translate(" + node.x + "," + node.y + ")"; });
            edgeGroups.select("path").attr("d", function (edge) {
                var sourceRadius = nodeRadius(edge.source) + 2;
                var targetRadius = nodeRadius(edge.target) + 7;
                var dx = edge.target.x - edge.source.x;
                var dy = edge.target.y - edge.source.y;
                var length = Math.max(1, Math.sqrt(dx * dx + dy * dy));
                var sx = edge.source.x + dx / length * sourceRadius;
                var sy = edge.source.y + dy / length * sourceRadius;
                var tx = edge.target.x - dx / length * targetRadius;
                var ty = edge.target.y - dy / length * targetRadius;
                return "M" + sx + "," + sy + " L" + tx + "," + ty;
            });
        }

        function selectNode(node) {
            state.selectedId = node.id;
            state.selectedType = node.entityType;
            highlight(node.id);
            renderNodeDetail(node);
        }

        function selectEdge(edge) {
            state.selectedId = edge.id;
            state.selectedType = edge.entityType;
            highlightEdge(edge);
            renderEdgeDetail(edge);
        }

        function clearSelection() {
            state.selectedId = null;
            state.selectedType = null;
            detail.hidden = true;
            restoreHighlight();
        }

        function highlight(nodeId) {
            var neighborIds = new Set([nodeId]);
            links.forEach(function (edge) {
                var source = endpointId(edge.source);
                var target = endpointId(edge.target);
                if (source === nodeId) neighborIds.add(target);
                if (target === nodeId) neighborIds.add(source);
            });
            nodeGroups.classed("is-dimmed", function (node) { return !neighborIds.has(node.id); })
                .classed("is-selected", function (node) { return node.id === nodeId; });
            edgeGroups.classed("is-active", function (edge) {
                return endpointId(edge.source) === nodeId || endpointId(edge.target) === nodeId;
            }).classed("is-dimmed", function (edge) {
                return endpointId(edge.source) !== nodeId && endpointId(edge.target) !== nodeId;
            });
        }

        function highlightEdge(edge) {
            var source = endpointId(edge.source);
            var target = endpointId(edge.target);
            nodeGroups.classed("is-dimmed", function (node) { return node.id !== source && node.id !== target; });
            edgeGroups.classed("is-active", function (candidate) { return candidate.id === edge.id; })
                .classed("is-dimmed", function (candidate) { return candidate.id !== edge.id; });
        }

        function restoreHighlight() {
            if (state.selectedId) {
                if (state.selectedType && state.selectedType.includes("edge")) {
                    var edge = links.find(function (item) { return item.id === state.selectedId; });
                    if (edge) return highlightEdge(edge);
                }
                return highlight(state.selectedId);
            }
            if (!nodeGroups || !edgeGroups) return;
            nodeGroups.classed("is-dimmed", false).classed("is-selected", false);
            edgeGroups.classed("is-active", false).classed("is-dimmed", false);
        }

        function renderNodeDetail(node) {
            openDetail(node.entityType === "file" ? "File" : "Symbol", nodeLabel(node));
            var body = detail.querySelector("[data-srcx-detail-fields]");
            if (node.entityType === "file") renderFileFacts(body, node);
            else renderSymbolFacts(body, node);
            renderMiniFlow(body, node);
        }

        function renderFileFacts(body, node) {
            body.appendChild(factList([
                ["Scope", node.build + " / " + node.project + " / " + node.sourceSet],
                ["Path", node.path],
                ["Relationships", node.incomingCount + " inbound / " + node.outgoingCount + " outbound / " + node.internalCount + " internal"],
                ["Importance", node.importanceReasons.length ? node.importanceReasons.join(", ") : "No policy signal"],
            ]));
            appendSection(body, "Declarations", node.symbols.length ? node.symbols.map(function (symbol) {
                return symbol.kind + " / " + symbol.name + " / line " + symbol.line;
            }) : ["No declaration details in the bounded index"]);
            var findings = data.findings.filter(function (finding) { return node.findingIds.includes(finding.id); });
            if (findings.length) appendFindingSection(body, findings);
            var unlocated = data.findings.filter(function (finding) {
                return finding.build === node.build && finding.project === node.project && finding.filePath === null;
            }).length;
            if (unlocated) appendSection(body, "Project-only findings", [unlocated + " findings have no exact file evidence"]);
            var cycles = data.cycles.filter(function (cycle) { return cycle.memberIds.includes(node.id); });
            if (cycles.length) appendSection(body, "Resolved cycles", cycles.map(function (cycle) {
                return cycle.memberIds.length + " files / " + cycle.edgeIds.length + " internal links";
            }));
        }

        function renderSymbolFacts(body, node) {
            body.appendChild(factList([
                ["Declaration", node.file + ":" + node.line],
                ["Scope", node.build + " / " + node.project + " / " + node.sourceSet],
                ["Kind", node.kind],
                ["Usage", node.workspaceInbound + " inbound / " + node.outgoingCount + " outbound / " + node.crossBuildInbound + " cross-build inbound"],
                ["Importance", node.importanceReasons.length ? node.importanceReasons.join(", ") : "No policy signal"],
            ]));
        }

        function renderEdgeDetail(edge) {
            var source = entityById(endpointId(edge.source));
            var target = entityById(endpointId(edge.target));
            openDetail("Relationship", nodeLabel(source) + " -> " + nodeLabel(target));
            var body = detail.querySelector("[data-srcx-detail-fields]");
            var kinds = edge.kindCounts ? edge.kindCounts.map(countLabel).join(", ") : edge.label;
            var evidence = edge.evidenceCounts ? edge.evidenceCounts.map(countLabel).join(", ") : edge.evidence;
            body.appendChild(factList([
                ["Direction", nodeLabel(source) + " -> " + nodeLabel(target)],
                ["Kinds", kinds],
                ["Evidence", evidence],
                ["Boundary", edge.crossBuild ? "Cross-build" : "Within build"],
                ["Records", String(edge.count)],
            ]));
            var occurrences = edge.occurrences || [];
            appendSection(body, "Source evidence", occurrences.slice(0, 12).map(function (occurrence) {
                return occurrence.file + ":" + occurrence.line + " / " + occurrence.context;
            }));
        }

        function renderMiniFlow(body, selected) {
            var incoming = links.filter(function (edge) { return endpointId(edge.target) === selected.id; });
            var outgoing = links.filter(function (edge) { return endpointId(edge.source) === selected.id; });
            var flow = document.createElement("section");
            flow.className = "srcx-dashboard__architecture-flow";
            var heading = document.createElement("h4");
            heading.textContent = "Relationship flow";
            flow.appendChild(heading);
            var lanes = document.createElement("div");
            lanes.appendChild(flowLane("Inbound", incoming, true));
            var center = document.createElement("div");
            center.className = "srcx-dashboard__architecture-flow-center";
            center.textContent = nodeLabel(selected);
            lanes.appendChild(center);
            lanes.appendChild(flowLane("Outbound", outgoing, false));
            flow.appendChild(lanes);
            body.appendChild(flow);
        }

        function flowLane(label, relationships, inbound) {
            var lane = document.createElement("div");
            var title = document.createElement("span");
            title.textContent = label;
            lane.appendChild(title);
            relationships.slice(0, 5).forEach(function (edge) {
                var neighborId = inbound ? endpointId(edge.source) : endpointId(edge.target);
                var neighbor = entityById(neighborId);
                var button = document.createElement("button");
                button.type = "button";
                button.textContent = nodeLabel(neighbor) + " / " + compactEdgeLabel(edge);
                button.addEventListener("click", function () { selectEdge(edge); });
                lane.appendChild(button);
            });
            if (relationships.length > 5) {
                var more = document.createElement("small");
                more.textContent = "+" + (relationships.length - 5) + " more";
                lane.appendChild(more);
            }
            if (!relationships.length) {
                var empty = document.createElement("small");
                empty.textContent = "None shown";
                lane.appendChild(empty);
            }
            return lane;
        }

        function openDetail(kicker, title) {
            detail.hidden = false;
            detail.querySelector("[data-srcx-detail-kicker]").textContent = kicker;
            detail.querySelector("[data-srcx-detail-title]").textContent = title;
            detail.querySelector("[data-srcx-detail-fields]").replaceChildren();
        }

        function appendFindingSection(parent, findings) {
            var section = document.createElement("section");
            var heading = document.createElement("h4");
            heading.textContent = "Exact file findings";
            section.appendChild(heading);
            findings.forEach(function (finding) {
                var row = document.createElement("article");
                row.className = "srcx-dashboard__architecture-finding";
                var severity = document.createElement("span");
                severity.textContent = finding.severity;
                var message = document.createElement("strong");
                message.textContent = finding.message;
                var suggestion = document.createElement("p");
                suggestion.textContent = finding.suggestion;
                row.append(severity, message, suggestion);
                section.appendChild(row);
            });
            parent.appendChild(section);
        }

        function appendSection(parent, title, rows) {
            var section = document.createElement("section");
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

        function wireControls() {
            controls.querySelectorAll("[data-srcx-graph-view]").forEach(function (button) {
                button.addEventListener("click", function () {
                    state.view = button.dataset.srcxGraphView;
                    state.selectedId = null;
                    detail.hidden = true;
                    controls.querySelectorAll("[data-srcx-graph-view]").forEach(function (candidate) {
                        candidate.setAttribute("aria-pressed", String(candidate === button));
                    });
                    draw();
                });
            });
            var search = controls.querySelector("[data-srcx-graph-search]");
            search.addEventListener("input", function () {
                state.query = search.value;
                draw();
            });
            controls.querySelectorAll("[data-srcx-graph-action]").forEach(function (button) {
                button.addEventListener("click", function () {
                    var action = button.dataset.srcxGraphAction;
                    if (action === "zoom-in") svg.transition().duration(130).call(zoom.scaleBy, 1.28);
                    if (action === "zoom-out") svg.transition().duration(130).call(zoom.scaleBy, 1 / 1.28);
                    if (action === "fit") fitGraph();
                    if (action === "reset") draw();
                });
            });
            detail.querySelector("[data-srcx-detail-close]").addEventListener("click", clearSelection);
            root.addEventListener("keydown", function (event) {
                if (event.key === "Escape") clearSelection();
            });
        }

        function updateStatus(projection) {
            var status = root.querySelector("[data-srcx-graph-status]");
            var label = state.view.charAt(0).toUpperCase() + state.view.slice(1);
            status.textContent = label + " / " + projection.nodes.length + " nodes / " + projection.links.length + " links";
        }

        function emptyViewMessage() {
            if (state.view === "cycles") return "No exact file cycles in the bounded map";
            if (state.view === "problems") return "No exact file findings or structural signals in the bounded map";
            return "No nodes match this view";
        }

        function fitGraph() {
            if (!zoomLayer || !zoomLayer.node()) return;
            var bounds = zoomLayer.node().getBBox();
            if (!bounds.width || !bounds.height) return;
            var scale = Math.min(2.2, 0.88 / Math.max(bounds.width / width, bounds.height / height));
            var x = width / 2 - scale * (bounds.x + bounds.width / 2);
            var y = height / 2 - scale * (bounds.y + bounds.height / 2);
            svg.transition().duration(reducedMotion() ? 0 : 180)
                .call(zoom.transform, d3.zoomIdentity.translate(x, y).scale(scale));
        }

        function resize() {
            var nextWidth = viewport.clientWidth || width;
            var nextHeight = graphHeight();
            if (nextWidth === width && nextHeight === height) return;
            width = nextWidth;
            height = nextHeight;
            svg.attr("viewBox", "0 0 " + width + " " + height);
            draw();
        }

        function dragStarted(event, node) {
            if (!event.active) simulation.alphaTarget(0.18).restart();
            node.fx = node.x;
            node.fy = node.y;
        }

        function dragged(event, node) {
            node.fx = event.x;
            node.fy = event.y;
        }

        function dragEnded(event, node) {
            if (!event.active) simulation.alphaTarget(0);
            node.fx = node.x;
            node.fy = node.y;
        }

        function entityById(id) {
            return nodes.find(function (node) { return node.id === id; });
        }
    }

    function groupCenters(nodes, width, height) {
        var groups = Array.from(new Set(nodes.map(nodeGroup))).sort();
        var count = Math.max(1, groups.length);
        var columns = Math.ceil(Math.sqrt(count * width / height));
        var rows = Math.ceil(count / columns);
        return new Map(groups.map(function (group, index) {
            return [group, {
                x: ((index % columns) + 0.5) * width / columns,
                y: (Math.floor(index / columns) + 0.5) * height / rows,
            }];
        }));
    }

    function initializeNodes(nodes, centers) {
        var grouped = d3.group(nodes, nodeGroup);
        grouped.forEach(function (members, group) {
            var center = centers.get(group);
            members.sort(function (left, right) { return left.id.localeCompare(right.id); });
            members.forEach(function (node, index) {
                var angle = index * 2.399963229728653;
                var radius = 28 + 24 * Math.sqrt(index);
                node.x = center.x + Math.cos(angle) * radius;
                node.y = center.y + Math.sin(angle) * radius;
            });
        });
    }

    function nodeGroup(node) {
        return node.clusterId || (node.build + "::" + node.project);
    }

    function nodeRadius(node) {
        if (node.entityType === "file") return Math.min(18, 7 + Math.sqrt(node.relationshipCount || 1));
        return node.important ? 10 : 6;
    }

    function nodeColor(root, node) {
        if (node.fileFindingCount > 0) return cssColor(root, "error");
        if (node.important) return cssColor(root, "primary");
        var colors = ["secondary", "accent", "tertiary", "primary"];
        var hash = Array.from(nodeGroup(node)).reduce(function (sum, character) { return sum + character.charCodeAt(0); }, 0);
        return cssColor(root, colors[hash % colors.length]);
    }

    function cssColor(root, name) {
        return getComputedStyle(root).getPropertyValue("--srcx-" + name).trim() || "#36d399";
    }

    function nodeClass(node) {
        var classes = "srcx-dashboard__architecture-svg-node";
        if (node.important) classes += " is-important";
        if (node.fileFindingCount > 0) classes += " has-problem";
        return classes;
    }

    function edgeClass(edge) {
        var classes = "srcx-dashboard__architecture-svg-edge";
        if (edge.crossBuild) classes += " is-cross-build";
        var evidence = edge.evidenceCounts || [];
        if (edge.evidence === "HEURISTIC" || evidence.some(function (item) { return item.evidence === "HEURISTIC"; })) {
            classes += " is-heuristic";
        }
        return classes;
    }

    function nodeLabel(node) {
        return node ? (node.name || node.qualifiedName || node.id) : "Unknown";
    }

    function nodeTitle(node) {
        if (node.entityType === "file") return node.path + "; " + node.scope;
        return node.qualifiedName + "; " + node.kind + "; " + node.file + ":" + node.line;
    }

    function edgeLabel(edge) {
        return endpointId(edge.source) + " to " + endpointId(edge.target) + "; " + (edge.count || 1) + " records";
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

    function linkDistance(edge) {
        return edge.crossBuild ? 190 : 115;
    }

    function graphHeight() {
        return Math.max(420, Math.min(600, Math.round((window.innerHeight || 800) * 0.58)));
    }

    function reducedMotion() {
        return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    }

    function shorten(value, limit) {
        return value.length <= limit ? value : value.slice(0, limit - 1) + "...";
    }
})();
