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
        if (window.srcxAtlasSqliteBase64) {
            return Promise.resolve(decodeBase64(window.srcxAtlasSqliteBase64));
        }
        if (document.location.protocol === "file:") {
            return Promise.reject(new Error("atlas.sqlite sidecar missing on file://"));
        }
        var url = new URL("atlas.sqlite", document.baseURI).href;
        try {
            return fetchBytes(url).catch(function () {
                return xhrBytes(url);
            });
        } catch (error) {
            return Promise.reject(error);
        }
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
        var links = (data.fileEdges || []).map(function (edge) {
            return {
                id: edge.id,
                source: edge.source,
                target: edge.target,
                kind: edge.kind,
                recordCount: edge.recordCount,
            };
        });
        var layout = layoutRoomsOfAir(nodes, width, height);
        scatterParticlesInRooms(nodes, links, layout.cells);
        var rooms = svg.append("g").attr("class", "srcx-dashboard__architecture-build-regions");
        layout.cells.forEach(function (cell, build) {
            var group = rooms.append("g").attr("class", "srcx-dashboard__architecture-build-region");
            group.append("rect")
                .attr("class", "srcx-dashboard__architecture-build-region-body")
                .attr("x", cell.minX)
                .attr("y", cell.minY)
                .attr("width", Math.max(1, cell.maxX - cell.minX))
                .attr("height", Math.max(1, cell.maxY - cell.minY));
            group.append("text")
                .attr("x", cell.minX + 10)
                .attr("y", cell.minY + 16)
                .text(build);
        });
        var nodeById = new Map(nodes.map(function (node) { return [node.id, node]; }));
        var edges = svg.append("g").attr("class", "srcx-dashboard__architecture-svg-edges");
        links.forEach(function (edge) {
            var source = typeof edge.source === "object" ? edge.source : nodeById.get(edge.source);
            var target = typeof edge.target === "object" ? edge.target : nodeById.get(edge.target);
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
                return "translate(" + node.x + "," + node.y + ")";
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

    function layoutRoomsOfAir(nodes, width, height) {
        var pad = 12;
        var overlay = 44;
        var frame = {
            x: pad,
            y: pad + overlay,
            w: Math.max(1, width - pad * 2),
            h: Math.max(1, height - pad * 2 - overlay),
        };
        var builds = Array.from(d3.group(nodes, function (node) { return node.build; }), function (entry) {
            return { key: entry[0], members: entry[1], weight: Math.max(1, entry[1].length) };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var packed = packWeightedRooms(builds, frame);
        var cells = new Map();
        builds.forEach(function (build) {
            cells.set(build.key, packed.get(build.key));
        });
        return { cells: cells };
    }

    function packWeightedRooms(builds, frame) {
        var cells = new Map();
        var gap = 18;
        var cols = builds.length <= 3 ? builds.length : 3;
        var rowWeight = [];
        var rowItems = [];
        builds.forEach(function (build, index) {
            var row = Math.floor(index / cols);
            if (!rowItems[row]) {
                rowItems[row] = [];
                rowWeight[row] = 0;
            }
            rowItems[row].push(build);
            rowWeight[row] += build.weight;
        });
        var weightSum = rowWeight.reduce(function (sum, value) { return sum + value; }, 0) || 1;
        var y = frame.y;
        rowItems.forEach(function (items, row) {
            var rowH = Math.max(120, frame.h * (rowWeight[row] / weightSum) - gap);
            var x = frame.x;
            var rowTotal = items.reduce(function (sum, item) { return sum + item.weight; }, 0) || 1;
            items.forEach(function (item) {
                var cellW = Math.max(140, (frame.w - gap * (items.length - 1)) * (item.weight / rowTotal));
                cells.set(item.key, {
                    minX: x,
                    minY: y,
                    maxX: x + cellW,
                    maxY: y + rowH,
                });
                x += cellW + gap;
            });
            y += rowH + gap;
        });
        return cells;
    }

    function scatterParticlesInRooms(nodes, links, cells) {
        var byBuild = d3.group(nodes, function (node) { return node.build; });
        byBuild.forEach(function (members, build) {
            var cell = cells.get(build);
            if (!cell) return;
            scatterParticlesInRoom(members, links, cell);
        });
    }

    function scatterParticlesInRoom(members, links, cell) {
        var heading = 26;
        var pad = 22;
        var minX = cell.minX + pad;
        var maxX = cell.maxX - pad;
        var minY = cell.minY + heading;
        var maxY = cell.maxY - pad;
        var cx = (minX + maxX) / 2;
        var cy = (minY + maxY) / 2;
        var rx = Math.max(24, (maxX - minX) / 2);
        var ry = Math.max(24, (maxY - minY) / 2);
        var golden = Math.PI * (3 - Math.sqrt(5));
        members.forEach(function (node, index) {
            var t = (index + 0.5) / Math.max(1, members.length);
            var angle = index * golden;
            var radius = Math.sqrt(t);
            node.x = cx + Math.cos(angle) * radius * rx * 0.78;
            node.y = cy + Math.sin(angle) * radius * ry * 0.78;
        });
        var foci = new Map();
        var groups = Array.from(d3.group(members, function (node) {
            return node.project + "::" + node.sourceSet;
        }));
        groups.forEach(function (entry, index) {
            var angle = index * golden + 1.1;
            var radius = groups.length === 1 ? 0 : 0.28 + (index % 3) * 0.12;
            foci.set(entry[0], {
                x: cx + Math.cos(angle) * radius * rx,
                y: cy + Math.sin(angle) * radius * ry,
            });
        });
        var memberIds = new Set(members.map(function (node) { return node.id; }));
        var roomLinks = links.filter(function (edge) {
            var sourceId = edge.source && edge.source.id ? edge.source.id : edge.source;
            var targetId = edge.target && edge.target.id ? edge.target.id : edge.target;
            return memberIds.has(sourceId) && memberIds.has(targetId);
        }).map(function (edge) {
            return {
                source: edge.source && edge.source.id ? edge.source.id : edge.source,
                target: edge.target && edge.target.id ? edge.target.id : edge.target,
            };
        });
        var simulation = d3.forceSimulation(members)
            .force("charge", d3.forceManyBody().strength(-64))
            .force("collide", d3.forceCollide().radius(function (node) {
                return outerNodeRadius(node) + 16;
            }).iterations(3))
            .force("x", d3.forceX(function (node) {
                var focus = foci.get(node.project + "::" + node.sourceSet);
                return focus ? focus.x : cx;
            }).strength(0.07))
            .force("y", d3.forceY(function (node) {
                var focus = foci.get(node.project + "::" + node.sourceSet);
                return focus ? focus.y : cy;
            }).strength(0.07))
            .stop();
        if (roomLinks.length) {
            simulation.force("link", d3.forceLink(roomLinks).id(function (node) {
                return node.id;
            }).distance(72).strength(0.08));
        }
        var ticks = 0;
        while (ticks < 140) {
            simulation.tick();
            members.forEach(function (node) {
                var radius = outerNodeRadius(node) + 10;
                node.x = Math.max(minX + radius, Math.min(maxX - radius, node.x));
                node.y = Math.max(minY + radius, Math.min(maxY - radius, node.y));
            });
            ticks += 1;
        }
    }
})();
