package com.buukle.agent.common.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

public final class AvatarGenerator {
    private AvatarGenerator() {
    }

    public static String generateBase64() {
        return generateRobotBase64();
    }

    public static String generateUserBase64() {
        int size = 120;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        ThreadLocalRandom r = ThreadLocalRandom.current();
        int cx = size / 2, cy = size / 2;

        int hue = r.nextInt(360);
        Color line = Color.getHSBColor(hue / 360f, 0.7f, 0.35f);
        Color accent = Color.getHSBColor(hue / 360f, 0.5f, 0.6f);
        Color bg = Color.getHSBColor(hue / 360f, 0.08f, 0.97f);

        // Dashed stroke for main outlines
        Stroke dash = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4, 3}, 0);

        // Background circle
        g.setColor(bg);
        g.fillOval(4, 4, size - 8, size - 8);
        g.setStroke(dash);
        g.setColor(accent);
        g.drawOval(4, 4, size - 8, size - 8);

        // Cape (dashed lines radiating from shoulders)
        g.setStroke(dash);
        g.setColor(accent);
        int[] capePointsX = {cx - 20, cx - 30, cx, cx + 30, cx + 20};
        int[] capePointsY = {cy + 8, cy + 44, cy + 36, cy + 44, cy + 8};
        g.drawPolygon(capePointsX, capePointsY, 5);

        // Head (hexagon)
        int hr = 24;
        int[] headX = {cx, cx + hr, cx + hr, cx, cx - hr, cx - hr};
        int[] headY = {cy - hr, cy - hr / 2, cy + hr / 2, cy + hr, cy + hr / 2, cy - hr / 2};
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(line);
        g.drawPolygon(headX, headY, 6);

        // Eyes (two rectangular LED-style)
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        g.drawRoundRect(cx - 14, cy - 12, 8, 4, 2, 2);
        g.drawRoundRect(cx + 6, cy - 12, 8, 4, 2, 2);
        g.setColor(Color.WHITE);
        g.fillRoundRect(cx - 13, cy - 11, 6, 2, 1, 1);
        g.fillRoundRect(cx + 7, cy - 11, 6, 2, 1, 1);

        // Mask bridge (line connecting eyes)
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        g.drawLine(cx - 6, cy - 10, cx + 6, cy - 10);

        // Smile (dashed arc)
        g.setStroke(dash);
        g.setColor(line);
        g.drawArc(cx - 8, cy + 4, 16, 10, 0, -180);

        // Chest "S" symbol (code-style: angular S made of lines)
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        int sx = cx - 5, sy = cy + 28, sw = 10, sh = 12;
        g.drawLine(sx + sw, sy, sx, sy);
        g.drawLine(sx, sy, sx, sy + sh / 2);
        g.drawLine(sx, sy + sh / 2, sx + sw, sy + sh / 2);
        g.drawLine(sx + sw, sy + sh / 2, sx + sw, sy + sh);
        g.drawLine(sx + sw, sy + sh, sx, sy + sh);

        // Code brackets
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        g.drawLine(cx - 28, cy - 20, cx - 34, cy - 16);
        g.drawLine(cx - 34, cy - 16, cx - 28, cy - 12);
        g.drawLine(cx + 28, cy - 20, cx + 34, cy - 16);
        g.drawLine(cx + 34, cy - 16, cx + 28, cy - 12);

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static String generateRobotBase64() {
        int size = 120;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        ThreadLocalRandom r = ThreadLocalRandom.current();
        int cx = size / 2, cy = size / 2;

        // Accent color (line color) — random dark hue
        int hue = r.nextInt(360);
        Color line = Color.getHSBColor(hue / 360f, 0.7f, 0.35f);
        Color fill = Color.getHSBColor(hue / 360f, 0.15f, 0.92f);
        Color bgFill = Color.getHSBColor(hue / 360f, 0.08f, 0.97f);

        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Background circle
        g.setColor(bgFill);
        g.fillOval(4, 4, size - 8, size - 8);
        g.setColor(line);
        g.drawOval(4, 4, size - 8, size - 8);

        // Antenna
        g.drawLine(cx, cy - 38, cx, cy - 50);
        g.fillOval(cx - 4, cy - 56, 8, 8);

        // Head (rounded rectangle)
        int hw = 34, hh = 36;
        g.setColor(fill);
        g.fillRoundRect(cx - hw, cy - hh, hw * 2, hh * 2, 8, 8);
        g.setColor(line);
        g.drawRoundRect(cx - hw, cy - hh, hw * 2, hh * 2, 8, 8);

        // Eyes (two squares)
        g.setColor(line);
        int ew = 8, eh = 6;
        g.fillRoundRect(cx - 14 - ew / 2, cy - 12 - eh / 2, ew, eh, 2, 2);
        g.fillRoundRect(cx + 14 - ew / 2, cy - 12 - eh / 2, ew, eh, 2, 2);

        // Inner glow for eyes
        Color glow = Color.getHSBColor(hue / 360f, 0.6f, 0.7f);
        g.setColor(glow);
        g.fillRoundRect(cx - 14 - ew / 2 + 2, cy - 12 - eh / 2 + 1, ew - 4, eh - 2, 1, 1);
        g.fillRoundRect(cx + 14 - ew / 2 + 2, cy - 12 - eh / 2 + 1, ew - 4, eh - 2, 1, 1);

        // Mouth (short horizontal line)
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(line);
        g.drawLine(cx - 8, cy + 14, cx + 8, cy + 14);

        // Ear bolts (small circles on sides)
        g.drawOval(cx - hw - 5, cy - 5, 6, 6);
        g.drawOval(cx + hw - 1, cy - 5, 6, 6);

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
