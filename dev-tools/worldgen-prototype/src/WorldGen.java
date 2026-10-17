import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Prototype C generator. Terrain stages are prototype A's (pure Java, no java.awt, no libGDX); on top of them run
 * the set-territory model: set territories + themed cores, harbors + ferries, the AI start layer, roads + bridges,
 * and a reachability validator. Universes Beyond planes get moated isles planned before sea level (prototype B).
 */
final class WorldGen {
    static final byte OCEAN = 0, DEEP = 1, LAKE = 2, BEACH = 3, GRASS = 4, FOREST = 5, JUNGLE = 6, DESERT = 7,
            TUNDRA = 8, SNOW = 9, SWAMP = 10, HILLS = 11, MOUNTAIN = 12;
    static final String[] CLASS = {"ocean", "deep", "lake", "beach", "grass", "forest", "jungle", "desert",
            "tundra", "snow", "swamp", "hills", "mountain"};
    static final String[] STAGES = {"landmass+islands", "plates+ranges", "erosion(age)", "sea level+coast",
            "wind rainfall", "priority-flood", "flow+rivers+lakes", "climate+classes",
            "territories(partition)", "sets+cores(assign)", "harbors+ferries", "ai starts", "roads+bridges", "reachability"};
    static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DY = {0, 0, 1, -1, 1, -1, 1, -1};
    static final float[] DD = {1, 1, 1, 1, 1.4142f, 1.4142f, 1.4142f, 1.4142f};

    final Settings s; final SetTable sets; final int W, H, N; final float sc;
    final float[] elev, cont, qx, qy, uplift, rain, filled, acc, temp, moist;
    final int[] rec, order, coastDist, seaDist;
    final boolean[] ocean, lake, marsh, mainRiver;   // mainRiver = the pruned D8 network (no delta arms / corner fills)
    final byte[] river, cls;   // river: 0 none, 1 stream, 2 river, 3 great river (2 tiles wide)
    int nLand; float riverThr;
    final long[] ns = new long[STAGES.length];
    /** Planned theme isles: {planeIndex, centerX, centerY, radius R0} in tiles; moats are carved after sea level. */
    final ArrayList<float[]> isles = new ArrayList<>();
    Territories terr; Harbors harbors; AiStarts ai; Roads roads; Validator valid;
    final ArrayList<String> log = new ArrayList<>();   // [TFR-WorldGen]-style diagnostic lines

    WorldGen(Settings s, SetTable sets) {
        this.s = s; this.sets = sets; W = H = s.size; N = W * H; sc = W / 700f;
        elev = new float[N]; cont = new float[N]; qx = new float[N]; qy = new float[N]; uplift = new float[N];
        rain = new float[N]; filled = new float[N]; acc = new float[N]; temp = new float[N]; moist = new float[N];
        rec = new int[N]; order = new int[N]; coastDist = new int[N]; seaDist = new int[N];
        ocean = new boolean[N]; lake = new boolean[N]; marsh = new boolean[N]; mainRiver = new boolean[N]; river = new byte[N]; cls = new byte[N];
    }

    void generate() {
        long t = System.nanoTime();
        landmass(); t = lap(0, t);
        ranges(); t = lap(1, t);
        erosion(); t = lap(2, t);
        seaLevel(); t = lap(3, t);
        rainfall(); t = lap(4, t);
        nLand = priorityFlood(elev, W, H, ocean, filled, order, rec); t = lap(5, t);
        hydrology(); t = lap(6, t);
        climate(); t = lap(7, t);
        terr = new Territories(this); terr.partitionStage(); t = lap(8, t);
        terr.assignStage(); t = lap(9, t);
        harbors = new Harbors(this); harbors.build(); t = lap(10, t);
        ai = new AiStarts(this); ai.build(); t = lap(11, t);
        roads = new Roads(this); roads.build(); t = lap(12, t);
        valid = new Validator(this); valid.run(); lap(13, t);
    }

    void log(String tag, String msg) { log.add("[TFR-" + tag + "] " + msg); }

    private long lap(int k, long t0) { long t = System.nanoTime(); ns[k] = t - t0; return t; }

    boolean isLand(int i) { return !ocean[i] && !lake[i]; }

