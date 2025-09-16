package com.acme.pdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.*;

@Service
public class DoclingClient {
    @Value("${docling.pythonCmd:python3}") private String pythonCmd;
    @Value("${docling.scriptPath:../docling/docling_runner.py}") private String scriptPath;

    private final ObjectMapper M = new ObjectMapper();

    public ObjectNode runRaw(File pdf) throws Exception {
        String cmd = Optional.ofNullable(System.getenv("DOC_PY_CMD")).filter(s -> !s.isBlank()).orElse(pythonCmd);
        Process p = new ProcessBuilder(cmd, scriptPath, pdf.getAbsolutePath())
                .redirectErrorStream(true).directory(new File(".")).start();
        try (InputStream is = p.getInputStream()) {
            int code = p.waitFor();
            String out = new String(is.readAllBytes());
            if (code != 0 || out.isBlank()) return M.createObjectNode();
            return (ObjectNode) M.readTree(out);
        }
    }

    /** Extract lightweight heading list: [{title,level,page,bbox:{x,y,w,h}}] */
    public ArrayNode headings(ObjectNode doclingJson) {
        ArrayNode arr = M.createArrayNode();
        if (doclingJson.isEmpty()) return arr;

        JsonNode pages = doclingJson.path("pages");
        for (JsonNode p : pages) {
            int pageNo = p.path("page_no").asInt(p.path("number").asInt(1)); // tolerate schema flavors
            for (JsonNode b : p.path("blocks")) {
                String kind = b.path("type").asText();
                if (!kind.toLowerCase().contains("heading")) continue;

                String text = b.path("text").asText().trim();
                if (text.isEmpty()) continue;

                int level = b.path("level").asInt(
                        kind.equalsIgnoreCase("heading1") ? 1 :
                        kind.equalsIgnoreCase("heading2") ? 2 :
                        kind.equalsIgnoreCase("heading3") ? 3 : 4);

                JsonNode bb = b.path("bbox");
                ObjectNode one = M.createObjectNode();
                one.put("title", text);
                one.put("level", level);
                one.put("page", pageNo);

                ObjectNode bbox = M.createObjectNode();
                bbox.put("x", bb.path("x").asDouble(bb.path("left").asDouble(0)));
                bbox.put("y", bb.path("y").asDouble(bb.path("top").asDouble(0)));
                bbox.put("w", bb.path("w").asDouble(bb.path("width").asDouble(0)));
                bbox.put("h", bb.path("h").asDouble(bb.path("height").asDouble(0)));

                one.set("bbox", bbox);
                arr.add(one);
            }
        }
        return arr;
    }
}
