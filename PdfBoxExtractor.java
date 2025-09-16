package com.acme.pdf.service;

import com.acme.pdf.util.IoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
public class PdfBoxExtractor {
    private static final ObjectMapper M = new ObjectMapper();

    /** container for one form field (may have multiple widgets; we pick first visible) */
    public static class FieldGeom {
        public String key, originalName;
        public String ui; // text, checkbox, radio, select, multiselect
        public ObjectNode schema; // JSON Schema for this field
        public int page = 1;
        public PDRectangle rect; // in user units
    }

    public ObjectNode toJsonEnvelope(File pdf, ArrayNode headings) throws Exception {
        Map<String, FieldGeom> fields = new LinkedHashMap<>();

        try (PDDocument doc = PDDocument.load(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();

            if (form != null) {
                for (PDField f : flatten(form.getFields())) {
                    FieldGeom g = new FieldGeom();
                    g.originalName = f.getFullyQualifiedName();
                    g.key = IoUtils.sanitizeKey(g.originalName);
                    g.schema = M.createObjectNode().put("title", g.originalName);

                    if (f instanceof PDTextField) {
                        g.ui = "text"; g.schema.put("type", "string");
                    } else if (f instanceof PDCheckBox) {
                        g.ui = "checkbox"; g.schema.put("type", "boolean");
                    } else if (f instanceof PDRadioButton rb) {
                        g.ui = "radio"; g.schema.put("type", "string");
                        ArrayNode en = M.createArrayNode();
                        for (String v : rb.getOnValues()) en.add(v);
                        g.schema.set("enum", en);
                    } else if (f instanceof PDComboBox cb) {
                        g.ui = "select"; g.schema.put("type", "string");
                        ArrayNode en = M.createArrayNode();
                        for (String v : cb.getOptionsDisplayValues()) en.add(v);
                        g.schema.set("enum", en);
                    } else if (f instanceof PDListBox lb) {
                        g.ui = "multiselect";
                        g.schema.put("type", "array").put("uniqueItems", true);
                        ArrayNode en = M.createArrayNode();
                        for (String v : lb.getOptionsDisplayValues()) en.add(v);
                        ObjectNode items = M.createObjectNode().put("type", "string");
                        items.set("enum", en);
                        g.schema.set("items", items);
                    } else {
                        g.ui = "text"; g.schema.put("type", "string");
                    }

                    // take first visible widget rect+page for grouping
                    PDRectangle bestRect = null;
                    int bestPage = 1;
                    for (PDAnnotationWidget w : f.getWidgets()) {
                        if (w.getRectangle() != null && w.getPage() != null) {
                            bestRect = w.getRectangle();
                            bestPage = doc.getPages().indexOf(w.getPage()) + 1; // 1-based
                            break;
                        }
                    }
                    g.rect = bestRect;
                    g.page = bestPage;
                    fields.put(g.key, g);
                }
            }
        }

        // Build JSON Schema + uiHints
        ObjectNode schemaRoot = M.createObjectNode();
        schemaRoot.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schemaRoot.put("$id", "urn:acme:pdf:digitalizer:" + UUID.randomUUID());
        schemaRoot.put("type", "object");

        ObjectNode props = M.createObjectNode();
        ObjectNode uiHints = M.createObjectNode();
        ObjectNode fieldMap = M.createObjectNode();

        for (FieldGeom g : fields.values()) {
            props.set(g.key, g.schema);
            uiHints.put(g.key, g.ui);
            fieldMap.put(g.key, g.originalName);
        }
        schemaRoot.set("properties", props);
        schemaRoot.set("required", M.createArrayNode());

        // Build LAYOUT using headings + geometry
        ArrayNode layout = buildLayout(fields.values(), headings);

        ObjectNode env = M.createObjectNode();
        env.set("schema", schemaRoot);
        env.set("uiHints", uiHints);
        ObjectNode prov = M.createObjectNode();
        prov.set("fieldMap", fieldMap);
        env.set("provenance", prov);
        env.set("layout", layout);
        return env;
    }

    private ArrayNode buildLayout(Collection<FieldGeom> fields, ArrayNode headings) {
        ArrayNode out = M.createArrayNode();

        // Group headings by page then sort by vertical position (top->bottom)
        Map<Integer, List<ObjectNode>> perPage = new HashMap<>();
        for (JsonNode h : headings) {
            int page = h.path("page").asInt(1);
            perPage.computeIfAbsent(page, k -> new ArrayList<>()).add((ObjectNode) h);
        }
        perPage.values().forEach(list -> list.sort(Comparator.comparingDouble(n -> n.path("bbox").path("y").asDouble())));

        // Helper to find nearest upstream heading on same page
        for (FieldGeom f : fields) {
            ObjectNode sec = nearestHeadingFor(f, perPage.getOrDefault(f.page, List.of()));
            if (sec == null) sec = defaultSection(); // "Ungrouped"
            sec.withArray("keys").add(f.key);
        }

        // Build final array in document order (page -> headings order -> default section last per page)
        List<ObjectNode> ordered = new ArrayList<>();
        perPage.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            for (ObjectNode h : e.getValue()) {
                if (h.withArray("keys").size() > 0) ordered.add(h);
            }
            // Add leftover Ungrouped on that page if any keys exist
            ObjectNode leftover = (ObjectNode) hDefaultByPage.get(e.getKey());
            if (leftover != null && leftover.withArray("keys").size() > 0) ordered.add(leftover);
        });
        // Also add any global default (if no headings found at all)
        if (hDefaultByPage.isEmpty() && defaultGlobal.withArray("keys").size() > 0) {
            ordered.add(defaultGlobal);
        }

        for (ObjectNode s : ordered) out.add(toLayoutSection(s));
        return out;
    }

    // default section holders
    private final Map<Integer, Object> hDefaultByPage = new HashMap<>();
    private final ObjectNode defaultGlobal = defaultSection();

    private ObjectNode defaultSection() {
        ObjectNode s = M.createObjectNode();
        s.put("title", "Ungrouped");
        s.put("page", -1);
        s.set("bbox", M.createObjectNode().put("x", 0).put("y", 0).put("w", 0).put("h", 0));
        s.set("keys", M.createArrayNode());
        return s;
    }

    private ObjectNode nearestHeadingFor(FieldGeom f, List<ObjectNode> candidates) {
        if (f.rect == null || candidates.isEmpty()) return null;
        double fx = f.rect.getLowerLeftX(), fy = f.rect.getLowerLeftY();

        ObjectNode best = null;
        double bestDy = Double.MAX_VALUE;

        for (ObjectNode h : candidates) {
            JsonNode bb = h.path("bbox");
            double hx = bb.path("x").asDouble();
            double hy = bb.path("y").asDouble();
            double dy = fy - hy; // distance above
            if (dy >= -5 && dy < bestDy) { // allow slight overlap
                bestDy = dy;
                best = h;
            }
        }
        if (best == null) {
            // fall back per-page default bucket
            return (ObjectNode) hDefaultByPage.computeIfAbsent(f.page, k -> defaultSection());
        }
        return best;
    }

    private List<PDField> flatten(List<PDField> fields) {
        List<PDField> out = new ArrayList<>();
        for (PDField f : fields) {
            out.add(f);
            if (f instanceof PDNonTerminalField nt) out.addAll(flatten(nt.getChildren()));
        }
        return out;
    }
}
