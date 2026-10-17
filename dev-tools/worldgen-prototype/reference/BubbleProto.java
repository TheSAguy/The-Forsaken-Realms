import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Throwaway research prototype (not TFR code): procedural substrate (continents, islands, ranges, rivers,
 * climate) + a two-level "bubble" layer: plane REALMS (capacity-balanced geodesic partition, Hungarian
 * plane->realm assignment, island-as-realm) and set BUBBLES inside each realm (Poisson seeds, budgeted
 * multi-source Dijkstra growth that stops at rivers/ridges, 2-tile seams, Hungarian set->bubble), then a
 * bounded climate "nudge" so each bubble's skin agrees with its theme.
 *
 * Usage: java BubbleProto.java out=DIR seed=1 size=700 continents=2 islands=0.5 water=0.5 rivers=0.5 age=0.5 coverage=0.55
 */
public class BubbleProto {
    static int W = 700, H = 700, N;
    static long seed = 1;
    static int continents = 2;
    static int GAP = 3;
    static double islands = 0.5, water = 0.5, riversS = 0.5, age = 0.5, coverage = 0.55;
    static String outDir = ".";
    static Random rng;

    // ---------- plane table: name, T, M, E, coast, islandPref, color, sets ----------
    static final String[][] PLANES = {
        {"Crossroads (Multiversal)", ".50", ".50", ".30", ".30", "0", "C8B88A", "10E 2X2 2XM 30A 3ED 4ED 5ED 6ED 7ED 8ED 9ED A25 CHR CMM CMR EMA FDN FRA IMA LEA LEB M10 M11 M12 M13 M14 M15 M19 M20 M21 MB1 ME1 ME2 ME3 ME4 MH1 MH2 MH3 MM2 MM3 MMA MOM OM1 PIO UMA VMA ORI"},
        {"Dominaria", ".55", ".50", ".35", ".40", "0", "D9A441", "2ED ALL APC ATQ BRO CSP DMR DMU DOM DRK FEM FUT ICE INV JUD LEG LGN MIR ODY ONS PCY PLC PLS PO2 POR S99 SCG TOR TSP TSR UDS ULG USG VIS WTH"},
        {"Ravnica", ".55", ".45", ".20", ".30", "0", "8C8C9E", "DGM DIS GPT GRN GTC MKM RAV RNA RTR RVR WAR"},
        {"Innistrad", ".28", ".65", ".40", ".40", "0", "3B3F5C", "AVR DKA EMN INR ISD MID SIR SOI VOW"},
        {"Mirrodin/New Phyrexia", ".50", ".25", ".40", ".20", "0", "B0B7BD", "5DN DST MBS MRD NPH ONE SOM"},
        {"Lorwyn-Shadowmoor", ".55", ".72", ".30", ".30", "0", "E6D35A", "ECL EVE LRW MOR SHM"},
        {"Zendikar", ".50", ".60", ".80", ".40", "0", "A0522D", "BFZ OGW ROE WWK ZEN ZNR"},
        {"Kamigawa", ".42", ".65", ".55", ".60", ".4", "D46A8C", "BOK CHK NEO SOK"},
        {"Rath", ".78", ".30", ".60", ".20", "0", "5A1E1E", "EXO NMS STH TMP TPR"},
        {"Kaladesh/Avishkar", ".70", ".45", ".35", ".40", "0", "E0802B", "AER KLD KLR DFT"},
        {"Amonkhet", ".93", ".08", ".25", ".40", "0", "E8C872", "AKH AKR HOU"},
        {"Alara", ".50", ".50", ".50", ".30", "0", "7A4FA0", "ALA ARB CFX"},
        {"Ixalan", ".88", ".85", ".30", ".80", ".5", "1E8F5A", "LCI RIX XLN"},
        {"Tarkir", ".50", ".30", ".65", ".30", "0", "B5651D", "DTK FRF KTK TDM"},
        {"Theros", ".72", ".40", ".40", ".85", ".5", "C9A0DC", "BNG JOU THB THS"},
        {"UB: Forgotten Realms", ".50", ".55", ".40", ".50", "1", "A33B3B", "AFR CLB HBG"},
        {"Eldraine", ".50", ".66", ".35", ".30", "0", "2E6B30", "ELD WOE"},
        {"Bloomburrow", ".55", ".72", ".25", ".30", "0", "F4A6A6", "BLB"},
        {"Duskmourn", ".40", ".50", ".30", ".20", "0", "4B2E39", "DSK"},
        {"Edge (space)", ".40", ".40", ".40", ".50", "1", "101030", "EOE"},
        {"Capenna", ".50", ".35", ".25", ".30", "0", "C0A060", "SNC"},
        {"Thunder Junction", ".82", ".15", ".40", ".20", "0", "C27B3E", "OTJ"},
        {"Arcavios", ".50", ".55", ".35", ".40", "0", "5B7FBF", "SOS"},
        {"Fiora", ".55", ".50", ".30", ".50", "0", "8E44AD", "CN2 CNS"},
        {"UB: Marvel", ".50", ".50", ".30", ".50", "1", "E23636", "MSH SPM"},
        {"UB: Middle-earth", ".45", ".60", ".50", ".50", "1", "6B8E23", "HOB LTR"},
        {"Rabiah", ".93", ".10", ".25", ".40", "0", "F0B341", "ARN"},
        {"UB: Avatar", ".50", ".50", ".40", ".60", "1", "4FB0C6", "TLA"},
        {"Kylem", ".62", ".40", ".40", ".30", "0", "D35400", "BBD"},
        {"UB: Final Fantasy", ".50", ".50", ".40", ".50", "1", "2C3E8F", "FIN"},
        {"Ulgrotha", ".40", ".45", ".40", ".30", "0", "7F8C8D", "HML"},
        {"Ikoria", ".55", ".50", ".65", ".30", "0", "556B2F", "IKO"},
        {"Kaldheim", ".08", ".50", ".60", ".50", "0", "6FB7E9", "KHM"},
        {"Mercadia", ".65", ".40", ".70", ".30", "0", "9B59B6", "MMQ"},
        {"UB: Earth (PTK)", ".60", ".50", ".40", ".50", "1", "C0392B", "PTK"},
        {"UB: TMNT", ".50", ".50", ".30", ".50", "1", "27AE60", "TMT"},
    };
    // per-set climate overrides (sub-themes inside a plane)
    static final Map<String, double[]> SET_OVR = new HashMap<>();
    static {
        for (String s : "ICE ALL CSP".split(" ")) SET_OVR.put(s, new double[]{0.12, 0.45});
        for (String s : "MIR VIS WTH".split(" ")) SET_OVR.put(s, new double[]{0.85, 0.65});
        for (String s : "ODY TOR JUD".split(" ")) SET_OVR.put(s, new double[]{0.75, 0.20});
        for (String s : "SHM EVE".split(" ")) SET_OVR.put(s, new double[]{0.35, 0.70});
        for (String s : "HOU".split(" ")) SET_OVR.put(s, new double[]{0.97, 0.03});
        for (String s : "KHM".split(" ")) SET_OVR.put(s, new double[]{0.06, 0.5});
    }

    static int P;
    static double[] pT, pM, pE, pC, pIsl, pWeight;
    static String[][] pSets;

