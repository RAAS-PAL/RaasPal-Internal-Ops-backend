package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProposalExportService {

    private static final Color NAVY      = new Color(15,  23,  42);
    private static final Color CYAN      = new Color(6,   182, 212);
    private static final Color WHITE     = new Color(241, 245, 249);
    private static final Color SLATE_300 = new Color(203, 213, 225);

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        InputStream template = getClass().getResourceAsStream("/templates/proposal-template.pptx");
        if (template == null) {
            throw new IllegalStateException("Proposal template not found in classpath");
        }

        try (XMLSlideShow ppt = new XMLSlideShow(template)) {

            // ── 1. Replace placeholders in the title slide ────────────────────
            XSLFSlide titleSlide = ppt.getSlides().get(0);
            String title = proposal.getTitle() != null && !proposal.getTitle().isBlank()
                    ? proposal.getTitle() : "Robot Solution Proposal";
            replaceText(titleSlide, "Project Name", title);
            replaceText(titleSlide, "Name: ",  "Name:  RAASPAL Team");
            replaceText(titleSlide, "Title: ", "Title: Solution Specialist");
            replaceText(titleSlide, "Date:",   "Date:  " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));

            // ── 2. Remove all slides except the title (index 0) ──────────────
            int total = ppt.getSlides().size();
            for (int i = total - 1; i >= 1; i--) {
                ppt.removeSlide(i);
            }

            // ── 3. Add content slides for each proposal section ───────────────
            List<String[]> sections = parseMarkdownSections(proposal.getProposalContent());
            for (String[] section : sections) {
                addContentSlide(ppt, section[0], section[1]);
            }

            // ── 4. Closing slide ──────────────────────────────────────────────
            addClosingSlide(ppt);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Slide builders ───────────────────────────────────────────────────────

    private void addContentSlide(XMLSlideShow ppt, String title, String body) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(NAVY);

        // Section title
        addText(slide, title,
                new Rectangle2D.Double(50, 28, 860, 80),
                26.0, true, CYAN, TextAlign.LEFT);

        // Content body
        String clean = cleanMarkdown(body);
        XSLFTextBox contentBox = slide.createTextBox();
        contentBox.setAnchor(new Rectangle2D.Double(50, 120, 860, 390));
        contentBox.clearText();

        for (String line : clean.split("\n")) {
            if (line.isBlank()) continue;
            XSLFTextParagraph para = contentBox.addNewTextParagraph();
            boolean bullet = line.startsWith("- ") || line.startsWith("• ");
            if (bullet) {
                para.setBullet(true);
                line = line.replaceFirst("^[-•]\\s+", "");
            }
            XSLFTextRun run = para.addNewTextRun();
            run.setText(line);
            run.setFontSize(15.0);
            run.setFontColor(bullet ? SLATE_300 : WHITE);
        }

        // RAASPAL watermark bottom-right
        addText(slide, "RAASPAL · Confidential",
                new Rectangle2D.Double(600, 500, 310, 30),
                10.0, false, new Color(71, 85, 105), TextAlign.RIGHT);
    }

    private void addClosingSlide(XMLSlideShow ppt) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(NAVY);

        addText(slide, "Thank You",
                new Rectangle2D.Double(80, 170, 800, 120),
                48.0, true, WHITE, TextAlign.CENTER);

        addText(slide, "RAASPAL — Robot Solution Specialists",
                new Rectangle2D.Double(80, 310, 800, 60),
                20.0, false, CYAN, TextAlign.CENTER);

        addText(slide, "Final specifications and pricing are subject to RAASPAL verification and site survey.",
                new Rectangle2D.Double(80, 420, 800, 50),
                12.0, false, new Color(100, 116, 139), TextAlign.CENTER);
    }

    // ─── Text helpers ─────────────────────────────────────────────────────────

    private void addText(XSLFSlide slide, String text, Rectangle2D.Double anchor,
                         double size, boolean bold, Color color, TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.clearText();
        XSLFTextParagraph para = box.addNewTextParagraph();
        para.setTextAlign(align);
        XSLFTextRun run = para.addNewTextRun();
        run.setText(text);
        run.setFontSize(size);
        run.setBold(bold);
        run.setFontColor(color);
    }

    private void replaceText(XSLFSlide slide, String find, String replace) {
        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape)) continue;
            for (XSLFTextParagraph para : ((XSLFTextShape) shape).getTextParagraphs()) {
                for (XSLFTextRun run : para.getTextRuns()) {
                    String raw = run.getRawText();
                    if (raw != null && raw.contains(find)) {
                        run.setText(raw.replace(find, replace));
                    }
                }
            }
        }
    }

    // ─── Markdown helpers ──────────────────────────────────────────────────────

    private String cleanMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("\\*(.+?)\\*", "$1")
                .replaceAll("^#{1,6}\\s+", "")
                .replaceAll("`(.+?)`", "$1")
                .trim();
    }

    private List<String[]> parseMarkdownSections(String markdown) {
        List<String[]> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return sections;
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## ")) {
                if (currentTitle != null) {
                    sections.add(new String[]{currentTitle, currentBody.toString().trim()});
                    currentBody = new StringBuilder();
                }
                currentTitle = line.substring(3).trim();
            } else if (!line.startsWith("# ") && currentTitle != null) {
                currentBody.append(line).append("\n");
            }
        }
        if (currentTitle != null) {
            sections.add(new String[]{currentTitle, currentBody.toString().trim()});
        }
        return sections;
    }

}
