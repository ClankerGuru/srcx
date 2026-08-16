(function (global) {
    "use strict";

    const states = new WeakMap();
    const mapScriptUrl = document.currentScript?.src || "";
    const layoutWorkerUrl = mapScriptUrl
        ? new URL("docx-atlas-layout-worker.js", mapScriptUrl).href
        : "assets/docx-atlas-layout-worker.js";
    const colors = {
        background: "#f6f1e6",
        ink: "#181818",
        grid: "rgba(24, 24, 24, 0.10)",
        workspace: "#f6f1e6",
        build: "#d8eff5",
        project: "#f5e6b8",
        source_set: "#dce8c2",
        package: "#e8dcf4",
        file: "#f1d9cb",
        type: "#f3c2c2",
        member: "#d7cfef",
        selected: "#53c7e8",
        secondary: "#f3c342",
        context: "#8b887f",
    };
    const minimumNodeWidth = 172;
    const minimumNodeHeight = 66;
    const headerHeight = 28;
    const padding = 14;
    const gap = 14;
    const minScale = 0.06;
    const maxScale = 8;
    const dragThreshold = 4;
    const maximumActivePointers = 2;
    const maximumUndoEntries = 100;
    const maximumSnapshotSamples = 8;

    function mount(host, sliceJson) {
        destroy(host);
        const canvas = document.createElement("canvas");
        canvas.className = "srcx-dashboard__architecture-canvas";
        canvas.setAttribute("data-docx-atlas-canvas", "");
        canvas.setAttribute("role", "img");
        canvas.setAttribute("aria-label", "Interactive semantic workspace map");
        const svg = host.querySelector("[data-docx-atlas-svg]");
        const legacySvgPresentation = svg ? {
            visibility: svg.style.visibility,
            pointerEvents: svg.style.pointerEvents,
            ariaHidden: svg.getAttribute("aria-hidden"),
        } : null;
        if (svg) {
            svg.style.visibility = "hidden";
            svg.style.pointerEvents = "none";
            svg.setAttribute("aria-hidden", "true");
        }
        host.insertBefore(canvas, host.firstChild);
        const state = {
            host,
            canvas,
            legacySvg: svg,
            legacySvgPresentation,
            context: canvas.getContext("2d", { alpha: false }),
            frame: null,
            sliceJson,
            acceptedSliceJson: null,
            nodes: [],
            nodesById: new Map(),
            relations: [],
            worldBounds: { x: 0, y: 0, width: 1, height: 1 },
            camera: { x: 0, y: 0, scale: 1, mode: "fit" },
            pointer: null,
            pointers: new Map(),
            gesture: null,
            boxSelection: null,
            selectedNodeId: null,
            selectedRelationId: null,
            secondaryNodeIds: new Set(),
            secondaryRelationIds: new Set(),
            hoveredNodeId: null,
            hoveredRelationId: null,
            offsets: new Map(),
            minimumSizes: new Map(),
            sessionOverrideIds: new Set(),
            layers: new Set(["containment", "files", "relationships", "labels"]),
            automaticExpandedIds: new Set(),
            undo: [],
            redrawPending: false,
            userNavigated: false,
            reducedMotion: global.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches === true,
            lastCollisionCount: 0,
            layoutWorker: null,
            layoutRevision: 0,
            layoutStatus: "loading",
            layoutRuntime: typeof global.Worker === "function" ? "worker" : "main-thread",
            layoutError: null,
            paintRevision: null,
            paintedNodeId: null,
            paintedNodePoint: null,
            drawError: null,
        };
        states.set(host, state);
        wire(state);
        resize(state, true);
        state.resizeObserver = new ResizeObserver(() => resize(state, !state.userNavigated));
        state.resizeObserver.observe(host);
        requestLayout(state, sliceJson, { resetUndo: true, fitWhenReady: true });
    }

    function update(host, sliceJson) {
        const state = states.get(host);
        if (!state) {
            mount(host, sliceJson);
            return;
        }
        state.userNavigated = false;
        requestLayout(state, sliceJson, { resetUndo: true, fitWhenReady: true });
    }

    function destroy(host) {
        const state = states.get(host);
        if (!state) return;
        releaseActivePointers(state);
        cancelLayoutWorker(state);
        state.layoutRevision += 1;
        state.resizeObserver?.disconnect();
        state.abortController?.abort();
        state.canvas.remove();
        const svg = state.legacySvg;
        if (svg) {
            svg.style.visibility = state.legacySvgPresentation.visibility;
            svg.style.pointerEvents = state.legacySvgPresentation.pointerEvents;
            if (state.legacySvgPresentation.ariaHidden === null) svg.removeAttribute("aria-hidden");
            else svg.setAttribute("aria-hidden", state.legacySvgPresentation.ariaHidden);
        }
        clearPublishedState(host);
        states.delete(host);
    }

    function command(host, action) {
        const state = states.get(host);
        if (!state) return;
        if (action === "zoom-in") zoom(state, 1.22, viewportCenter(state));
        else if (action === "zoom-out") zoom(state, 1 / 1.22, viewportCenter(state));
        else if (action === "fit") fit(state);
        else if (action === "reset") reset(state);
        else if (action === "undo") undo(state);
        else if (action === "clear-selection") select(state, null, null);
        else if (action.startsWith("layers:")) applyLayers(state, action.slice(7));
        else if (action.startsWith("camera:")) applyCamera(state, action);
        else if (action.startsWith("fly-to-node:")) flyToNode(state, action.slice(12));
        else if (action.startsWith("preview-node:")) previewNode(state, action.slice(13));
        else if (action === "clear-preview") publishPreview(state, null, null);
        else if (action.startsWith("select-node:")) select(state, action.slice(12), null);
        else if (action.startsWith("select-edge:")) select(state, null, action.slice(12));
        else if (action.startsWith("toggle-node:")) toggleSecondary(state, action.slice(12), null);
        else if (action.startsWith("toggle-edge:")) toggleSecondary(state, null, action.slice(12));
        else if (action.startsWith("secondary-nodes:")) {
            replaceSecondaryNodes(state, action.slice("secondary-nodes:".length));
        } else if (action.startsWith("secondary-edges:")) {
            replaceSecondaryRelations(state, action.slice("secondary-edges:".length));
        } else if (action === "clear-secondary") clearSecondary(state);
        else if (action.startsWith("open-node:")) openNode(state, action.slice(10));
        scheduleDraw(state);
        publish(state);
    }

    function snapshot(host) {
        const state = states.get(host);
        if (!state) return "{}";
        return JSON.stringify({
            renderer: "canvas",
            nodes: state.nodes.length,
            relations: state.relations.length,
            scale: state.camera.scale,
            panX: state.camera.x,
            panY: state.camera.y,
            selectedNodeId: state.selectedNodeId,
            selectedRelationId: state.selectedRelationId,
            secondaryNodeCount: state.secondaryNodeIds.size,
            secondaryRelationCount: state.secondaryRelationIds.size,
            previewNodeId: state.hoveredNodeId,
            previewRelationId: state.hoveredRelationId,
            activePointerCount: state.pointers.size,
            boxSelecting: state.boxSelection != null,
            undoDepth: state.undo.length,
            layoutOverrideCount: state.offsets.size + state.minimumSizes.size,
            lastCollisionCount: state.lastCollisionCount,
            reducedMotion: state.reducedMotion,
            layoutState: state.layoutStatus,
            layoutRuntime: state.layoutRuntime,
            layoutRevision: state.layoutRevision,
            paintRevision: state.paintRevision,
            paintedNodeId: state.paintedNodeId,
            paintedNodePoint: state.paintedNodePoint,
            drawError: state.drawError,
            worldBounds: state.worldBounds,
            canvas: {
                width: state.width,
                height: state.height,
                bitmapWidth: state.canvas.width,
                bitmapHeight: state.canvas.height,
            },
            sampleNodes: state.nodes.slice(0, maximumSnapshotSamples).map((node) => {
                const rect = effectiveRect(state, node);
                const topLeft = screenPointFor(state, rect);
                return {
                    id: node.id,
                    x: topLeft.x,
                    y: topLeft.y,
                    width: rect.width * state.camera.scale,
                    height: rect.height * state.camera.scale,
                    container: node.record.hierarchy.directChildCount > 0,
                };
            }),
            sampleRelations: state.relations.slice(0, maximumSnapshotSamples).map((relation) => ({
                id: relation.id,
            })),
        });
    }

    function requestLayout(state, sliceJson, options) {
        cancelLayoutWorker(state);
        invalidatePaint(state);
        state.sliceJson = sliceJson;
        state.layoutRevision += 1;
        state.layoutStatus = "loading";
        state.layoutError = null;
        state.drawError = null;
        state.host.removeAttribute("data-docx-atlas-draw-error");
        state.layoutRuntime = typeof global.Worker === "function" ? "worker" : "main-thread";
        const request = layoutRequest(state, options || {});
        publishLayoutLifecycle(state);
        if (state.layoutRuntime === "main-thread") {
            runMainThreadLayout(state, request);
            return;
        }
        startLayoutWorker(state, request);
    }

    function layoutRequest(state, options) {
        return {
            type: "layout",
            revision: state.layoutRevision,
            sliceJson: state.sliceJson,
            layers: Array.from(state.layers),
            preserveResolvedLayoutState: options.preserveResolvedLayoutState === true,
            resetUndo: options.resetUndo === true,
            fitWhenReady: options.fitWhenReady === true,
            automaticExpandedIds: Array.from(state.automaticExpandedIds),
            layoutState: {
                offsets: Array.from(state.offsets.entries()),
                minimumSizes: Array.from(state.minimumSizes.entries()),
                sessionOverrideIds: Array.from(state.sessionOverrideIds),
            },
        };
    }

    function startLayoutWorker(state, request) {
        let worker;
        try {
            worker = new global.Worker(layoutWorkerUrl);
            state.layoutWorker = worker;
            worker.addEventListener("message", (event) => acceptWorkerMessage(state, worker, request, event.data));
            worker.addEventListener("error", (event) => {
                event.preventDefault();
                if (currentLayoutRequest(state, request.revision, worker)) {
                    runMainThreadLayout(state, request, worker);
                }
            }, { once: true });
            worker.postMessage(request);
        } catch (_) {
            worker?.terminate();
            if (state.layoutWorker === worker) state.layoutWorker = null;
            runMainThreadLayout(state, request);
        }
    }

    function acceptWorkerMessage(state, worker, request, message) {
        if (!currentLayoutRequest(state, request.revision, worker)) return;
        if (message?.type === "layout-result" && message.revision === request.revision) {
            cancelLayoutWorker(state);
            acceptLayoutResult(state, request, message.result);
        } else if (message?.type === "layout-error" && message.revision === request.revision) {
            cancelLayoutWorker(state);
            failLayout(state, request.revision, message.message);
        }
    }

    function runMainThreadLayout(state, request, failedWorker) {
        if (failedWorker && !currentLayoutRequest(state, request.revision, failedWorker)) return;
        if (!failedWorker && !currentLayoutRequest(state, request.revision)) return;
        if (failedWorker) cancelLayoutWorker(state);
        state.layoutRuntime = "main-thread";
        publishLayoutLifecycle(state);
        global.setTimeout(() => {
            if (!currentLayoutRequest(state, request.revision)) return;
            try {
                acceptLayoutResult(state, request, layoutEngine().compute(request));
            } catch (error) {
                failLayout(state, request.revision, error instanceof Error ? error.message : String(error));
            }
        }, 0);
    }

    function currentLayoutRequest(state, revision, worker) {
        return states.get(state.host) === state && state.layoutRevision === revision &&
            (!worker || state.layoutWorker === worker);
    }

    function acceptLayoutResult(state, request, result) {
        if (!currentLayoutRequest(state, request.revision)) return;
        state.frame = result.frame;
        state.acceptedSliceJson = request.sliceJson;
        state.offsets = new Map(result.layoutState.offsets);
        state.minimumSizes = new Map(result.layoutState.minimumSizes);
        state.sessionOverrideIds = new Set(result.layoutState.sessionOverrideIds);
        state.automaticExpandedIds = new Set(result.automaticExpandedIds);
        applyGeometry(state, result);
        publishGeometryDiagnostic(state);
        retainInteractionState(state);
        if (request.resetUndo) state.undo.length = 0;
        state.layoutStatus = "ready";
        state.layoutError = null;
        if (request.fitWhenReady && !state.userNavigated) fit(state);
        publishRetainedTargets(state);
        scheduleDraw(state);
        publish(state);
    }

    function failLayout(state, revision, message) {
        if (!currentLayoutRequest(state, revision)) return;
        state.layoutStatus = "error";
        state.layoutError = message || "Workspace Atlas layout failed";
        publishLayoutLifecycle(state);
    }

    function cancelLayoutWorker(state) {
        state.layoutWorker?.terminate();
        state.layoutWorker = null;
    }

    function layoutEngine() {
        if (!global.docxAtlasLayout) throw new Error("Workspace Atlas layout engine is unavailable");
        return global.docxAtlasLayout;
    }

    function retainInteractionState(state) {
        state.selectedNodeId = retainedId(state.frame.content.nodes, state.selectedNodeId);
        state.selectedRelationId = retainedId(state.frame.content.relations, state.selectedRelationId);
        state.secondaryNodeIds = retainedIds(state.frame.content.nodes, state.secondaryNodeIds);
        state.secondaryRelationIds = retainedIds(state.frame.content.relations, state.secondaryRelationIds);
        if (state.selectedNodeId) state.secondaryNodeIds.delete(state.selectedNodeId);
        if (state.selectedRelationId) state.secondaryRelationIds.delete(state.selectedRelationId);
        state.hoveredNodeId = retainedId(state.frame.content.nodes, state.hoveredNodeId);
        state.hoveredRelationId = retainedId(state.frame.content.relations, state.hoveredRelationId);
    }

    function publishRetainedTargets(state) {
        setOptionalAttribute(state.host, "data-docx-atlas-selected-node-id", state.selectedNodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-selected-edge-id", state.selectedRelationId);
        setOptionalAttribute(state.host, "data-docx-atlas-hovered-node-id", state.hoveredNodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-hovered-edge-id", state.hoveredRelationId);
        setOptionalAttribute(state.host, "data-docx-atlas-preview-node-id", state.hoveredNodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-preview-edge-id", state.hoveredRelationId);
    }

    function retainedId(records, id) {
        return id && records.some((record) => record.id === id) ? id : null;
    }

    function retainedIds(records, ids) {
        const available = new Set(records.map((record) => record.id));
        return new Set(Array.from(ids).filter((id) => available.has(id)));
    }

    function applyGeometry(state, result) {
        const recordsById = new Map(state.frame.content.nodes.map((record) => [record.id, record]));
        const nodesById = new Map();
        result.nodes.forEach((geometry) => {
            const record = recordsById.get(geometry.id);
            if (record) nodesById.set(geometry.id, { ...geometry, record, children: [] });
        });
        nodesById.forEach((node) => {
            if (node.parentId && nodesById.has(node.parentId)) {
                nodesById.get(node.parentId).children.push(node);
            }
        });
        nodesById.forEach((node) => node.children.sort((left, right) => left.id.localeCompare(right.id)));
        const relationsById = new Map(state.frame.content.relations.map((relation) => [relation.id, relation]));
        state.nodesById = nodesById;
        state.nodes = result.nodes.map((node) => nodesById.get(node.id)).filter(Boolean);
        state.relations = result.relationIds.map((id) => relationsById.get(id)).filter(Boolean);
        state.worldBounds = result.worldBounds;
    }

    function wire(state) {
        const abortController = new AbortController();
        const options = { signal: abortController.signal };
        state.abortController = abortController;
        state.canvas.addEventListener("pointerdown", (event) => pointerDown(state, event), options);
        state.canvas.addEventListener("pointermove", (event) => pointerMove(state, event), options);
        state.canvas.addEventListener("pointerup", (event) => pointerUp(state, event, false), options);
        state.canvas.addEventListener("pointercancel", (event) => pointerUp(state, event, true), options);
        state.canvas.addEventListener("pointerleave", () => pointerLeave(state), options);
        state.canvas.addEventListener("dblclick", (event) => openAt(state, event), options);
        state.canvas.addEventListener("wheel", (event) => wheel(state, event), { ...options, passive: false });
        state.canvas.addEventListener("keydown", (event) => keyDown(state, event), options);
        state.canvas.tabIndex = 0;
    }

    function pointerDown(state, event) {
        event.preventDefault();
        if (!state.pointers.has(event.pointerId) && state.pointers.size >= maximumActivePointers) return;
        capturePointer(state.canvas, event.pointerId);
        const point = canvasPoint(state, event);
        state.pointers.set(event.pointerId, {
            id: event.pointerId,
            point,
            pointerType: event.pointerType || "mouse",
        });
        publishPointerState(state);
        if (state.pointers.size === 2 && activePointersAreTouch(state)) {
            beginPinch(state);
            return;
        }
        if (state.pointers.size !== 1) return;
        const world = worldPoint(state, point);
        const node = hitNode(state, world);
        const relation = node ? null : hitRelation(state, point);
        const resize = node && node.children.length > 0 && nearResizeHandle(state, node, point);
        const touch = event.pointerType === "touch";
        const box = event.shiftKey && !touch;
        const nodeDrag = event.altKey && node && !touch;
        state.pointer = {
            pointerId: event.pointerId,
            start: point,
            current: point,
            camera: { ...state.camera },
            nodeId: node?.id || null,
            relationId: relation?.id || null,
            additive: event.shiftKey || event.metaKey || event.ctrlKey,
            resize,
            mode: box ? "box" : touch ? "touch-pan" : resize ? "resize" : nodeDrag ? "node-drag" : "pan",
            moved: false,
            originalOffsets: copyMap(state.offsets),
            originalSizes: copyMap(state.minimumSizes),
            originalNodeSize: node ? { width: node.width, height: node.height } : null,
        };
        state.boxSelection = box ? { start: point, current: point } : null;
        state.canvas.style.cursor = resize ? "nwse-resize" : nodeDrag ? "move" : box ? "crosshair" : "grabbing";
        state.canvas.focus({ preventScroll: true });
        publishPointerState(state);
        scheduleDraw(state);
    }

    function pointerMove(state, event) {
        const point = canvasPoint(state, event);
        if (state.pointers.has(event.pointerId)) {
            const active = state.pointers.get(event.pointerId);
            state.pointers.set(event.pointerId, { ...active, point });
        }
        if (state.gesture?.type === "pinch") {
            applyPinch(state);
            return;
        }
        if (!state.pointer || state.pointer.pointerId !== event.pointerId) {
            if (state.pointers.size === 0) publishPreviewAt(state, point);
            return;
        }
        const drag = state.pointer;
        drag.current = point;
        drag.moved = drag.moved || distance(drag.start, point) > dragThreshold;
        if (!drag.moved) return;
        if (drag.mode === "box") {
            state.boxSelection = { start: drag.start, current: point };
        } else if (drag.mode === "resize") {
            const delta = screenDeltaToWorld(state, drag.start, point);
            state.minimumSizes = copyMap(drag.originalSizes);
            state.minimumSizes.set(drag.nodeId, {
                width: Math.max(minimumNodeWidth, drag.originalNodeSize.width + delta.x),
                height: Math.max(minimumNodeHeight, drag.originalNodeSize.height + delta.y),
            });
            relayoutWithState(state);
        } else if (drag.mode === "node-drag") {
            const delta = screenDeltaToWorld(state, drag.start, point);
            state.offsets = copyMap(drag.originalOffsets);
            translateNodeAndDescendants(state, drag.nodeId, delta);
        } else {
            state.camera.x = drag.camera.x + point.x - drag.start.x;
            state.camera.y = drag.camera.y + point.y - drag.start.y;
            state.camera.mode = "manual";
            state.userNavigated = true;
            publishViewport(state);
        }
        scheduleDraw(state);
    }

    function pointerUp(state, event, cancelled) {
        const point = canvasPoint(state, event);
        if (state.pointers.has(event.pointerId)) {
            const active = state.pointers.get(event.pointerId);
            state.pointers.set(event.pointerId, { ...active, point });
        }
        state.pointers.delete(event.pointerId);
        releasePointer(state.canvas, event.pointerId);
        publishPointerState(state);
        if (state.gesture?.type === "pinch") {
            finishPinch(state, cancelled);
            return;
        }
        const drag = state.pointer;
        if (!drag || drag.pointerId !== event.pointerId) return;
        state.pointer = null;
        state.boxSelection = null;
        state.canvas.style.cursor = "grab";
        if (cancelled) {
            if (drag.mode === "node-drag" || drag.mode === "resize") {
                state.offsets = drag.originalOffsets;
                state.minimumSizes = drag.originalSizes;
                relayoutWithState(state);
            }
        } else if (drag.moved && drag.mode === "box") {
            completeBoxSelection(state, drag.start, drag.current);
        } else if (drag.moved && (drag.mode === "node-drag" || drag.mode === "resize")) {
            const changedIds = resolveSiblingCollisions(state, drag.nodeId);
            relayoutWithState(state);
            pushUndo(state, drag.originalOffsets, drag.originalSizes, changedIds);
            changedIds.forEach((id) => publishLayout(state, id));
        } else if (!drag.moved) {
            if (drag.additive) toggleSecondary(state, drag.nodeId, drag.relationId);
            else select(state, drag.nodeId, drag.relationId);
        }
        publish(state);
        scheduleDraw(state);
    }

    function pointerLeave(state) {
        if (state.pointers.size === 0) publishPreview(state, null, null);
    }

    function beginPinch(state) {
        const points = Array.from(state.pointers.values()).slice(0, maximumActivePointers);
        const centroid = midpoint(points[0].point, points[1].point);
        state.pointer = null;
        state.boxSelection = null;
        state.gesture = {
            type: "pinch",
            pointerIds: points.map((pointer) => pointer.id),
            startDistance: Math.max(1, distance(points[0].point, points[1].point)),
            startCentroid: centroid,
            startWorldCentroid: worldPoint(state, centroid),
            startCamera: { ...state.camera },
        };
        state.host.setAttribute("data-docx-atlas-gesture", "pinch");
        scheduleDraw(state);
    }

    function applyPinch(state) {
        const gesture = state.gesture;
        const points = gesture.pointerIds.map((id) => state.pointers.get(id)).filter(Boolean);
        if (points.length !== 2) return;
        const centroid = midpoint(points[0].point, points[1].point);
        const currentDistance = Math.max(1, distance(points[0].point, points[1].point));
        state.camera.scale = clamp(
            gesture.startCamera.scale * currentDistance / gesture.startDistance,
            minScale,
            maxScale,
        );
        state.camera.x = centroid.x - gesture.startWorldCentroid.x * state.camera.scale;
        state.camera.y = centroid.y - gesture.startWorldCentroid.y * state.camera.scale;
        state.camera.mode = "manual";
        state.userNavigated = true;
        publishViewport(state);
        scheduleDraw(state);
    }

    function finishPinch(state, cancelled) {
        const gesture = state.gesture;
        state.gesture = null;
        state.host.setAttribute("data-docx-atlas-gesture", "idle");
        if (cancelled && gesture) {
            state.camera = { ...gesture.startCamera };
            publishViewport(state);
        }
        if (!cancelled && gesture) publishSemanticZoom(state, gesture.startCentroid);
        const remaining = Array.from(state.pointers.values());
        if (remaining.length === 1 && remaining[0].pointerType === "touch") {
            const pointer = remaining[0];
            state.pointer = {
                pointerId: pointer.id,
                start: pointer.point,
                current: pointer.point,
                camera: { ...state.camera },
                nodeId: null,
                relationId: null,
                additive: false,
                resize: false,
                mode: "touch-pan",
                moved: true,
                originalOffsets: copyMap(state.offsets),
                originalSizes: copyMap(state.minimumSizes),
                originalNodeSize: null,
            };
        }
        publish(state);
        scheduleDraw(state);
    }

    function openAt(state, event) {
        const node = hitNode(state, worldPoint(state, canvasPoint(state, event)));
        if (node) openNode(state, node.id);
    }

    function wheel(state, event) {
        event.preventDefault();
        const factor = Math.exp(-event.deltaY * 0.0015);
        zoom(state, factor, canvasPoint(state, event));
    }

    function keyDown(state, event) {
        if (event.key === "+" || event.key === "=") command(state.host, "zoom-in");
        else if (event.key === "-") command(state.host, "zoom-out");
        else if (event.key === "0") command(state.host, "fit");
        else if (event.key === "Escape") command(state.host, "clear-selection");
        else if (event.key === "Enter") openKeyboardTarget(state);
        else if ((event.key === " " || event.key === "Spacebar") && !event.repeat) toggleKeyboardTarget(state);
        else if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "z") command(state.host, "undo");
        else return;
        event.preventDefault();
    }

    function openKeyboardTarget(state) {
        const nodeId = state.hoveredNodeId || state.selectedNodeId;
        if (nodeId) openNode(state, nodeId);
    }

    function toggleKeyboardTarget(state) {
        const nodeId = state.hoveredNodeId || state.selectedNodeId;
        const relationId = nodeId ? null : state.hoveredRelationId || state.selectedRelationId;
        if (!nodeId && !relationId) return;
        if ((nodeId && nodeId === state.selectedNodeId) ||
            (relationId && relationId === state.selectedRelationId)) {
            select(state, null, null);
        } else {
            toggleSecondary(state, nodeId, relationId);
        }
    }

    function openNode(state, nodeId) {
        const node = state.nodesById.get(nodeId);
        if (!node || node.record.hierarchy.directChildCount === 0) return;
        state.userNavigated = false;
        state.host.setAttribute("data-docx-atlas-open-node-id", node.id);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-open", { bubbles: true, composed: true }));
    }

    function resize(state, shouldFit) {
        const rect = state.host.getBoundingClientRect();
        const ratio = Math.max(1, global.devicePixelRatio || 1);
        state.width = Math.max(1, rect.width);
        state.height = Math.max(1, rect.height);
        state.canvas.width = Math.round(state.width * ratio);
        state.canvas.height = Math.round(state.height * ratio);
        state.canvas.style.width = `${state.width}px`;
        state.canvas.style.height = `${state.height}px`;
        state.pixelRatio = ratio;
        if (shouldFit) fit(state);
        scheduleDraw(state);
        publishViewport(state);
    }

    function fit(state) {
        const bounds = effectiveWorldBounds(state);
        const availableWidth = Math.max(1, state.width - 56);
        const availableHeight = Math.max(1, state.height - 56);
        const scale = Math.min(availableWidth / bounds.width, availableHeight / bounds.height, maxScale);
        state.camera.scale = clamp(scale, minScale, maxScale);
        state.camera.x = (state.width - bounds.width * state.camera.scale) / 2 - bounds.x * state.camera.scale;
        state.camera.y = (state.height - bounds.height * state.camera.scale) / 2 - bounds.y * state.camera.scale;
        state.camera.mode = "fit";
        state.userNavigated = false;
        publishViewport(state);
        scheduleDraw(state);
    }

    function reset(state) {
        const changedIds = new Set([...state.offsets.keys(), ...state.minimumSizes.keys()]);
        state.offsets.clear();
        state.minimumSizes.clear();
        state.sessionOverrideIds.clear();
        state.undo.length = 0;
        state.lastCollisionCount = 0;
        relayoutWithState(state, true);
        changedIds.forEach((id) => publishLayout(state, id));
        fit(state);
    }

    function undo(state) {
        const previous = state.undo.pop();
        if (!previous) return;
        state.offsets = previous.offsets;
        state.minimumSizes = previous.sizes;
        relayoutWithState(state);
        scheduleDraw(state);
        previous.changedIds.forEach((id) => publishLayout(state, id));
        state.host.setAttribute("data-docx-atlas-undo-depth", String(state.undo.length));
    }

    function zoom(state, factor, anchor) {
        const before = worldPoint(state, anchor);
        state.camera.scale = clamp(state.camera.scale * factor, minScale, maxScale);
        state.camera.x = anchor.x - before.x * state.camera.scale;
        state.camera.y = anchor.y - before.y * state.camera.scale;
        state.camera.mode = "manual";
        state.userNavigated = true;
        publishSemanticZoom(state, anchor);
        publishViewport(state);
        scheduleDraw(state);
    }

    function publishSemanticZoom(state, anchor) {
        const hovered = hitNode(state, worldPoint(state, anchor));
        if (hovered && hovered.record.hierarchy.directChildCount > 0) {
            const rect = effectiveRect(state, hovered);
            const screenSize = Math.max(rect.width, rect.height) * state.camera.scale;
            if (screenSize >= 280 && !state.automaticExpandedIds.has(hovered.id)) {
                state.automaticExpandedIds.add(hovered.id);
                publishSemanticExpansion(state, hovered.id, "expand");
            }
        }
        Array.from(state.automaticExpandedIds).forEach((id) => {
            const node = state.nodesById.get(id);
            if (!node) return;
            const rect = effectiveRect(state, node);
            const screenSize = Math.max(rect.width, rect.height) * state.camera.scale;
            if (screenSize <= 160) {
                state.automaticExpandedIds.delete(id);
                publishSemanticExpansion(state, id, "collapse");
            }
        });
    }

    function publishSemanticExpansion(state, nodeId, action) {
        state.host.setAttribute("data-docx-atlas-semantic-node-id", nodeId);
        state.host.setAttribute("data-docx-atlas-semantic-action", action);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-semantic-zoom", { bubbles: true, composed: true }));
    }

    function select(state, nodeId, relationId) {
        state.selectedNodeId = nodeId;
        state.selectedRelationId = relationId;
        if (!nodeId && !relationId) {
            state.secondaryNodeIds.clear();
            state.secondaryRelationIds.clear();
        }
        if (nodeId) state.secondaryNodeIds.delete(nodeId);
        if (relationId) state.secondaryRelationIds.delete(relationId);
        setOptionalAttribute(state.host, "data-docx-atlas-selected-node-id", nodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-selected-edge-id", relationId);
        state.host.setAttribute("data-docx-atlas-selection-mode", "primary");
        setOptionalAttribute(state.host, "data-docx-atlas-interaction-node-id", nodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-interaction-edge-id", relationId);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-selection", { bubbles: true, composed: true }));
        publishSecondaryCounts(state);
    }

    function toggleSecondary(state, nodeId, relationId) {
        if ((nodeId && nodeId === state.selectedNodeId) ||
            (relationId && relationId === state.selectedRelationId)) return;
        if (nodeId) toggleSetValue(state.secondaryNodeIds, nodeId);
        if (relationId) toggleSetValue(state.secondaryRelationIds, relationId);
        publishSecondaryInteraction(state, nodeId, relationId);
    }

    function addSecondary(state, nodeId, relationId) {
        if ((nodeId && nodeId === state.selectedNodeId) ||
            (relationId && relationId === state.selectedRelationId)) return;
        if (nodeId && state.secondaryNodeIds.has(nodeId)) return;
        if (relationId && state.secondaryRelationIds.has(relationId)) return;
        if (nodeId) state.secondaryNodeIds.add(nodeId);
        if (relationId) state.secondaryRelationIds.add(relationId);
        publishSecondaryInteraction(state, nodeId, relationId);
    }

    function publishSecondaryInteraction(state, nodeId, relationId) {
        state.host.setAttribute("data-docx-atlas-selection-mode", "additive");
        setOptionalAttribute(state.host, "data-docx-atlas-interaction-node-id", nodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-interaction-edge-id", relationId);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-selection", { bubbles: true, composed: true }));
        publishSecondaryCounts(state);
        scheduleDraw(state);
    }

    function publishSecondaryCounts(state) {
        state.host.setAttribute("data-docx-atlas-secondary-node-count", String(state.secondaryNodeIds.size));
        state.host.setAttribute("data-docx-atlas-secondary-edge-count", String(state.secondaryRelationIds.size));
    }

    function replaceSecondaryNodes(state, value) {
        const available = availableIds(state, "nodes");
        state.secondaryNodeIds = parsedIdSet(value, available, state.selectedNodeId);
        publishSecondaryCounts(state);
        scheduleDraw(state);
    }

    function replaceSecondaryRelations(state, value) {
        const available = availableIds(state, "relations");
        state.secondaryRelationIds = parsedIdSet(value, available, state.selectedRelationId);
        publishSecondaryCounts(state);
        scheduleDraw(state);
    }

    function clearSecondary(state) {
        state.secondaryNodeIds.clear();
        state.secondaryRelationIds.clear();
        publishSecondaryCounts(state);
        scheduleDraw(state);
    }

    function parsedIdSet(value, available, primaryId) {
        return new Set(
            value.split(",")
                .map((id) => id.trim())
                .filter((id) => id && id !== primaryId && (!available || available.has(id))),
        );
    }

    function availableIds(state, kind) {
        if (state.layoutStatus !== "ready" || !state.frame) return null;
        return new Set(state.frame.content[kind].map((record) => record.id));
    }

    function completeBoxSelection(state, start, current) {
        const box = normalizedRect(start, current);
        const worldStart = worldPoint(state, { x: box.x, y: box.y });
        const worldEnd = worldPoint(state, { x: box.x + box.width, y: box.y + box.height });
        const worldBox = normalizedRect(worldStart, worldEnd);
        const hits = state.nodes.filter((node) => contains(worldBox, rectCenter(effectiveRect(state, node))));
        const leaves = hits.filter((node) => node.children.length === 0);
        const selected = leaves.length > 0 ? leaves : hits;
        selected.forEach((node) => addSecondary(state, node.id, null));
        state.host.setAttribute("data-docx-atlas-box-selection-count", String(selected.length));
        state.host.setAttribute("data-docx-atlas-gesture", "idle");
    }

    function publishPreviewAt(state, point) {
        const node = hitNode(state, worldPoint(state, point));
        const relation = node ? null : hitRelation(state, point);
        publishPreview(state, node?.id || null, relation?.id || null);
    }

    function previewNode(state, nodeId) {
        const available = state.layoutStatus !== "ready" || state.nodesById.has(nodeId) ? nodeId : null;
        publishPreview(state, available, null);
    }

    function publishPreview(state, nodeId, relationId) {
        if (nodeId === state.hoveredNodeId && relationId === state.hoveredRelationId) return;
        state.hoveredNodeId = nodeId;
        state.hoveredRelationId = relationId;
        setOptionalAttribute(state.host, "data-docx-atlas-hovered-node-id", nodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-hovered-edge-id", relationId);
        setOptionalAttribute(state.host, "data-docx-atlas-preview-node-id", nodeId);
        setOptionalAttribute(state.host, "data-docx-atlas-preview-edge-id", relationId);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-preview", { bubbles: true, composed: true }));
        scheduleDraw(state);
    }

    function draw(state) {
        state.redrawPending = false;
        const context = state.context;
        const ratio = state.pixelRatio || 1;
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.fillStyle = colors.background;
        context.fillRect(0, 0, state.width, state.height);
        drawGrid(state, context);
        context.save();
        context.translate(state.camera.x, state.camera.y);
        context.scale(state.camera.scale, state.camera.scale);
        const secondaryOrdinals = createSecondaryOrdinals(state);
        const paintedNodes = [];
        drawRelations(state, context, secondaryOrdinals);
        state.nodes.forEach((node) => {
            if (drawNode(state, context, node, secondaryOrdinals)) paintedNodes.push(node);
        });
        context.restore();
        drawBoxSelection(state, context);
        publishPaintedSemanticNode(state, paintedNodes);
        publishGeometryDiagnostic(state);
    }

    function drawGrid(state, context) {
        const spacing = Math.max(16, 32 * state.camera.scale);
        context.fillStyle = colors.grid;
        const offsetX = ((state.camera.x % spacing) + spacing) % spacing;
        const offsetY = ((state.camera.y % spacing) + spacing) % spacing;
        for (let x = offsetX; x < state.width; x += spacing) {
            for (let y = offsetY; y < state.height; y += spacing) context.fillRect(x, y, 1, 1);
        }
    }

    function drawRelations(state, context, secondaryOrdinals) {
        state.relations.forEach((relation) => {
            const source = state.nodesById.get(relation.endpoints.sourceNodeId);
            const target = state.nodesById.get(relation.endpoints.targetNodeId);
            if (!source || !target) return;
            const sourceRect = effectiveRect(state, source);
            const targetRect = effectiveRect(state, target);
            const from = rectCenter(sourceRect);
            const to = rectCenter(targetRect);
            const selected = relation.id === state.selectedRelationId;
            const secondary = state.secondaryRelationIds.has(relation.id);
            const hovered = relation.id === state.hoveredRelationId;
            const selectedNeighbor = relationTouchesSelectedNode(state, relation);
            context.save();
            if (hovered) drawRelationHalo(context, from, to, state.camera.scale);
            context.strokeStyle = selected ? colors.selected : secondary ? colors.secondary :
                hovered || selectedNeighbor ? colors.ink : "rgba(24,24,24,0.30)";
            context.lineWidth = (selected ? 4 : secondary ? 3 : hovered || selectedNeighbor ? 2.5 : 1.25) /
                state.camera.scale;
            context.setLineDash(
                selected || secondary || hovered || selectedNeighbor ? [] :
                    [6 / state.camera.scale, 4 / state.camera.scale],
            );
            context.beginPath();
            context.moveTo(from.x, from.y);
            context.lineTo(to.x, to.y);
            context.stroke();
            drawArrow(context, from, to, state.camera.scale, selected, secondary);
            if (selected || selectedNeighbor) drawEndpointMarkers(context, from, to, state.camera.scale);
            if (selected) drawPrimaryBadge(context, midpoint(from, to), state.camera.scale);
            if (secondary) {
                drawSecondaryBadge(
                    context,
                    midpoint(from, to),
                    secondaryOrdinals.get(`relation:${relation.id}`),
                    state.camera.scale,
                );
            }
            context.restore();
        });
    }

    function relationTouchesSelectedNode(state, relation) {
        return Boolean(state.selectedNodeId) &&
            (relation.endpoints.sourceNodeId === state.selectedNodeId ||
                relation.endpoints.targetNodeId === state.selectedNodeId);
    }

    function drawRelationHalo(context, from, to, scale) {
        context.save();
        context.strokeStyle = colors.selected;
        context.globalAlpha = 0.34;
        context.lineWidth = 9 / scale;
        context.setLineDash([]);
        context.beginPath();
        context.moveTo(from.x, from.y);
        context.lineTo(to.x, to.y);
        context.stroke();
        context.restore();
    }

    function drawEndpointMarkers(context, from, to, scale) {
        const radius = 4.5 / scale;
        context.save();
        context.setLineDash([]);
        context.fillStyle = colors.ink;
        [from, to].forEach((point) => {
            context.beginPath();
            context.arc(point.x, point.y, radius, 0, Math.PI * 2);
            context.fill();
        });
        context.restore();
    }

    function drawArrow(context, from, to, scale, selected, secondary) {
        const angle = Math.atan2(to.y - from.y, to.x - from.x);
        const size = (selected ? 10 : 7) / scale;
        context.fillStyle = selected ? colors.selected : secondary ? colors.secondary : colors.ink;
        context.beginPath();
        context.moveTo(to.x, to.y);
        context.lineTo(to.x - Math.cos(angle - 0.45) * size, to.y - Math.sin(angle - 0.45) * size);
        context.lineTo(to.x - Math.cos(angle + 0.45) * size, to.y - Math.sin(angle + 0.45) * size);
        context.closePath();
        context.fill();
    }

    function drawNode(state, context, node, secondaryOrdinals) {
        const rect = effectiveRect(state, node);
        const screenWidth = rect.width * state.camera.scale;
        const screenHeight = rect.height * state.camera.scale;
        if (screenWidth < 2 || screenHeight < 2) return false;
        const roles = node.record.roles || [];
        const selected = node.id === state.selectedNodeId;
        const secondary = state.secondaryNodeIds.has(node.id);
        const hovered = node.id === state.hoveredNodeId;
        const endpointOnly = roles.includes("ENDPOINT") && !roles.includes("PRIMARY") && !roles.includes("FOCUS");
        const ancestor = roles.includes("ANCESTOR");
        context.save();
        context.fillStyle = selected ? colors.selected : ancestor ? ancestorTint(node.record.kind) : nodeColor(node.record.kind);
        context.globalAlpha = endpointOnly ? 0.58 : 0.94;
        context.strokeStyle = selected ? colors.ink : endpointOnly ? colors.context : colors.ink;
        context.lineWidth = (selected ? 5 : hovered ? 3 : 1.5) / state.camera.scale;
        context.setLineDash(endpointOnly ? [7 / state.camera.scale, 5 / state.camera.scale] : []);
        context.fillRect(rect.x, rect.y, rect.width, rect.height);
        context.strokeRect(rect.x, rect.y, rect.width, rect.height);
        if (hovered) drawNodeHalo(context, rect, state.camera.scale);
        if (selected) {
            drawPrimaryBadge(
                context,
                { x: rect.x + rect.width - 12 / state.camera.scale, y: rect.y + 12 / state.camera.scale },
                state.camera.scale,
            );
        }
        if (secondary) {
            const inset = 5 / state.camera.scale;
            context.strokeStyle = colors.secondary;
            context.lineWidth = 3 / state.camera.scale;
            context.strokeRect(rect.x + inset, rect.y + inset, rect.width - inset * 2, rect.height - inset * 2);
            drawSecondaryBadge(
                context,
                { x: rect.x + rect.width - inset * 2, y: rect.y + inset * 2 },
                secondaryOrdinals.get(`node:${node.id}`),
                state.camera.scale,
            );
        }
        if (node.children.length > 0 && screenHeight > 18) {
            context.fillStyle = ancestor ? "rgba(83,199,232,0.22)" : "rgba(24,24,24,0.09)";
            context.fillRect(rect.x, rect.y, rect.width, headerHeight);
            context.beginPath();
            context.moveTo(rect.x, rect.y + headerHeight);
            context.lineTo(rect.x + rect.width, rect.y + headerHeight);
            context.stroke();
        }
        if (state.layers.has("labels") && screenWidth > 42 && screenHeight > 18) {
            drawLabel(state, context, node, rect);
        }
        if (node.children.length > 0 && screenWidth > 28) drawResizeHandle(state, context, rect);
        context.restore();
        return true;
    }

    function ancestorTint(kind) {
        return kind === "WORKSPACE" || kind === "BUILD" || kind === "PROJECT"
            ? "#dff1ee"
            : nodeColor(kind);
    }

    function drawNodeHalo(context, rect, scale) {
        const outset = 7 / scale;
        context.save();
        context.globalAlpha = 0.52;
        context.strokeStyle = colors.selected;
        context.lineWidth = 5 / scale;
        context.setLineDash([]);
        context.strokeRect(
            rect.x - outset,
            rect.y - outset,
            rect.width + outset * 2,
            rect.height + outset * 2,
        );
        context.restore();
    }

    function publishPaintedSemanticNode(state, paintedNodes) {
        if (state.layoutStatus !== "ready" || !state.frame) return;
        const proof = paintedNodes
            .slice()
            .reverse()
            .map((node) => paintedNodeProof(state, node))
            .find(Boolean);
        if (!proof) return;
        state.paintRevision = state.layoutRevision;
        state.paintedNodeId = proof.nodeId;
        state.paintedNodePoint = proof.point;
        state.host.setAttribute("data-docx-atlas-paint-revision", String(state.paintRevision));
        state.host.setAttribute("data-docx-atlas-painted-node-id", proof.nodeId);
        state.host.setAttribute("data-docx-atlas-painted-node-x", String(proof.point.x));
        state.host.setAttribute("data-docx-atlas-painted-node-y", String(proof.point.y));
    }

    function paintedNodeProof(state, node) {
        const rect = effectiveRect(state, node);
        const topLeft = screenPointFor(state, rect);
        const width = rect.width * state.camera.scale;
        const height = rect.height * state.camera.scale;
        const inset = Math.max(1, Math.min(8, width / 4, height / 4));
        const points = [
            { x: topLeft.x + width / 2, y: topLeft.y + height / 2 },
            { x: topLeft.x + inset, y: topLeft.y + inset },
            { x: topLeft.x + width - inset, y: topLeft.y + height - inset },
            { x: topLeft.x + 0.25, y: topLeft.y + height / 2 },
        ];
        const point = points.find((candidate) =>
            candidate.x >= 0 && candidate.y >= 0 && candidate.x < state.width && candidate.y < state.height);
        return point ? { nodeId: node.id, point } : null;
    }

    function invalidatePaint(state) {
        state.paintRevision = null;
        state.paintedNodeId = null;
        state.paintedNodePoint = null;
        [
            "data-docx-atlas-paint-revision",
            "data-docx-atlas-painted-node-id",
            "data-docx-atlas-painted-node-x",
            "data-docx-atlas-painted-node-y",
        ].forEach((name) => state.host.removeAttribute(name));
    }

    function createSecondaryOrdinals(state) {
        const targets = [
            ...Array.from(state.secondaryNodeIds).map((id) => `node:${id}`),
            ...Array.from(state.secondaryRelationIds).map((id) => `relation:${id}`),
        ].sort((left, right) => left.localeCompare(right));
        return new Map(targets.map((target, index) => [target, index + 1]));
    }

    function drawSecondaryBadge(context, point, ordinal, scale) {
        if (!ordinal) return;
        const radius = 10 / scale;
        context.save();
        context.setLineDash([]);
        context.globalAlpha = 1;
        context.fillStyle = colors.secondary;
        context.strokeStyle = colors.ink;
        context.lineWidth = 2 / scale;
        context.beginPath();
        context.arc(point.x, point.y, radius, 0, Math.PI * 2);
        context.fill();
        context.stroke();
        context.fillStyle = colors.ink;
        context.font = `800 ${11 / scale}px ui-monospace, SFMono-Regular, Menlo, monospace`;
        context.textAlign = "center";
        context.textBaseline = "middle";
        context.fillText(String(ordinal), point.x, point.y, radius * 1.5);
        context.restore();
    }

    function drawPrimaryBadge(context, point, scale) {
        const radius = 11 / scale;
        context.save();
        context.setLineDash([]);
        context.globalAlpha = 1;
        context.fillStyle = colors.selected;
        context.strokeStyle = colors.ink;
        context.lineWidth = 2.5 / scale;
        context.beginPath();
        context.arc(point.x, point.y, radius, 0, Math.PI * 2);
        context.fill();
        context.stroke();
        context.fillStyle = colors.ink;
        context.font = `900 ${10 / scale}px ui-monospace, SFMono-Regular, Menlo, monospace`;
        context.textAlign = "center";
        context.textBaseline = "middle";
        context.fillText("P", point.x, point.y, radius * 1.5);
        context.restore();
    }

    function drawLabel(state, context, node, rect) {
        const fontSize = clamp(13 / state.camera.scale, 5, 18);
        context.fillStyle = colors.ink;
        context.font = `700 ${fontSize}px ui-monospace, SFMono-Regular, Menlo, monospace`;
        context.textBaseline = "middle";
        const maxWidth = Math.max(20, rect.width - padding * 2);
        context.fillText(ellipsize(context, node.record.presentation.label, maxWidth), rect.x + padding, rect.y + 14, maxWidth);
        const count = node.record.hierarchy.descendantCount;
        if (count > 0 && rect.width * state.camera.scale > 85) {
            context.font = `600 ${Math.max(5, fontSize * 0.72)}px ui-monospace, SFMono-Regular, Menlo, monospace`;
            context.fillText(`${count} descendants`, rect.x + padding, rect.y + headerHeight + 10, maxWidth);
        }
    }

    function drawResizeHandle(state, context, rect) {
        const size = 10 / state.camera.scale;
        context.fillStyle = colors.ink;
        context.fillRect(rect.x + rect.width - size, rect.y + rect.height - size, size, size);
    }

    function drawBoxSelection(state, context) {
        if (!state.boxSelection) return;
        const rect = normalizedRect(state.boxSelection.start, state.boxSelection.current);
        context.save();
        context.fillStyle = "rgba(83, 199, 232, 0.16)";
        context.strokeStyle = colors.selected;
        context.lineWidth = 2;
        context.setLineDash([8, 5]);
        context.fillRect(rect.x, rect.y, rect.width, rect.height);
        context.strokeRect(rect.x, rect.y, rect.width, rect.height);
        context.restore();
    }

    function scheduleDraw(state) {
        if (state.redrawPending) return;
        invalidatePaint(state);
        state.redrawPending = true;
        requestAnimationFrame(() => drawSafely(state));
    }

    function drawSafely(state) {
        try {
            draw(state);
            state.drawError = null;
            state.host.removeAttribute("data-docx-atlas-draw-error");
        } catch (error) {
            state.redrawPending = false;
            state.drawError = error instanceof Error ? error.message : String(error);
            state.host.setAttribute("data-docx-atlas-draw-error", state.drawError);
            publishGeometryDiagnostic(state);
        }
    }

    function publishGeometryDiagnostic(state) {
        const first = state.nodes[0] || null;
        const rect = first ? effectiveRect(state, first) : null;
        const topLeft = rect ? screenPointFor(state, rect) : null;
        state.host.setAttribute(
            "data-docx-atlas-geometry-diagnostic",
            JSON.stringify({
                resultNodes: state.nodes.length,
                firstNode: first && {
                    id: first.id,
                    x: rect.x,
                    y: rect.y,
                    width: rect.width,
                    height: rect.height,
                },
                worldBounds: state.worldBounds,
                canvas: {
                    width: state.width,
                    height: state.height,
                    bitmapWidth: state.canvas.width,
                    bitmapHeight: state.canvas.height,
                },
                camera: state.camera,
                transformedFirstRect: rect && {
                    x: topLeft.x,
                    y: topLeft.y,
                    width: rect.width * state.camera.scale,
                    height: rect.height * state.camera.scale,
                },
            }),
        );
    }

    function hitNode(state, point) {
        return state.nodes
            .slice()
            .sort((a, b) => b.depth - a.depth || b.id.localeCompare(a.id))
            .find((node) => contains(effectiveRect(state, node), point));
    }

    function hitRelation(state, screenPoint) {
        const tolerance = 8;
        return state.relations.find((relation) => {
            const source = state.nodesById.get(relation.endpoints.sourceNodeId);
            const target = state.nodesById.get(relation.endpoints.targetNodeId);
            if (!source || !target) return false;
            const from = screenPointFor(state, rectCenter(effectiveRect(state, source)));
            const to = screenPointFor(state, rectCenter(effectiveRect(state, target)));
            return distanceToSegment(screenPoint, from, to) <= tolerance;
        });
    }

    function effectiveRect(state, node) {
        const offset = accumulatedOffset(state, node);
        const minimum = state.minimumSizes.get(node.id);
        const base = {
            x: node.x + offset.x,
            y: node.y + offset.y,
            width: Math.max(node.width, minimum?.width || 0),
            height: Math.max(node.height, minimum?.height || 0),
        };
        if (node.children.length === 0) return base;
        const childRects = node.children.map((child) => effectiveRect(state, child));
        const left = Math.min(base.x, ...childRects.map((rect) => rect.x - padding));
        const right = Math.max(base.x + base.width, ...childRects.map((rect) => rect.x + rect.width + padding));
        const bottom = Math.max(base.y + base.height, ...childRects.map((rect) => rect.y + rect.height + padding));
        return {
            x: left,
            y: base.y,
            width: right - left,
            height: bottom - base.y,
        };
    }

    function accumulatedOffset(state, node) {
        let x = 0;
        let y = 0;
        let current = node;
        while (current) {
            const offset = state.offsets.get(current.id);
            if (offset) {
                x += offset.x;
                y += offset.y;
            }
            current = current.parentId ? state.nodesById.get(current.parentId) : null;
        }
        return { x, y };
    }

    function translateNodeAndDescendants(state, nodeId, delta) {
        const previous = state.offsets.get(nodeId) || { x: 0, y: 0 };
        state.offsets.set(nodeId, { x: previous.x + delta.x, y: previous.y + delta.y });
    }

    function resolveSiblingCollisions(state, nodeId) {
        const node = state.nodesById.get(nodeId);
        const changedIds = new Set(node ? [nodeId] : []);
        if (!node) return changedIds;
        const parent = node.parentId ? state.nodesById.get(node.parentId) : null;
        const siblings = (parent
            ? parent.children
            : state.nodes.filter((candidate) => !candidate.parentId || !state.nodesById.has(candidate.parentId)))
            .filter((candidate) => candidate.id !== nodeId)
            .sort((left, right) => left.id.localeCompare(right.id));
        const nodeRect = effectiveRect(state, node);
        let collisionCount = 0;
        let rightEdge = nodeRect.x;
        let bottomEdge = nodeRect.y;
        siblings.forEach((sibling) => {
            const siblingRect = effectiveRect(state, sibling);
            if (rectanglesOverlap(nodeRect, siblingRect, gap)) collisionCount += 1;
            if (intervalsOverlap(nodeRect.y, nodeRect.height, siblingRect.y, siblingRect.height, gap)) {
                rightEdge = Math.max(rightEdge, siblingRect.x + siblingRect.width + gap);
            }
            if (intervalsOverlap(nodeRect.x, nodeRect.width, siblingRect.x, siblingRect.width, gap)) {
                bottomEdge = Math.max(bottomEdge, siblingRect.y + siblingRect.height + gap);
            }
        });
        if (collisionCount > 0) {
            const moveRight = rightEdge - nodeRect.x;
            const moveDown = bottomEdge - nodeRect.y;
            const delta = moveRight <= moveDown ? { x: moveRight, y: 0 } : { x: 0, y: moveDown };
            const previous = state.offsets.get(nodeId) || { x: 0, y: 0 };
            state.offsets.set(nodeId, { x: previous.x + delta.x, y: previous.y + delta.y });
        }
        state.lastCollisionCount = collisionCount;
        state.host.setAttribute("data-docx-atlas-collision-count", String(collisionCount));
        return changedIds;
    }

    function pushUndo(state, offsets, sizes, changedIds) {
        if (mapsEqual(state.offsets, offsets) && mapsEqual(state.minimumSizes, sizes)) return;
        state.undo.push({ offsets, sizes, changedIds: new Set(changedIds) });
        if (state.undo.length > maximumUndoEntries) state.undo.splice(0, state.undo.length - maximumUndoEntries);
        state.host.setAttribute("data-docx-atlas-undo-depth", String(state.undo.length));
    }

    function relayoutWithState(state, fitWhenReady) {
        requestLayout(state, state.sliceJson, {
            preserveResolvedLayoutState: hasAcceptedCurrentSlice(state),
            resetUndo: false,
            fitWhenReady: fitWhenReady === true,
        });
    }

    function flyToNode(state, nodeId) {
        const node = state.nodesById.get(nodeId);
        if (!node) return;
        const bounds = effectiveRect(state, node);
        const availableWidth = Math.max(1, state.width - 96);
        const availableHeight = Math.max(1, state.height - 96);
        const scale = Math.min(availableWidth / bounds.width, availableHeight / bounds.height, 3.4);
        state.camera.scale = clamp(scale, minScale, maxScale);
        state.camera.x = (state.width - bounds.width * state.camera.scale) / 2 - bounds.x * state.camera.scale;
        state.camera.y = (state.height - bounds.height * state.camera.scale) / 2 - bounds.y * state.camera.scale;
        state.camera.mode = "manual";
        state.userNavigated = true;
        publishViewport(state);
    }

    function applyCamera(state, action) {
        const values = action.slice(7).split(":").map(Number);
        if (values.length !== 3 || values.some((value) => !Number.isFinite(value)) || values[2] <= 0) return;
        state.camera.x = values[0];
        state.camera.y = values[1];
        state.camera.scale = clamp(values[2], minScale, maxScale);
        state.camera.mode = "manual";
        state.userNavigated = true;
        publishViewport(state);
    }

    function applyLayers(state, value) {
        const next = new Set(value.split(",").filter(Boolean));
        if (sameSet(state.layers, next)) return;
        state.layers = next;
        requestLayout(state, state.sliceJson, {
            preserveResolvedLayoutState: hasAcceptedCurrentSlice(state),
            resetUndo: false,
            fitWhenReady: true,
        });
    }

    function hasAcceptedCurrentSlice(state) {
        return state.frame != null && state.acceptedSliceJson === state.sliceJson;
    }

    function effectiveWorldBounds(state) {
        return state.worldBounds;
    }

    function nearResizeHandle(state, node, point) {
        const rect = effectiveRect(state, node);
        const screen = screenPointFor(state, { x: rect.x + rect.width, y: rect.y + rect.height });
        return Math.abs(screen.x - point.x) <= 18 && Math.abs(screen.y - point.y) <= 18;
    }

    function publish(state) {
        publishLayoutLifecycle(state);
        state.host.setAttribute("data-docx-atlas-motion", state.reducedMotion ? "reduced" : "full");
        state.host.setAttribute("data-docx-atlas-undo-depth", String(state.undo.length));
        publishSecondaryCounts(state);
        publishPointerState(state);
        publishViewport(state);
    }

    function publishLayoutLifecycle(state) {
        state.host.setAttribute("data-docx-atlas-renderer", "canvas");
        state.host.setAttribute("data-docx-atlas-node-count", String(state.nodes.length));
        state.host.setAttribute("data-docx-atlas-edge-count", String(state.relations.length));
        state.host.setAttribute("data-docx-atlas-state", state.layoutStatus);
        state.host.setAttribute("data-docx-atlas-layout-runtime", state.layoutRuntime);
        state.host.setAttribute("data-docx-atlas-layout-revision", String(state.layoutRevision));
        setOptionalAttribute(state.host, "data-docx-atlas-layout-error", state.layoutError);
    }

    function publishViewport(state) {
        state.host.setAttribute("data-docx-atlas-scale", String(state.camera.scale));
        state.host.setAttribute("data-docx-atlas-pan-x", String(state.camera.x));
        state.host.setAttribute("data-docx-atlas-pan-y", String(state.camera.y));
        state.host.setAttribute("data-docx-atlas-viewport-mode", state.camera.mode);
        state.host.dispatchEvent(new CustomEvent("docx-atlas-viewport", { bubbles: true, composed: true }));
    }

    function publishLayout(state, nodeId) {
        const offset = state.offsets.get(nodeId) || { x: 0, y: 0 };
        const minimum = state.minimumSizes.get(nodeId) || { width: 0, height: 0 };
        state.host.setAttribute("data-docx-atlas-layout-node-id", nodeId);
        state.host.setAttribute("data-docx-atlas-layout-x", String(offset.x));
        state.host.setAttribute("data-docx-atlas-layout-y", String(offset.y));
        state.host.setAttribute("data-docx-atlas-layout-width", String(minimum.width));
        state.host.setAttribute("data-docx-atlas-layout-height", String(minimum.height));
        state.host.dispatchEvent(new CustomEvent("docx-atlas-layout", { bubbles: true, composed: true }));
    }

    function clearPublishedState(host) {
        [
            "data-docx-atlas-renderer",
            "data-docx-atlas-node-count",
            "data-docx-atlas-edge-count",
            "data-docx-atlas-state",
            "data-docx-atlas-layout-runtime",
            "data-docx-atlas-layout-revision",
            "data-docx-atlas-layout-error",
            "data-docx-atlas-draw-error",
            "data-docx-atlas-geometry-diagnostic",
            "data-docx-atlas-paint-revision",
            "data-docx-atlas-painted-node-id",
            "data-docx-atlas-painted-node-x",
            "data-docx-atlas-painted-node-y",
            "data-docx-atlas-selected-node-id",
            "data-docx-atlas-selected-edge-id",
            "data-docx-atlas-secondary-node-count",
            "data-docx-atlas-secondary-edge-count",
            "data-docx-atlas-hovered-node-id",
            "data-docx-atlas-hovered-edge-id",
            "data-docx-atlas-preview-node-id",
            "data-docx-atlas-preview-edge-id",
            "data-docx-atlas-interaction-node-id",
            "data-docx-atlas-interaction-edge-id",
            "data-docx-atlas-selection-mode",
            "data-docx-atlas-active-pointer-count",
            "data-docx-atlas-gesture",
            "data-docx-atlas-box-selection-count",
            "data-docx-atlas-undo-depth",
            "data-docx-atlas-collision-count",
            "data-docx-atlas-motion",
            "data-docx-atlas-open-node-id",
            "data-docx-atlas-semantic-node-id",
            "data-docx-atlas-semantic-action",
            "data-docx-atlas-layout-node-id",
            "data-docx-atlas-layout-x",
            "data-docx-atlas-layout-y",
            "data-docx-atlas-layout-width",
            "data-docx-atlas-layout-height",
            "data-docx-atlas-scale",
            "data-docx-atlas-pan-x",
            "data-docx-atlas-pan-y",
            "data-docx-atlas-viewport-mode",
        ].forEach((name) => host.removeAttribute(name));
    }

    function setOptionalAttribute(element, name, value) {
        if (value) element.setAttribute(name, value);
        else element.removeAttribute(name);
    }

    function nodeColor(kind) {
        return colors[String(kind).toLowerCase()] || colors.workspace;
    }

    function viewportCenter(state) {
        return { x: state.width / 2, y: state.height / 2 };
    }

    function canvasPoint(state, event) {
        const rect = state.canvas.getBoundingClientRect();
        return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    }

    function worldPoint(state, point) {
        return {
            x: (point.x - state.camera.x) / state.camera.scale,
            y: (point.y - state.camera.y) / state.camera.scale,
        };
    }

    function screenPointFor(state, point) {
        return {
            x: point.x * state.camera.scale + state.camera.x,
            y: point.y * state.camera.scale + state.camera.y,
        };
    }

    function screenDeltaToWorld(state, start, current) {
        return {
            x: (current.x - start.x) / state.camera.scale,
            y: (current.y - start.y) / state.camera.scale,
        };
    }

    function copyMap(source) {
        return new Map(Array.from(source.entries()).map(([key, value]) => [key, { ...value }]));
    }

    function sameSet(left, right) {
        return left.size === right.size && Array.from(left).every((value) => right.has(value));
    }

    function toggleSetValue(values, value) {
        if (values.has(value)) values.delete(value);
        else values.add(value);
    }

    function capturePointer(canvas, pointerId) {
        try {
            canvas.setPointerCapture(pointerId);
        } catch (_) {
            // A browser may reject capture when a synthetic pointer is used by the smoke harness.
        }
    }

    function releasePointer(canvas, pointerId) {
        try {
            if (canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
        } catch (_) {
            // Pointer capture can already be gone after a native cancellation.
        }
    }

    function releaseActivePointers(state) {
        Array.from(state.pointers.keys()).forEach((pointerId) => releasePointer(state.canvas, pointerId));
        state.pointers.clear();
        state.pointer = null;
        state.gesture = null;
        state.boxSelection = null;
        publishPointerState(state);
    }

    function publishPointerState(state) {
        const gesture = state.gesture?.type || (state.boxSelection ? "box" : state.pointer?.mode) || "idle";
        state.host.setAttribute("data-docx-atlas-active-pointer-count", String(state.pointers.size));
        state.host.setAttribute("data-docx-atlas-gesture", gesture);
    }

    function activePointersAreTouch(state) {
        return state.pointers.size === maximumActivePointers &&
            Array.from(state.pointers.values()).every((pointer) => pointer.pointerType === "touch");
    }

    function midpoint(left, right) {
        return { x: (left.x + right.x) / 2, y: (left.y + right.y) / 2 };
    }

    function normalizedRect(start, end) {
        return {
            x: Math.min(start.x, end.x),
            y: Math.min(start.y, end.y),
            width: Math.abs(end.x - start.x),
            height: Math.abs(end.y - start.y),
        };
    }

    function rectanglesOverlap(left, right, spacing) {
        return left.x < right.x + right.width + spacing &&
            left.x + left.width + spacing > right.x &&
            left.y < right.y + right.height + spacing &&
            left.y + left.height + spacing > right.y;
    }

    function intervalsOverlap(leftStart, leftLength, rightStart, rightLength, spacing) {
        return leftStart < rightStart + rightLength + spacing &&
            leftStart + leftLength + spacing > rightStart;
    }

    function mapsEqual(left, right) {
        if (left.size !== right.size) return false;
        return Array.from(left.entries()).every(([id, value]) => {
            const counterpart = right.get(id);
            if (!counterpart) return false;
            const keys = Object.keys(value);
            return keys.length === Object.keys(counterpart).length &&
                keys.every((key) => value[key] === counterpart[key]);
        });
    }

    function rectCenter(rect) {
        return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
    }

    function contains(rect, point) {
        return point.x >= rect.x && point.x <= rect.x + rect.width && point.y >= rect.y && point.y <= rect.y + rect.height;
    }

    function distance(a, b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    function distanceToSegment(point, start, end) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        if (dx === 0 && dy === 0) return distance(point, start);
        const t = clamp(((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy), 0, 1);
        return distance(point, { x: start.x + t * dx, y: start.y + t * dy });
    }

    function ellipsize(context, text, maxWidth) {
        if (context.measureText(text).width <= maxWidth) return text;
        let shortened = text;
        while (shortened.length > 1 && context.measureText(`${shortened}…`).width > maxWidth) {
            shortened = shortened.slice(0, -1);
        }
        return `${shortened}…`;
    }

    function clamp(value, minimum, maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    global.docxAtlasMap = Object.freeze({ mount, update, destroy, command, snapshot });
})(window);
