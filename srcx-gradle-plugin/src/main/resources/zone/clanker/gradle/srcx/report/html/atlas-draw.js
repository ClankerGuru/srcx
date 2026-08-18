(function () {
    "use strict";

    window.srcxAtlasDraw = drawSeed;
    window.srcxAtlasBoot = bootFromSqlite;

    function bootFromSqlite() {
        if (!window.d3) {
            console.error("SRCX atlas boot: D3 is missing");
            return;
        }
        loadSqliteBytes()
            .then(function (bytes) {
                if (typeof window.srcxAtlasReadSeed !== "function") {
                    throw new Error("Wasm seed reader is not ready");
                }
                window.srcxAtlasReadSeed(bytesToBase64(bytes));
            })
            .catch(function (error) {
                console.error("SRCX atlas seed failed", error);
            });
    }

    function loadSqliteBytes() {
        var url = new URL("atlas.sqlite", document.baseURI).href;
        return fetchBytes(url).catch(function () {
            return xhrBytes(url);
        }).catch(function () {
            if (window.srcxAtlasSqliteBase64) {
                return decodeBase64(window.srcxAtlasSqliteBase64);
            }
            throw new Error("atlas.sqlite could not be read from " + url);
        });
    }

    function fetchBytes(url) {
        return fetch(url).then(function (response) {
            if (!response.ok) throw new Error("fetch " + url + " -> " + response.status);
            return response.arrayBuffer();
        }).then(function (buffer) {
            return new Uint8Array(buffer);
        });
    }

    function xhrBytes(url) {
        return new Promise(function (resolve, reject) {
            var request = new XMLHttpRequest();
            request.open("GET", url, true);
            request.responseType = "arraybuffer";
            request.onload = function () {
                if (request.status === 0 || (request.status >= 200 && request.status < 300)) {
                    resolve(new Uint8Array(request.response));
                    return;
                }
                reject(new Error("xhr " + url + " -> " + request.status));
            };
            request.onerror = function () {
                reject(new Error("xhr " + url + " failed"));
            };
            request.send();
        });
    }

    function bytesToBase64(bytes) {
        var chunk = 0x8000;
        var pieces = [];
        for (var index = 0; index < bytes.length; index += chunk) {
            pieces.push(String.fromCharCode.apply(null, bytes.subarray(index, index + chunk)));
        }
        return btoa(pieces.join(""));
    }

    function decodeBase64(encoded) {
        var binary = atob(encoded);
        var bytes = new Uint8Array(binary.length);
        for (var index = 0; index < binary.length; index += 1) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
    }

    function drawSeed(root, data) {
        if (!root || !window.d3 || !data || !Array.isArray(data.fileNodes)) return;
        if (data.fileNodes.length === 0) return;
        var svgElement = root.querySelector("[data-srcx-graph-svg]");
        var fallback = root.querySelector("[data-srcx-graph-fallback]");
        var viewport = root.querySelector(".srcx-dashboard__architecture-viewport");
        if (!svgElement || !viewport) return;
        if (fallback) fallback.hidden = true;
        svgElement.removeAttribute("hidden");
        var width = Math.max(320, viewport.clientWidth || 1024);
        var height = Math.max(320, viewport.clientHeight || 640);
        var svg = d3.select(svgElement);
        svg.selectAll("*").remove();
        svg.attr("viewBox", "0 0 " + width + " " + height);
        var nodes = data.fileNodes.map(function (node) {
            var records = Number(node.relationshipRecordCount || 0);
            return {
                id: node.id,
                name: node.name,
                path: node.path,
                build: node.build,
                project: node.project,
                sourceSet: node.sourceSet,
                symbols: node.symbols || [],
                content: node.content || "",
                important: !!node.important,
                relationshipRecordCount: records,
                visualSignal: Math.min(1, records / 24),
            };
        });
        var layout = layoutLooseRooms(nodes, width, height);
        var rooms = svg.append("g").attr("class", "srcx-dashboard__architecture-svg-rooms");
        layout.cells.forEach(function (cell, build) {
            rooms.append("rect")
                .attr("class", "srcx-dashboard__architecture-svg-build")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", Math.max(1, cell.maxY - cell.minY));
            rooms.append("text")
                .attr("class", "srcx-dashboard__architecture-svg-build-label")
                .attr("x", cell.minX + 10)
                .attr("y", cell.minY + 16)
                .text(build);
        });
        var homes = layout.homes;
        var edges = svg.append("g").attr("class", "srcx-dashboard__architecture-svg-edges");
        (data.fileEdges || []).forEach(function (edge) {
            var source = homes.get(edge.source);
            var target = homes.get(edge.target);
            if (!source || !target) return;
            edges.append("line")
                .attr("class", "srcx-dashboard__architecture-svg-edge")
                .attr("x1", source.x)
                .attr("y1", source.y)
                .attr("x2", target.x)
                .attr("y2", target.y);
        });
        var nodeLayer = svg.append("g").attr("class", "srcx-dashboard__architecture-svg-nodes");
        var groups = nodeLayer.selectAll("g")
            .data(nodes, function (node) { return node.id; })
            .join("g")
            .attr("class", function (node) {
                return "srcx-dashboard__architecture-svg-node" + (node.important ? " is-important" : "");
            })
            .attr("transform", function (node) {
                var home = homes.get(node.id) || { x: width / 2, y: height / 2 };
                return "translate(" + home.x + "," + home.y + ")";
            });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-hit")
            .attr("r", function (node) { return outerNodeRadius(node) + 6; });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-dot")
            .attr("r", nodeRadius);
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-core")
            .attr("r", function (node) { return Math.max(2, nodeRadius(node) * 0.36); });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-ring is-importance-ring")
            .attr("r", function (node) { return nodeRadius(node) + 4; });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-ring is-finding-ring")
            .attr("r", function (node) { return nodeRadius(node) + 7; });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-ring is-cycle-ring")
            .attr("r", function (node) { return nodeRadius(node) + 10; });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-ring is-analysis-cycle-ring")
            .attr("r", function (node) { return nodeRadius(node) + 13; });
        groups.append("text")
            .attr("class", "srcx-dashboard__architecture-svg-node-name srcx-dashboard__architecture-svg-node-label")
            .attr("dx", function (node) { return outerNodeRadius(node) + 6; })
            .attr("dy", "0.32em")
            .attr("text-anchor", "start")
            .text(function (node) { return node.name; });
        root.dataset.srcxEnhanced = "true";
        root.dataset.srcxSeedCount = String(nodes.length);
        fillFileSource(root, nodes[0]);
    }

    function fillFileSource(root, node) {
        if (!node) return;
        var title = root.querySelector("[data-srcx-detail-title]");
        var fields = root.querySelector("[data-srcx-detail-fields]");
        if (title) title.textContent = node.path || node.name;
        if (fields && node.content) fields.textContent = node.content;
    }

    function nodeRadius(node) {
        var signal = Math.max(0, Math.min(1, Number(node.visualSignal || 0)));
        return 4.5 + Math.pow(signal, 0.72) * 15.5;
    }

    function outerNodeRadius(node) {
        var radius = nodeRadius(node);
        if (node && node.important) radius = Math.max(radius, nodeRadius(node) + 4);
        return radius;
    }

    function layoutLooseRooms(nodes, width, height) {
        var pad = 10;
        var overlay = 44;
        var frame = {
            x: pad,
            y: pad + overlay,
            w: Math.max(1, width - pad * 2),
            h: Math.max(1, height - pad * 2 - overlay),
        };
        var builds = Array.from(d3.group(nodes, function (node) { return node.build; }), function (entry) {
            return {
                key: entry[0],
                members: entry[1].slice().sort(function (left, right) { return left.id.localeCompare(right.id); }),
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var gap = 16;
        var heading = 22;
        var rows = Math.max(1, Math.ceil(Math.sqrt(builds.length)));
        var cols = Math.max(1, Math.ceil(builds.length / rows));
        var cellW = (frame.w - gap * (cols - 1)) / cols;
        var cellH = (frame.h - gap * (rows - 1)) / rows;
        var cells = new Map();
        var homes = new Map();
        builds.forEach(function (build, index) {
            var col = index % cols;
            var row = Math.floor(index / cols);
            var cell = {
                minX: frame.x + col * (cellW + gap),
                minY: frame.y + row * (cellH + gap),
                maxX: frame.x + col * (cellW + gap) + cellW,
                maxY: frame.y + row * (cellH + gap) + cellH,
            };
            cells.set(build.key, cell);
            var inner = {
                x: cell.minX + 16,
                y: cell.minY + heading,
                w: Math.max(40, cell.maxX - cell.minX - 32),
                h: Math.max(40, cell.maxY - cell.minY - heading - 12),
            };
            var packed = packLooseParticles(build.members, inner);
            build.members.forEach(function (node) {
                var placed = packed.get(node.id);
                if (placed) homes.set(node.id, placed);
            });
        });
        return { cells: cells, homes: homes };
    }

    function packLooseParticles(members, inner) {
        var placed = new Map();
        if (!members.length) return placed;
        var count = members.length;
        var cols = Math.max(2, Math.round(Math.sqrt(count * (inner.w / Math.max(1, inner.h)))));
        var rows = Math.max(1, Math.ceil(count / cols));
        var stepX = inner.w / cols;
        var stepY = inner.h / rows;
        members.forEach(function (node, index) {
            var col = index % cols;
            var row = Math.floor(index / cols);
            var jitterX = ((index * 37) % 7) - 3;
            var jitterY = ((index * 53) % 7) - 3;
            placed.set(node.id, {
                x: inner.x + (col + 0.5) * stepX + jitterX,
                y: inner.y + (row + 0.5) * stepY + jitterY,
            });
        });
        return placed;
    }
})();
