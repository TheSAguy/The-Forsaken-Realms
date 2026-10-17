import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** DEBUG renderer only (java.awt / ImageIO). Never part of the Android-safe core. */
final class Render {
    static final int[] PAL = {
        0x3ea6e6, 0x2c7cbe, 0x4f9fe4, 0xead9a2, 0x52ab3e, 0x2f8634, 0x1d6b2b, 0xe2c88c, 0xb9cdbf, 0xf3f6fa, 0x5d6e46, 0x86a65a, 0x8e8c86};
    static final int RIVER1 = 0x3d84d6, RIVER2 = 0x2c6cc4, ROAD = 0xf6eed6, BRIDGE = 0x8a5a2a, PASS = 0xd9cfb4, CORE_GOLD = 0xf2cf3a;

    static int shade(int c, float f) {
        int r = Math.min(255, Math.max(0, (int) (((c >> 16) & 255) * f))), g = Math.min(255, Math.max(0, (int) (((c >> 8) & 255) * f))), b = Math.min(255, Math.max(0, (int) ((c & 255) * f)));
        return (r << 16) | (g << 8) | b;
    }
    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), gg = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bb = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (gg << 8) | bb;
    }
    static float hill(WorldGen g, int x, int y) {
        if (x == 0 || y == 0) return 1;
        int i = y * g.W + x; float d = (g.elev[i - 1 - g.W] - g.elev[i]) * 9f * g.sc;
        float k = g.cls[i] == WorldGen.MOUNTAIN ? 2.2f : 1f;
        return Math.max(0.8f, Math.min(1.15f, 1 + d * k));
    }
    static int baseColor(WorldGen g, int i) {
        byte cl = g.cls[i]; int c = PAL[cl];
        if (cl == WorldGen.BEACH && g.temp[i] < 0.27f) c = 0xd4e8f2;
        if (cl == WorldGen.MOUNTAIN && g.temp[i] < 0.3f) c = 0xd8dde2;
        return c;
    }
    static int planeColor(WorldGen g, int t) {
        Territories tr = g.terr;
        if (tr.kind[t] == Territories.PLAYER) return 0xffffff;
        if (tr.setOf[t] < 0) return 0x9a9a9a;
        SetTable.SetDef d = g.sets.sets.get(tr.setOf[t]);
        return d.core ? CORE_GOLD : g.sets.planes.get(d.plane).color;
    }

    /** (1) Terrain map in the reference palette: rivers, faint territory borders, roads, bridges, passes, docks, ferry lanes, boats. */
    static BufferedImage terrain(WorldGen g, int S) {
        int W = g.W, H = g.H; Territories tr = g.terr; Roads rd = g.roads;
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x; int c = baseColor(g, i);
            if (g.isLand(i)) c = shade(c, hill(g, x, y));
            if (g.river[i] > 0 && g.isLand(i)) c = g.river[i] >= 2 ? RIVER2 : RIVER1;
            int t = tr.tid[i]; boolean edge = false;
            if (t >= 0) edge = (x < W - 1 && tr.tid[i + 1] >= 0 && tr.tid[i + 1] != t) || (y < H - 1 && tr.tid[i + W] >= 0 && tr.tid[i + W] != t);
            if (rd.road[i]) c = rd.bridge[i] ? BRIDGE : rd.pass[i] ? PASS : (g.temp[i] < 0.27f && (g.cls[i] == WorldGen.SNOW || g.cls[i] == WorldGen.TUNDRA || g.cls[i] == WorldGen.MOUNTAIN || g.cls[i] == WorldGen.BEACH)) ? 0x9c8a66 : ROAD;
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) img.setRGB(x * S + dx, y * S + dy, c);
            if (edge && !rd.road[i]) img.setRGB(x * S, y * S, shade(c, 0.72f));
        }
        Graphics2D gr = img.createGraphics(); gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (S >= 2) bridges(g, gr, S);
        lanes(g, gr, S); docks(g, gr, S); capitals(g, gr, S, false);
        gr.dispose(); return img;
    }

    /** Bridges as planks across the river: a dark-outlined brown deck one tile wider than the road. */
    static void bridges(WorldGen g, Graphics2D gr, int S) {
        int W = g.W; boolean[] b = g.roads.bridge;
        for (int i = 0; i < g.N; i++) {
            if (!b[i]) continue; int x = i % W, y = i / W;
            boolean horiz = (x > 0 && g.roads.road[i - 1]) || (x < W - 1 && g.roads.road[i + 1]);   // road runs E-W -> rails N and S
            float px = x * S, py = y * S, e = 1.0f * S;
            java.awt.geom.Rectangle2D.Float r = horiz ? new java.awt.geom.Rectangle2D.Float(px - 0.5f * S, py - e, 2f * S, S + 2 * e)
                                                      : new java.awt.geom.Rectangle2D.Float(px - e, py - 0.5f * S, S + 2 * e, 2f * S);
            gr.setColor(new Color(BRIDGE)); gr.fill(r); gr.setColor(new Color(0x3b2410)); gr.setStroke(new BasicStroke(0.5f * S)); gr.draw(r);
        }
        gr.setStroke(new BasicStroke(1f));
    }

    static void lanes(WorldGen g, Graphics2D gr, int S) {
        int W = g.W; Harbors hb = g.harbors;
        for (int k = 0; k < hb.ferries.size(); k++) {
            int[] p = hb.paths.get(k); if (p.length < 2) continue;
            Path2D.Float path = new Path2D.Float(); int step = Math.max(2, Math.round(5 * g.sc));
            path.moveTo((p[0] % W + 0.5f) * S, (p[0] / W + 0.5f) * S);
            for (int j = step; j < p.length - 1; j += step) path.lineTo((p[j] % W + 0.5f) * S, (p[j] / W + 0.5f) * S);
            path.lineTo((p[p.length - 1] % W + 0.5f) * S, (p[p.length - 1] / W + 0.5f) * S);
            float dash = 3f * S;
            gr.setStroke(new BasicStroke(Math.max(1f, 0.8f * S), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{dash, dash}, 0));
            gr.setColor(hb.ferries.get(k)[3] == 0 ? new Color(255, 255, 255, 215) : new Color(255, 246, 200, 170));
            gr.draw(path);
            int m = p[p.length / 2]; boat(gr, (m % W + 0.5f) * S, (m / W + 0.5f) * S, S);
        }
        gr.setStroke(new BasicStroke(1f));
    }

    static void boat(Graphics2D gr, float x, float y, int S) {
        float u = Math.max(1.4f, 1.6f * S);
        Path2D.Float hull = new Path2D.Float(); hull.moveTo(x - 2.2f * u, y); hull.lineTo(x + 2.2f * u, y); hull.lineTo(x + 1.4f * u, y + 1.2f * u); hull.lineTo(x - 1.4f * u, y + 1.2f * u); hull.closePath();
        Path2D.Float sail = new Path2D.Float(); sail.moveTo(x - 0.2f * u, y - 2.8f * u); sail.lineTo(x - 0.2f * u, y - 0.2f * u); sail.lineTo(x + 1.8f * u, y - 0.2f * u); sail.closePath();
        gr.setColor(new Color(0x5b3a1e)); gr.fill(hull); gr.setColor(Color.WHITE); gr.fill(sail); gr.setColor(new Color(0x3a2a1a)); gr.setStroke(new BasicStroke(Math.max(0.6f, 0.35f * S))); gr.draw(sail);
        gr.setStroke(new BasicStroke(1f));
    }

    /** Docks: an anchor glyph on a dark disc (scale >= 2), a ringed marker at 1x. */
    static void docks(WorldGen g, Graphics2D gr, int S) {
        int W = g.W;
        for (int[] d : g.harbors.docks) {
            float x = (d[0] % W + 0.5f) * S, y = (d[0] / W + 0.5f) * S;
            float r = S >= 2 ? 4.2f * S : 3.2f;
            gr.setColor(new Color(0x1d2b4a)); gr.fill(new java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r));
            gr.setColor(Color.WHITE); gr.setStroke(new BasicStroke(S >= 2 ? 0.55f * S : 0.9f));
            gr.draw(new java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r));
            if (S >= 2) {   // anchor: ring, shaft, stock, curved arms
                float u = r / 3.4f;
                gr.draw(new java.awt.geom.Ellipse2D.Float(x - 0.6f * u, y - 2.6f * u, 1.2f * u, 1.2f * u));
                gr.draw(new java.awt.geom.Line2D.Float(x, y - 1.4f * u, x, y + 2.2f * u));
                gr.draw(new java.awt.geom.Line2D.Float(x - 1.2f * u, y - 0.9f * u, x + 1.2f * u, y - 0.9f * u));
                gr.draw(new java.awt.geom.Arc2D.Float(x - 2f * u, y - 0.6f * u, 4f * u, 2.8f * u, 200, 140, java.awt.geom.Arc2D.OPEN));
            } else gr.fill(new java.awt.geom.Rectangle2D.Float(x - 0.8f, y - 0.8f, 1.6f, 1.6f));
        }
        gr.setStroke(new BasicStroke(1f));
    }

    static void capitals(WorldGen g, Graphics2D gr, int S, boolean labels) {
        int W = g.W; Territories tr = g.terr; AiStarts ai = g.ai;
        gr.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, 7 * S)));
        for (int k = 0; k < ai.cap.length; k++) {
            int h = tr.hub[ai.cap[k]]; float x = (h % W + 0.5f) * S, y = (h / W + 0.5f) * S, r = S >= 2 ? 6.5f * S / 2 + 4 : 4.5f;
            gr.setColor(Color.BLACK); gr.fill(new java.awt.geom.Ellipse2D.Float(x - r - 1.5f, y - r - 1.5f, 2 * r + 3, 2 * r + 3));
            gr.setColor(new Color(0xd8262b)); gr.fill(new java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r));
            if (labels) halo(gr, "AI" + (k + 1) + " " + tr.label(ai.cap[k]), x + r + 3, y - r - 2, new Color(0xffe0e0));
        }
        if (tr.playerT >= 0) {
            int h = tr.hub[tr.playerT]; float x = (h % W + 0.5f) * S, y = (h / W + 0.5f) * S, r = S >= 2 ? 6.5f * S / 2 + 4 : 4.5f;
            gr.setColor(Color.BLACK); gr.fill(new java.awt.geom.Rectangle2D.Float(x - r - 1.5f, y - r - 1.5f, 2 * r + 3, 2 * r + 3));
            gr.setColor(Color.WHITE); gr.fill(new java.awt.geom.Rectangle2D.Float(x - r, y - r, 2 * r, 2 * r));
            if (labels) halo(gr, "PLAYER (wild 6th area)", x + r + 3, y - r - 2, Color.WHITE);
        }
    }

    static void halo(Graphics2D gr, String s, float x, float y, Color fg) {
        gr.setColor(new Color(0, 0, 0, 200));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) gr.drawString(s, x + dx, y + dy);
        gr.setColor(fg); gr.drawString(s, x, y);
    }

    /** (2) Territory/theme map: plane-colored territories, stamped themed cores, set codes, AI capitals, player area, legend. */
    static BufferedImage territories(WorldGen g, int S, int legendW, String title) {
        int W = g.W, H = g.H; Territories tr = g.terr;
        BufferedImage img = new BufferedImage(W * S + legendW, H * S, BufferedImage.TYPE_INT_RGB);
        int[] pc = new int[tr.T]; for (int t = 0; t < tr.T; t++) pc[t] = planeColor(g, t);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x, t = tr.tid[i]; int c = baseColor(g, i);
            if (g.isLand(i)) c = mix(c, 0xbfbfa8, 0.35f);                  // muted climate under the themes
            if (t >= 0) {
                int core = tr.coreOf[i];
                c = core == t ? mix(c, pc[t], 0.78f) : mix(c, pc[t], 0.22f);
                if (core == t && tr.kind[t] == Territories.PLAYER && ((x + y) % 4 < 2)) c = 0xd0d0d0;   // hatched player core
            }
            if (g.isLand(i)) c = shade(c, 0.5f + 0.5f * hill(g, x, y));
            if (g.river[i] > 0 && g.isLand(i)) c = mix(c, RIVER1, 0.7f);
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) img.setRGB(x * S + dx, y * S + dy, c);
            if (t >= 0) {   // territory border (dark) and core outline (darker plane tone)
                boolean e = (x < W - 1 && tr.tid[i + 1] != t && tr.tid[i + 1] >= 0) || (y < H - 1 && tr.tid[i + W] != t && tr.tid[i + W] >= 0);
                boolean ce = tr.coreOf[i] == t && ((x < W - 1 && tr.coreOf[i + 1] != t) || (y < H - 1 && tr.coreOf[i + W] != t) || (x > 0 && tr.coreOf[i - 1] != t) || (y > 0 && tr.coreOf[i - W] != t));
                if (ce) for (int d = 0; d < S; d++) { img.setRGB(x * S + d, y * S, shade(pc[t], 0.45f)); img.setRGB(x * S, y * S + d, shade(pc[t], 0.45f)); }
                if (e) for (int d = 0; d < S; d++) { if (x < W - 1 && tr.tid[i + 1] != t) img.setRGB(x * S + S - 1, y * S + d, 0x1a1a1a); if (y < H - 1 && tr.tid[i + W] != t) img.setRGB(x * S + d, y * S + S - 1, 0x1a1a1a); }
            }
        }
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        lanes(g, gr, S); docks(g, gr, S);
        // set codes on the larger bubbles (core sets bigger), at the core's centroid
        float[] lx = new float[tr.T], ly = new float[tr.T]; int[] ln = new int[tr.T];
        for (int i = 0; i < g.N; i++) { int t = tr.coreOf[i]; if (t >= 0) { lx[t] += i % W; ly[t] += i / W; ln[t]++; } }
        int[] sorted = tr.area.clone(); java.util.Arrays.sort(sorted); int med = sorted[sorted.length / 2];
        Font small = new Font("SansSerif", Font.BOLD, Math.round(5.2f * S)), big = new Font("SansSerif", Font.BOLD, Math.round(7f * S));
        for (int t = 0; t < tr.T; t++) {
            if (ln[t] == 0 || tr.kind[t] == Territories.PLAYER) continue;
            boolean isCap = false; for (int c : g.ai.cap) if (c == t) isCap = true; if (isCap) continue;   // the AI label carries the code
            boolean core = tr.setOf[t] >= 0 && g.sets.sets.get(tr.setOf[t]).core;
            if (!core && tr.area[t] < 0.6f * med) continue;
            String s = tr.label(t); gr.setFont(core ? big : small); FontMetrics fm = gr.getFontMetrics();
            float x = (lx[t] / ln[t] + 0.5f) * S - fm.stringWidth(s) / 2f, y = (ly[t] / ln[t] + 0.5f) * S + fm.getAscent() / 2.6f;
            halo(gr, s, x, y, core ? new Color(0xfff3b0) : Color.WHITE);
        }
        // isle plane names
        gr.setFont(new Font("SansSerif", Font.BOLD | Font.ITALIC, Math.round(5.5f * S)));
        for (float[] o : g.isles) { String nm = g.sets.planes.get((int) o[0]).name; FontMetrics fm = gr.getFontMetrics();
            halo(gr, nm, o[1] * S - fm.stringWidth(nm) / 2f, (o[2] - 1.35f * o[3] - 2) * S, new Color(0xcfe8ff)); }
        capitals(g, gr, S, true);
        legend(g, gr, W * S, H * S, legendW, title);
        gr.dispose(); return img;
    }

    static void legend(WorldGen g, Graphics2D gr, int x0, int h, int lw, String title) {
        if (lw <= 0) return;
        gr.setColor(new Color(0x16181d)); gr.fillRect(x0, 0, lw, h);
        int y = 22, x = x0 + 12; gr.setColor(Color.WHITE); gr.setFont(new Font("SansSerif", Font.BOLD, 15)); gr.drawString(title, x, y); y += 18;
        gr.setFont(new Font("SansSerif", Font.PLAIN, 11)); gr.setColor(new Color(0xb8bcc6));
        Territories tr = g.terr;
        int coreN = 0, setN = 0; for (int t = 0; t < tr.T; t++) if (tr.setOf[t] >= 0) { setN++; if (g.sets.sets.get(tr.setOf[t]).core) coreN++; }
        String[] lines = {g.W + "x" + g.H + " tiles, seed " + g.s.seed + ", cohesion " + g.s.cohesion,
            setN + " set territories (" + coreN + " core sets at " + g.s.coreWeight + "x area)",
            "fold reprints: " + g.s.foldReprints + ", player area: " + (g.s.playerArea ? "(a) reserved" : "(b) none / carve-out"),
            g.ai.cap.length + " AI capitals (red), docks " + g.harbors.docks.size() + ", ferry lanes " + g.harbors.ferries.size()};
        for (String l : lines) { gr.drawString(l, x, y); y += 14; }
        y += 6;
        int[][] sym = {{CORE_GOLD}, {0xffffff}, {0x9a9a9a}};
        String[] sn = {"core set (AI start candidates)", "player area (wild, hatched)", "wild (no set)"};
        for (int k = 0; k < 3; k++) { gr.setColor(new Color(sym[k][0])); gr.fillRect(x, y - 9, 12, 10); gr.setColor(Color.WHITE); gr.drawString(sn[k], x + 18, y); y += 14; }
        y += 6; gr.setFont(new Font("SansSerif", Font.BOLD, 12)); gr.setColor(Color.WHITE); gr.drawString("Planes (sets on map)", x, y); y += 15;
        gr.setFont(new Font("SansSerif", Font.PLAIN, 11));
        int[] cnt = new int[g.sets.planes.size()]; for (int t = 0; t < tr.T; t++) if (tr.setOf[t] >= 0) cnt[g.sets.sets.get(tr.setOf[t]).plane]++;
        for (int p = 0; p < g.sets.planes.size(); p++) {
            if (cnt[p] == 0) continue; SetTable.Plane pl = g.sets.planes.get(p);
            gr.setColor(new Color(pl.color)); gr.fillRect(x, y - 9, 12, 10); gr.setColor(new Color(0xdfe2e8));
            gr.drawString(pl.name + "  " + cnt[p] + (pl.square ? "  (square core)" : ""), x + 18, y); y += 13;
            if (y > h - 10) break;
        }
    }

    /** (3) 2x crop of the terrain where docks, ferries and bridges are densest. */
    static BufferedImage crop2x(WorldGen g) {
        int W = g.W, win = W / 2; int best = -1, bx = 0, by = 0; int step = Math.max(10, W / 40);
        for (int y0 = 0; y0 + win <= W; y0 += step) for (int x0 = 0; x0 + win <= W; x0 += step) {
            int sc = 0;
            for (int[] d : g.harbors.docks) { int x = d[0] % W, y = d[0] / W; if (x >= x0 + 8 && x < x0 + win - 8 && y >= y0 + 8 && y < y0 + win - 8) sc += 8; }
            for (int[] p : g.harbors.paths) { int m = p[p.length / 2]; int x = m % W, y = m / W; if (x >= x0 && x < x0 + win && y >= y0 && y < y0 + win) sc += 3; }
            if (sc > best) { best = sc; bx = x0; by = y0; }
        }
        // add bridge density as a tie-breaker pass around the best dock window
        int bestB = -1, fx = bx, fy = by;
        for (int y0 = Math.max(0, by - 3 * step); y0 <= Math.min(W - win, by + 3 * step); y0 += step) for (int x0 = Math.max(0, bx - 3 * step); x0 <= Math.min(W - win, bx + 3 * step); x0 += step) {
            int sc = 0;
            for (int[] d : g.harbors.docks) { int x = d[0] % W, y = d[0] / W; if (x >= x0 + 8 && x < x0 + win - 8 && y >= y0 + 8 && y < y0 + win - 8) sc += 8; }
            for (int y = y0; y < y0 + win; y += 1) for (int x = x0; x < x0 + win; x++) if (g.roads.bridge[y * W + x]) sc += 1;
            if (sc > bestB) { bestB = sc; fx = x0; fy = y0; }
        }
        BufferedImage full = terrain(g, 2);
        return full.getSubimage(fx * 2, fy * 2, win * 2, win * 2);
    }

    static void write(BufferedImage img, String path) throws Exception { ImageIO.write(img, "png", new File(path)); }
}
