package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.ai.service.ProposalGenerationAiService;
import com.raaspal.robotrecommendation.proposal.dto.SlideManifest;
import com.raaspal.robotrecommendation.proposal.dto.SlideManifest.SlideData;
import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProposalExportService {

    private static final Logger log = LoggerFactory.getLogger(ProposalExportService.class);

    private static final double W = 720;
    private static final double H = 540;

    private static final Color DARK_BLUE  = new Color(8,   62, 146);
    private static final Color MID_BLUE   = new Color(12,  82, 170);
    private static final Color WHITE      = new Color(241, 245, 249);
    private static final Color CYAN       = new Color(6,  182, 212);
    private static final Color MUTED      = new Color(148, 163, 184);
    private static final Color DARK_MUTED = new Color(71,  85, 105);

    private final ProposalGenerationAiService aiService;

    public ProposalExportService(ProposalGenerationAiService aiService) {
        this.aiService = aiService;
    }

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        SlideManifest manifest = null;
        try {
            manifest = aiService.generateSlideManifest(proposal.getProposalContent());
        } catch (Exception e) {
            log.warn("Slide manifest AI call failed — using text fallback: {}", e.getMessage());
        }

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) W, (int) H));

            if (manifest != null && manifest.slides() != null && !manifest.slides().isEmpty()) {
                log.info("Building PPTX from AI manifest ({} slides)", manifest.slides().size());
                buildFromManifest(ppt, manifest, proposal);
            } else {
                log.info("Building PPTX from text fallback");
                buildFallback(ppt, proposal);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Manifest-based rendering ─────────────────────────────────────────────

    private void buildFromManifest(XMLSlideShow ppt, SlideManifest manifest, GeneratedProposal proposal) {
        for (SlideData slide : manifest.slides()) {
            switch (slide.type() != null ? slide.type() : "content") {
                case "title"     -> buildTitleSlide(ppt, slide, proposal);
                case "key_stats" -> buildKeyStatsSlide(ppt, slide);
                case "table"     -> buildTableSlide(ppt, slide);
                case "closing"   -> buildClosingSlide(ppt, slide);
                default          -> buildContentSlide(ppt, slide);
            }
        }
    }

    private void buildTitleSlide(XMLSlideShow ppt, SlideData data, GeneratedProposal proposal) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        String title = data.title() != null ? data.title()
                : (proposal.getTitle() != null ? proposal.getTitle() : "Robot Solution Proposal");
        String subtitle = data.subtitle() != null ? data.subtitle() : "RAASPAL Customer Proposal";

        // Top cyan bar
        addRect(slide, 0, 0, W, 8, CYAN);

        // "PREPARED BY" small label
        addText(slide, "PREPARED BY RAASPAL",
                rect(60, 22, 600, 20), 9, false, MUTED, TextAlign.LEFT);

        // Main title
        addText(slide, title,
                rect(60, 46, W - 80, 120), 30, true, WHITE, TextAlign.LEFT);

        // Cyan divider
        addRect(slide, 60, 178, 400, 2, CYAN);

        // Subtitle
        addText(slide, subtitle,
                rect(60, 190, 580, 36), 15, false, MUTED, TextAlign.LEFT);

        // Date
        addText(slide, date,
                rect(60, 232, 580, 26), 12, false, DARK_MUTED, TextAlign.LEFT);

        // Bottom accent band
        addRect(slide, 0, H - 56, W, 56, MID_BLUE);
        addText(slide, "RAASPAL · AI Robot Solution & Proposal Generator",
                rect(30, H - 42, W - 60, 28), 11, false, MUTED, TextAlign.LEFT);
        addText(slide, "CONFIDENTIAL",
                rect(30, H - 42, W - 60, 28), 11, true, CYAN, TextAlign.RIGHT);
    }

    private void buildKeyStatsSlide(XMLSlideShow ppt, SlideData data) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        String title = data.title() != null ? data.title() : "Key Numbers";
        addText(slide, title,
                rect(50, 22, W - 100, 46), 22, true, WHITE, TextAlign.LEFT);
        addRect(slide, 50, 72, W - 100, 2, CYAN);

        List<SlideManifest.StatItem> stats = data.stats() != null ? data.stats() : List.of();
        int count = Math.min(stats.size(), 4);
        if (count == 0) return;

        // Layout: up to 4 boxes in a row, centred
        double boxW  = 160;
        double boxH  = 130;
        double gap   = 20;
        double totalW = count * boxW + (count - 1) * gap;
        double startX = (W - totalW) / 2.0;
        double startY = 170;

        for (int i = 0; i < count; i++) {
            SlideManifest.StatItem stat = stats.get(i);
            double bx = startX + i * (boxW + gap);

            // Box background (slightly lighter)
            addRoundRect(slide, bx, startY, boxW, boxH, MID_BLUE);

            // Cyan top accent on each box
            addRect(slide, bx, startY, boxW, 4, CYAN);

            // Value in large cyan
            String value = stat.value() != null ? stat.value() : "—";
            addText(slide, value,
                    rect(bx + 8, startY + 14, boxW - 16, 60), 24, true, CYAN, TextAlign.CENTER);

            // Label below
            String label = stat.label() != null ? stat.label() : "";
            addText(slide, label,
                    rect(bx + 8, startY + 80, boxW - 16, 40), 11, false, MUTED, TextAlign.CENTER);
        }

        footerText(slide);
    }

    private void buildContentSlide(XMLSlideShow ppt, SlideData data) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        String title = data.title() != null ? data.title() : "";
        addText(slide, title,
                rect(50, 22, W - 100, 46), 20, true, WHITE, TextAlign.LEFT);
        addRect(slide, 50, 72, W - 100, 2, CYAN);

        List<String> bullets = data.bullets() != null ? data.bullets() : List.of();
        if (!bullets.isEmpty()) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(rect(60, 88, W - 110, H - 132));

            boolean first = true;
            for (String bullet : bullets) {
                if (bullet == null || bullet.isBlank()) continue;
                XSLFTextParagraph para = first
                        ? box.getTextParagraphs().get(0)
                        : box.addNewTextParagraph();
                first = false;
                para.setBullet(true);
                para.setSpaceBefore(4.0);
                XSLFTextRun run = para.addNewTextRun();
                run.setText(bullet);
                run.setFontSize(13.5);
                run.setFontFamily("Calibri");
                run.setFontColor(WHITE);
            }
            if (first) {
                // no bullets written — write empty placeholder
                box.getTextParagraphs().get(0).addNewTextRun().setText("");
            }
        }

        footerText(slide);
    }

    private void buildTableSlide(XMLSlideShow ppt, SlideData data) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        String title = data.title() != null ? data.title() : "Specifications";
        addText(slide, title,
                rect(50, 22, W - 100, 46), 20, true, WHITE, TextAlign.LEFT);
        addRect(slide, 50, 72, W - 100, 2, CYAN);

        List<String>       headers = data.headers() != null ? data.headers() : List.of("Feature", "Details");
        List<List<String>> rows    = data.rows()    != null ? data.rows()    : List.of();

        int numCols = headers.size();
        int numRows = rows.size() + 1; // +1 for header row
        if (numCols == 0 || numRows <= 1) {
            footerText(slide);
            return;
        }

        double tableX = 50;
        double tableY = 86;
        double tableW = W - 100;
        double rowH   = Math.min(32, (H - tableY - 60) / numRows);

        XSLFTable table = slide.createTable(numRows, numCols);
        table.setAnchor(rect(tableX, tableY, tableW, rowH * numRows));

        double colW = tableW / numCols;
        for (int c = 0; c < numCols; c++) {
            table.setColumnWidth(c, colW);
        }

        // Header row
        for (int c = 0; c < numCols; c++) {
            XSLFTableCell cell = table.getCell(0, c);
            cell.setFillColor(CYAN);
            cell.setBorderColor(XSLFTableCell.BorderEdge.bottom, DARK_BLUE);
            cell.setBorderWidth(XSLFTableCell.BorderEdge.bottom, 1.0);
            XSLFTextParagraph para = cell.getTextParagraphs().isEmpty()
                    ? cell.addNewTextParagraph() : cell.getTextParagraphs().get(0);
            para.setTextAlign(TextAlign.LEFT);
            XSLFTextRun run = para.addNewTextRun();
            run.setText(c < headers.size() ? headers.get(c) : "");
            run.setFontSize(12.0);
            run.setBold(true);
            run.setFontFamily("Calibri");
            run.setFontColor(DARK_BLUE);
        }

        // Data rows
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            Color bg = r % 2 == 0 ? DARK_BLUE : MID_BLUE;
            for (int c = 0; c < numCols; c++) {
                XSLFTableCell cell = table.getCell(r + 1, c);
                cell.setFillColor(bg);
                cell.setBorderColor(XSLFTableCell.BorderEdge.bottom, new Color(30, 70, 140));
                cell.setBorderWidth(XSLFTableCell.BorderEdge.bottom, 0.5);
                XSLFTextParagraph para = cell.getTextParagraphs().isEmpty()
                        ? cell.addNewTextParagraph() : cell.getTextParagraphs().get(0);
                para.setTextAlign(TextAlign.LEFT);
                XSLFTextRun run = para.addNewTextRun();
                run.setText(c < row.size() ? row.get(c) : "");
                run.setFontSize(11.5);
                run.setFontFamily("Calibri");
                run.setFontColor(WHITE);
            }
        }

        footerText(slide);
    }

    private void buildClosingSlide(XMLSlideShow ppt, SlideData data) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);
        addRect(slide, 0, 0, W, 8, CYAN);

        String title = data.title() != null ? data.title() : "Next Steps";
        addText(slide, title,
                rect(80, 30, W - 160, 60), 36, true, WHITE, TextAlign.CENTER);
        addRect(slide, 200, 98, W - 400, 2, CYAN);

        List<String> bullets = data.bullets() != null ? data.bullets() : List.of();
        if (!bullets.isEmpty()) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(rect(100, 118, W - 200, H - 210));
            boolean first = true;
            for (String bullet : bullets) {
                if (bullet == null || bullet.isBlank()) continue;
                XSLFTextParagraph para = first
                        ? box.getTextParagraphs().get(0)
                        : box.addNewTextParagraph();
                first = false;
                para.setBullet(true);
                para.setSpaceBefore(6.0);
                XSLFTextRun run = para.addNewTextRun();
                run.setText(bullet);
                run.setFontSize(13.0);
                run.setFontFamily("Calibri");
                run.setFontColor(MUTED);
            }
            if (first) {
                box.getTextParagraphs().get(0).addNewTextRun().setText("");
            }
        }

        addText(slide, "RAASPAL — Robot Solution Specialists",
                rect(80, H - 80, W - 160, 30), 13, false, CYAN, TextAlign.CENTER);
        addText(slide, "Final specifications and pricing require RAASPAL verification and site survey.",
                rect(80, H - 54, W - 160, 36), 9, false, DARK_MUTED, TextAlign.CENTER);
    }

    // ─── Text-based fallback ──────────────────────────────────────────────────

    private void buildFallback(XMLSlideShow ppt, GeneratedProposal proposal) {
        String title = (proposal.getTitle() != null && !proposal.getTitle().isBlank())
                ? proposal.getTitle() : "Robot Solution Proposal";
        String date  = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

        // Title slide
        SlideData titleData = new SlideData("title", title, "RAASPAL Customer Proposal",
                null, null, null, null);
        buildTitleSlide(ppt, titleData, proposal);

        // Content slides from parsed sections
        List<String[]> sections = parseMarkdownSections(proposal.getProposalContent());
        for (String[] section : sections) {
            List<String> bullets = new ArrayList<>();
            for (String line : section[1].split("\n")) {
                line = line.trim();
                if (line.isBlank()) continue;
                String stripped = line.replaceFirst("^[-•]\\s*", "");
                if (!stripped.isBlank()) bullets.add(stripped);
                if (bullets.size() >= 6) break;
            }
            SlideData sd = new SlideData("content", section[0], null, bullets, null, null, null);
            buildContentSlide(ppt, sd);
        }

        // Closing
        SlideData closing = new SlideData("closing", "Next Steps", null,
                List.of(
                        "Schedule a RAASPAL site survey",
                        "Verify robot specifications with the RAASPAL team",
                        "Confirm budget and timeline with the customer",
                        "Present the proposal for customer sign-off"
                ),
                null, null, null);
        buildClosingSlide(ppt, closing);
    }

    // ─── Shape helpers ────────────────────────────────────────────────────────

    private void fillBackground(XSLFSlide slide, Color color) {
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(rect(0, 0, W, H));
        bg.setFillColor(color);
        bg.setLineColor(color);
    }

    private void addRect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(rect(x, y, w, h));
        shape.setFillColor(color);
        shape.setLineColor(color);
    }

    private void addRoundRect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ROUND_RECT);
        shape.setAnchor(rect(x, y, w, h));
        shape.setFillColor(color);
        shape.setLineColor(color);
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D.Double anchor,
                         double size, boolean bold, Color color, TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        XSLFTextParagraph para = box.getTextParagraphs().get(0);
        para.setTextAlign(align);
        XSLFTextRun run = para.addNewTextRun();
        run.setText(text != null ? text : "");
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontColor(color);
        run.setFontFamily("Calibri");
    }

    private void footerText(XSLFSlide slide) {
        addText(slide, "RAASPAL · Confidential",
                rect(W - 220, H - 26, 200, 18), 8, false, DARK_MUTED, TextAlign.RIGHT);
    }

    private static Rectangle2D.Double rect(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
    }

    // ─── Markdown parser (fallback) ───────────────────────────────────────────

    private List<String[]> parseMarkdownSections(String markdown) {
        List<String[]> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return sections;
        String currentTitle = null;
        StringBuilder body  = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (currentTitle != null)
                    sections.add(new String[]{ currentTitle, body.toString().trim() });
                currentTitle = line.replaceFirst("^#{1,6}\\s+", "").trim();
                body = new StringBuilder();
            } else if (currentTitle != null) {
                body.append(line).append("\n");
            }
        }
        if (currentTitle != null) sections.add(new String[]{ currentTitle, body.toString().trim() });
        if (sections.isEmpty()) {
            for (String block : markdown.split("\\n\\n+")) {
                block = block.trim();
                if (block.isBlank()) continue;
                String[] lines = block.split("\\n", 2);
                sections.add(new String[]{ lines[0].trim(), lines.length > 1 ? lines[1].trim() : "" });
            }
        }
        return sections;
    }
}