    // ---------- substrate ----------
    static double[] elev, fill, T, M, hNorm;
    static boolean[] land, river, mountain, lake;
    static int[] comp;
    static int[] compSize;

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            String[] kv = a.split("=", 2);
            switch (kv[0]) {
                case "out": outDir = kv[1]; break;
                case "seed": seed = Long.parseLong(kv[1]); break;
                case "size": W = H = Integer.parseInt(kv[1]); break;
                case "continents": continents = Integer.parseInt(kv[1]); break;
                case "islands": islands = Double.parseDouble(kv[1]); break;
                case "water": water = Double.parseDouble(kv[1]); break;
                case "rivers": riversS = Double.parseDouble(kv[1]); break;
                case "age": age = Double.parseDouble(kv[1]); break;
                case "coverage": coverage = Double.parseDouble(kv[1]); break;
                case "gap": GAP = Integer.parseInt(kv[1]); break;
                case "mode": mode = kv[1]; break;
                case "cohesion": cohesion = Double.parseDouble(kv[1]); break;
                case "ais": AIS = Integer.parseInt(kv[1]); break;
            }
        }
        N = W * H;
        rng = new Random(seed);
        initNoise(seed);
        loadPlanes();
        long t0 = System.nanoTime();
        int[][] islandReq = planIslands();
        genLandmass(islandReq);
        long t1 = System.nanoTime();
        hydrology();
        long t2 = System.nanoTime();
        climate();
        labelComponents();
        long t3 = System.nanoTime();
        if (mode.equals("territory")) {
            territories(islandReq);
            long tt = System.nanoTime();
            renderTerritories();
            System.out.printf(Locale.ROOT, "TIMING ms: landmass %.0f, hydrology %.0f, climate+components %.0f, territories(+assign+cores) %.0f, total-gen %.0f%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (tt - t3) / 1e6, (tt - t0) / 1e6);
            reportTerritories();
            return;
        }
        realms(islandReq);
        long t4 = System.nanoTime();
        bubbles();
        long t5 = System.nanoTime();
        nudge();
        long t6 = System.nanoTime();
        render();
        System.out.printf(Locale.ROOT, "TIMING ms: landmass %.0f, hydrology %.0f, climate+components %.0f, realms %.0f, bubbles %.0f, nudge %.0f, total-gen %.0f%n",
            (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6, (t5 - t4) / 1e6, (t6 - t5) / 1e6, (t6 - t0) / 1e6);
        report();
    }

    static void loadPlanes() {
        P = PLANES.length;
        pT = new double[P]; pM = new double[P]; pE = new double[P]; pC = new double[P]; pIsl = new double[P]; pWeight = new double[P];
        pSets = new String[P][];
        for (int i = 0; i < P; i++) {
            pT[i] = Double.parseDouble(PLANES[i][1]); pM[i] = Double.parseDouble(PLANES[i][2]);
            pE[i] = Double.parseDouble(PLANES[i][3]); pC[i] = Double.parseDouble(PLANES[i][4]);
            pIsl[i] = Double.parseDouble(PLANES[i][5]);
            pSets[i] = PLANES[i][7].split(" ");
            int n = pSets[i].length;
            double setW = i == 0 ? 0.45 : 1.0; // Crossroads districts are half-size
            pWeight[i] = 0.6 + setW * n;   // realm area weight, linear in sets
        }
    }

    // ---------- noise ----------
    static int[] perm = new int[512];
    static void initNoise(long s) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        Random r = new Random(s * 31 + 7);
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = p[i]; p[i] = p[j]; p[j] = t; }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }
    static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    static double grad(int h, double x, double y) {
        switch (h & 7) {
            case 0: return x + y; case 1: return -x + y; case 2: return x - y; case 3: return -x - y;
            case 4: return x; case 5: return -x; case 6: return y; default: return -y;
        }
    }
    static double noise(double x, double y) {
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255;
        x -= Math.floor(x); y -= Math.floor(y);
        double u = fade(x), v = fade(y);
        int a = perm[X] + Y, b = perm[X + 1] + Y;
        double r = lerp(v, lerp(u, grad(perm[a], x, y), grad(perm[b], x - 1, y)),
                lerp(u, grad(perm[a + 1], x, y - 1), grad(perm[b + 1], x - 1, y - 1)));
        return r * 0.7071;
    }
    static double lerp(double t, double a, double b) { return a + t * (b - a); }
    static double fbm(double x, double y, int oct) {
        double s = 0, a = 1, f = 1, n = 0;
        for (int i = 0; i < oct; i++) { s += a * noise(x * f, y * f); n += a; a *= 0.5; f *= 2.03; }
        return s / n;
    }
    static double ridged(double x, double y, int oct) {
        double s = 0, a = 1, f = 1, n = 0, prev = 1;
        for (int i = 0; i < oct; i++) {
            double r = 1 - Math.abs(noise(x * f, y * f) * 1.6);
            r = Math.max(0, r); r *= r;
            s += a * r * prev; n += a; prev = r; a *= 0.5; f *= 2.1;
        }
        return s / n;
    }

    // ---------- islands planned for island-native themes (guarantee by construction) ----------
    static int[][] planIslands() {
        java.util.List<Integer> ps = new ArrayList<>();
        for (int p = 0; p < P; p++) if (pIsl[p] >= 1) ps.add(p);
        ps.sort((a, b) -> Double.compare(pT[a], pT[b]));
        java.util.List<int[]> req = new ArrayList<>();
        int perSide = (ps.size() + 1) / 2;
        for (int k = 0; k < ps.size(); k++) {
            int p = ps.get(k); int side = k % 2, slot = k / 2;
            double lat = 0.12 + 0.76 * (slot + 0.5) / perSide;           // colder themes further north
            double x = side == 0 ? 0.075 + rng.nextDouble() * 0.02 : 0.905 + rng.nextDouble() * 0.02;
            int rad = (int) Math.round(Math.sqrt((900 + 650 * pSets[p].length) / Math.PI) * 1.15);
            req.add(new int[]{p, (int) (x * W), (int) (lat * H), rad});
        }
        return req.toArray(new int[0][]);
    }

    static void genLandmass(int[][] islandReq) {
        elev = new double[N];
        java.util.List<double[]> cs = new ArrayList<>();
        int tries = 0;
        while (cs.size() < continents && tries++ < 5000) {
            double cx = 0.34 + rng.nextDouble() * 0.32, cy = 0.32 + rng.nextDouble() * 0.36;
            boolean ok = true;
            for (double[] c : cs) if (Math.hypot(c[0] - cx, c[1] - cy) < 0.42 / Math.sqrt(continents)) ok = false;
            if (ok) cs.add(new double[]{cx, cy, 0.27 / Math.sqrt(continents) * (0.85 + rng.nextDouble() * 0.3), rng.nextDouble() * Math.PI, 0.7 + rng.nextDouble() * 0.6});
        }
        int nIslets = (int) Math.round(islands * 28);
        java.util.List<double[]> isl = new ArrayList<>();
        for (int i = 0; i < nIslets; i++) {
            double a = rng.nextDouble() * Math.PI * 2, rr = 0.33 + rng.nextDouble() * 0.10;
            isl.add(new double[]{0.5 + Math.cos(a) * rr, 0.5 + Math.sin(a) * rr, 0.012 + rng.nextDouble() * 0.03});
        }
        double ridgeAmp = 0.15 + 0.55 * (1 - age);
        double rough = 0.22 * (1.25 - 0.5 * age);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double nx = x / (double) W, ny = y / (double) H;
            double wx = nx + 0.07 * fbm(nx * 3 + 5.2, ny * 3 + 1.3, 3), wy = ny + 0.07 * fbm(nx * 3 + 9.1, ny * 3 + 4.7, 3);
            double g = 0;
            for (double[] c : cs) {
                double dx = wx - c[0], dy = wy - c[1];
                double ca = Math.cos(c[3]), sa = Math.sin(c[3]);
                double u = (dx * ca + dy * sa) / c[4], v = (-dx * sa + dy * ca) * c[4];
                double d = Math.hypot(u, v) / c[2];
                g = Math.max(g, Math.exp(-d * d));
            }
            for (double[] c : isl) { double d = Math.hypot(wx - c[0], wy - c[1]) / c[2]; g = Math.max(g, 0.9 * Math.exp(-d * d)); }
            for (int[] r : islandReq) { double d = Math.hypot(x - r[1], y - r[2]) / r[3]; if (d < 1) g = Math.max(g, Math.exp(-d * d * 0.7)); else g *= smooth(1.0, 2.2, d); }
            double shape = smooth(0.15, 0.6, g);                       // flat-topped land potential
            double n = fbm(wx * 6, wy * 6, 5);
            double mask = smooth(0.45, 0.65, fbm(nx * 2.2 + 20, ny * 2.2 + 20, 3) * 0.5 + 0.5);
            double rdg = ridged(wx * 4.5 + 11, wy * 4.5 + 3, 4);
            double edge = Math.max(Math.abs(nx - 0.5), Math.abs(ny - 0.5)) * 2;
            elev[y * W + x] = 0.6 * shape + rough * n + ridgeAmp * rdg * mask * smooth(0.35, 0.9, shape) - Math.max(0, edge - 0.93) * 6;
        }
        for (int i = 0; i < Math.round(age * 3); i++) blur(elev);
        double sea = quantile(elev, water);
        // guarantee theme islands by construction: solid core, 7-tile moat
        for (int[] r : islandReq) for (int y = Math.max(0, r[2] - r[3] - 9); y < Math.min(H, r[2] + r[3] + 9); y++) for (int x = Math.max(0, r[1] - r[3] - 9); x < Math.min(W, r[1] + r[3] + 9); x++) {
            double d = Math.hypot(x - r[1], y - r[2]); int i = y * W + x;
            if (d < 0.6 * r[3]) elev[i] = Math.max(elev[i], sea + 0.05);
            else if (d > r[3] && d < r[3] + 7) elev[i] = Math.min(elev[i], sea - 0.02);
        }
        land = new boolean[N];
        double mx = -1e9;
        for (int i = 0; i < N; i++) { land[i] = elev[i] > sea; mx = Math.max(mx, elev[i]); }
        hNorm = new double[N];
        for (int i = 0; i < N; i++) hNorm[i] = land[i] ? (elev[i] - sea) / (mx - sea) : 0;
    }
    static double smooth(double a, double b, double x) { double t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
    static void blur(double[] a) {
        double[] b = new double[N];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double s = 0; int c = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; s += a[yy * W + xx]; c++;
            }
            b[y * W + x] = s / c;
        }
        System.arraycopy(b, 0, a, 0, N);
    }
    static double quantile(double[] a, double q) {
        double[] c = a.clone(); Arrays.sort(c); return c[(int) Math.min(c.length - 1, Math.max(0, q * c.length))];
    }

    static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DY = {0, 0, 1, -1, 1, -1, 1, -1};
    static final double[] DL = {1, 1, 1, 1, 1.4142, 1.4142, 1.4142, 1.4142};

    // ---------- hydrology: priority-flood + epsilon, D8 receivers, accumulation ----------
    static void hydrology() {
        fill = elev.clone();
        int[] recv = new int[N]; Arrays.fill(recv, -1);
        int[] order = new int[N]; int on = 0;
        boolean[] vis = new boolean[N];
        Heap hp = new Heap(N);
        for (int i = 0; i < N; i++) if (!land[i]) {
            vis[i] = true;
            int x = i % W, y = i / W;
            for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx >= 0 && yy >= 0 && xx < W && yy < H && land[yy * W + xx]) { hp.push(i, fill[i]); break; } }
        }
        while (hp.size > 0) {
            int c = hp.popIdx();
            int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) {
                int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                int n = yy * W + xx; if (vis[n]) continue; vis[n] = true;
                fill[n] = Math.max(elev[n], fill[c] + 1e-6); recv[n] = c; order[on++] = n; hp.push(n, fill[n]);
            }
        }
        double[] flux = new double[N];
        for (int i = 0; i < N; i++) if (land[i]) flux[i] = 1;
        for (int k = on - 1; k >= 0; k--) { int i = order[k]; int r = recv[i]; if (r >= 0 && land[r]) flux[r] += flux[i]; }
        lake = new boolean[N];
        int landN = 0; for (int i = 0; i < N; i++) if (land[i]) landN++;
        double[] lf = new double[landN]; int j = 0; for (int i = 0; i < N; i++) if (land[i]) lf[j++] = flux[i];
        Arrays.sort(lf);
        double riverFrac = 0.004 + 0.022 * riversS;
        double thr = lf[(int) ((1 - riverFrac) * (landN - 1))];
        river = new boolean[N];
        for (int i = 0; i < N; i++) {
            if (!land[i]) continue;
            if (fill[i] - elev[i] > 0.012 + 0.02 * (1 - age)) lake[i] = true;
            if (flux[i] >= thr) river[i] = true;
        }
        // mountains: top slice of land elevation; younger worlds have more
        double[] lh = new double[landN]; j = 0; for (int i = 0; i < N; i++) if (land[i]) lh[j++] = hNorm[i];
        Arrays.sort(lh);
        double mthr = lh[(int) ((1 - (0.03 + 0.06 * (1 - age))) * (landN - 1))];
        mountain = new boolean[N];
        for (int i = 0; i < N; i++) if (land[i] && !river[i] && !lake[i] && hNorm[i] >= mthr) mountain[i] = true;
    }

    static boolean isWater(int i) { return !land[i] || lake[i]; }

    static int[] distWater;
    static void climate() {
        T = new double[N]; M = new double[N];
        distWater = bfsDist(i -> isWater(i));
        int[] dRiver = bfsDist(i -> river[i]);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x; double lat = y / (double) (H - 1);
            T[i] = 0.12 + 0.86 * lat + 0.10 * fbm(x * 0.012 + 50, y * 0.012 + 50, 3) - 0.35 * hNorm[i];
            M[i] = 0.55 * Math.exp(-distWater[i] / 60.0) + 0.15 * Math.exp(-dRiver[i] / 6.0) + 0.45 * (fbm(x * 0.006 + 80, y * 0.006 + 80, 4) * 0.5 + 0.5);
        }
        rankNormalize(M);
    }
    static void rankNormalize(double[] a) {
        Integer[] idx = new Integer[N]; int n = 0;
        for (int i = 0; i < N; i++) if (land[i]) idx[n++] = i;
        Integer[] li = Arrays.copyOf(idx, n);
        Arrays.sort(li, (p, q) -> Double.compare(a[p], a[q]));
        for (int k = 0; k < n; k++) a[li[k]] = k / (double) Math.max(1, n - 1);
    }
    interface Pred { boolean t(int i); }
    static int[] bfsDist(Pred src) {
        int[] d = new int[N]; Arrays.fill(d, Integer.MAX_VALUE); int[] q = new int[N]; int qh = 0, qt = 0;
        for (int i = 0; i < N; i++) if (src.t(i)) { d[i] = 0; q[qt++] = i; }
        while (qh < qt) { int c = q[qh++]; int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx; if (d[n] > d[c] + 1) { d[n] = d[c] + 1; q[qt++] = n; } } }
        return d;
    }
    static void labelComponents() {
        comp = new int[N]; Arrays.fill(comp, -1); java.util.List<Integer> sizes = new ArrayList<>(); int[] q = new int[N];
        for (int s = 0; s < N; s++) {
            if (!land[s] || lake[s] || comp[s] >= 0) continue;
            int id = sizes.size(); int qh = 0, qt = 0; q[qt++] = s; comp[s] = id;
            while (qh < qt) { int c = q[qh++]; int x = c % W, y = c / W;
                for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx; if (land[n] && !lake[n] && comp[n] < 0) { comp[n] = id; q[qt++] = n; } } }
            sizes.add(qt);
        }
        compSize = sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------- realms ----------
    static int[] realm; static int R; static int[] realmPlane; static boolean[] realmIsland;
    static double[] rT, rM, rE, rC, rArea, rCx, rCy;
    static int[] seedTile;
    static int MIN_ISLAND = 150;
    static java.util.List<String> notes = new ArrayList<>();

    static void realms(int[][] islandReq) {
        realm = new int[N]; Arrays.fill(realm, -1);
        int landTotal = 0; for (int c : compSize) landTotal += c;
        double totalWeight = 0; for (double w : pWeight) totalWeight += w;
        double unit = landTotal / (totalWeight * 1.25);  // tiles per weight unit, 25% reserved for wild realms
        // (1) island components become single realms; pre-assign requested theme islands
        java.util.List<Integer> realmComp = new ArrayList<>();
        java.util.List<Integer> pre = new ArrayList<>();
        int[] compRealm = new int[compSize.length]; Arrays.fill(compRealm, -1);
        double maxIsland = unit * 8;
        java.util.List<Integer> seeds = new ArrayList<>();
        for (int c = 0; c < compSize.length; c++) {
            if (compSize[c] < MIN_ISLAND || compSize[c] > maxIsland) continue;
            compRealm[c] = realmComp.size(); realmComp.add(c); pre.add(-1); seeds.add(-1);
        }
        for (int[] r : islandReq) {
            int t = r[2] * W + r[1]; int c = comp[t];
            if (c >= 0 && compRealm[c] >= 0 && pre.get(compRealm[c]) < 0) pre.set(compRealm[c], r[0]);
            else notes.add("requested island for " + PLANES[r[0]][0] + " merged into a larger landmass or sank (falls back to Hungarian)");
        }
        int nIslandRealms = realmComp.size();
        for (int i = 0; i < N; i++) if (comp[i] >= 0 && compRealm[comp[i]] >= 0) realm[i] = compRealm[comp[i]];
        // (2) big components: Poisson seeds, Lloyd, capacity-balanced geodesic partition
        java.util.List<int[]> bigSeeds = new ArrayList<>(); // {tile, comp}
        int kBig = Math.max(1, (int) Math.round(P * 1.35) - nIslandRealms); double bigLand = 0;
        for (int c = 0; c < compSize.length; c++) if (compSize[c] > maxIsland) bigLand += compSize[c];
        for (int c = 0; c < compSize.length; c++) {
            if (compSize[c] <= maxIsland) continue;
            int k = Math.max(1, (int) Math.round(kBig * compSize[c] / bigLand));
            java.util.List<Integer> tiles = new ArrayList<>();
            for (int i = 0; i < N; i++) if (comp[i] == c && !mountain[i] && !river[i]) tiles.add(i);
            Collections.shuffle(tiles, rng);
            double r = 0.8 * Math.sqrt(compSize[c] / (double) k);
            java.util.List<Integer> acc = new ArrayList<>();
            while (acc.size() < k && r > 4) {
                for (int t : tiles) {
                    if (acc.size() >= k) break;
                    boolean ok = true; int x = t % W, y = t / W;
                    for (int a : acc) if (Math.hypot(a % W - x, a / W - y) < r) { ok = false; break; }
                    if (ok) acc.add(t);
                }
                r *= 0.85;
            }
            for (int a : acc) bigSeeds.add(new int[]{a, c});
        }
        R = nIslandRealms + bigSeeds.size();
        seedTile = new int[R];
        for (int i = 0; i < bigSeeds.size(); i++) seedTile[nIslandRealms + i] = bigSeeds.get(i)[0];
        double[] target = new double[R];
        Arrays.fill(target, 1);
        double[] wgt = new double[R];
        // equal-area pass with Lloyd moves
        for (int it = 0; it < 2; it++) { geodesicPartition(nIslandRealms, wgt, target, 3); lloyd(nIslandRealms); }
        geodesicPartition(nIslandRealms, wgt, target, 6);
        stats();
        // (3) Hungarian plane -> realm (non-preassigned)
        realmPlane = new int[R]; Arrays.fill(realmPlane, -1);
        realmIsland = new boolean[R];
        for (int r = 0; r < nIslandRealms; r++) { realmIsland[r] = true; realmPlane[r] = pre.get(r); }
        boolean[] planeDone = new boolean[P];
        for (int r = 0; r < R; r++) if (realmPlane[r] >= 0) planeDone[realmPlane[r]] = true;
        java.util.List<Integer> rowsP = new ArrayList<>(), colsR = new ArrayList<>();
        for (int p = 0; p < P; p++) if (!planeDone[p]) rowsP.add(p);
        for (int r = 0; r < R; r++) if (realmPlane[r] < 0) colsR.add(r);
        double[][] cost = new double[rowsP.size()][Math.max(rowsP.size(), colsR.size())];
        double meanArea = 0; int cnt = 0; for (int r : colsR) { meanArea += rArea[r]; cnt++; } meanArea /= Math.max(1, cnt);
        for (int a = 0; a < rowsP.size(); a++) for (int b = 0; b < cost[0].length; b++) {
            if (b >= colsR.size()) { cost[a][b] = 1e6; continue; }
            cost[a][b] = fitCost(rowsP.get(a), colsR.get(b), unit);
        }
        long h0 = System.nanoTime();
        int[] asg = hungarian(cost);
        notes.add(String.format(Locale.ROOT, "Hungarian plane->realm %dx%d took %.2f ms", cost.length, cost[0].length, (System.nanoTime() - h0) / 1e6));
        for (int a = 0; a < rowsP.size(); a++) if (asg[a] < colsR.size()) realmPlane[colsR.get(asg[a])] = rowsP.get(a);
            else notes.add("no realm left for " + PLANES[rowsP.get(a)][0]);
        // (4) rebalance big realms toward their plane's size (wild realms get 1 unit)
        for (int r = nIslandRealms; r < R; r++) target[r] = realmPlane[r] >= 0 ? pWeight[realmPlane[r]] : 1.6;
        Arrays.fill(wgt, 0);
        geodesicPartition(nIslandRealms, wgt, target, 10);
        stats();
    }

    static double fitCost(int p, int r, double unit) {
        double c = 3.0 * Math.abs(rT[r] - pT[p]) + 2.0 * Math.abs(rM[r] - pM[p]) + 1.5 * Math.abs(rE[r] - pE[p]) + 1.0 * Math.abs(rC[r] - pC[p]);
        if (pIsl[p] > 0 && realmIsland[r]) c -= 0.6 * pIsl[p];
        if (pIsl[p] >= 1 && !realmIsland[r]) c += 2.0;
        if (p == 0) c += 3.0 * Math.hypot(rCx[r] - 0.5, rCy[r] - 0.5); // Crossroads wants the middle
        double want = pWeight[p] * unit; double lr = Math.log(Math.max(1, rArea[r]) / want);
        if (realmIsland[r]) c += 0.8 * lr * lr; // island size can't rebalance
        return c;
    }

    /** Capacity-balanced multi-source Dijkstra over land (big components only, realm ids >= firstBig). */
    static void geodesicPartition(int firstBig, double[] wgt, double[] target, int iters) {
        double[] best = new double[N]; int[] owner = new int[N];
        double[] stepCost = new double[N];
        for (int i = 0; i < N; i++) {
            if (!land[i] || lake[i]) { stepCost[i] = -1; continue; }
            stepCost[i] = 1 + (river[i] ? 10 : 0) + (mountain[i] ? 6 : 0) + 0.6 * (fbm((i % W) * 0.08, (i / W) * 0.08, 2) + 1);
        }
        double sumT = 0; for (int r = firstBig; r < R; r++) sumT += target[r];
        int bigLand = 0; for (int i = 0; i < N; i++) if (comp[i] >= 0 && realm[i] < 0 || (realm[i] >= firstBig)) if (land[i] && !lake[i] && comp[i] >= 0 && compSize[comp[i]] > 0) bigLand += 0;
        Heap hp = new Heap(N * 3);
        double bestErr = 1e18; int[] bestOwner = null;
        for (int it = 0; it < iters; it++) {
            Arrays.fill(best, Double.MAX_VALUE); Arrays.fill(owner, -1);
            for (int r = firstBig; r < R; r++) { if (wgt[r] <= 0) wgt[r] = 1; int s = seedTile[r]; best[s] = 0; owner[s] = r; hp.push(s, 0); }
            while (hp.size > 0) {
                double d = hp.topKey(); int c = hp.popIdx(); if (d > best[c]) continue;
                int x = c % W, y = c / W;
                for (int k = 0; k < 8; k++) {
                    int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                    int n = yy * W + xx; if (stepCost[n] < 0) continue;
                    if (realm[n] >= 0 && realm[n] < firstBig) continue; // island realms are fixed
                    double nd = d + DL[k] * 0.5 * (stepCost[c] + stepCost[n]) / wgt[owner[c]];
                    if (nd < best[n]) { best[n] = nd; owner[n] = owner[c]; hp.push(n, nd); }
                }
            }
            double[] area = new double[R]; double tot = 0;
            for (int i = 0; i < N; i++) if (owner[i] >= 0) { area[owner[i]]++; tot++; }
            double err = 0;
            for (int r = firstBig; r < R; r++) err += Math.abs(area[r] / tot - target[r] / sumT);
            if (err < bestErr) { bestErr = err; bestOwner = owner.clone(); }
            // additive weight update: under-sized realms push their frontier out
            for (int r = firstBig; r < R; r++) { double ratio = (target[r] / sumT) / Math.max(1e-9, area[r] / tot); wgt[r] *= Math.max(0.75, Math.min(1.35, Math.sqrt(ratio))); }
        }
        for (int i = 0; i < N; i++) if (bestOwner[i] >= 0) realm[i] = bestOwner[i];
        lastPartitionErr = bestErr;
    }
    static double lastPartitionErr;

    static void lloyd(int firstBig) {
        double[] sx = new double[R], sy = new double[R], n = new double[R];
        for (int i = 0; i < N; i++) if (realm[i] >= firstBig) { sx[realm[i]] += i % W; sy[realm[i]] += i / W; n[realm[i]]++; }
        for (int r = firstBig; r < R; r++) {
            if (n[r] == 0) continue;
            sx[r] /= n[r]; sy[r] /= n[r];
        }
        double[] bd = new double[R]; Arrays.fill(bd, 1e18);
        for (int i = 0; i < N; i++) { int r = realm[i]; if (r < firstBig || river[i] || mountain[i]) continue;
            double d = (i % W - sx[r]) * (i % W - sx[r]) + (i / W - sy[r]) * (i / W - sy[r]); if (d < bd[r]) { bd[r] = d; seedTile[r] = i; } }
    }

    static void stats() {
        rT = new double[R]; rM = new double[R]; rE = new double[R]; rC = new double[R]; rArea = new double[R]; rCx = new double[R]; rCy = new double[R];
        int[] dOcean = bfsDist(i -> !land[i]);
        for (int i = 0; i < N; i++) { int r = realm[i]; if (r < 0) continue;
            rT[r] += T[i]; rM[r] += M[i]; rE[r] += hNorm[i]; rC[r] += dOcean[i] <= 4 ? 1 : 0; rArea[r]++; rCx[r] += (i % W) / (double) W; rCy[r] += (i / W) / (double) H; }
        double eMax = 0;
        for (int r = 0; r < R; r++) if (rArea[r] > 0) { rT[r] /= rArea[r]; rM[r] /= rArea[r]; rE[r] /= rArea[r]; rC[r] /= rArea[r]; rCx[r] /= rArea[r]; rCy[r] /= rArea[r]; eMax = Math.max(eMax, rE[r]); }
        for (int r = 0; r < R; r++) rE[r] = eMax > 0 ? rE[r] / eMax : 0; // relative ruggedness
    }

    // ---------- Hungarian (rows <= cols), O(n^2 m) ----------
    static int[] hungarian(double[][] a) {
        int n = a.length, m = a[0].length;
        double[] u = new double[n + 1], v = new double[m + 1]; int[] p = new int[m + 1], way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i; int j0 = 0; double[] minv = new double[m + 1]; Arrays.fill(minv, Double.MAX_VALUE); boolean[] used = new boolean[m + 1];
            do { used[j0] = true; int i0 = p[j0], j1 = 0; double delta = Double.MAX_VALUE;
                for (int j = 1; j <= m; j++) if (!used[j]) { double cur = a[i0 - 1][j - 1] - u[i0] - v[j]; if (cur < minv[j]) { minv[j] = cur; way[j] = j0; } if (minv[j] < delta) { delta = minv[j]; j1 = j; } }
                for (int j = 0; j <= m; j++) if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else minv[j] -= delta;
                j0 = j1; } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);
        }
        int[] ans = new int[n];
        for (int j = 1; j <= m; j++) if (p[j] != 0) ans[p[j] - 1] = j - 1;
        return ans;
    }

    // ---------- bubbles ----------
    static int[] bubble; static java.util.List<int[]> bubbleInfo = new ArrayList<>(); // {realm, plane, seedTile}
    static java.util.List<String> bubbleSet = new ArrayList<>();
    static int mergedSets = 0, totalSets = 0;
    static double[] bT, bM; static int[] bArea;

    static void bubbles() {
        bubble = new int[N]; Arrays.fill(bubble, -1);
        int[] dBorder = new int[N]; // distance to realm border (BFS from tiles whose neighbor is another realm/water)
        {
            int[] q = new int[N]; int qh = 0, qt = 0; Arrays.fill(dBorder, Integer.MAX_VALUE);
            for (int i = 0; i < N; i++) { if (realm[i] < 0) continue; int x = i % W, y = i / W; boolean b = false;
                for (int k = 0; k < 8 && !b; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) { b = true; continue; } int n = yy * W + xx; if (realm[n] != realm[i]) b = true; }
                if (b) { dBorder[i] = 0; q[qt++] = i; } }
            while (qh < qt) { int c = q[qh++]; int x = c % W, y = c / W;
                for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx; if (realm[n] == realm[c] && dBorder[n] > dBorder[c] + 1) { dBorder[n] = dBorder[c] + 1; q[qt++] = n; } } }
        }
        double[] stepCost = new double[N];
        for (int i = 0; i < N; i++) stepCost[i] = (!land[i] || lake[i]) ? -1 : 1 + (river[i] ? 25 : 0) + (mountain[i] ? 8 : 0) + 1.2 * rng.nextDouble();
        java.util.List<Integer>[] realmTiles = new java.util.List[R];
        for (int r = 0; r < R; r++) realmTiles[r] = new ArrayList<>();
        for (int i = 0; i < N; i++) if (realm[i] >= 0) realmTiles[realm[i]].add(i);
        java.util.List<Integer> budgets = new ArrayList<>();
        for (int r = 0; r < R; r++) {
            int p = realmPlane[r]; if (p < 0) continue;
            String[] sets = pSets[p]; int n = sets.length; totalSets += n;
            double area = realmTiles[r].size();
            double setW = p == 0 ? 0.45 : 1.0;
            double a = coverage * area / n;
            a = Math.max(120, Math.min(2500, a));
            // Poisson seeds in the realm interior
            java.util.List<Integer> cand = new ArrayList<>();
            for (int t : realmTiles[r]) if (dBorder[t] >= 3 && !river[t] && !mountain[t]) cand.add(t);
            if (cand.isEmpty()) cand.addAll(realmTiles[r]);
            Collections.shuffle(cand, rng);
            double rad = 1.05 * Math.sqrt(a); java.util.List<Integer> acc = new ArrayList<>();
            while (acc.size() < n && rad >= 5) {
                for (int t : cand) { if (acc.size() >= n) break; boolean ok = true; int x = t % W, y = t / W;
                    for (int s : acc) if (Math.hypot(s % W - x, s / W - y) < rad) { ok = false; break; } if (ok && !acc.contains(t)) acc.add(t); }
                rad *= 0.85;
            }
            int nb = acc.size();
            if (nb == 0) { mergedSets += n; notes.add(PLANES[p][0] + ": realm too small for any bubble"); continue; }
            if (nb < n) { mergedSets += n - nb; notes.add(PLANES[p][0] + ": realm fits " + nb + " bubbles for " + n + " sets (" + (n - nb) + " sets share a bubble)"); }
            // per-bubble budget
            int budget = (int) Math.round(coverage * area / Math.max(1, nb));
            budget = Math.max(120, Math.min(2500, budget));
            int first = bubbleInfo.size();
            for (int s : acc) { bubbleInfo.add(new int[]{r, p, s}); budgets.add((int) Math.max(120, budget * (0.6 + 0.8 * rng.nextDouble()))); }
            growBubbles(first, bubbleInfo.size(), r, stepCost, budgets);
            // Hungarian set -> bubble by sub-climate
            int bn = bubbleInfo.size() - first;
            double[][] bc = new double[bn][2]; int[] ba = new int[bn];
            for (int i = 0; i < N; i++) { int b = bubble[i]; if (b < first || b >= first + bn) continue; bc[b - first][0] += T[i]; bc[b - first][1] += M[i]; ba[b - first]++; }
            for (int b = 0; b < bn; b++) if (ba[b] > 0) { bc[b][0] /= ba[b]; bc[b][1] /= ba[b]; }
            // group sets into bn slots: if fewer bubbles than sets, extra sets are appended round-robin
            double[][] cost = new double[bn][bn];
            String[] lead = Arrays.copyOf(sets, bn);
            for (int s = 0; s < bn; s++) for (int b = 0; b < bn; b++) { double[] o = SET_OVR.getOrDefault(lead[s], new double[]{pT[p], pM[p]}); cost[s][b] = 3 * Math.abs(bc[b][0] - o[0]) + 2 * Math.abs(bc[b][1] - o[1]); }
            String[] lab = new String[bn];
            if (bn > 0) { int[] h = hungarian(cost); for (int s = 0; s < bn; s++) lab[h[s]] = lead[s]; }
            for (int s = bn; s < n; s++) lab[s % Math.max(1, bn)] = lab[s % Math.max(1, bn)] + "+" + sets[s];
            for (int b = 0; b < bn; b++) bubbleSet.add(PLANES[p][0] + ":" + lab[b]);
        }
        // tidy: remove spurs (<=2 same-bubble 8-neighbors)
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < N; i++) { int b = bubble[i]; if (b < 0) continue; int x = i % W, y = i / W, same = 0;
            for (int k = 0; k < 8; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; if (bubble[yy * W + xx] == b) same++; }
            if (same <= 2 && i != bubbleInfo.get(b)[2]) bubble[i] = -1; }
        int B = bubbleInfo.size(); bT = new double[B]; bM = new double[B]; bArea = new int[B];
        for (int i = 0; i < N; i++) { int b = bubble[i]; if (b < 0) continue; bT[b] += T[i]; bM[b] += M[i]; bArea[b]++; }
        for (int b = 0; b < B; b++) if (bArea[b] > 0) { bT[b] /= bArea[b]; bM[b] /= bArea[b]; }
    }

    /** Budgeted multi-source Dijkstra inside one realm: each bubble stops at its budget; a 2-tile seam is kept between bubbles. */
    static void growBubbles(int from, int to, int r, double[] stepCost, java.util.List<Integer> budgets) {
        if (to <= from) return;
        Heap hp = new Heap(1 << 16);
        HashMap<Integer, Double> best = new HashMap<>();
        int[] size = new int[to - from];
        for (int b = from; b < to; b++) { int s = bubbleInfo.get(b)[2]; bubble[s] = b; size[b - from] = 1; best.put(s, 0.0); hp.push(s, 0); }
        HashMap<Integer, Integer> owner = new HashMap<>();
        for (int b = from; b < to; b++) owner.put(bubbleInfo.get(b)[2], b);
        while (hp.size > 0) {
            double d = hp.topKey(); int c = hp.popIdx(); Double bd = best.get(c); if (bd != null && d > bd) continue;
            int b = owner.get(c); if (size[b - from] >= budgets.get(b)) continue;
            int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) {
                int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                int n = yy * W + xx; if (realm[n] != r || stepCost[n] < 0 || bubble[n] >= 0) continue;
                // seam: no other bubble within Chebyshev radius 2
                boolean clash = false;
                for (int ey = -GAP; ey <= GAP && !clash; ey++) for (int ex = -GAP; ex <= GAP; ex++) { int ax = xx + ex, ay = yy + ey; if (ax < 0 || ay < 0 || ax >= W || ay >= H) continue; int o = bubble[ay * W + ax]; if (o >= 0 && o != b) { clash = true; break; } }
                if (clash) continue;
                double nd = d + DL[k] * stepCost[n];
                Double ob = best.get(n);
                if (ob == null || nd < ob) {
                    if (size[b - from] >= budgets.get(b)) break;
                    best.put(n, nd); owner.put(n, b); bubble[n] = b; size[b - from]++; hp.push(n, nd);
                }
            }
        }
    }

    // ---------- bounded climate nudge so each bubble's skin agrees with its theme ----------
    static int nudged = 0; static double residBefore = 0, residAfter = 0;
    static void nudge() {
        int B = bubbleInfo.size();
        double[] dT = new double[B], dM = new double[B];
        for (int b = 0; b < B; b++) {
            int p = bubbleInfo.get(b)[1]; String bs = bubbleSet.get(b); String set = bs.substring(bs.lastIndexOf(':') + 1).split("\\+")[0];
            double[] o = SET_OVR.getOrDefault(set, new double[]{pT[p], pM[p]});
            double et = o[0] - bT[b], em = o[1] - bM[b];
            residBefore += Math.abs(et) + Math.abs(em);
            if (Math.abs(et) > 0.10 || Math.abs(em) > 0.15) nudged++;
            dT[b] = Math.abs(et) > 0.10 ? Math.max(-0.3, Math.min(0.3, et)) : 0;
            dM[b] = Math.abs(em) > 0.15 ? Math.max(-0.3, Math.min(0.3, em)) : 0;
            residAfter += Math.abs(et - dT[b]) + Math.abs(em - dM[b]);
        }
        int[] dIn = new int[N]; int[] q = new int[N]; int qh = 0, qt = 0; Arrays.fill(dIn, Integer.MAX_VALUE);
        for (int i = 0; i < N; i++) { if (bubble[i] < 0) continue; int x = i % W, y = i / W; boolean e = false;
            for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H || bubble[yy * W + xx] != bubble[i]) e = true; }
            if (e) { dIn[i] = 1; q[qt++] = i; } }
        while (qh < qt) { int c = q[qh++]; int x = c % W, y = c / W;
            for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx; if (bubble[n] == bubble[c] && dIn[n] > dIn[c] + 1) { dIn[n] = dIn[c] + 1; q[qt++] = n; } } }
        Tn = T.clone(); Mn = M.clone();
        for (int i = 0; i < N; i++) { int b = bubble[i]; if (b < 0) continue; double f = Math.min(1, dIn[i] / 3.0); Tn[i] += f * dT[b]; Mn[i] += f * dM[b]; }
    }
    static double[] Tn, Mn;

    // ---------- render ----------
    static int climateColor(double t, double m, int i) {
        if (!land[i]) return elev[i] < quantile_sea_deep ? 0x1F6FB2 : 0x35A2E0;
        if (lake[i] || river[i]) return 0x2E86C1;
        if (mountain[i]) return t < 0.25 ? 0xE8EEF2 : 0x8A7F70;
        if (t < 0.15) return 0xF2F6F8;           // snow
        if (t < 0.26) return 0xBFD3C1;           // tundra/taiga
        if (t > 0.74 && m < 0.32) return 0xE6CF96; // desert
        if (t > 0.70 && m > 0.62) return 0x2F8F3A; // jungle
        if (m < 0.30) return 0xB9C46A;           // steppe
        if (m > 0.62) return 0x3E8E41;           // forest
        return 0x6DBA4F;                          // grassland
    }
    static double quantile_sea_deep = -1e9;

    static void render() throws Exception {
        quantile_sea_deep = quantile(elev, water * 0.6);
        int S = 2;
        BufferedImage sub = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage bub = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        BufferedImage rlm = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < N; i++) {
            int x = i % W, y = i / W;
            sub.setRGB(x, y, climateColor(T[i], M[i], i));
            int base = climateColor(Tn[i], Mn[i], i);
            int col = base;
            int b = bubble[i];
            if (b >= 0 && !river[i] && !lake[i]) {
                int pc = Integer.parseInt(PLANES[bubbleInfo.get(b)[1]][6], 16);
                col = mix(base, pc, 0.62);
                boolean edge = false;
                for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H || bubble[yy * W + xx] != b) edge = true; }
                if (edge) col = mix(pc, 0x000000, 0.55);
            }
            int rc = realm[i] >= 0 ? (realmPlane[realm[i]] >= 0 ? Integer.parseInt(PLANES[realmPlane[realm[i]]][6], 16) : 0x9A9A9A) : (land[i] ? 0x606060 : 0x2A5D8F);
            if (realm[i] >= 0) { boolean e = false; for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx >= 0 && yy >= 0 && xx < W && yy < H && realm[yy * W + xx] >= 0 && realm[yy * W + xx] != realm[i]) e = true; } if (e) { rc = 0x202020; } }
            if (river[i] || lake[i]) rc = 0x2E86C1;
            rlm.setRGB(x, y, rc);
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) bub.setRGB(x * S + dx, y * S + dy, col);
        }
        Graphics2D g = bub.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        for (int b = 0; b < bubbleInfo.size(); b++) {
            int s = bubbleInfo.get(b)[2]; int x = (s % W) * S, y = (s / W) * S;
            g.setColor(Color.BLACK); g.fillRect(x - 3, y - 3, 7, 7); g.setColor(Color.WHITE); g.fillRect(x - 2, y - 2, 5, 5);
        }
        // realm labels
        for (int r = 0; r < R; r++) {
            if (realmPlane[r] < 0 || rArea[r] == 0) continue;
            String name = PLANES[realmPlane[r]][0];
            int x = (int) (rCx[r] * W * S), y = (int) (rCy[r] * H * S);
            FontMetrics fm = g.getFontMetrics(); int w = fm.stringWidth(name);
            g.setColor(new Color(0, 0, 0, 150)); g.fillRect(x - w / 2 - 2, y - 11, w + 4, 14);
            g.setColor(Color.WHITE); g.drawString(name, x - w / 2, y);
        }
        g.dispose();
        ImageIO.write(sub, "png", new File(outDir, "substrate_s" + seed + ".png"));
        ImageIO.write(bub, "png", new File(outDir, "bubbles_s" + seed + ".png"));
        ImageIO.write(rlm, "png", new File(outDir, "realms_s" + seed + ".png"));
    }
    static int mix(int a, int b, double t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static void report() {
        int landN = 0, riverN = 0, mtnN = 0, lakeN = 0, bubN = 0;
        for (int i = 0; i < N; i++) { if (land[i]) landN++; if (river[i]) riverN++; if (mountain[i]) mtnN++; if (lake[i]) lakeN++; if (bubble[i] >= 0) bubN++; }
        int B = bubbleInfo.size();
        int[] a = Arrays.copyOf(bArea, B); Arrays.sort(a);
        int nLand = 0; for (int c : compSize) if (c >= MIN_ISLAND) nLand++;
        int assigned = 0; for (int r = 0; r < R; r++) if (realmPlane[r] >= 0) assigned++;
        System.out.printf(Locale.ROOT, "SLIDERS seed=%d size=%d continents=%d islands=%.2f water=%.2f rivers=%.2f age=%.2f coverage=%.2f%n", seed, W, continents, islands, water, riversS, age, coverage);
        System.out.printf(Locale.ROOT, "WORLD land %.1f%%, landmasses>=%d tiles: %d, river tiles %d, lake tiles %d, mountain tiles %d%n", 100.0 * landN / N, MIN_ISLAND, nLand, riverN, lakeN, mtnN);
        System.out.printf(Locale.ROOT, "REALMS %d (assigned %d planes of %d; %d wild), partition area error %.3f%n", R, assigned, P, R - assigned, lastPartitionErr);
        System.out.printf(Locale.ROOT, "BUBBLES %d for %d sets (%d sets share a bubble); bubble tiles %d = %.1f%% of land%n", B, totalSets, mergedSets, bubN, 100.0 * bubN / landN);
        if (B > 0) System.out.printf(Locale.ROOT, "BUBBLE AREA tiles: min %d, p10 %d, median %d, p90 %d, max %d (median ~%dx%d tiles)%n", a[0], a[B / 10], a[B / 2], a[B * 9 / 10], a[B - 1], (int) Math.sqrt(a[B / 2]), (int) Math.sqrt(a[B / 2]));
        System.out.printf(Locale.ROOT, "FIT bubbles needing a climate nudge: %d of %d; mean |dT|+|dM| before %.3f, after %.3f%n", nudged, B, residBefore / Math.max(1, B), residAfter / Math.max(1, B));
        for (int r = 0; r < R; r++) if (realmPlane[r] >= 0) { int p = realmPlane[r];
            System.out.printf(Locale.ROOT, "  %-26s %s area %6.0f  T %.2f/%.2f  M %.2f/%.2f  E %.2f/%.2f  coast %.2f%n", PLANES[p][0], realmIsland[r] ? "isle " : "main ", rArea[r], rT[r], pT[p], rM[r], pM[p], rE[r], pE[p], rC[r]); }
        for (String s : notes) System.out.println("NOTE " + s);
    }

    // =====================================================================================================
    // TERRITORY MODE: every land tile belongs to a set-territory (the conquest unit); the set's themed
    // "bubble" is the territory's core, fading to the climate ground through a margin. Core sets get 2x area
    // and host the AI starts. cohesion=1 keeps a plane's sets inside one plane realm; cohesion=0 scatters
    // sets over the whole world and lets the climate-fit Hungarian decide where each goes.
    // =====================================================================================================
    static String mode = "bubbles"; static double cohesion = 1; static int AIS = 5; static double coreFrac = 0.55;
    static final Set<String> CORE = new HashSet<>(Arrays.asList("LEA LEB 2ED 3ED 4ED 5ED 6ED 7ED 8ED 9ED 10E M10 M11 M12 M13 M14 M15 ORI M19 M20 M21 FDN".split(" ")));
    static int[] terr, tSeed, tPlaneOf; static String[] tSetOf; static double[] tArea, tTm, tMm; static boolean[] tCore; static int TN;
    static int[] aiStarts = new int[0];
    static double tPartErr;

    static double setWeight(int p, String s) { return CORE.contains(s) ? 2.0 : (p == 0 ? 0.8 : 1.0); }

    static void territories(int[][] islandReq) {
        java.util.List<int[]> sets = new ArrayList<>(); // {plane, idx}
        for (int p = 0; p < P; p++) for (int k = 0; k < pSets[p].length; k++) sets.add(new int[]{p, k});
        int S = sets.size();
        int[] group = new int[N]; Arrays.fill(group, -1);
        java.util.List<Integer> seedList = new ArrayList<>(); java.util.List<Integer> seedRealm = new ArrayList<>();
        if (cohesion >= 0.5) {
            realms(islandReq);
            for (int i = 0; i < N; i++) if (realm[i] >= 0 && realmPlane[realm[i]] >= 0) group[i] = realm[i];
            for (int r = 0; r < R; r++) {
                int p = realmPlane[r]; if (p < 0) continue;
                java.util.List<Integer> cand = new ArrayList<>();
                for (int i = 0; i < N; i++) if (realm[i] == r && !river[i] && !mountain[i]) cand.add(i);
                for (int s : poisson(cand, pSets[p].length)) { seedList.add(s); seedRealm.add(r); }
            }
        } else {
            int landTotal = 0; for (int c : compSize) if (c >= 400) landTotal += c;
            for (int i = 0; i < N; i++) if (comp[i] >= 0 && compSize[comp[i]] >= 400) group[i] = comp[i];
            int K = (int) Math.round(S * 1.10);
            for (int c = 0; c < compSize.length; c++) {
                if (compSize[c] < 400) continue;
                int k = Math.max(1, (int) Math.round(K * compSize[c] / (double) landTotal));
                java.util.List<Integer> cand = new ArrayList<>();
                for (int i = 0; i < N; i++) if (comp[i] == c && !river[i] && !mountain[i]) cand.add(i);
                for (int s : poisson(cand, k)) { seedList.add(s); seedRealm.add(-1); }
            }
        }
        TN = seedList.size(); tSeed = seedList.stream().mapToInt(Integer::intValue).toArray();
        double[] targets = new double[TN]; Arrays.fill(targets, 1);
        terr = partition(tSeed, targets, group, 6);
        territoryStats();
        // Hungarian: sets (rows) x territories (cols)
        double[][] cost = new double[S][Math.max(S, TN)];
        double meanA = 0; for (double a : tArea) meanA += a; meanA /= TN;
        for (int a = 0; a < S; a++) {
            int p = sets.get(a)[0]; String s = pSets[p][sets.get(a)[1]];
            double[] o = SET_OVR.getOrDefault(s, new double[]{pT[p], pM[p]});
            for (int b = 0; b < cost[0].length; b++) {
                if (b >= TN) { cost[a][b] = 1e6; continue; }
                double c = 3 * Math.abs(tTm[b] - o[0]) + 2 * Math.abs(tMm[b] - o[1]);
                boolean isl = comp[tSeed[b]] >= 0 && compSize[comp[tSeed[b]]] < 9000;
                if (pIsl[p] >= 1 && !isl) c += 2.0; else if (pIsl[p] > 0 && isl) c -= 0.5 * pIsl[p];
                if (cohesion >= 0.5 && realmPlane[seedRealm.get(b)] != p) c += 1e5;
                double lr = Math.log(Math.max(1, tArea[b]) / (meanA * setWeight(p, s))); c += 0.4 * lr * lr;
                cost[a][b] = c;
            }
        }
        long h0 = System.nanoTime();
        int[] asg = hungarian(cost);
        notes.add(String.format(Locale.ROOT, "Hungarian set->territory %dx%d took %.1f ms", S, cost[0].length, (System.nanoTime() - h0) / 1e6));
        tPlaneOf = new int[TN]; Arrays.fill(tPlaneOf, -1); tSetOf = new String[TN]; tCore = new boolean[TN];
        for (int a = 0; a < S; a++) { int b = asg[a]; if (b >= TN) { notes.add("unplaced set " + pSets[sets.get(a)[0]][sets.get(a)[1]]); continue; }
            tPlaneOf[b] = sets.get(a)[0]; tSetOf[b] = pSets[sets.get(a)[0]][sets.get(a)[1]]; tCore[b] = CORE.contains(tSetOf[b]); }
        for (int b = 0; b < TN; b++) targets[b] = tPlaneOf[b] < 0 ? 1.0 : setWeight(tPlaneOf[b], tSetOf[b]);
        terr = partition(tSeed, targets, group, 10);
        territoryStats();
        // themed cores: budgeted growth from each seed inside its own territory
        bubble = new int[N]; Arrays.fill(bubble, -1);
        Heap hp = new Heap(1 << 16); double[] best = new double[N]; Arrays.fill(best, Double.MAX_VALUE); int[] size = new int[TN];
        for (int b = 0; b < TN; b++) { if (tPlaneOf[b] < 0) continue; best[tSeed[b]] = 0; bubble[tSeed[b]] = b; size[b] = 1; hp.push(tSeed[b], 0); }
        while (hp.size > 0) {
            double d = hp.topKey(); int c = hp.popIdx(); if (d > best[c]) continue; int b = bubble[c];
            if (size[b] >= coreFrac * tArea[b]) continue;
            int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx;
                if (terr[n] != b || bubble[n] >= 0 || isWater(n)) continue;
                double nd = d + DL[k] * (1 + (river[n] ? 25 : 0) + (mountain[n] ? 8 : 0) + 1.2 * (((n * 2654435761L) >>> 7) % 1000) / 1000.0);
                if (nd < best[n] && size[b] < coreFrac * tArea[b]) { best[n] = nd; bubble[n] = b; size[b]++; hp.push(n, nd); } }
        }
        // AI starts: farthest-point sampling over core-set territories
        java.util.List<Integer> cores = new ArrayList<>(); for (int b = 0; b < TN; b++) if (tCore[b]) cores.add(b);
        if (!cores.isEmpty()) {
            java.util.List<Integer> pick = new ArrayList<>(); pick.add(cores.get(rng.nextInt(cores.size())));
            while (pick.size() < Math.min(AIS, cores.size())) { int bestB = -1; double bd = -1;
                for (int b : cores) { if (pick.contains(b)) continue; double md = 1e18; for (int q : pick) md = Math.min(md, Math.hypot(tSeed[b] % W - tSeed[q] % W, tSeed[b] / W - tSeed[q] / W)); if (md > bd) { bd = md; bestB = b; } }
                pick.add(bestB); }
            aiStarts = pick.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    static java.util.List<Integer> poisson(java.util.List<Integer> cand, int k) {
        java.util.List<Integer> acc = new ArrayList<>(); if (cand.isEmpty() || k <= 0) return acc;
        Collections.shuffle(cand, rng);
        double r = 0.9 * Math.sqrt(cand.size() / (double) k);
        while (acc.size() < k && r >= 2) {
            for (int t : cand) { if (acc.size() >= k) break; int x = t % W, y = t / W; boolean ok = true;
                for (int a : acc) if (Math.abs(a % W - x) < r && Math.abs(a / W - y) < r && Math.hypot(a % W - x, a / W - y) < r) { ok = false; break; }
                if (ok && !acc.contains(t)) acc.add(t); }
            r *= 0.85;
        }
        return acc;
    }

    /** Capacity-balanced multi-source Dijkstra (multiplicative weights); a seed only grows inside its own group. */
    static int[] partition(int[] seeds, double[] targets, int[] group, int iters) {
        int K = seeds.length; double[] w = new double[K]; Arrays.fill(w, 1);
        double[] stepCost = new double[N];
        for (int i = 0; i < N; i++) stepCost[i] = (!land[i] || lake[i]) ? -1 : 1 + (river[i] ? 10 : 0) + (mountain[i] ? 6 : 0) + 0.6 * (fbm((i % W) * 0.08, (i / W) * 0.08, 2) + 1);
        double[] best = new double[N]; int[] owner = new int[N]; int[] bestOwner = null; double bestErr = 1e18;
        HashMap<Integer, Double> gT = new HashMap<>(); for (int s = 0; s < K; s++) gT.merge(group[seeds[s]], targets[s], Double::sum);
        Heap hp = new Heap(N * 2);
        for (int it = 0; it < iters; it++) {
            Arrays.fill(best, Double.MAX_VALUE); Arrays.fill(owner, -1);
            for (int s = 0; s < K; s++) { best[seeds[s]] = 0; owner[seeds[s]] = s; hp.push(seeds[s], 0); }
            while (hp.size > 0) { double d = hp.topKey(); int c = hp.popIdx(); if (d > best[c]) continue; int x = c % W, y = c / W;
                for (int k = 0; k < 8; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx;
                    if (stepCost[n] < 0 || group[n] != group[c] || group[n] < 0) continue;
                    double nd = d + DL[k] * 0.5 * (stepCost[c] + stepCost[n]) / w[owner[c]];
                    if (nd < best[n]) { best[n] = nd; owner[n] = owner[c]; hp.push(n, nd); } } }
            double[] area = new double[K]; HashMap<Integer, Double> gA = new HashMap<>();
            for (int i = 0; i < N; i++) if (owner[i] >= 0) { area[owner[i]]++; gA.merge(group[i], 1.0, Double::sum); }
            double err = 0, tot = 0;
            for (int s = 0; s < K; s++) { int g = group[seeds[s]]; double want = targets[s] / gT.get(g) * gA.getOrDefault(g, 1.0); err += Math.abs(area[s] - want); tot += want;
                w[s] *= Math.max(0.75, Math.min(1.35, Math.sqrt(want / Math.max(1, area[s])))); }
            err /= Math.max(1, tot);
            if (err < bestErr) { bestErr = err; bestOwner = owner.clone(); }
        }
        tPartErr = bestErr;
        return bestOwner;
    }

    static void territoryStats() {
        tArea = new double[TN]; tTm = new double[TN]; tMm = new double[TN];
        for (int i = 0; i < N; i++) { int t = terr[i]; if (t < 0) continue; tArea[t]++; tTm[t] += T[i]; tMm[t] += M[i]; }
        for (int t = 0; t < TN; t++) if (tArea[t] > 0) { tTm[t] /= tArea[t]; tMm[t] /= tArea[t]; }
    }

    static void renderTerritories() throws Exception {
        quantile_sea_deep = quantile(elev, water * 0.6);
        int S = 2; BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        double[] dT = new double[TN], dM = new double[TN]; double[] cT = new double[TN], cM = new double[TN], cA = new double[TN];
        for (int i = 0; i < N; i++) { int b = bubble[i]; if (b < 0) continue; cT[b] += T[i]; cM[b] += M[i]; cA[b]++; }
        for (int b = 0; b < TN; b++) { if (tPlaneOf[b] < 0 || cA[b] == 0) continue; int p = tPlaneOf[b]; double[] o = SET_OVR.getOrDefault(tSetOf[b], new double[]{pT[p], pM[p]});
            double et = o[0] - cT[b] / cA[b], em = o[1] - cM[b] / cA[b]; residBefore += Math.abs(et) + Math.abs(em);
            dT[b] = Math.abs(et) > 0.10 ? Math.max(-0.3, Math.min(0.3, et)) : 0; dM[b] = Math.abs(em) > 0.15 ? Math.max(-0.3, Math.min(0.3, em)) : 0;
            if (dT[b] != 0 || dM[b] != 0) nudged++; residAfter += Math.abs(et - dT[b]) + Math.abs(em - dM[b]); }
        for (int i = 0; i < N; i++) {
            int x = i % W, y = i / W; int t = terr[i]; int b = bubble[i];
            double tt = T[i], mm = M[i]; if (b >= 0) { tt += dT[b]; mm += dM[b]; }
            int col = climateColor(tt, mm, i);
            if (t >= 0 && tPlaneOf[t] >= 0 && !river[i] && !lake[i]) {
                int pc = tCore[t] ? 0xF5D547 : Integer.parseInt(PLANES[tPlaneOf[t]][6], 16);
                col = mix(col, pc, b >= 0 ? 0.62 : 0.16);
            }
            boolean edge = false; if (t >= 0) for (int k = 0; k < 4; k++) { int xx = x + DX[k], yy = y + DY[k]; if (xx >= 0 && yy >= 0 && xx < W && yy < H && terr[yy * W + xx] >= 0 && terr[yy * W + xx] != t) edge = true; }
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) img.setRGB(x * S + dx, y * S + dy, col);
            if (edge) img.setRGB(x * S, y * S, 0x111111);
        }
        Graphics2D g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        for (int b = 0; b < TN; b++) { if (tPlaneOf[b] < 0) continue; int s = tSeed[b]; int x = (s % W) * S, y = (s / W) * S;
            g.setColor(Color.BLACK); g.fillRect(x - 3, y - 3, 7, 7); g.setColor(Color.WHITE); g.fillRect(x - 2, y - 2, 5, 5);
            g.setColor(Color.BLACK); g.drawString(tSetOf[b], x + 5, y + 4); }
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        for (int k = 0; k < aiStarts.length; k++) { int s = tSeed[aiStarts[k]]; int x = (s % W) * S, y = (s / W) * S;
            g.setColor(Color.BLACK); g.fillOval(x - 9, y - 9, 18, 18); g.setColor(new Color(220, 40, 40)); g.fillOval(x - 7, y - 7, 14, 14);
            g.setColor(Color.WHITE); g.drawString("AI" + (k + 1), x + 9, y - 6); }
        g.dispose();
        ImageIO.write(img, "png", new File(outDir, "territories_c" + (cohesion >= 0.5 ? 1 : 0) + "_s" + seed + ".png"));
    }

    static void reportTerritories() {
        int landN = 0, terrN = 0, coreN = 0; for (int i = 0; i < N; i++) { if (land[i] && !lake[i]) landN++; if (terr[i] >= 0) terrN++; if (bubble[i] >= 0) coreN++; }
        java.util.List<Double> coreA = new ArrayList<>(), expA = new ArrayList<>(); int wild = 0;
        for (int t = 0; t < TN; t++) { if (tPlaneOf[t] < 0) { wild++; continue; } (tCore[t] ? coreA : expA).add(tArea[t]); }
        Collections.sort(coreA); Collections.sort(expA);
        System.out.printf(Locale.ROOT, "TERRITORIES %d (%d with a set, %d wild) cover %.1f%% of land; themed cores %.1f%% of land; partition err %.3f%n", TN, TN - wild, wild, 100.0 * terrN / landN, 100.0 * coreN / landN, tPartErr);
        if (!expA.isEmpty()) System.out.printf(Locale.ROOT, "  non-core set territory tiles: p10 %.0f median %.0f p90 %.0f (median ~%dx%d)%n", expA.get(expA.size() / 10), expA.get(expA.size() / 2), expA.get(expA.size() * 9 / 10), (int) Math.sqrt(expA.get(expA.size() / 2)), (int) Math.sqrt(expA.get(expA.size() / 2)));
        if (!coreA.isEmpty()) System.out.printf(Locale.ROOT, "  core-set territory tiles: p10 %.0f median %.0f p90 %.0f (n=%d)%n", coreA.get(coreA.size() / 10), coreA.get(coreA.size() / 2), coreA.get(coreA.size() * 9 / 10), coreA.size());
        double md = 1e18; for (int a = 0; a < aiStarts.length; a++) for (int b = a + 1; b < aiStarts.length; b++) md = Math.min(md, Math.hypot(tSeed[aiStarts[a]] % W - tSeed[aiStarts[b]] % W, tSeed[aiStarts[a]] / W - tSeed[aiStarts[b]] / W));
        System.out.printf(Locale.ROOT, "  AI starts %d on core-set territories, min pairwise distance %.0f tiles; climate nudge on %d cores, mean misfit %.3f -> %.3f%n", aiStarts.length, md, nudged, residBefore / Math.max(1, TN - wild), residAfter / Math.max(1, TN - wild));
        for (String s : notes) System.out.println("NOTE " + s);
    }

    // ---------- tiny binary heap (min) keyed by double, values int ----------
    static final class Heap {
        int[] v; double[] k; int size;
        Heap(int cap) { v = new int[cap]; k = new double[cap]; }
        void push(int val, double key) {
            if (size == v.length) { v = Arrays.copyOf(v, size * 2); k = Arrays.copyOf(k, size * 2); }
            int i = size++; v[i] = val; k[i] = key;
            while (i > 0) { int p = (i - 1) >> 1; if (k[p] <= k[i]) break; swap(i, p); i = p; }
        }
        double topKey() { return k[0]; }
        int popIdx() {
            int r = v[0]; size--; v[0] = v[size]; k[0] = k[size]; int i = 0;
            while (true) { int l = 2 * i + 1, rr = l + 1, m = i; if (l < size && k[l] < k[m]) m = l; if (rr < size && k[rr] < k[m]) m = rr; if (m == i) break; swap(i, m); i = m; }
            return r;
        }
        void swap(int a, int b) { int tv = v[a]; v[a] = v[b]; v[b] = tv; double tk = k[a]; k[a] = k[b]; k[b] = tk; }
    }
}
