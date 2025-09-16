package com.acme.pdf.service;

import com.acme.pdf.util.IoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
public class PdfBoxExtractor {
    private static final ObjectMapper M = new ObjectMapper();

    /** Captures one PDF form field plus geometry for layout grouping */
    public static class FieldGeom {
        public String key;
        public String originalName;
        public String ui;          // text | checkbox | radio | select | multiselect
        public ObjectNode schema;  // JSON Schema fragment for this field
        public int page = 1;       // 1-based
        public PDRectangle rect;   // widget rect (first visible)
    }

    /** Build the full response envelope (schema + uiHints + provenance + layout) */
    public ObjectNode toJsonEnvelope(File pdf, ArrayNode headings) throws Exception {
        Map<String, FieldGeom> fields = new LinkedHashMap<>();

        try (PDDocument doc = PDDocument.load(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) {
                for (PDField f : flatten(form.getFields())) {
                    FieldGeom g = new FieldGeom();
                    g.originalName = f.getFullyQualifiedName();
                    g.key = IoUtils.sanitizeKey(g.originalName);

                    // schema + ui type
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
                        ObjectNode items = M.createObjectNode();
                        items.put("type", "string");
                        items.set("enum", en);
                        g.schema.set("items", items);
                    } else {
                        g.ui = "text"; g.schema.put("type", "string");
                    }

                    // take first visible widget to get page + bbox
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

        // ---------- Build JSON Schema + uiHints + provenance ----------
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

        // ---------- Build layout from Docling headings + field geometry ----------
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

    /** Group fields under the nearest upstream heading on the same page; fall back to per-page "Ungrouped". */
    private ArrayNode buildLayout(Collection<FieldGeom> fields, ArrayNode headings) {
        // Normalize headings into per-page lists, sorted by Y (top->bottom-ish)
        Map<Integer, List<ObjectNode>> perPage = new HashMap<>();
        for (JsonNode h : headings) {
            int page = h.path("page").asInt(1);
            ObjectNode hn = M.createObjectNode();
            hn.put("title", h.path("title").asText(""));
            hn.put("page", page);
            ObjectNode bb = M.createObjectNode();
            JsonNode hb = h.path("bbox");
            bb.put("x", hb.path("x").asDouble(0));
            bb.put("y", hb.path("y").asDouble(0));
            bb.put("w", hb.path("w").asDouble(0));
            bb.put("h", hb.path("h").asDouble(0));
            hn.set("bbox", bb);
            hn.set("keys", M.createArrayNode());
            perPage.computeIfAbsent(page, k -> new ArrayList<>()).add(hn);
        }
        perPage.values().forEach(list ->
            list.sort(Comparator.comparingDouble(n -> n.path("bbox").path("y").asDouble()))
        );

        // Assign each field to nearest heading above it; else per-page default section
        Map<Integer, ObjectNode> defaultByPage = new HashMap<>();
        for (FieldGeom f : fields) {
            ObjectNode sec = nearestHeadingFor(f, perPage.getOrDefault(f.page, List.of()));
            if (sec == null) {
                sec = defaultByPage.computeIfAbsent(f.page, this::defaultSection);
            }
            sec.withArray("keys").add(f.key);
        }

        // Build final ordered layout (page asc -> headings top->bottom -> default bucket)
        ArrayNode out = M.createArrayNode();
        Set<Integer> allPages = new TreeSet<>(perPage.keySet());
        allPages.addAll(defaultByPage.keySet());
        for (int pg : allPages) {
            for (ObjectNode h : perPage.getOrDefault(pg, List.of())) {
                if (h.withArray("keys").size() > 0) out.add(toLayoutSection(h));
            }
            ObjectNode def = defaultByPage.get(pg);
            if (def != null && def.withArray("keys").size() > 0) out.add(toLayoutSection(def));
        }

        // If still empty (no headings at all), dump a single default with all fields
        if (out.size() == 0 && !fields.isEmpty()) {
            ObjectNode def = defaultSection(-1);
            for (FieldGeom f : fields) def.withArray("keys").add(f.key);
            out.add(toLayoutSection(def));
        }
        return out;
    }

    /** <<< This is the missing method you noticed. Converts a heading node to the minimal layout section. >>> */
    private ObjectNode toLayoutSection(ObjectNode headingNode) {
        ObjectNode s = M.createObjectNode();
        s.put("title", headingNode.path("title").asText("Section"));
        ArrayNode keys = M.createArrayNode();
        headingNode.withArray("keys").forEach(keys::add);
        s.set("keys", keys);
        return s;
    }

    private ObjectNode defaultSection(int page) {
        ObjectNode s = M.createObjectNode();
        s.put("title", page > 0 ? ("Ungrouped (page " + page + ")") : "Ungrouped");
        s.put("page", page);
        s.set("bbox", M.createObjectNode().put("x", 0).put("y", 0).put("w", 0).put("h", 0));
        s.set("keys", M.createArrayNode());
        return s;
    }

    /** Find the nearest heading above the field on the same page (by Y). */
    private ObjectNode nearestHeadingFor(FieldGeom f, List<ObjectNode> candidates) {
        if (f.rect == null || candidates.isEmpty()) return null;
        double fy = f.rect.getLowerLeftY(); // PDF user-space Y
        ObjectNode best = null;
        double bestDy = Double.MAX_VALUE;
        for (ObjectNode h : candidates) {
            double hy = h.path("bbox").path("y").asDouble();
            double dy = fy - hy;              // prefer headings above the field
            if (dy >= -5 && dy < bestDy) {    // small tolerance for overlap
                bestDy = dy;
                best = h;
            }
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
