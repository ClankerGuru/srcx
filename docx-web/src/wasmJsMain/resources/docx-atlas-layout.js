(function (global) {
    "use strict";

    const minimumNodeWidth = 172;
    const minimumNodeHeight = 66;
    const headerHeight = 28;
    const padding = 14;
    const gap = 14;

    function compute(request) {
        const frame = parseSlice(request.sliceJson);
        const layoutState = request.preserveResolvedLayoutState
            ? retainLayoutState(frame, request.layoutState || {})
            : resolveLayoutState(frame, request.layoutState || {});
        const geometry = layoutFrame({
            frame,
            layers: request.layers,
            offsets: layoutState.offsets,
            minimumSizes: layoutState.minimumSizes,
        });
        return {
            frame,
            ...geometry,
            layoutState,
            automaticExpandedIds: request.preserveResolvedLayoutState
                ? retainedAutomaticExpandedIds(frame, request.automaticExpandedIds)
                : automaticExpandedIds(frame),
        };
    }

    function parseSlice(value) {
        const parsed = JSON.parse(value);
        if (!parsed || !parsed.content || !Array.isArray(parsed.content.nodes) ||
            !Array.isArray(parsed.content.relations)) {
            throw new Error("Workspace Atlas layout requires a typed WorkspaceGraphSlice");
        }
        return parsed;
    }

    function resolveLayoutState(frame, previous) {
        const available = new Set(frame.content.nodes.map((node) => node.id));
        const previousSessionIds = new Set(previous.sessionOverrideIds || []);
        const offsets = retainedEntries(previous.offsets, available, previousSessionIds);
        const minimumSizes = retainedEntries(previous.minimumSizes, available, previousSessionIds);
        const overrides = frame.layoutOverrides ?? frame.layout?.overrides ?? frame.session?.layout?.overrides;
        const entries = Array.isArray(overrides)
            ? overrides.map((override) => [override.id, override])
            : Object.entries(overrides || {});
        const sessionOverrideIds = [];
        entries.forEach(([id, override]) => {
            if (!id || !available.has(id) || !override || typeof override !== "object") return;
            if (finitePoint(override.position)) setEntry(offsets, id, override.position);
            if (finiteSize(override.minimumSize)) setEntry(minimumSizes, id, override.minimumSize);
            sessionOverrideIds.push(id);
        });
        return {
            offsets: sortedEntries(offsets),
            minimumSizes: sortedEntries(minimumSizes),
            sessionOverrideIds: sessionOverrideIds.sort((left, right) => left.localeCompare(right)),
        };
    }

    function retainLayoutState(frame, previous) {
        const available = new Set(frame.content.nodes.map((node) => node.id));
        return {
            offsets: sortedEntries(retainedEntries(previous.offsets, available, new Set())),
            minimumSizes: sortedEntries(retainedEntries(previous.minimumSizes, available, new Set())),
            sessionOverrideIds: (previous.sessionOverrideIds || [])
                .filter((id) => available.has(id))
                .sort((left, right) => left.localeCompare(right)),
        };
    }

    function retainedEntries(source, available, excluded) {
        return (Array.isArray(source) ? source : [])
            .filter(([id, value]) => available.has(id) && !excluded.has(id) && value && typeof value === "object")
            .map(([id, value]) => [id, { ...value }]);
    }

    function setEntry(entries, id, value) {
        const next = [id, { ...value }];
        const index = entries.findIndex(([candidate]) => candidate === id);
        if (index < 0) entries.push(next);
        else entries[index] = next;
    }

    function sortedEntries(entries) {
        return entries.sort(([left], [right]) => left.localeCompare(right));
    }

    function automaticExpandedIds(frame) {
        const available = new Set(frame.content.nodes.map((node) => node.id));
        const expanded = Array.isArray(frame.automaticExpandedIds) ? frame.automaticExpandedIds : [];
        return expanded.filter((id) => available.has(id));
    }

    function retainedAutomaticExpandedIds(frame, expandedIds) {
        const available = new Set(frame.content.nodes.map((node) => node.id));
        return (expandedIds || []).filter((id) => available.has(id));
    }

    function finitePoint(value) {
        return value && Number.isFinite(value.x) && Number.isFinite(value.y);
    }

    function finiteSize(value) {
        return value && Number.isFinite(value.width) && Number.isFinite(value.height) &&
            value.width >= 0 && value.height >= 0;
    }

    function layoutFrame(request) {
        const frame = request.frame;
        if (!frame || !frame.content || !Array.isArray(frame.content.nodes)) {
            throw new Error("Workspace Atlas layout requires a parsed WorkspaceGraphSlice");
        }
        const layers = new Set(request.layers || []);
        const offsets = new Map(Array.isArray(request.offsets) ? request.offsets : []);
        const minimumSizes = new Map(Array.isArray(request.minimumSizes) ? request.minimumSizes : []);
        const records = frame.content.nodes.filter((record) => layerAllowsNode(layers, record));
        const nodesById = new Map();
        records.forEach((record) => {
            nodesById.set(record.id, {
                id: record.id,
                parentId: record.hierarchy.parentId,
                depth: record.hierarchy.depth,
                children: [],
                x: 0,
                y: 0,
                width: minimumNodeWidth,
                height: minimumNodeHeight,
            });
        });
        nodesById.forEach((node) => {
            if (node.parentId && nodesById.has(node.parentId)) {
                nodesById.get(node.parentId).children.push(node);
            }
        });
        nodesById.forEach((node) => node.children.sort((left, right) => left.id.localeCompare(right.id)));
        const roots = Array.from(nodesById.values())
            .filter((node) => !node.parentId || !nodesById.has(node.parentId))
            .sort((left, right) => left.id.localeCompare(right.id));
        roots.forEach((root) => measureNode(root, minimumSizes));
        let rootY = 0;
        roots.forEach((root) => {
            placeNode(root, 0, rootY);
            rootY += root.height + gap * 2;
        });
        const orderedNodes = Array.from(nodesById.values())
            .sort((left, right) => left.depth - right.depth || left.id.localeCompare(right.id));
        const worldBounds = effectiveWorldBounds(orderedNodes, nodesById, offsets, minimumSizes);
        const nodes = orderedNodes.map(({ children, grid, ...node }) => node);
        const relationIds = layers.has("relationships")
            ? frame.content.relations.map((relation) => relation.id).sort((left, right) => left.localeCompare(right))
            : [];
        return { nodes, relationIds, worldBounds };
    }

    function measureNode(node, minimumSizes) {
        node.children.forEach((child) => measureNode(child, minimumSizes));
        const minimum = minimumSizes.get(node.id) || { width: 0, height: 0 };
        if (node.children.length === 0) {
            node.width = Math.max(minimumNodeWidth, minimum.width);
            node.height = Math.max(minimumNodeHeight, minimum.height);
            return;
        }
        const columns = Math.max(1, Math.ceil(Math.sqrt(node.children.length * 1.45)));
        const rows = Math.ceil(node.children.length / columns);
        const columnWidths = new Array(columns).fill(0);
        const rowHeights = new Array(rows).fill(0);
        node.children.forEach((child, index) => {
            const column = index % columns;
            const row = Math.floor(index / columns);
            columnWidths[column] = Math.max(columnWidths[column], child.width);
            rowHeights[row] = Math.max(rowHeights[row], child.height);
        });
        node.grid = { columns, columnWidths, rowHeights };
        node.width = Math.max(minimum.width, padding * 2 + sum(columnWidths) + gap * Math.max(0, columns - 1));
        node.height = Math.max(
            minimum.height,
            headerHeight + padding * 2 + sum(rowHeights) + gap * Math.max(0, rows - 1),
        );
    }

    function placeNode(node, x, y) {
        node.x = x;
        node.y = y;
        if (!node.grid) return;
        const { columns, columnWidths, rowHeights } = node.grid;
        const columnOffsets = cumulativeOffsets(columnWidths);
        const rowOffsets = cumulativeOffsets(rowHeights);
        node.children.forEach((child, index) => {
            const column = index % columns;
            const row = Math.floor(index / columns);
            placeNode(
                child,
                x + padding + columnOffsets[column] + gap * column,
                y + headerHeight + padding + rowOffsets[row] + gap * row,
            );
        });
    }

    function boundsFor(nodes) {
        if (nodes.length === 0) return { x: 0, y: 0, width: 1, height: 1 };
        let left = Number.POSITIVE_INFINITY;
        let top = Number.POSITIVE_INFINITY;
        let right = Number.NEGATIVE_INFINITY;
        let bottom = Number.NEGATIVE_INFINITY;
        nodes.forEach((node) => {
            left = Math.min(left, node.x);
            top = Math.min(top, node.y);
            right = Math.max(right, node.x + node.width);
            bottom = Math.max(bottom, node.y + node.height);
        });
        return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) };
    }

    function effectiveWorldBounds(nodes, nodesById, offsets, minimumSizes) {
        const cache = new Map();
        return boundsFor(nodes.map((node) => effectiveRect(node, nodesById, offsets, minimumSizes, cache)));
    }

    function effectiveRect(node, nodesById, offsets, minimumSizes, cache) {
        if (cache.has(node.id)) return cache.get(node.id);
        const offset = accumulatedOffset(node, nodesById, offsets);
        const minimum = minimumSizes.get(node.id);
        const base = {
            x: node.x + offset.x,
            y: node.y + offset.y,
            width: Math.max(node.width, minimum?.width || 0),
            height: Math.max(node.height, minimum?.height || 0),
        };
        if (node.children.length === 0) {
            cache.set(node.id, base);
            return base;
        }
        let left = base.x;
        let right = base.x + base.width;
        let bottom = base.y + base.height;
        node.children.forEach((child) => {
            const childRect = effectiveRect(child, nodesById, offsets, minimumSizes, cache);
            left = Math.min(left, childRect.x - padding);
            right = Math.max(right, childRect.x + childRect.width + padding);
            bottom = Math.max(bottom, childRect.y + childRect.height + padding);
        });
        const rect = { x: left, y: base.y, width: right - left, height: bottom - base.y };
        cache.set(node.id, rect);
        return rect;
    }

    function accumulatedOffset(node, nodesById, offsets) {
        let x = 0;
        let y = 0;
        let current = node;
        while (current) {
            const offset = offsets.get(current.id);
            if (offset) {
                x += offset.x;
                y += offset.y;
            }
            current = current.parentId ? nodesById.get(current.parentId) : null;
        }
        return { x, y };
    }

    function layerAllowsNode(layers, record) {
        const kind = String(record.kind).toLowerCase();
        if (kind === "file") return layers.has("files");
        if (kind === "type" || kind === "member") return layers.has("symbols");
        if (kind === "problem") return layers.has("problems");
        if (kind === "cycle") return layers.has("cycles");
        if (kind === "task") return layers.has("tasks");
        if (kind === "dependency") return layers.has("dependencies");
        if (kind === "variant") return layers.has("variants");
        if (kind === "upgrade") return layers.has("upgrades");
        return true;
    }

    function cumulativeOffsets(values) {
        let total = 0;
        return values.map((value) => {
            const offset = total;
            total += value;
            return offset;
        });
    }

    function sum(values) {
        return values.reduce((total, value) => total + value, 0);
    }

    global.docxAtlasLayout = Object.freeze({ compute, layoutFrame });
})(typeof self !== "undefined" ? self : window);
