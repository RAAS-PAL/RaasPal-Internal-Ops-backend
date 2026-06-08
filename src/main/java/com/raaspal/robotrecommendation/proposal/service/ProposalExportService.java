package com.raaspal.robotrecommendation.proposal.service;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;
import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import com.raaspal.robotrecommendation.robot.entity.Robot;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
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
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProposalExportService {

    private static final Logger log = LoggerFactory.getLogger(ProposalExportService.class);

    private static final double W = 720;
    private static final double H = 540;

    private static final Color BG_DARK   = new Color(8,   62, 146);
    private static final Color BG_MID    = new Color(12,  82, 170);
    private static final Color WHITE     = new Color(241, 245, 249);
    private static final Color CYAN      = new Color(6,  182, 212);
    private static final Color MUTED     = new Color(148, 163, 184);
    private static final Color DIM       = new Color(71,  85, 105);
    private static final Color DARK_CYAN = new Color(8,  145, 178);

    private record ProposalSection(String title, List<String> bullets) {}

    private record RobotCtx(Robot robot, RecommendationItem item,
                             byte[] imageBytes, PictureType pictureType) {
        boolean hasImage() { return imageBytes != null; }
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    public byte[] exportToPptx(GeneratedProposal proposal) throws IOException {
        RecommendationItem item = proposal.getRecommendationItem();
        Robot robot = item != null ? item.getRobot() : null;

        byte[] imageBytes = null;
        PictureType pictureType = PictureType.JPEG;
        if (robot != null && robot.getImageUrl() != null && !robot.getImageUrl().isBlank()) {
            try {
                pictureType = detectPictureType(robot.getImageUrl());
                imageBytes = downloadImageBytes(robot.getImageUrl());
                log.info("Robot image loaded from {}", robot.getImageUrl());
            } catch (Exception e) {
                log.warn("Could not load robot image: {}", e.getMessage());
            }
        }

        RobotCtx ctx = new RobotCtx(robot, item, imageBytes, pictureType);
        List<ProposalSection> sections = parseProposalSections(proposal.getProposalContent());

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) W, (int) H));

            buildTitleSlide(ppt, proposal, ctx);

            if (robot != null) {
                buildRobotProfileSlide(ppt, ctx);
            }

            for (ProposalSection section : sections) {
                buildContentSlide(ppt, section.title(), section.bullets());
            }

            buildClosingSlide(ppt, ctx);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    // ─── Slide builders ───────────────────────────────────────────────────────

    private void buildTitleSlide(XMLSlideShow ppt, GeneratedProposal proposal, RobotCtx ctx) {
        XSLFSlide slide = ppt.createSlide();
        fillBg(slide, BG_DARK);

        String title   = safe(proposal.getTitle(), "Robot Solution Proposal");
        String robTag  = robotLabel(ctx.robot());
        String recName = proposal.getRecommendation() != null
                && proposal.getRecommendation().getName() != null
                ? proposal.getRecommendation().getName() : "";
        String date    = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

        boolean hasImg = ctx.hasImage();
        double textW = hasImg ? W * 0.55 : W - 80;

        addRect(slide, 0, 0, W, 8, CYAN);

        addText(slide, "PREPARED BY  RAASPAL",
                rect(60, 20, textW, 20), 9, false, MUTED, TextAlign.LEFT);

        addText(slide, title,
                rect(60, 44, textW - 20, 130), 28, true, WHITE, TextAlign.LEFT);

        addRect(slide, 60, 182, Math.min(380, textW - 40), 2, CYAN);

        if (!robTag.isBlank()) {
            addText(slide, robTag,
                    rect(60, 192, textW - 20, 28), 13, true, CYAN, TextAlign.LEFT);
        }
        if (!recName.isBlank()) {
            addText(slide, recName,
                    rect(60, 218, textW - 20, 24), 11, false, MUTED, TextAlign.LEFT);
        }

        addText(slide, date,
                rect(60, 248, textW - 20, 22), 11, false, DIM, TextAlign.LEFT);

        if (hasImg) {
            addPicture(slide, ppt, ctx.imageBytes(), ctx.pictureType(),
                    W * 0.57, 10, W * 0.40, H * 0.76);
        }

        addRect(slide, 0, H - 52, W, 52, BG_MID);
        addText(slide, "RAASPAL  ·  AI Robot Solution & Proposal Generator",
                rect(28, H - 38, W - 56, 26), 10, false, MUTED, TextAlign.LEFT);
        addText(slide, "CONFIDENTIAL",
                rect(28, H - 38, W - 56, 26), 10, true, CYAN, TextAlign.RIGHT);
    }

    private void buildRobotProfileSlide(XMLSlideShow ppt, RobotCtx ctx) {
        Robot robot = ctx.robot();
        RecommendationItem item = ctx.item();
        XSLFSlide slide = ppt.createSlide();
        fillBg(slide, BG_DARK);
        addRect(slide, 0, 0, W, 8, CYAN);

        addText(slide, robotLabel(robot),
                rect(50, 16, W - 120, 42), 22, true, WHITE, TextAlign.LEFT);

        String fitLevel = item != null && item.getFitLevel() != null
                ? item.getFitLevel().toUpperCase() : "";
        if (!fitLevel.isBlank()) {
            addRect(slide, 50, 62, 120, 22, CYAN);
            addText(slide, fitLevel, rect(52, 64, 116, 18), 9, true, BG_DARK, TextAlign.CENTER);
        }

        double ruleY = fitLevel.isBlank() ? 66 : 90;
        addRect(slide, 50, ruleY, 320, 2, CYAN);
        double contentY = ruleY + 10;

        if (ctx.hasImage()) {
            addPicture(slide, ppt, ctx.imageBytes(), ctx.pictureType(),
                    42, contentY, 292, H - contentY - 68);

            double rx = 350;
            double rw = W - rx - 26;

            String why = item != null ? safe(item.getWhyRecommended(), "") : "";
            if (!why.isBlank()) {
                addText(slide, "WHY THIS ROBOT",
                        rect(rx, contentY, rw, 20), 9, true, CYAN, TextAlign.LEFT);
                String t = why.length() > 500 ? why.substring(0, 497) + "…" : why;
                addText(slide, t, rect(rx, contentY + 24, rw, 230), 11, false, WHITE, TextAlign.LEFT);
            }

            double metaY = H - 104;
            addMetaBox(slide, rx, metaY, rw / 2 - 5, robotType(robot), "TYPE");
            addMetaBox(slide, rx + rw / 2 + 5, metaY, rw / 2 - 5, priceBand(robot), "PRICE BAND");

            String price = formatPrice(robot);
            if (!price.isBlank()) {
                addText(slide, price, rect(rx, metaY + 52, rw, 20), 10, false, MUTED, TextAlign.LEFT);
            }
        } else {
            String why = item != null ? safe(item.getWhyRecommended(), "") : "";
            if (!why.isBlank()) {
                addText(slide, "WHY THIS ROBOT",
                        rect(50, contentY, W - 80, 20), 9, true, CYAN, TextAlign.LEFT);
                String t = why.length() > 800 ? why.substring(0, 797) + "…" : why;
                addText(slide, t, rect(50, contentY + 24, W - 80, 300), 12, false, WHITE, TextAlign.LEFT);
            }
        }

        footer(slide);
    }

    private void buildContentSlide(XMLSlideShow ppt, String title, List<String> bullets) {
        XSLFSlide slide = ppt.createSlide();
        fillBg(slide, BG_DARK);

        addRect(slide, 0, 0, 6, H, CYAN);

        addText(slide, title.toUpperCase(),
                rect(24, 20, W - 44, 40), 18, true, WHITE, TextAlign.LEFT);
        addRect(slide, 24, 66, W - 48, 2, DARK_CYAN);

        if (!bullets.isEmpty()) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(rect(32, 78, W - 70, H - 118));
            boolean first = true;
            for (String b : bullets) {
                if (b == null || b.isBlank()) continue;
                XSLFTextParagraph para = first
                        ? box.getTextParagraphs().get(0)
                        : box.addNewTextParagraph();
                first = false;
                para.setBullet(true);
                para.setSpaceBefore(6.0);
                XSLFTextRun run = para.addNewTextRun();
                run.setText(b);
                run.setFontSize(12.5);
                run.setFontFamily("Calibri");
                run.setFontColor(WHITE);
            }
            if (first) box.getTextParagraphs().get(0).addNewTextRun().setText("");
        }

        footer(slide);
    }

    private void buildClosingSlide(XMLSlideShow ppt, RobotCtx ctx) {
        XSLFSlide slide = ppt.createSlide();
        fillBg(slide, BG_DARK);
        addRect(slide, 0, 0, W, 8, CYAN);
        addRect(slide, 0, H - 8, W, 8, CYAN);

        addText(slide, "NEXT STEPS",
                rect(60, 28, W - 120, 54), 34, true, WHITE, TextAlign.CENTER);
        addRect(slide, 200, 88, W - 400, 2, CYAN);

        List<String> steps = new ArrayList<>();
        RecommendationItem item = ctx.item();
        if (item != null && item.getSuggestedNextStep() != null
                && !item.getSuggestedNextStep().isBlank()) {
            for (String line : item.getSuggestedNextStep().split("\n")) {
                line = line.replaceFirst("^[-*•\\d.]+\\s*", "").trim();
                if (!line.isBlank() && steps.size() < 5) steps.add(line);
            }
        }
        if (steps.isEmpty()) {
            steps = List.of(
                    "Schedule a RAASPAL site survey",
                    "Verify robot specifications and performance with RAASPAL team",
                    "Confirm budget and contract terms with the customer",
                    "Present and obtain customer sign-off on the proposal"
            );
        }

        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(rect(100, 106, W - 200, H - 180));
        boolean first = true;
        for (String step : steps) {
            XSLFTextParagraph para = first
                    ? box.getTextParagraphs().get(0)
                    : box.addNewTextParagraph();
            first = false;
            para.setBullet(true);
            para.setSpaceBefore(8.0);
            XSLFTextRun run = para.addNewTextRun();
            run.setText(step);
            run.setFontSize(13.0);
            run.setFontFamily("Calibri");
            run.setFontColor(MUTED);
        }
        if (first) box.getTextParagraphs().get(0).addNewTextRun().setText("");

        addText(slide, "RAASPAL  —  Robot Solution Specialists",
                rect(60, H - 64, W - 120, 26), 12, false, CYAN, TextAlign.CENTER);
        addText(slide, "Final specifications and pricing require RAASPAL site verification.",
                rect(60, H - 42, W - 120, 28), 9, false, DIM, TextAlign.CENTER);
    }

    // ─── Proposal parser ──────────────────────────────────────────────────────

    private List<ProposalSection> parseProposalSections(String markdown) {
        List<ProposalSection> result = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return result;

        String currentTitle = null;
        List<String> currentBullets = new ArrayList<>();
        List<String> currentParagraph = new ArrayList<>();

        for (String raw : markdown.split("\n")) {
            String line = raw.trim();
            if (line.matches("^#{1,3}\\s+.*")) {
                flush(result, currentTitle, currentBullets, currentParagraph);
                currentTitle = line.replaceFirst("^#{1,3}\\s+", "").trim();
                currentBullets = new ArrayList<>();
                currentParagraph = new ArrayList<>();
            } else if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                String b = line.replaceFirst("^[-*•]\\s+", "").trim();
                if (!b.isBlank() && currentBullets.size() < 6) currentBullets.add(b);
            } else if (!line.isBlank() && !line.startsWith("|") && !line.startsWith("---")) {
                currentParagraph.add(line);
            }
        }
        flush(result, currentTitle, currentBullets, currentParagraph);
        return result;
    }

    private void flush(List<ProposalSection> result, String title,
                       List<String> bullets, List<String> paragraph) {
        if (title == null) return;
        List<String> out = new ArrayList<>(bullets);
        if (out.isEmpty()) {
            String text = String.join(" ", paragraph);
            for (String s : text.split("(?<=[.!?])\\s+")) {
                s = s.trim();
                if (!s.isBlank() && s.length() > 15 && out.size() < 5) out.add(s);
            }
        }
        if (!out.isEmpty()) result.add(new ProposalSection(title, out));
    }

    // ─── Visual helpers ───────────────────────────────────────────────────────

    private void addMetaBox(XSLFSlide slide, double x, double y, double w,
                             String value, String label) {
        addRect(slide, x, y, w, 42, BG_MID);
        addRect(slide, x, y, w, 3, CYAN);
        addText(slide, value, rect(x + 4, y + 6, w - 8, 18), 11, true, WHITE, TextAlign.LEFT);
        addText(slide, label, rect(x + 4, y + 25, w - 8, 15), 8, false, MUTED, TextAlign.LEFT);
    }

    private void fillBg(XSLFSlide slide, Color color) {
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(rect(0, 0, W, H));
        bg.setFillColor(color);
        bg.setLineColor(color);
    }

    private void addRect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFAutoShape s = slide.createAutoShape();
        s.setShapeType(ShapeType.RECT);
        s.setAnchor(rect(x, y, w, h));
        s.setFillColor(color);
        s.setLineColor(color);
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

    private void addPicture(XSLFSlide slide, XMLSlideShow ppt, byte[] bytes,
                             PictureType type, double x, double y, double w, double h) {
        try {
            XSLFPictureData pd = ppt.addPicture(bytes, type);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(rect(x, y, w, h));
        } catch (Exception e) {
            log.warn("Could not embed picture: {}", e.getMessage());
        }
    }

    private void footer(XSLFSlide slide) {
        addText(slide, "RAASPAL  ·  Confidential",
                rect(W - 200, H - 24, 186, 18), 8, false, DIM, TextAlign.RIGHT);
    }

    private static Rectangle2D.Double rect(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
    }

    // ─── Image helpers ────────────────────────────────────────────────────────

    private byte[] downloadImageBytes(String imageUrl) throws IOException {
        var url = URI.create(imageUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "RAASPAL-PPTX/1.0");
        try (InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }

    private PictureType detectPictureType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png"))  return PictureType.PNG;
        if (lower.contains(".gif"))  return PictureType.GIF;
        if (lower.contains(".bmp"))  return PictureType.BMP;
        if (lower.contains(".tiff") || lower.contains(".tif")) return PictureType.TIFF;
        return PictureType.JPEG;
    }

    // ─── Robot data helpers ───────────────────────────────────────────────────

    private String robotLabel(Robot robot) {
        if (robot == null) return "";
        return ((robot.getBrand() != null ? robot.getBrand() : "") + " "
                + (robot.getModel() != null ? robot.getModel() : "")).trim();
    }

    private String robotType(Robot robot) {
        return (robot == null || robot.getRobotType() == null) ? "—" : robot.getRobotType().name();
    }

    private String priceBand(Robot robot) {
        return (robot == null || robot.getPriceBand() == null) ? "—" : robot.getPriceBand().name();
    }

    private String formatPrice(Robot robot) {
        if (robot == null) return "";
        List<String> parts = new ArrayList<>();
        BigDecimal r = robot.getRentalPrice();
        BigDecimal s = robot.getSellingPrice();
        if (r != null) parts.add("Rental: " + r.toPlainString());
        if (s != null) parts.add("Purchase: " + s.toPlainString());
        return String.join("   ·   ", parts);
    }

    private static String safe(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
