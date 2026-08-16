"use strict";

importScripts("docx-atlas-layout.js");

self.addEventListener("message", (event) => {
    const request = event.data;
    if (!request || request.type !== "layout") return;
    try {
        const result = self.docxAtlasLayout.compute(request);
        self.postMessage({ type: "layout-result", revision: request.revision, result });
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        self.postMessage({ type: "layout-error", revision: request.revision, message });
    }
});
