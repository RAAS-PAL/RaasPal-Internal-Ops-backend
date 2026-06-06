package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.apache.poi.sl.usermodel.ShapeType;
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

    private static final double W = 720;   // slide width  (points, 10 in)
    private static final double H = 540;   // slide height (points, 7.5 in)

    private static final Color DARK_BLUE  = new Color(8,   62, 146);
    private static final Color WHITE      = new Color(241, 245, 249);
    private static final Color CYAN       = new Color(6,  182, 212);
    private static final Color MUTED      = new Color(148, 163, 184);
    private static final Color DARK_MUTED = new Color(71,  85, 105);

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) W, (int) H));

            addTitleSlide(ppt, proposal);

            List<String[]> sections = parseMarkdownSections(proposal.getProposalContent());
            for (String[] section : sections) {
                addContentSlide(ppt, section[0], section[1]);
            }

            addClosingSlide(ppt);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Slide builders ───────────────────────────────────────────────────────

    private void addTitleSlide(XMLSlideShow ppt, GeneratedProposal proposal) {
        XSLFSlide slide = ppt.createSlide();

        // Full-slide background rectangle (safer than getBackground().setFillColor)
        fillBackground(slide, DARK_BLUE);

        String title = (proposal.getTitle() != null && !proposal.getTitle().isBlank())
                ? proposal.getTitle() : "Robot Solution Proposal";
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

        // Top cyan bar
        addRect(slide, 0, 0, W, 7, CYAN);

        addText(slide, "RAAS PAL",
                rect(60, 30, 600, 80), 42, true, WHITE, TextAlign.LEFT);

        addText(slide, "Robot Solution & Proposal Generator",
                rect(60, 110, 600, 34), 16, false, MUTED, TextAlign.LEFT);

        // Accent divider
        addRect(slide, 60, 152, 580, 2, CYAN);

        addText(slide, title,
                rect(60, 168, 580, 120), 22, true, WHITE, TextAlign.LEFT);

        addText(slide, "Prepared by: RAASPAL Team",
                rect(60, 320, 580, 26), 12, false, MUTED, TextAlign.LEFT);
        addText(slide, "Date: " + date,
                rect(60, 346, 580, 26), 12, false, MUTED, TextAlign.LEFT);

        addText(slide, "CONFIDENTIAL — For internal RAASPAL use only",
                rect(60, H - 46, 580, 26), 9, false, DARK_MUTED, TextAlign.LEFT);
    }

    private void addContentSlide(XMLSlideShow ppt, String title, String body) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        addText(slide, title,
                rect(50, 20, W - 100, 52), 20, true, WHITE, TextAlign.LEFT);

        addRect(slide, 50, 78, W - 100, 2, CYAN);

        String clean = cleanMarkdown(body);
        XSLFTextBox contentBox = slide.createTextBox();
        contentBox.setAnchor(rect(50, 90, W - 100, H - 126));

        boolean first = true;
        for (String line : clean.split("\n")) {
            if (line.isBlank()) continue;
            boolean isBullet = line.startsWith("- ") || line.startsWith("• ");
            XSLFTextParagraph para = first ? contentBox.getTextParagraphs().get(0)
                                           : contentBox.addNewTextParagraph();
            first = false;
            para.setSpaceBefore(0.0);
            if (isBullet) {
                para.setBullet(true);
                line = line.replaceFirst("^[-•]\\s+", "");
            }
            XSLFTextRun run = para.addNewTextRun();
            run.setText(line);
            run.setFontSize(12.0);
            run.setFontFamily("Calibri");
            run.setFontColor(isBullet ? MUTED : WHITE);
        }

        // If no content was added, write a placeholder in the first paragraph
        if (first) {
            XSLFTextParagraph para = contentBox.getTextParagraphs().get(0);
            XSLFTextRun run = para.addNewTextRun();
            run.setText("");
        }

        addText(slide, "RAASPAL · Confidential",
                rect(W - 220, H - 26, 200, 18), 8, false, DARK_MUTED, TextAlign.RIGHT);
    }

    private void addClosingSlide(XMLSlideShow ppt) {
        XSLFSlide slide = ppt.createSlide();
        fillBackground(slide, DARK_BLUE);

        addRect(slide, 0, 0, W, 7, CYAN);

        addText(slide, "Thank You",
                rect(80, 150, W - 160, 100), 52, true, WHITE, TextAlign.CENTER);

        addText(slide, "RAASPAL — Robot Solution Specialists",
                rect(80, 272, W - 160, 48), 20, false, CYAN, TextAlign.CENTER);

        addText(slide, "Explore · Innovate · Inspire",
                rect(80, 330, W - 160, 34), 13, false, MUTED, TextAlign.CENTER);

        addText(slide, "Final specifications and pricing are subject to RAASPAL verification and site survey.",
                rect(80, 428, W - 160, 48), 10, false, DARK_MUTED, TextAlign.CENTER);
    }

    // ─── Shape helpers ────────────────────────────────────────────────────────

    /** Use a full-slide auto-shape rect as background — more reliable than getBackground(). */
    private void fillBackground(XSLFSlide slide, Color color) {
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(rect(0, 0, W, H));
        bg.setFillColor(color);
        bg.setLineColor(color);
    }

    /** Solid-color rectangle using an auto-shape (not a text box). */
    private void addRect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(rect(x, y, w, h));
        shape.setFillColor(color);
        shape.setLineColor(color);
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D.Double anchor,
                         double size, boolean bold, Color color, TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        // Use the first paragraph that POI creates automatically
        XSLFTextParagraph para = box.getTextParagraphs().get(0);
        para.setTextAlign(align);
        XSLFTextRun run = para.addNewTextRun();
        run.setText(text != null ? text : "");
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontColor(color);
        run.setFontFamily("Calibri");
    }

    private static Rectangle2D.Double rect(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
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
