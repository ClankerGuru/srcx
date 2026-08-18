(function () {
    "use strict";

    window.srcxAtlasDraw = drawSeed;
    window.srcxAtlasBoot = bootFromSqlite;

    function bootFromSqlite() {
        if (!window.d3 || typeof window.srcxAtlasReadSeed !== "function") return;
        fetch("atlas.sqlite").then(function (response) {
            return response.arrayBuffer();
        }).then(function (buffer) {
            window.srcxAtlasReadSeed(bytesToBase64(new Uint8Array(buffer)));
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

    function drawSeed(root, data) {
        if (!root || !window.d3 || !data || !Array.isArray(data.fileNodes)) return;
        var svgElement = root.querySelector("[data-srcx-graph-svg]");
        var fallback = root.querySelector("[data-srcx-graph-fallback]");
        var viewport = root.querySelector(".srcx-dashboard__architecture-viewport");
        if (!svgElement || !viewport) return;
        if (fallback) fallback.hidden = true;
        svgElement.removeAttribute("hidden");
        var width = Math.max(320, viewport.clientWidth || 390);
        var height = Math.max(320, viewport.clientHeight || 390);
        var svg = d3.select(svgElement);
        svg.selectAll("*").remove();
        svg.attr("viewBox", "0 0 " + width + " " + height);
        var nodes = data.fileNodes.map(function (node) {
            return {
                id: node.id,
                name: node.name,
                path: node.path,
                build: node.build,
                project: node.project,
                sourceSet: node.sourceSet,
                symbols: node.symbols || [],
                content: node.content || "",
            };
        });
        var builds = {};
        (data.builds || []).forEach(function (build) { builds[build.name] = build; });
        var layout = layoutSeedToViewport(nodes, width, height, builds);
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
                .attr("x", cell.minX + 8)
                .attr("y", cell.minY + 12)
                .text(build);
        });
        var edges = svg.append("g").attr("class", "srcx-dashboard__architecture-svg-edges");
        (data.fileEdges || []).forEach(function (edge) {
            var source = layout.homes.get(edge.source);
            var target = layout.homes.get(edge.target);
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
            .attr("class", "srcx-dashboard__architecture-svg-node is-label-pinned")
            .attr("transform", function (node) {
                var home = layout.homes.get(node.id) || { x: width / 2, y: height / 2 };
                return "translate(" + home.x + "," + home.y + ")";
            });
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-hit")
            .attr("r", 16);
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-dot")
            .attr("r", 8);
        groups.append("circle")
            .attr("class", "srcx-dashboard__architecture-svg-node-core")
            .attr("r", 3);
        groups.append("text")
            .attr("class", "srcx-dashboard__architecture-svg-node-name srcx-dashboard__architecture-svg-node-label")
            .attr("dx", function (node) { return node.labelDirection === "left" ? -12 : 12; })
            .attr("text-anchor", function (node) { return node.labelDirection === "left" ? "end" : "start"; })
            .attr("dy", "0.32em")
            .text(function (node) { return node.name; });
        root.dataset.srcxEnhanced = "true";
        fillFileSource(root, nodes[0]);
    }

    function fillFileSource(root, node) {
        if (!node) return;
        var title = root.querySelector("[data-srcx-detail-title]");
        var fields = root.querySelector("[data-srcx-detail-fields]");
        if (title) title.textContent = node.path || node.name;
        if (fields && node.content) fields.textContent = node.content;
    }

    function layoutSeedToViewport(nodes, width, height, buildByName) {
        var gap = 8;
        var pad = 6;
        var overlay = 44;
        var frame = {
            x: pad,
            y: pad + overlay,
            w: Math.max(1, width - pad * 2),
            h: Math.max(1, height - pad * 2 - overlay),
        };
        var projects = Array.from(d3.group(nodes, function (node) {
            return node.build + "::" + node.project;
        }), function (entry) {
            var members = entry[1].slice().sort(function (left, right) { return left.id.localeCompare(right.id); });
            var rectangles = members.map(function (node) {
                return { key: node.id, width: 96, height: 24, nodeRadius: 8 };
            });
            return {
                key: entry[0],
                build: members[0].build,
                project: members[0].project,
                members: members,
                rectangles: rectangles,
                weight: members.length,
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var buildItems = Array.from(d3.group(projects, function (project) { return project.build; }), function (entry) {
            return {
                key: entry[0],
                build: entry[0],
                projects: entry[1],
                weight: entry[1].reduce(function (sum, project) { return sum + project.weight; }, 0),
            };
        }).sort(function (left, right) { return left.key.localeCompare(right.key); });
        var cols = seedLabelColumns(frame.w);
        var heading = 10;
        var bandGap = 2;
        var totalRows = buildItems.reduce(function (sum, item) {
            return sum + Math.max(1, Math.ceil(item.weight / Math.max(1, cols)));
        }, 0);
        var reserved = buildItems.length * (heading + bandGap);
        var rowH = Math.max(26, Math.min(28, (frame.h - reserved) / Math.max(1, totalRows)));
        var cursor = frame.y;
        var buildTiles = new Map();
        buildItems.forEach(function (buildItem) {
            var rows = Math.max(1, Math.ceil(buildItem.weight / Math.max(1, cols)));
            var band = heading + rows * rowH;
            buildTiles.set(buildItem.key, {
                x: frame.x,
                y: cursor,
                w: frame.w,
                h: band,
                cols: cols,
                heading: heading,
                rowH: rowH,
            });
            cursor += band + bandGap;
        });
        var cells = new Map();
        var homes = new Map();
        buildItems.forEach(function (buildItem) {
            var tile = buildTiles.get(buildItem.key);
            cells.set(buildItem.build, {
                minX: tile.x,
                minY: tile.y,
                maxX: tile.x + tile.w,
                maxY: tile.y + tile.h,
            });
            var allRects = [];
            buildItem.projects.forEach(function (project) {
                allRects = allRects.concat(project.rectangles);
            });
            var packed = packLabeledSeedGrid(allRects, tile);
            buildItem.projects.forEach(function (project) {
                project.members.forEach(function (node) {
                    var placed = packed.get(node.id);
                    if (!placed) return;
                    node.labelDirection = placed.direction;
                    homes.set(node.id, { x: placed.x, y: placed.y });
                });
            });
        });
        return { cells: cells, homes: homes };
    }

    function seedLabelColumns(width) {
        var cols = Math.max(1, Math.floor(Math.max(1, width) / 168));
        if (width >= 220 && cols < 2) cols = 2;
        return cols;
    }

    function packLabeledSeedGrid(rectangles, tile) {
        var placed = new Map();
        if (!rectangles.length) return placed;
        var ordered = rectangles.slice().sort(function (left, right) { return left.key.localeCompare(right.key); });
        var cols = tile.cols || seedLabelColumns(tile.w);
        var heading = tile.heading || 18;
        var rows = Math.max(1, Math.ceil(ordered.length / Math.max(1, cols)));
        var rowH = tile.rowH || Math.max(18, (tile.h - heading) / rows);
        var mid = tile.x + tile.w / 2;
        ordered.forEach(function (rectangle, index) {
            var col = index % cols;
            var row = Math.floor(index / cols);
            var leftCol = cols > 1 && col === 0;
            placed.set(rectangle.key, {
                x: leftCol ? mid - 14 : (cols > 1 ? mid + 14 : tile.x + 16),
                y: tile.y + heading + row * rowH + rowH / 2,
                direction: leftCol ? "left" : "right",
            });
        });
        return placed;
    }
})();
