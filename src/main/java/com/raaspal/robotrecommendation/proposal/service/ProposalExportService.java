package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
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

    // Match the template's visual identity exactly
    private static final Color DARK_BLUE  = new Color(8,   62,  146); // #083E92 — template bg
    private static final Color WHITE      = new Color(241, 245, 249); // #F1F5F9
    private static final Color CYAN       = new Color(6,   182, 212); // #06B6D4
    private static final Color MUTED      = new Color(148, 163, 184); // #94A3B8
    private static final Color DARK_MUTED = new Color(71,  85,  105); // #475569

    // Logo position/size from template EMU coords (÷ 12700 → points)
    // Template values: x=10142227, y=272628, cx=1475880, cy=423842 EMU
    private static final double LOGO_X = 10142227.0 / 12700.0; // ≈ 798.6 pt
    private static final double LOGO_Y =   272628.0 / 12700.0; // ≈  21.5 pt
    private static final double LOGO_W =  1475880.0 / 12700.0; // ≈ 116.2 pt
    private static final double LOGO_H =   423842.0 / 12700.0; // ≈  33.4 pt

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        InputStream template = getClass().getResourceAsStream("/templates/proposal-template.pptx");
        if (template == null) {
            throw new IllegalStateException("Proposal template not found in classpath");
        }

        try (XMLSlideShow ppt = new XMLSlideShow(template)) {

            // 1. Replace placeholders in the title slide
            XSLFSlide titleSlide = ppt.getSlides().get(0);
            String title = proposal.getTitle() != null && !proposal.getTitle().isBlank()
                    ? proposal.getTitle() : "Robot Solution Proposal";
            replaceText(titleSlide, "Project Name", title);
            replaceText(titleSlide, "Name: ",  "Name:  RAASPAL Team");
            replaceText(titleSlide, "Title: ", "Title: Solution Specialist");
            replaceText(titleSlide, "Date:",   "Date:  " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));

            // 2. Extract RAASPAL logo bytes from the Contents slide before clearing
            XSLFPictureData logoSource = extractLogoFromSlide(ppt.getSlides().get(1));
            byte[] logoBytes   = logoSource != null ? logoSource.getData() : null;
            PictureType logoType = logoSource != null ? logoSource.getType() : null;

            // 3. Remove all slides after the title
            int total = ppt.getSlides().size();
            for (int i = total - 1; i >= 1; i--) {
                ppt.removeSlide(i);
            }

            // 4. Re-register the logo into the presentation so new slides can reference it
            XSLFPictureData logoData = (logoBytes != null)
                    ? ppt.addPicture(logoBytes, logoType)
                    : null;

            // 5. Content slides for each AI-generated section
            List<String[]> sections = parseMarkdownSections(proposal.getProposalContent());
            for (String[] section : sections) {
                addContentSlide(ppt, section[0], section[1], logoData);
            }

            // 6. Closing slide
            addClosingSlide(ppt, logoData);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Slide builders ───────────────────────────────────────────────────────

    private void addContentSlide(XMLSlideShow ppt, String title, String body,
                                 XSLFPictureData logoData) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(DARK_BLUE);

        // RAASPAL logo — top-right matching template position
        addLogo(slide, logoData, LOGO_X, LOGO_Y, LOGO_W, LOGO_H);

        // Section title
        addText(slide, title,
                new Rectangle2D.Double(50, 26, 700, 62),
                24.0, true, WHITE, TextAlign.LEFT, "Arial");

        // Cyan accent bar below title
        XSLFTextBox accentBar = slide.createTextBox();
        accentBar.setAnchor(new Rectangle2D.Double(50, 92, 860, 3));
        accentBar.setFillColor(CYAN);
        accentBar.setLineColor(CYAN);
        accentBar.clearText();

        // Content body
        String clean = cleanMarkdown(body);
        XSLFTextBox contentBox = slide.createTextBox();
        contentBox.setAnchor(new Rectangle2D.Double(50, 106, 860, 398));
        contentBox.clearText();

        for (String line : clean.split("\n")) {
            if (line.isBlank()) continue;
            XSLFTextParagraph para = contentBox.addNewTextParagraph();
            boolean isBullet = line.startsWith("- ") || line.startsWith("• ");
            if (isBullet) {
                para.setBullet(true);
                line = line.replaceFirst("^[-•]\\s+", "");
            }
            XSLFTextRun run = para.addNewTextRun();
            run.setText(line);
            run.setFontSize(15.0);
            run.setFontFamily("Arial");
            run.setFontColor(isBullet ? MUTED : WHITE);
        }

        // Watermark bottom-right
        addText(slide, "RAASPAL · Confidential",
                new Rectangle2D.Double(620, 511, 290, 22),
                9.0, false, DARK_MUTED, TextAlign.RIGHT, "Arial");
    }

    private void addClosingSlide(XMLSlideShow ppt, XSLFPictureData logoData) {
        XSLFSlide slide = ppt.createSlide();
        slide.getBackground().setFillColor(DARK_BLUE);

        // Logo centered, slightly larger than content slides
        double cLogoW = LOGO_W * 1.6;
        double cLogoH = LOGO_H * 1.6;
        addLogo(slide, logoData, (960 - cLogoW) / 2.0, 295, cLogoW, cLogoH);

        addText(slide, "Thank You",
                new Rectangle2D.Double(80, 165, 800, 115),
                48.0, true, WHITE, TextAlign.CENTER, "Arial");

        addText(slide, "RAASPAL — Robot Solution Specialists",
                new Rectangle2D.Double(80, 348, 800, 54),
                20.0, false, CYAN, TextAlign.CENTER, "Arial");

        addText(slide, "Explore . Innovate . Inspire",
                new Rectangle2D.Double(80, 412, 800, 38),
                13.0, false, MUTED, TextAlign.CENTER, "Arial");

        addText(slide, "Final specifications and pricing are subject to RAASPAL verification and site survey.",
                new Rectangle2D.Double(80, 462, 800, 50),
                11.0, false, DARK_MUTED, TextAlign.CENTER, "Arial");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Finds the RAASPAL logo on a slide — the smallest picture between 1 KB and 60 KB. */
    private XSLFPictureData extractLogoFromSlide(XSLFSlide slide) {
        XSLFPictureData candidate = null;
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFPictureShape pic) {
                XSLFPictureData data = pic.getPictureData();
                if (data == null) continue;
                long size = data.getData().length;
                if (size > 1_000 && size < 60_000) {
                    // Prefer PNG; fall back to anything in the logo-size range
                    if (candidate == null || data.getType() == PictureType.PNG) {
                        candidate = data;
                    }
                }
            }
        }
        return candidate;
    }

    private void addLogo(XSLFSlide slide, XSLFPictureData logoData,
                         double x, double y, double w, double h) {
        if (logoData == null) return;
        XSLFPictureShape logo = slide.createPicture(logoData);
        logo.setAnchor(new Rectangle2D.Double(x, y, w, h));
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D.Double anchor,
                         double size, boolean bold, Color color, TextAlign align, String font) {
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
        run.setFontFamily(font);
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
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("`(.+?)`", "$1")
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
                    sections.add(new String[]{currentTitle, currentBody.toString().trim()});
                    currentBody = new StringBuilder();
                }
                currentTitle = line.replaceFirst("^#{1,6}\\s+", "").trim();
            } else if (currentTitle != null) {
                currentBody.append(line).append("\n");
            }
        }
        if (currentTitle != null) {
            sections.add(new String[]{currentTitle, currentBody.toString().trim()});
        }
        // Fallback: if no ## headers found (e.g. plain-text stored proposals),
        // split by blank-line blocks and treat the first line of each as the title.
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