    // ------------------------------------------------------------------ 1. landmass
    void landmass() {
        SplittableRandom r = new SplittableRandom(s.seed);
        Noise wa = new Noise(s.seed + 11), wb = new Noise(s.seed + 12), base = new Noise(s.seed + 13);
        int C = s.continents; float land = 1 - s.water;
        float[] cw = new float[C]; float sum = 0;
        for (int c = 0; c < C; c++) { cw[c] = (c == 0 ? 1.6f : 0.6f) + 0.8f * (float) r.nextDouble(); sum += cw[c]; }
        float[][] centers = new float[C][];
        ArrayList<float[]> blobs = new ArrayList<>();
        for (int c = 0; c < C; c++) {
            float rad = (float) Math.sqrt(land * 0.8f * cw[c] / sum / Math.PI);
            float bx = 0.5f, by = 0.5f, best = -1e9f, m = Math.min(0.42f, rad * 0.7f + 0.1f);
            for (int t = 0; t < 400; t++) {
                float x = m + (float) r.nextDouble() * (1 - 2 * m), y = m + (float) r.nextDouble() * (1 - 2 * m);
                float score;
                if (c == 0) score = C <= 2 ? -dist(x, y, 0.5f, 0.5f) + 0.08f * (float) r.nextDouble() : -Math.abs(dist(x, y, 0.5f, 0.5f) - 0.12f);
                else { score = 1e9f; for (int o = 0; o < c; o++) score = Math.min(score, dist(x, y, centers[o][0], centers[o][1]) - rad - centers[o][2]); }
                if (score > best) { best = score; bx = x; by = y; }
            }
            centers[c] = new float[]{bx, by, rad};
            int k = 3 + r.nextInt(3);
            for (int b = 0; b < k; b++) {
                double a = r.nextDouble() * Math.PI * 2; float off = rad * (b == 0 ? 0 : 0.2f + 0.5f * (float) r.nextDouble());
                blobs.add(new float[]{bx + (float) Math.cos(a) * off, by + (float) Math.sin(a) * off, rad * (0.55f + 0.3f * (float) r.nextDouble()), c});
            }
        }
        ArrayList<float[]> isl = new ArrayList<>();
        // islands: seeds in open ocean (tested against the unwarped blob field), with small satellite clusters
        int nI = Math.round(s.islands * 48), placed = 0;
        for (int k = 0; k < nI; k++) {
            for (int t = 0; t < 120; t++) {
                float x = 0.07f + 0.86f * (float) r.nextDouble(), y = 0.07f + 0.86f * (float) r.nextDouble();
                float ex0 = 2 * x - 1, ey0 = 2 * y - 1;
                float clr = (k % 2 == 0 && t < 80) ? 0.05f : 0;   // half the islands keep clear of coasts (open-sea islands)
                if (blobField(blobs, x, y) > 0.03f || blobField(blobs, x + clr, y) > 0.03f || blobField(blobs, x - clr, y) > 0.03f
                        || blobField(blobs, x, y + clr) > 0.03f || blobField(blobs, x, y - clr) > 0.03f || 0.55f * Math.sqrt(ex0 * ex0 + ey0 * ey0) + 0.45f * Math.max(Math.abs(ex0), Math.abs(ey0)) > 0.80f) continue;
                boolean nearIsle = false; float isleI = 0;
                for (float[] o : isles) if (dist(x * W, y * H, o[1], o[2]) < 1.3f * o[3] + 7 * sc + 34 * sc) nearIsle = true;
                if (nearIsle) continue;
                float rr = (6 + 22 * (float) Math.pow(r.nextDouble(), 2)) / 700f;
                isl.add(new float[]{x, y, rr}); placed++;
                int sat = r.nextDouble() < 0.5 ? 1 + r.nextInt(3) : 0;
                for (int j = 0; j < sat; j++) {
                    double a = r.nextDouble() * Math.PI * 2; float d = rr * (1.6f + 2f * (float) r.nextDouble());
                    isl.add(new float[]{x + (float) Math.cos(a) * d, y + (float) Math.sin(a) * d, rr * (0.4f + 0.4f * (float) r.nextDouble())});
                }
                break;
            }
        }
        if (Boolean.getBoolean("dbg")) System.out.println("islands placed " + placed + "/" + nI + " (+satellites " + (isl.size() - placed) + ")");
        float gain = 0.62f - 0.22f * s.age; Noise isleN = new Noise(s.seed + 14);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x; float u = (x + 0.5f) / W, v = (y + 0.5f) / H;
            float px = u + 0.10f * wa.fbm(u * 3, v * 3, 4, 0.5f), py = v + 0.10f * wb.fbm(u * 3 + 5.2f, v * 3 + 1.3f, 4, 0.5f);
            qx[i] = px; qy[i] = py;
            float F = blobField(blobs, px, py), I = 0;
            // theme isles live in TILE space (unwarped) so the carved core and moat line up with their own coast;
            // regular islands are suppressed near them so nothing crosses a moat
            boolean nearIsle = false; float isleI = 0;
            for (float[] o : isles) {
                float d = dist(x, y, o[1], o[2]);
                if (d < 1.3f * o[3] + 7 * sc + 12 * sc) nearIsle = true;
                if (d < 1.35f * o[3]) {   // flat-topped, noise-lobed outline (no cone/dome)
                    float de = d * (1 + 0.24f * isleN.fbm(x * 0.035f / sc, y * 0.035f / sc, 3, 0.5f));
                    float v0 = 1 - smooth(0.5f, 1f, de / (1.25f * o[3])); I = Math.max(I, v0);
                    isleI = Math.max(isleI, 1 - smooth(0.9f, 1.3f, d / o[3]));   // shields the isle from the outer falloff
                }
            }
            if (!nearIsle) for (float[] b : isl) {
                float dx = px - b[0], dy = py - b[1]; if (Math.abs(dx) > b[2] || Math.abs(dy) > b[2]) continue;
                I = Math.max(I, 1 - smooth(0.3f, 1f, (float) Math.sqrt(dx * dx + dy * dy) / b[2]));
            }
            float n = base.fbm(px * 5, py * 5, 7, gain);
            float ex = 2 * px - 1, ey = 2 * py - 1;   // organic outer falloff (warped, between round and square)
            float edge = smooth(0.78f, 1.0f, 0.55f * (float) Math.sqrt(ex * ex + ey * ey) + 0.45f * Math.max(Math.abs(ex), Math.abs(ey))
                    + 0.12f * wa.fbm(u * 2.5f + 9, v * 2.5f - 4, 2, 0.5f));   // irregular outer coast, not a disc
            edge *= 1 - isleI;
            cont[i] = Math.max(F, 1.15f * I);   // islands peak a bit above continent plateaus so they survive sea level
            float rim = smooth(0.93f, 0.97f, Math.max(Math.abs(2 * u - 1), Math.abs(2 * v - 1)));   // hard ocean border
            elev[i] = 0.6f * cont[i] + 0.32f * n - 0.9f * edge - 2f * rim;
        }
    }

    static float warpedField(ArrayList<float[]> blobs, Noise wa, Noise wb, float u, float v) {
        float px = u + 0.10f * wa.fbm(u * 3, v * 3, 4, 0.5f), py = v + 0.10f * wb.fbm(u * 3 + 5.2f, v * 3 + 1.3f, 4, 0.5f);
        return blobField(blobs, px, py);
    }

    /** Continent field: each continent = max of its sub-blobs; where two continents overlap a strait is carved. */
    static float blobField(ArrayList<float[]> blobs, float x, float y) {
        float f1 = 0, f2 = 0; int c1 = -1; float cur = 0; int cc = -1;
        for (int k = 0; k <= blobs.size(); k++) {
            float[] b = k < blobs.size() ? blobs.get(k) : null;
            if (b == null || (int) b[3] != cc) {          // continent boundary in the list: fold its max into f1/f2
                if (cc >= 0) { if (cur > f1) { f2 = f1; f1 = cur; c1 = cc; } else if (cur > f2) f2 = cur; }
                if (b == null) break; cc = (int) b[3]; cur = 0;
            }
            float d = dist(x, y, b[0], b[1]) / b[2]; if (d < 1.15f) cur = Math.max(cur, 1 - smooth(0.5f, 1.15f, d));
        }
        return Math.max(0, f1 - 0.9f * f2);
    }

    // ------------------------------------------------------------------ 2. plates and ranges
    void ranges() {
        SplittableRandom r = new SplittableRandom(s.seed * 7 + 3);
        int P = 6 + 2 * s.continents;
        float[] sx = new float[P], sy = new float[P], vx = new float[P], vy = new float[P];
        for (int p = 0; p < P; p++) {
            sx[p] = (float) r.nextDouble(); sy[p] = (float) r.nextDouble();
            double a = r.nextDouble() * Math.PI * 2, m = 0.5 + 0.5 * r.nextDouble();
            vx[p] = (float) (Math.cos(a) * m); vy[p] = (float) (Math.sin(a) * m);
        }
        Noise seg = new Noise(s.seed + 21), rid = new Noise(s.seed + 22);
        float width = 0.016f + 0.020f * s.age, sharp = 1 + 2.5f * (1 - s.age);
        Noise jag = new Noise(s.seed + 23);
        for (int i = 0; i < N; i++) {
            float x = qx[i], y = qy[i]; int a = 0, b = 0; float d1 = 1e9f, d2 = 1e9f;
            for (int p = 0; p < P; p++) {
                float dx = x - sx[p], dy = y - sy[p], d = dx * dx + dy * dy;
                if (d < d1) { d2 = d1; b = a; d1 = d; a = p; } else if (d < d2) { d2 = d; b = p; }
            }
            float ex = sx[b] - sx[a], ey = sy[b] - sy[a], len = (float) Math.sqrt(ex * ex + ey * ey);
            float bd = (d2 - d1) / (2 * len);                       // distance to the plate boundary
            bd += (0.006f + 0.008f * (1 - s.age)) * jag.fbm(x * 40, y * 40, 3, 0.55f);   // jagged crest line (young = rougher)
            if (Math.abs(bd) > 3 * width) { uplift[i] = 0; continue; }
            float conv = ((vx[a] - vx[b]) * ex + (vy[a] - vy[b]) * ey) / len; // > 0: plates converge
            float z = Math.abs(bd) / width;   // young: pointed profile exp(-z), old: rounded exp(-z^2)
            float belt = (float) Math.exp(-(s.age * z * z + (1 - s.age) * z * 1.3f));
            float segment = smooth(-0.1f, 0.4f, seg.fbm(x * 3, y * 3, 2, 0.5f));  // ranges of finite length
            float crest = (float) Math.pow(rid.ridged(x * 11, y * 11, 3, 0.5f), sharp);   // spurs and passes along the range
            uplift[i] = conv > 0 ? conv * belt * segment * (0.55f + 0.45f * crest) : 0.3f * conv * belt * segment;
        }
        int blur = Math.round(4 * s.age * sc);
        if (blur > 0) boxBlur(uplift, W, H, blur);                   // ridge softening with age
        float oceanW = 0.25f + 0.5f * s.islands;
        for (int i = 0; i < N; i++) elev[i] += s.mountains * 0.5f * uplift[i] * (cont[i] + (1 - cont[i]) * oceanW);
    }

    // ------------------------------------------------------------------ 3. erosion, scaled by age
    void erosion() {
        int iters = Math.round(1 + 10 * s.age), thermal = Math.round(6 * s.age);
        float sea = percentile(elev.clone(), s.water);
        int w2 = W / 2, h2 = H / 2, n2 = w2 * h2;
        float[] h = new float[n2], h0, f2 = new float[n2], a2 = new float[n2];
        int[] o2 = new int[n2], r2 = new int[n2]; boolean[] wet = new boolean[n2];
        for (int y = 0; y < h2; y++) for (int x = 0; x < w2; x++) {
            int i = 2 * y * W + 2 * x;
            h[y * w2 + x] = (elev[i] + elev[i + 1] + elev[i + W] + elev[i + W + 1]) * 0.25f - sea;
        }
        h0 = h.clone();
        for (int it = 0; it < iters; it++) {           // implicit stream power (Braun & Willett 2013), downstream first
            for (int i = 0; i < n2; i++) wet[i] = h[i] <= 0;
            int nl = priorityFlood(h, w2, h2, wet, f2, o2, r2);
            for (int o = 0; o < nl; o++) a2[o2[o]] = 1;
            for (int o = nl - 1; o >= 0; o--) { int c = o2[o]; if (r2[c] >= 0 && !wet[r2[c]]) a2[r2[c]] += a2[c]; }
            for (int o = 0; o < nl; o++) {
                int c = o2[o], rc = r2[c]; if (rc < 0 || h[c] <= h[rc]) continue;
                float dist = (Math.abs(rc - c) == 1 || Math.abs(rc - c) == w2) ? 1 : 1.4142f;
                float f = 0.004f * (float) Math.sqrt(a2[c]) / dist;
                h[c] = (h[c] + f * h[rc]) / (1 + f);
            }
        }
        float[] del = new float[N];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {   // bilinear upsample of the erosion delta
            float fx = Math.min(w2 - 1.001f, Math.max(0, x * 0.5f - 0.25f)), fy = Math.min(h2 - 1.001f, Math.max(0, y * 0.5f - 0.25f));
            int ix = (int) fx, iy = (int) fy; float tx = fx - ix, ty = fy - iy; int j = iy * w2 + ix;
            float d00 = h[j] - h0[j], d10 = h[j + 1] - h0[j + 1], d01 = h[j + w2] - h0[j + w2], d11 = h[j + w2 + 1] - h0[j + w2 + 1];
            del[y * W + x] = (d00 * (1 - tx) + d10 * tx) * (1 - ty) + (d01 * (1 - tx) + d11 * tx) * ty;
        }
        boxBlur(del, W, H, 1);                          // hide the half-res grid in the hillshade
        for (int i = 0; i < N; i++) elev[i] += del[i];
        float talus = 0.006f / sc; float[] d = new float[N];
        for (int it = 0; it < thermal; it++) {          // thermal slumping: rounds crests and steep coasts
            Arrays.fill(d, 0);
            for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
                int i = y * W + x; if (elev[i] <= sea) continue;
                for (int k = 0; k < 4; k++) {
                    int n = i + DX[k] + DY[k] * W; float diff = elev[i] - elev[n];
                    if (diff > talus) { float m = 0.2f * (diff - talus); d[i] -= m; d[n] += m; }
                }
            }
            for (int i = 0; i < N; i++) elev[i] += d[i];
        }
    }

    // ------------------------------------------------------------------ 4. sea level, coast smoothing, cleanup
    void seaLevel() {
        float sea = percentile(elev.clone(), s.water);
        boolean[] wet = new boolean[N];
        for (int i = 0; i < N; i++) { elev[i] -= sea; wet[i] = elev[i] <= 0; }
        int passes = Math.round(3 * s.age);
        for (int p = 0; p < passes; p++) {                // majority filter = smoother coasts on old worlds
            boolean[] nw = wet.clone();
            for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
                int i = y * W + x, c = 0;
                for (int k = 0; k < 8; k++) if (wet[i + DX[k] + DY[k] * W]) c++;
                if (c >= 6) nw[i] = true; else if (c <= 2) nw[i] = false;
            }
            wet = nw;
        }
        placeIsles(wet);
        if (!isles.isEmpty()) {   // land slivers stranded just outside a moat (< 600 tiles) go under water
            int[] lab0 = new int[N], q0 = new int[N]; Arrays.fill(lab0, -1);
            for (int i = 0; i < N; i++) if (!wet[i] && lab0[i] < 0) {
                int n = flood(wet, false, i, lab0, q0, i); if (n >= 600 * sc * sc) continue;
                boolean hit = false;
                for (int k = 0; k < n && !hit; k++) { int c = q0[k];
                    for (float[] o : isles) { float d = dist(c % W, c / W, o[1], o[2]); if (d > 1.3f * o[3] && d < 1.3f * o[3] + 7 * sc + 4) hit = true; } }
                if (hit) for (int k = 0; k < n; k++) wet[q0[k]] = true;
            }
        }
        int minIsle = Math.round(14 * sc * sc), minPond = Math.round(6 * sc * sc);
        int[] lab = new int[N]; int[] q = new int[N];
        // drop tiny land specks and tiny water specks
        for (int pass = 0; pass < 2; pass++) {
            boolean target = pass == 0 ? false : true; int min = pass == 0 ? minIsle : minPond;
            Arrays.fill(lab, -1);
            for (int i = 0; i < N; i++) {
                if (wet[i] != target || lab[i] >= 0) continue;
                int n = flood(wet, target, i, lab, q, i);
                if (n < min && (pass == 0 || !touchesEdge(q, n))) for (int k = 0; k < n; k++) wet[q[k]] = !target;
            }
        }
        for (int i = 0; i < N; i++) elev[i] = wet[i] ? Math.min(elev[i], -0.002f) : Math.max(elev[i], 0.002f);
        int[] cd = new int[N]; bfs(wet, cd, true);       // distance-from-coast ramp: interiors rise, basins shrink
        float[] ramp = new float[N];                      // blurred so BFS diamonds don't show in the relief
        for (int i = 0; i < N; i++) ramp[i] = wet[i] ? 0 : 0.22f * Math.min(1f, cd[i] / (70f * sc));
        int rb = Math.max(2, Math.round(5 * sc)); boxBlur(ramp, W, H, rb); boxBlur(ramp, W, H, rb);
        for (int i = 0; i < N; i++) if (!wet[i]) elev[i] += ramp[i];
        // ocean = water connected to the map edge; enclosed water stays below sea level -> filled into lakes later
        int qt = 0;
        for (int i = 0; i < N; i++) { int x = i % W, y = i / W; if ((x == 0 || y == 0 || x == W - 1 || y == H - 1) && wet[i]) { ocean[i] = true; q[qt++] = i; } }
        for (int qh = 0; qh < qt; qh++) {
            int c = q[qh], cx = c % W, cy = c / W;
            for (int k = 0; k < 4; k++) { int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int n = ny * W + nx; if (wet[n] && !ocean[n]) { ocean[n] = true; q[qt++] = n; } }
        }
        Arrays.fill(lab, -1);                             // big enclosed water = inland sea, not a lake
        for (int i = 0; i < N; i++) if (wet[i] && !ocean[i] && lab[i] < 0) {
            int n = flood(wet, true, i, lab, q, i);
            if (n > 0.004f * N) for (int k = 0; k < n; k++) ocean[q[k]] = true;
        }
    }

    /**
     * Theme isles (prototype B's guarantee by construction, done AFTER sea level so it cannot cut into land): each
     * island-native plane gets an isle sized for its sets, stamped where open sea is at least 1.3*R0 + moat + 3 tiles
     * from any land (distance transform), so the moat is natural sea. No room -> the plane shares the mainland.
     */
    void placeIsles(boolean[] wet) {
        if (!s.ubIsles) return;
        SplittableRandom r = new SplittableRandom(s.seed * 41 + 7);
        float wsum = s.playerArea ? s.coreWeight : 0;
        for (SetTable.SetDef d : sets.sets) wsum += d.core ? s.coreWeight : 1f;
        float unit = (1 - s.water) * N * 0.93f / wsum, moat = 7 * sc;
        Noise isleN = new Noise(s.seed + 14), relief = new Noise(s.seed + 15);
        ArrayList<Integer> order = new ArrayList<>();
        for (int p = 0; p < sets.planes.size(); p++) { SetTable.Plane pl = sets.planes.get(p); if (pl.islandPref >= 1 && pl.nSets > 0) order.add(p); }
        order.sort((a, b) -> sets.planes.get(b).nSets - sets.planes.get(a).nSets);
        int[] ld = new int[N]; boolean[] landM = new boolean[N];
        int margin = Math.round(0.05f * W);
        for (int p : order) {
            SetTable.Plane pl = sets.planes.get(p);
            float R0 = (float) Math.sqrt(pl.nSets * unit * 1.08f / Math.PI), need = 1.3f * R0 + moat + 3;
            for (int i = 0; i < N; i++) landM[i] = !wet[i];
            bfs(landM, ld, true);
            int best = -1; float bs = -1, maxD = 0; int maxI = -1;
            for (int t = 0; t < 4000; t++) {
                int x = margin + r.nextInt(W - 2 * margin), y = margin + r.nextInt(H - 2 * margin), i = y * W + x;
                if (x - R0 < margin || x + R0 > W - margin || y - R0 < margin || y + R0 > H - margin) continue;
                if (ld[i] > maxD) { maxD = ld[i]; maxI = i; }
                if (ld[i] < need) continue;
                float sc2 = Math.min(ld[i], 1.6f * need) + 0.4f * need * (float) r.nextDouble();
                if (sc2 > bs) { bs = sc2; best = i; }
            }
            if (best < 0 && maxD >= 0.75f * need) { R0 = (maxD - moat - 3) / 1.3f; best = maxI; }
            if (best < 0 || R0 < 8 * sc) { log("WorldGen", String.format(java.util.Locale.US, "no open sea for isle %s (needs %.0f tiles of clearance, best %.0f): its sets share the mainland", pl.name, need, maxD)); continue; }
            int cx = best % W, cy = best / W;
            for (int y = Math.max(0, (int) (cy - 1.4f * R0)); y < Math.min(H, (int) (cy + 1.4f * R0) + 1); y++)
                for (int x = Math.max(0, (int) (cx - 1.4f * R0)); x < Math.min(W, (int) (cx + 1.4f * R0) + 1); x++) {
                    int i = y * W + x; float d = dist(x, y, cx, cy);
                    float de = d * (1 + 0.26f * isleN.fbm(x * 0.035f / sc, y * 0.035f / sc, 3, 0.5f));
                    if (de >= R0 && d >= 0.55f * R0) continue;
                    wet[i] = false;
                    float in = Math.max(0, 1 - de / R0);
                    elev[i] = 0.004f + 0.05f * smooth(0f, 0.6f, in) + 0.035f * (0.5f + 0.5f * relief.fbm(x * 0.06f / sc, y * 0.06f / sc, 4, 0.5f));
                }
            isles.add(new float[]{p, cx, cy, R0});
            log("WorldGen", String.format(java.util.Locale.US, "isle %s: %d sets, R0 %.0f tiles at (%d,%d), open-sea clearance %d tiles (needs %.0f)", pl.name, pl.nSets, R0, cx, cy, ld[best], need));
        }
    }

    private boolean touchesEdge(int[] q, int n) {
        for (int k = 0; k < n; k++) { int x = q[k] % W, y = q[k] / W; if (x == 0 || y == 0 || x == W - 1 || y == H - 1) return true; }
        return false;
    }

    /** 4-connected flood of cells with mask==value from start; fills q[0..n) and labels lab. */
    int flood(boolean[] mask, boolean value, int start, int[] lab, int[] q, int label) {
        int qt = 0; q[qt++] = start; lab[start] = label;
        for (int qh = 0; qh < qt; qh++) {
            int c = q[qh], cx = c % W, cy = c / W;
            for (int k = 0; k < 4; k++) { int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int n = ny * W + nx; if (mask[n] == value && lab[n] < 0) { lab[n] = label; q[qt++] = n; } }
        }
        return qt;
    }

    // ------------------------------------------------------------------ 5. rainfall: westerly wind sweep with rain shadow
    void rainfall() {
        float[] relief = new float[N];
        for (int i = 0; i < N; i++) relief[i] = Math.max(0, elev[i]);
        int b = Math.max(1, Math.round(5 * sc)); boxBlur(relief, W, H, b); boxBlur(relief, W, H, b);
        float recharge = 0.02f + 0.03f * s.moisture;
        for (int y = 0; y < H; y++) {
            float hum = 1f;
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (ocean[i]) { hum = Math.min(1f, hum + recharge); rain[i] = 0; continue; }
                float up = x > 0 ? Math.max(0, relief[i] - relief[i - 1]) * 60f * sc : 0;
                float rr = Math.min(hum, hum * (0.006f / sc + up)); rain[i] = rr; hum = Math.max(0.05f, hum - rr);
            }
        }
        int rb = Math.max(1, Math.round(4 * sc)); boxBlur(rain, W, H, rb); boxBlur(rain, W, H, rb);
        for (int i = 0; i < N; i++) rain[i] += 0.002f / sc;     // base rain so every cell contributes flow
    }

    // ------------------------------------------------------------------ 6. Priority-Flood + epsilon (Barnes et al. 2014)
    /**
     * Fills depressions with an epsilon gradient so every non-water cell drains to water. Returns the number of
     * non-water cells; order[] lists them by ascending filled height (downstream first), rec[] is the steepest
     * D8 receiver on the filled surface (or -1 for water).
     */
    static int priorityFlood(float[] h, int w, int hh, boolean[] water, float[] filled, int[] order, int[] rec) {
        int n = w * hh; Heap pq = new Heap(n / 4); boolean[] closed = new boolean[n]; int no = 0;
        for (int i = 0; i < n; i++) {
            filled[i] = h[i]; rec[i] = -1;
            int x = i % w, y = i / w; boolean edge = x == 0 || y == 0 || x == w - 1 || y == hh - 1;
            if (water[i] || edge) {
                boolean seed = edge;
                for (int k = 0; k < 8 && !seed; k++) { int nx = x + DX[k], ny = y + DY[k]; if (nx >= 0 && ny >= 0 && nx < w && ny < hh && !water[ny * w + nx]) seed = true; }
                if (water[i]) closed[i] = true;
                if (seed) { closed[i] = true; pq.push(i, filled[i]); }
            }
        }
        while (pq.size > 0) {
            int c = pq.pop(), cx = c % w, cy = c / w;
            if (!water[c]) order[no++] = c;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 0 || ny < 0 || nx >= w || ny >= hh) continue;
                int nb = ny * w + nx; if (closed[nb]) continue; closed[nb] = true;
                if (filled[nb] <= filled[c]) filled[nb] = Math.nextUp(filled[c]);
                pq.push(nb, filled[nb]);
            }
        }
        for (int o = 0; o < no; o++) {
            int c = order[o], cx = c % w, cy = c / w; float best = 0; int br = -1;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 0 || ny < 0 || nx >= w || ny >= hh) continue;
                int nb = ny * w + nx; float sl = (filled[c] - filled[nb]) / DD[k];
                if (sl > best) { best = sl; br = nb; }
            }
            rec[c] = br;
        }
        return no;
    }

    // ------------------------------------------------------------------ 7. flow, lakes, rivers, deltas
    void hydrology() {
        // lakes: depressions filled by Priority-Flood, kept when big enough (old worlds keep smaller ones)
        int minLake = Math.round((40 - 30 * s.age) * sc * sc);
        boolean[] dep = new boolean[N];
        float lakeDepth = 0.045f - 0.037f * s.age;    // old worlds keep shallower basins as lakes
        for (int i = 0; i < N; i++) dep[i] = !ocean[i] && filled[i] - elev[i] > lakeDepth;
        int[] lab = new int[N], q = new int[N]; Arrays.fill(lab, -1);
        for (int i = 0; i < N; i++) {
            if (!dep[i] || lab[i] >= 0) continue;
            int n = flood(dep, true, i, lab, q, i); float depth = 0;
            for (int k = 0; k < n; k++) depth = Math.max(depth, filled[q[k]] - elev[q[k]]);
            if (n >= minLake) for (int k = 0; k < n; k++) lake[q[k]] = true;
        }
        // flow accumulation weighted by rainfall, in reverse flood order
        for (int o = 0; o < nLand; o++) acc[order[o]] = rain[order[o]];
        for (int o = nLand - 1; o >= 0; o--) { int c = order[o]; if (rec[c] >= 0 && !ocean[rec[c]]) acc[rec[c]] += acc[c]; }
        // threshold from the river-density slider (an accumulation quantile)
        float frac = (0.003f + 0.022f * s.rivers) * (0.6f + 0.6f * s.age);
        float[] la = new float[nLand]; for (int o = 0; o < nLand; o++) la[o] = acc[order[o]];
        Arrays.sort(la);
        float thr = la[Math.max(0, (int) ((1 - frac) * (nLand - 1)))]; riverThr = thr;
        boolean[] cand = new boolean[N];
        for (int o = 0; o < nLand; o++) { int c = order[o]; cand[c] = acc[c] >= thr && !lake[c]; }
        // prune short rivers / stub tributaries: young worlds keep only few, long rivers
        int minLen = Math.round((12 + 30 * (1 - s.age)) * sc), minTrib = Math.round((6 + 10 * (1 - s.age)) * sc);
        int[] len = new int[N], main = new int[N]; Arrays.fill(main, -1);
        for (int o = nLand - 1; o >= 0; o--) {
            int c = order[o], r = rec[c]; if (r < 0 || !cand[r]) continue;
            int l = cand[c] ? Math.max(1, len[c]) : (lake[c] ? minLen : -1);
            if (l > 0 && l + 1 > len[r]) { len[r] = l + 1; main[r] = c; }
        }
        boolean[] keep = new boolean[N];
        for (int o = 0; o < nLand; o++) {
            int c = order[o]; if (!cand[c]) continue; int r = rec[c];
            boolean mouth = r < 0 || ocean[r] || lake[r] || !cand[r];
            keep[c] = mouth ? Math.max(1, len[c]) >= minLen : keep[r] && (main[r] == c || len[c] >= minTrib);
        }
        // tiers by discharge
        int nr = 0; for (int o = 0; o < nLand; o++) if (keep[order[o]]) la[nr++] = acc[order[o]];
        Arrays.sort(la, 0, nr);
        float t2 = nr > 0 ? la[(int) (0.80f * (nr - 1))] : 0, t3 = nr > 0 ? la[(int) (0.96f * (nr - 1))] : 0;
        for (int o = 0; o < nLand; o++) { int c = order[o]; if (keep[c]) { river[c] = (byte) (acc[c] >= t3 ? 3 : acc[c] >= t2 ? 2 : 1); mainRiver[c] = true; } }
        // make every river 4-connected (tile autotiling and blocking need it): fill the corner of each diagonal step
        for (int o = 0; o < nLand; o++) {
            int c = order[o], r = rec[c]; if (river[c] == 0 || r < 0) continue;
            int dx = r % W - c % W, dy = r / W - c / W; if (dx == 0 || dy == 0) continue;
            int a = c + dx, b = c + dy * W;
            if (!isLand(a) || !isLand(b) || river[a] > 0 || river[b] > 0) continue;
            int pick = filled[a] <= filled[b] ? a : b; river[pick] = river[c];
        }
        // great rivers: 2 tiles wide (widen toward the lower 4-neighbour)
        for (int o = 0; o < nLand; o++) {
            int c = order[o]; if (river[c] != 3) continue; int cx = c % W, cy = c / W, best = -1; float bh = 1e9f;
            for (int k = 0; k < 4; k++) { int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 1 || ny < 1 || nx >= W - 1 || ny >= H - 1) continue;
                int n = ny * W + nx; if (isLand(n) && river[n] == 0 && filled[n] < bh) { bh = filled[n]; best = n; } }
            if (best >= 0) river[best] = 4;   // 4 = widening cell (counted as tier 3 elsewhere)
        }
        for (int i = 0; i < N; i++) if (river[i] == 4) river[i] = 3;
        if (s.age > 0.35f) deltas(main);
    }

    /** Old worlds: distributary channels and marsh around the biggest river mouths. */
    void deltas(int[] main) {
        SplittableRandom r = new SplittableRandom(s.seed * 5 + 9);
        ArrayList<Integer> mouths = new ArrayList<>();
        for (int o = 0; o < nLand; o++) { int c = order[o]; if (river[c] >= 2 && rec[c] >= 0 && ocean[rec[c]]) mouths.add(c); }
        mouths.sort((a, b) -> Float.compare(acc[b], acc[a]));
        int want = Math.round(1 + 4 * s.age), arms = 1 + Math.round(2.5f * s.age), L = Math.round((6 + 12 * s.age) * sc);
        ArrayList<Integer> used = new ArrayList<>();
        for (int m : mouths) {
            if (used.size() >= want) break;
            boolean near = false; for (int u : used) if (dist(m % W, m / W, u % W, u / W) < 4 * L) near = true;
            if (near) continue; used.add(m);
            int st = m; for (int k = 0; k < L && main[st] >= 0; k++) st = main[st];
            float bx = m % W - st % W, by = m / W - st / W, bl = (float) Math.max(1, Math.hypot(bx, by)); bx /= bl; by /= bl;
            for (int a = 0; a < arms; a++) {
                double ang = (arms == 1 ? 0.6 : -0.8 + 1.6 * a / (arms - 1)) + 0.2 * (r.nextDouble() - 0.5);
                float dx = (float) (bx * Math.cos(ang) - by * Math.sin(ang)), dy = (float) (bx * Math.sin(ang) + by * Math.cos(ang));
                int c = st, nPath = 0; int[] path = new int[4 * L]; boolean reached = false;
                for (int step = 0; step < 4 * L; step++) {
                    int cx = c % W, cy = c / W, best = -1; float bs = -1e9f;
                    for (int k = 0; k < 4; k++) {
                        int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 1 || ny < 1 || nx >= W - 1 || ny >= H - 1) continue;
                        float sc2 = DX[k] * dx + DY[k] * dy + 0.9f * (float) r.nextDouble();
                        if (sc2 > bs) { bs = sc2; best = ny * W + nx; }
                    }
                    if (best < 0) break;
                    if (ocean[best] || lake[best]) { reached = true; break; }
                    c = best; path[nPath++] = c;
                }
                if (reached) for (int k = 0; k < nPath; k++) if (river[path[k]] == 0) river[path[k]] = 1;
            }
            int rad = Math.round(L * 0.8f), mx = m % W, my = m / W;     // delta wetlands
            for (int y = Math.max(0, my - rad); y < Math.min(H, my + rad); y++) for (int x = Math.max(0, mx - rad); x < Math.min(W, mx + rad); x++) {
                int i = y * W + x; if (isLand(i) && dist(x, y, mx, my) < rad && elev[i] < 0.06f) marsh[i] = true;
            }
        }
    }

    // ------------------------------------------------------------------ 8. climate and classes
    void climate() {
        bfs(ocean, coastDist, true);                 // land: tiles to the ocean
        boolean[] land = new boolean[N]; for (int i = 0; i < N; i++) land[i] = !ocean[i];
        bfs(land, seaDist, true);                    // ocean: tiles to land
        boolean[] fresh = new boolean[N]; for (int i = 0; i < N; i++) fresh[i] = ocean[i] || lake[i] || river[i] > 0;
        int[] wd = new int[N]; bfs(fresh, wd, false);
        Noise cn = new Noise(s.seed + 51), mn = new Noise(s.seed + 52), dn = new Noise(s.seed + 53);
        // land elevation / ruggedness ranks
        float[] le = new float[nLand], mscore = new float[N]; int nl = 0;
        for (int i = 0; i < N; i++) if (isLand(i)) { mscore[i] = elev[i] + 0.6f * Math.max(0, uplift[i]) * s.mountains; le[nl++] = elev[i]; }
        Arrays.sort(le, 0, nl);
        float eTop = Math.max(1e-3f, le[(int) (0.99f * (nl - 1))]), eLow = le[(int) (0.3f * (nl - 1))];
        float[] ms = new float[nl]; nl = 0; for (int i = 0; i < N; i++) if (isLand(i)) ms[nl++] = mscore[i];
        Arrays.sort(ms, 0, nl);
        float mFrac = (0.005f + 0.11f * s.mountains) * (1 - 0.45f * s.age), hFrac = (0.03f + 0.10f * s.mountains) * (1 + 0.5f * s.age);
        float mThr = ms[(int) ((1 - mFrac) * (nl - 1))], hThr = ms[(int) (Math.max(0, 1 - mFrac - hFrac) * (nl - 1))];
        float[] rr = new float[nl]; nl = 0; for (int i = 0; i < N; i++) if (isLand(i)) rr[nl++] = rain[i];
        Arrays.sort(rr, 0, nl); float r95 = Math.max(1e-6f, rr[(int) (0.95f * (nl - 1))]);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x; float u = (float) x / W, v = (float) y / H;
            float eN = Math.max(0, Math.min(1, elev[i] / eTop));
            temp[i] = 0.5f + 0.3f * s.temperature + 1.1f * s.latitudeSpan * (v - 0.5f) - 0.18f * eN + 0.07f * cn.fbm(u * 4, v * 4, 3, 0.5f);
            moist[i] = 0.55f * Math.min(1, rain[i] / r95) + 0.4f * (float) Math.exp(-wd[i] / (18f * sc))
                    + 0.12f * mn.fbm(u * 5, v * 5, 3, 0.5f) + 0.8f * (s.moisture - 0.5f);
            if (ocean[i]) { cls[i] = seaDist[i] > 18 * sc && dn.fbm(u * 7, v * 7, 2, 0.5f) > 0.28f ? DEEP : OCEAN; continue; }
            if (lake[i]) { cls[i] = LAKE; continue; }
            float t = temp[i], m = moist[i];
            byte c;
            if (mscore[i] >= mThr) c = MOUNTAIN;
            else if (t < 0.12f) c = SNOW;
            else if (t < 0.22f) c = TUNDRA;
            else if (mscore[i] >= hThr) c = HILLS;
            else if ((marsh[i] || (elev[i] <= eLow && m > 0.82f)) && t > 0.3f) c = SWAMP;
            else if (t > 0.72f) c = m < 0.38f ? DESERT : m > 0.68f ? JUNGLE : GRASS;
            else if (t > 0.58f && m < 0.25f) c = DESERT;
            else c = m > 0.52f ? FOREST : GRASS;
            cls[i] = c;
        }
        modeFilter(2);
        for (int i = 0; i < N; i++) {                   // beaches: shore tiles that are not cliffs/mountains
            if (!isLand(i) || cls[i] == MOUNTAIN || cls[i] == SWAMP) continue;
            int lim = elev[i] < 0.02f + 0.03f * s.age ? 3 : 2;   // 2-3 tile sand rings like the reference
            if (coastDist[i] <= lim && temp[i] > 0.13f) cls[i] = BEACH;
        }
        // deep water as blocky 8x8 patches (the reference's darker squares)
        int B = 8;
        for (int by = 0; by < H; by += B) for (int bx = 0; bx < W; bx += B) {
            int deep = 0, tot = 0;
            for (int y = by; y < Math.min(H, by + B); y++) for (int x = bx; x < Math.min(W, bx + B); x++) { tot++; if (cls[y * W + x] == DEEP) deep++; }
            byte f = deep * 2 >= tot ? DEEP : OCEAN;
            for (int y = by; y < Math.min(H, by + B); y++) for (int x = bx; x < Math.min(W, bx + B); x++) {
                int i = y * W + x; if (ocean[i]) cls[i] = (f == DEEP && seaDist[i] > 3 * sc) ? DEEP : OCEAN;
            }
        }
    }

    /** Majority filter on soft land classes: removes speckle without touching mountains, water or rivers. */
    void modeFilter(int passes) {
        int[] cnt = new int[CLASS.length];
        for (int p = 0; p < passes; p++) {
            byte[] out = cls.clone();
            for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
                int i = y * W + x; if (!isLand(i) || cls[i] == MOUNTAIN) continue;
                Arrays.fill(cnt, 0); int best = cls[i];
                for (int k = 0; k < 8; k++) { int n = i + DX[k] + DY[k] * W; if (isLand(n)) cnt[cls[n]]++; }
                for (int c = 0; c < cnt.length; c++) if (cnt[c] > cnt[best]) best = c;
                if (cnt[best] >= 5 && best != MOUNTAIN) out[i] = (byte) best;
                if (cls[i] == MOUNTAIN && cnt[MOUNTAIN] < 2) out[i] = HILLS;
            }
            for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {   // lone mountain tiles -> hills
                int i = y * W + x; if (cls[i] != MOUNTAIN) continue; int m = 0;
                for (int k = 0; k < 8; k++) if (cls[i + DX[k] + DY[k] * W] == MOUNTAIN) m++;
                if (m < 2) out[i] = HILLS;
            }
            System.arraycopy(out, 0, cls, 0, N);
        }
    }

    /** 4-connected BFS distance from every cell where src==true (dist 0), into all other cells. */
    void bfs(boolean[] src, int[] d, boolean unused) {
        int[] q = new int[N]; int qt = 0;
        for (int i = 0; i < N; i++) { if (src[i]) { d[i] = 0; q[qt++] = i; } else d[i] = Integer.MAX_VALUE; }
        for (int qh = 0; qh < qt; qh++) {
            int c = q[qh], cx = c % W, cy = c / W;
            for (int k = 0; k < 4; k++) { int nx = cx + DX[k], ny = cy + DY[k]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int n = ny * W + nx; if (d[n] == Integer.MAX_VALUE) { d[n] = d[c] + 1; q[qt++] = n; } }
        }
    }

    // ------------------------------------------------------------------ helpers
    static float smooth(float e0, float e1, float x) { float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }
    static float dist(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return (float) Math.sqrt(dx * dx + dy * dy); }
    static float percentile(float[] a, float p) { Arrays.sort(a); return a[Math.max(0, Math.min(a.length - 1, (int) (p * (a.length - 1))))]; }

    static void boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length];
        for (int y = 0; y < h; y++) { float s = 0; int n = 0;
            for (int x = -r; x < w; x++) { if (x + r < w) { s += a[y * w + x + r]; n++; } if (x - r - 1 >= 0) { s -= a[y * w + x - r - 1]; n--; } if (x >= 0) tmp[y * w + x] = s / n; } }
        for (int x = 0; x < w; x++) { float s = 0; int n = 0;
            for (int y = -r; y < h; y++) { if (y + r < h) { s += tmp[(y + r) * w + x]; n++; } if (y - r - 1 >= 0) { s -= tmp[(y - r - 1) * w + x]; n--; } if (y >= 0) a[y * w + x] = s / n; } }
    }
}
