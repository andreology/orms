package com.acme.pdf.controller;

import com.acme.pdf.service.DoclingClient;
import com.acme.pdf.service.PdfBoxExtractor;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/api")
public class UploadController {
    @Autowired DoclingClient docling;
    @Autowired PdfBoxExtractor pdfBox;

    @PostMapping(value="/extract", consumes=MediaType.MULTIPART_FORM_DATA_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode extract(@RequestParam("file") MultipartFile file) throws Exception {
        File tmp = File.createTempFile("upload-", ".pdf");
        file.transferTo(tmp);

        ObjectNode doclingRaw = docling.runRaw(tmp);             // full document JSON
        ArrayNode headings   = docling.headings(doclingRaw);     // [{title,level,page,bbox}]
        ObjectNode envelope  = pdfBox.toJsonEnvelope(tmp, headings);

        // include docling raw for the agent (optional but requested earlier)
        envelope.set("docling", doclingRaw);
        return envelope;
    }
}
