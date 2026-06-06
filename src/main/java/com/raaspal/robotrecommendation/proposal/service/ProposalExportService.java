package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.*;
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

    // Slide canvas: 720pt × 540pt (default POI, 10" × 7.5")
    private static final double W = 720;
    private static final double H = 540;

    private static final Color DARK_BLUE  = new Color(8,   62, 146);
    private static final Color WHITE      = new Color(241, 245, 249);
    private static final Color CYAN       = new Color(6,  182, 212);
    private static final Color MUTED      = new Color(148, 163, 184);
    private static final Color DARK_MUTED = new Color(71,  85, 105);

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        // Build entirely from scratch — no template file, no removeSlide calls.
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) W, (int) H));

            // 1. Title slide
            addTitleSlide(ppt, proposal);

            // 2. Content slides — one per AI-generated section
            List<String[]> sections = parseMarkdownSections(proposal.getProposalContent());
            for (String[] section : sections) {
                addContentSlide(ppt, section[0], section[1]);
            }

            // 3. Closing slide
            addClosingSlide(ppt);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Slide builders ───────────────────────────────────────────────────────

    private void addTitleSlide(XMLSlideShow ppt, GeneratedProposal proposal) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(DARK_BLUE);

        String title = (proposal.getTitle() != null && !proposal.getTitle().isBlank())
                ? proposal.getTitle() : "Robot Solution Proposal";
        String date  = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

        // Brand bar at top
        addRect(slide, 0, 0, W, 8, CYAN);

        // RAAS PAL wordmark
        addText(slide, "RAAS PAL",
                new Rectangle2D.Double(60, 30, 600, 80),
                42, true, WHITE, TextAlign.LEFT);

        addText(slide, "Robot Solution & Proposal Generator",
                new Rectangle2D.Double(60, 108, 600, 36),
                16, false, MUTED, TextAlign.LEFT);

        // Divider
        addRect(slide, 60, 150, 600, 1.5, CYAN);

        // Proposal title
        addText(slide, title,
                new Rectangle2D.Double(60, 170, 600, 100),
                22, true, WHITE, TextAlign.LEFT);

        // Meta
        addText(slide, "Prepared by: RAASPAL Team",
                new Rectangle2D.Double(60, 310, 600, 28),
                13, false, MUTED, TextAlign.LEFT);
        addText(slide, "Date: " + date,
                new Rectangle2D.Double(60, 338, 600, 28),
                13, false, MUTED, TextAlign.LEFT);

        // Footer disclaimer
        addText(slide, "CONFIDENTIAL — For internal RAASPAL use only",
                new Rectangle2D.Double(60, H - 50, 600, 28),
                9, false, DARK_MUTED, TextAlign.LEFT);
    }

    private void addContentSlide(XMLSlideShow ppt, String title, String body) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(DARK_BLUE);

        // Section title
        addText(slide, title,
                new Rectangle2D.Double(50, 22, W - 100, 54),
                22, true, WHITE, TextAlign.LEFT);

        // Cyan accent bar
        addRect(slide, 50, 80, W - 100, 2.5, CYAN);

        // Content body
        String clean = cleanMarkdown(body);
        XSLFTextBox contentBox = slide.createTextBox();
        contentBox.setAnchor(new Rectangle2D.Double(50, 94, W - 100, H - 130));
        contentBox.clearText();

        for (String line : clean.split("\n")) {
            if (line.isBlank()) continue;
            boolean isBullet = line.startsWith("- ") || line.startsWith("• ");
            XSLFTextParagraph para = contentBox.addNewTextParagraph();
            para.setSpaceBefore(0.0);
            if (isBullet) {
                para.setBullet(true);
                line = line.replaceFirst("^[-•]\\s+", "");
            }
            XSLFTextRun run = para.addNewTextRun();
            run.setText(line);
            run.setFontSize(13.0);
            run.setFontFamily("Calibri");
            run.setFontColor(isBullet ? MUTED : WHITE);
        }

        // Watermark bottom-right
        addText(slide, "RAASPAL · Confidential",
                new Rectangle2D.Double(W - 220, H - 28, 200, 20),
                8, false, DARK_MUTED, TextAlign.RIGHT);
    }

    private void addClosingSlide(XMLSlideShow ppt) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(DARK_BLUE);

        addRect(slide, 0, 0, W, 8, CYAN);

        addText(slide, "Thank You",
                new Rectangle2D.Double(80, 150, W - 160, 110),
                52, true, WHITE, TextAlign.CENTER);

        addText(slide, "RAASPAL — Robot Solution Specialists",
                new Rectangle2D.Double(80, 278, W - 160, 50),
                20, false, CYAN, TextAlign.CENTER);

        addText(slide, "Explore · Innovate · Inspire",
                new Rectangle2D.Double(80, 338, W - 160, 36),
                13, false, MUTED, TextAlign.CENTER);

        addText(slide, "Final specifications and pricing are subject to RAASPAL verification and site survey.",
                new Rectangle2D.Double(80, 430, W - 160, 50),
                10, false, DARK_MUTED, TextAlign.CENTER);
    }

    // ─── Shape helpers ────────────────────────────────────────────────────────

    private void addRect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.setFillColor(color);
        box.setLineColor(color);
        box.clearText();
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D.Double anchor,
                         double size, boolean bold, Color color, TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.clearText();
        XSLFTextParagraph para = box.addNewTextParagraph();
        para.setTextAlign(align);
        XSLFTextRun run = para.addNewTextRun();
        run.setText(text != null ? text : "");
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontColor(color);
        run.setFontFamily("Calibri");
    }

    // ─── Markdown helpers ─────────────────────────────────────────────────────

    private String cleanMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("\\*(.+?)\\*",        "$1")
                .replaceAll("(?m)^#{1,6}\\s+",    "")
                .replaceAll("`(.+?)`",             "$1")
                .trim();
    }

    private List<String[]> parseMarkdownSections(String markdown) {
        List<String[]> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return sections;

        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : markdown.split("\n")) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (currentTitle != null) {
                    sections.add(new String[]{ currentTitle, currentBody.toString().trim() });
                    currentBody = new StringBuilder();
                }
                currentTitle = line.replaceFirst("^#{1,6}\\s+", "").trim();
            } else if (currentTitle != null) {
                currentBody.append(line).append("\n");
            }
        }
        if (currentTitle != null) {
            sections.add(new String[]{ currentTitle, currentBody.toString().trim() });
        }

        // Fallback: no headings — split by blank lines
        if (sections.isEmpty()) {
            for (String block : markdown.split("\\n\\n+")) {
                block = block.trim();
                if (block.isBlank()) continue;
                String[] lines = block.split("\\n", 2);
                sections.add(new String[]{
                        lines[0].trim(),
                        lines.length > 1 ? lines[1].trim() : ""
                });
            }
        }
        return sections;
    }
}
