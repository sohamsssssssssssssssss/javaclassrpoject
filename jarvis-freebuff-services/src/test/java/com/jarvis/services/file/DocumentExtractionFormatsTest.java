package com.jarvis.services.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies real Tika-backed extraction for representative binary document
 * formats. Fixtures are generated programmatically so the tests never depend
 * on binary files in the repository.
 */
class DocumentExtractionFormatsTest {

    private final DocumentExtractionService service = new DocumentExtractionService();

    @Test
    void extractsTextFromPdf(@TempDir Path temp) throws Exception {
        Path pdf = temp.resolve("sample.pdf");
        Files.write(pdf, minimalPdf("Hello JARVIS extraction check"));

        DocumentText result = service.extract(pdf);

        assertEquals(ExtractionStatus.SUCCESS, result.status(), () -> "extraction error: " + result.error());
        assertEquals("application/pdf", result.mediaType());
        assertTrue(result.text().contains("Hello JARVIS extraction check"),
                () -> "extracted text was: " + result.text());
        assertTrue(result.error().isEmpty());
    }

    @Test
    void extractsTextFromDocx(@TempDir Path temp) throws Exception {
        Path docx = temp.resolve("sample.docx");
        Files.write(docx, minimalDocx("Hello JARVIS docx extraction"));

        DocumentText result = service.extract(docx);

        assertEquals(ExtractionStatus.SUCCESS, result.status(), () -> "extraction error: " + result.error());
        assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                result.mediaType());
        assertTrue(result.text().contains("Hello JARVIS docx extraction"),
                () -> "extracted text was: " + result.text());
        assertTrue(result.error().isEmpty());
    }

    /**
     * Builds a valid single-page PDF (1.4) whose only content is one text
     * object, with a correct xref table so PDFBox needs no recovery scan.
     */
    private static byte[] minimalPdf(String sentence) {
        String content = "BT /F1 12 Tf 72 720 Td (" + sentence + ") Tj ET";
        String[] objects = {
                "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n",
                "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n",
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]"
                        + "/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n",
                "4 0 obj<</Length " + content.length() + ">>stream\n" + content + "\nendstream\nendobj\n",
                "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
        };

        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(body.length());
            body.append(object);
        }

        int xrefOffset = body.length();
        body.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            body.append(String.format("%010d 00000 n \n", offset));
        }
        body.append("trailer<</Size 6/Root 1 0 R>>\nstartxref\n")
                .append(xrefOffset)
                .append("\n%%EOF\n");

        return body.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Builds a minimal but spec-compliant DOCX package: content types,
     * package relationships, and one paragraph of text in word/document.xml.
     */
    private static byte[] minimalDocx(String sentence) throws Exception {
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String relationships = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:sectPr/>
                </w:body>
                </w:document>
                """.formatted(sentence);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(relationships.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
