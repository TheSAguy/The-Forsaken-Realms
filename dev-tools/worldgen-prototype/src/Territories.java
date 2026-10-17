import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * SET TERRITORIES: one per set, a full partition of playable land (capacity-balanced multi-source Dijkstra, rivers and
 * ridges costly, water impassable), core sets at coreWeight x area, an optional reserved player area, theme isles for
 * Universes Beyond planes. Then sets are assigned to territories (Hungarian over climate fit + plane cohesion + noise)
 * and each territory grows a themed CORE blob (coreFrac of its area). Pure Java.
 */
final class Territories {
    static final int NORMAL = 0, CORE = 1, PLAYER = 2, ISLE = 3;
    static float COH_W = Float.parseFloat(System.getProperty("cohW", "1.2")), RND_W = Float.parseFloat(System.getProperty("rndW", "6"));

    final WorldGen g; final Settings s; final SetTable st; final int W, H, N; final float sc;
    // land components
    int[] comp; int[] compSize; boolean[] compPlayable; int nComp; int isletCount, isletTiles;
    // territories
    int T; int[] tid, seed, kind, compOf, hub, area, setOf, islePlane; float[] weight, tT, tM, tE, coastShare, cx, cy;
    int[] coreOf; int[] coreArea; int playerT = -1;
    int[][] adj; int[][] adjBorder;          // neighbour territory ids and shared border length
    float unit, partErr; int partIters;
    // assignment diagnostics
    int[] realmOf; float cohesionScore, meanPlaneSpread; int unplaced; float hungarianMs;

    Territories(WorldGen g) { this.g = g; s = g.s; st = g.sets; W = g.W; H = g.H; N = g.N; sc = g.sc; }

    // =============================================================== stage 1: seeds + partition
    void partitionStage() {
        SplittableRandom r = new SplittableRandom(s.seed * 13 + 5);
        boolean[] land = new boolean[N]; for (int i = 0; i < N; i++) land[i] = g.isLand(i);
        comp = new int[N]; Arrays.fill(comp, -1); int[] q = new int[N];
        ArrayList<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < N; i++) if (land[i] && comp[i] < 0) sizes.add(g.flood(land, true, i, comp, q, sizes.size()));
        nComp = sizes.size(); compSize = new int[nComp]; for (int c = 0; c < nComp; c++) compSize[c] = sizes.get(c);
        // which components carry a planned theme isle
        int[] isleCompPlane = new int[nComp]; Arrays.fill(isleCompPlane, -1);
        for (float[] o : g.isles) { int i = (int) o[2] * W + (int) o[1]; if (comp[i] >= 0) isleCompPlane[comp[i]] = (int) o[0]; }
        // slots: what each seed will host
        ArrayList<int[]> slots = new ArrayList<>();   // {kind, plane(for ISLE), comp(-1 = unallocated)}
        int nCore = 0, nNormal = 0;
        boolean[] planeHasIsle = new boolean[st.planes.size()];
        for (int c = 0; c < nComp; c++) if (isleCompPlane[c] >= 0) planeHasIsle[isleCompPlane[c]] = true;
        for (SetTable.SetDef d : st.sets) {
            if (d.core) { slots.add(new int[]{CORE, -1, -1}); nCore++; }
            else if (planeHasIsle[d.plane]) slots.add(new int[]{ISLE, d.plane, -1});
            else { slots.add(new int[]{NORMAL, -1, -1}); nNormal++; }
        }
        if (s.playerArea) slots.add(new int[]{PLAYER, -1, -1});
        // unit area: playable non-isle land / non-isle weight (two passes so the islet cut-off uses the final unit)
        float wNon = nNormal + s.coreWeight * (nCore + (s.playerArea ? 1 : 0));
        compPlayable = new boolean[nComp];
        long landNon = 0; for (int c = 0; c < nComp; c++) if (isleCompPlane[c] < 0) landNon += compSize[c];
        unit = landNon / wNon;
        for (int pass = 0; pass < 3; pass++) {
            long L = 0; for (int c = 0; c < nComp; c++) if (isleCompPlane[c] < 0 && compSize[c] >= 0.45f * unit) L += compSize[c];
            unit = L / wNon;
        }
        for (int c = 0; c < nComp; c++) compPlayable[c] = isleCompPlane[c] >= 0 || compSize[c] >= 0.45f * unit;
        // core + player slots go to big landmasses, proportional to area (largest remainder)
        ArrayList<Integer> big = new ArrayList<>();
        for (int c = 0; c < nComp; c++) if (isleCompPlane[c] < 0 && compSize[c] >= 6 * unit) big.add(c);
        if (big.isEmpty()) { int bc = -1; for (int c = 0; c < nComp; c++) if (isleCompPlane[c] < 0 && (bc < 0 || compSize[c] > compSize[bc])) bc = c; big.add(bc); }
        int nCP = nCore + (s.playerArea ? 1 : 0);
        int[] cpPer = largestRemainder(big, nCP);
        int[] coreIn = new int[nComp]; for (int k = 0; k < big.size(); k++) coreIn[big.get(k)] = cpPer[k];
        // normal slots per playable non-isle component
        ArrayList<Integer> play = new ArrayList<>();
        for (int c = 0; c < nComp; c++) if (compPlayable[c] && isleCompPlane[c] < 0) play.add(c);
        float[] want = new float[play.size()]; float tot = 0;
        for (int k = 0; k < play.size(); k++) { int c = play.get(k); want[k] = Math.max(1f, compSize[c] / unit - s.coreWeight * coreIn[c]); tot += want[k]; }
        int[] nIn = new int[nComp];
        int[] base = new int[play.size()]; int sum = 0;
        for (int k = 0; k < want.length; k++) { base[k] = Math.max(1, (int) Math.floor(want[k] * nNormal / tot)); sum += base[k]; }
        while (sum < nNormal) { int bk = 0; float bf = -1e9f; for (int k = 0; k < want.length; k++) { float f = want[k] * nNormal / tot - base[k]; if (f > bf) { bf = f; bk = k; } } base[bk]++; sum++; }
        while (sum > nNormal) { int bk = -1; float bf = 1e9f; for (int k = 0; k < want.length; k++) { if (base[k] <= 1) continue; float f = want[k] * nNormal / tot - base[k]; if (f < bf) { bf = f; bk = k; } } if (bk < 0) break; base[bk]--; sum--; }
        for (int k = 0; k < play.size(); k++) nIn[play.get(k)] = base[k];
        // build the seed list: per component, cores/player first (global farthest point), then normal (best candidate)
        ArrayList<int[]> seedL = new ArrayList<>();   // {tile, kind, plane, comp}
        ArrayList<Integer> cpSeeds = new ArrayList<>();
        int[][] compCells = cellsByComp();
        // cores + player: global farthest-point over all big components, temperate land preferred
        ArrayList<Integer> cpComps = new ArrayList<>();
        for (int c : big) for (int k = 0; k < coreIn[c]; k++) cpComps.add(c);
        for (int c : cpComps) {
            int[] cells = compCells[c]; int bestT = -1; float bestS = -1;
            for (int t = 0; t < 400; t++) {
                int i = cells[r.nextInt(cells.length)]; if (!seedable(i, true)) continue;
                float md = 1e9f; for (int o : cpSeeds) md = Math.min(md, WorldGen.dist(i % W, i / W, o % W, o / W));
                float sc2 = md * (temperate(i) ? 1f : 0.75f) * (g.coastDist[i] > 8 * sc ? 1f : 0.85f);
                if (sc2 > bestS) { bestS = sc2; bestT = i; }
            }
            if (bestT < 0) bestT = cells[cells.length / 2];
            cpSeeds.add(bestT); seedL.add(new int[]{bestT, CORE, -1, c});
        }
        // the player slot = the core-type seed with the best spacing from the others (it is placed like a core set)
        // (kinds are relabelled below; which one is the player is decided after AI starts are known? no: reserve now, fairly)
        if (s.playerArea && !seedL.isEmpty()) {
            int bestK = 0; float bd = -1;
            for (int k = 0; k < seedL.size(); k++) { int i = seedL.get(k)[0]; float md = 1e9f;
                for (int m = 0; m < seedL.size(); m++) if (m != k) { int o = seedL.get(m)[0]; md = Math.min(md, WorldGen.dist(i % W, i / W, o % W, o / W)); }
                float cen = Math.max(0, 1 - 1.6f * WorldGen.dist(i % W, i / W, W / 2f, H / 2f) / W);   // player: central, temperate, still spaced
                float v = (0.35f * md / W * 4 + 0.65f * cen) * (temperate(i) ? 1f : 0.6f);
                if (v > bd) { bd = v; bestK = k; } }
            seedL.get(bestK)[1] = PLAYER;
        }
        for (int c = 0; c < nComp; c++) {
            int n = nIn[c]; int isleP = isleCompPlane[c];
            if (isleP >= 0) { n = 0; for (int[] sl : slots) if (sl[0] == ISLE && sl[1] == isleP) n++; }
            if (n == 0) continue;
            int[] cells = compCells[c]; ArrayList<Integer> mine = new ArrayList<>();
            for (int[] sd : seedL) if (sd[3] == c) mine.add(sd[0]);
            for (int k = 0; k < n; k++) {
                int bestT = -1; float bestS = -1;
                for (int t = 0; t < 40; t++) {
                    int i = cells[r.nextInt(cells.length)]; if (!seedable(i, cells.length > 200)) continue;
                    float md = 1e9f; for (int o : mine) md = Math.min(md, WorldGen.dist(i % W, i / W, o % W, o / W));
                    if (md > bestS) { bestS = md; bestT = i; }
                }
                if (bestT < 0) bestT = cells[r.nextInt(cells.length)];
                mine.add(bestT); seedL.add(new int[]{bestT, isleP >= 0 ? ISLE : NORMAL, isleP, c});
            }
        }
        T = seedL.size(); seed = new int[T]; kind = new int[T]; compOf = new int[T]; islePlane = new int[T]; weight = new float[T];
        for (int t = 0; t < T; t++) {
            int[] sd = seedL.get(t); seed[t] = sd[0]; kind[t] = sd[1]; islePlane[t] = sd[2]; compOf[t] = sd[3];
            weight[t] = (kind[t] == CORE || kind[t] == PLAYER) ? s.coreWeight : 1f;
            if (kind[t] == PLAYER) playerT = t;
        }
        isletCount = 0; isletTiles = 0;
        for (int c = 0; c < nComp; c++) if (!compPlayable[c]) { isletCount++; isletTiles += compSize[c]; }
        // capacity-balanced partition, then one Lloyd step (normal seeds to their centroid) and a re-balance
        tid = partition(Integer.getInteger("p1", 3));
        lloyd(); tid = partition(Integer.getInteger("p2", 6));
        fixFragments();
        stats();
        g.log("Territories", String.format(java.util.Locale.US, "%d territories (%d core, %d player, %d isle) on %d playable landmasses; unit %.0f tiles; %d islets (%d tiles) left wild; partition err %.3f after %d passes",
                T, count(CORE), count(PLAYER), count(ISLE), countPlayable(), unit, isletCount, isletTiles, partErr, partIters));
    }

    int count(int k) { int n = 0; for (int t = 0; t < T; t++) if (kind[t] == k) n++; return n; }
    int countPlayable() { int n = 0; for (boolean b : compPlayable) if (b) n++; return n; }

    boolean seedable(int i, boolean awayFromCoast) {
        byte c = g.cls[i];
        return c != WorldGen.MOUNTAIN && g.river[i] == 0 && !g.lake[i] && (!awayFromCoast || g.coastDist[i] >= 3);
    }
    boolean temperate(int i) { byte c = g.cls[i]; return c == WorldGen.GRASS || c == WorldGen.FOREST || c == WorldGen.HILLS || c == WorldGen.BEACH; }

    /** Split n items over components proportional to their land area (largest remainder). */
    int[] largestRemainder(List<Integer> comps, int n) {
        long tot = 0; for (int c : comps) tot += compSize[c];
        int[] out = new int[comps.size()]; int sum = 0; float[] frac = new float[comps.size()];
        for (int k = 0; k < out.length; k++) { float e = n * compSize[comps.get(k)] / (float) tot; out[k] = (int) e; frac[k] = e - out[k]; sum += out[k]; }
        while (sum < n) { int bk = 0; for (int k = 1; k < out.length; k++) if (frac[k] > frac[bk]) bk = k; out[bk]++; frac[bk] = -1; sum++; }
        return out;
    }

    int[][] cellsByComp() {
        int[] cnt = new int[nComp]; for (int i = 0; i < N; i++) if (comp[i] >= 0) cnt[comp[i]]++;
        int[][] out = new int[nComp][]; for (int c = 0; c < nComp; c++) out[c] = new int[cnt[c]];
        int[] fill = new int[nComp]; for (int i = 0; i < N; i++) if (comp[i] >= 0) out[comp[i]][fill[comp[i]]++] = i;
        return out;
    }

    float[] stepCost;

    /** Capacity-balanced multi-source Dijkstra (multiplicative weights); a seed only grows inside its own landmass. */
    int[] partition(int iters) {
        if (stepCost == null) {
            stepCost = new float[N]; Noise n = new Noise(s.seed + 71);
            for (int i = 0; i < N; i++) {
                if (comp[i] < 0 || !compPlayable[comp[i]]) { stepCost[i] = -1; continue; }
                byte c = g.cls[i];
                stepCost[i] = 1 + (g.river[i] > 0 ? 10 : 0) + (c == WorldGen.MOUNTAIN ? 6 : c == WorldGen.HILLS ? 1 : 0)
                        + 0.6f * (n.fbm(i % W * 0.08f / sc, i / W * 0.08f / sc, 2, 0.5f) + 1);
            }
        }
        float[] w = new float[T]; Arrays.fill(w, 1);
        float[] best = new float[N]; int[] owner = new int[N]; int[] bestOwner = null; float bestErr = 1e18f;
        float[] cw = new float[nComp]; for (int t = 0; t < T; t++) cw[compOf[t]] += weight[t];
        Heap hp = new Heap(N / 4); int it;
        for (it = 0; it < iters; it++) {
            Arrays.fill(best, Float.MAX_VALUE); Arrays.fill(owner, -1); hp.clear();
            for (int t = 0; t < T; t++) { best[seed[t]] = 0; owner[seed[t]] = t; hp.push(seed[t], 0); }
            while (hp.size > 0) {
                float d = hp.topKey(); int c = hp.pop(); if (d > best[c]) continue; int x = c % W, y = c / W; float wc = w[owner[c]];
                for (int k = 0; k < 8; k++) {
                    int xx = x + WorldGen.DX[k], yy = y + WorldGen.DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx;
                    if (stepCost[n] < 0) continue;
                    if (k >= 4 && (stepCost[y * W + xx] < 0 || stepCost[yy * W + x] < 0)) continue;   // no corner cutting across water
                    float nd = d + WorldGen.DD[k] * 0.5f * (stepCost[c] + stepCost[n]) / wc;
                    if (nd < best[n]) { best[n] = nd; owner[n] = owner[c]; hp.push(n, nd); }
                }
            }
            float[] ar = new float[T]; float[] ca = new float[nComp];
            for (int i = 0; i < N; i++) if (owner[i] >= 0) { ar[owner[i]]++; ca[comp[i]]++; }
            float err = 0, totW = 0;
            for (int t = 0; t < T; t++) {
                float wantA = weight[t] / cw[compOf[t]] * ca[compOf[t]];
                err += Math.abs(ar[t] - wantA); totW += wantA;
                w[t] *= (float) Math.max(0.75, Math.min(1.35, Math.sqrt(wantA / Math.max(1, ar[t]))));
            }
            err /= Math.max(1, totW);
            if (err < bestErr) { bestErr = err; bestOwner = owner.clone(); }
            if (err < 0.03f) { it++; break; }
        }
        partErr = bestErr; partIters = it;
        return bestOwner;
    }

    /** Move NORMAL/ISLE seeds to the land tile nearest their territory's centroid (one Lloyd step): rounder shapes. */
    void lloyd() {
        double[] sx = new double[T], sy = new double[T]; int[] n = new int[T];
        for (int i = 0; i < N; i++) { int t = tid[i]; if (t < 0) continue; sx[t] += i % W; sy[t] += i / W; n[t]++; }
        int[] bestI = new int[T]; float[] bd = new float[T]; Arrays.fill(bd, Float.MAX_VALUE); Arrays.fill(bestI, -1);
        for (int i = 0; i < N; i++) {
            int t = tid[i]; if (t < 0 || kind[t] == CORE || kind[t] == PLAYER || n[t] == 0 || !seedable(i, false)) continue;
            float d = WorldGen.dist(i % W, i / W, (float) (sx[t] / n[t]), (float) (sy[t] / n[t]));
            if (d < bd[t]) { bd[t] = d; bestI[t] = i; }
        }
        for (int t = 0; t < T; t++) if (bestI[t] >= 0) seed[t] = bestI[t];
    }

    int nb(int i, int d) { int x = i % W + WorldGen.DX[d], y = i / W + WorldGen.DY[d]; return x < 0 || y < 0 || x >= W || y >= H ? -1 : y * W + x; }

    /** Each territory keeps its largest 4-connected piece; detached pieces join the neighbour with the longest shared border. */
    void fixFragments() {
        int[] lab = new int[N], q = new int[N];
        for (int round = 0; round < 6; round++) {
            Arrays.fill(lab, -1); ArrayList<int[]> regions = new ArrayList<>();
            int[] largest = new int[T], largestA = new int[T]; Arrays.fill(largest, -1);
            for (int i = 0; i < N; i++) {
                if (tid[i] < 0 || lab[i] >= 0) continue; int b = tid[i], n = 0; q[n++] = i; lab[i] = regions.size();
                for (int h = 0; h < n; h++) { int c = q[h]; for (int d = 0; d < 4; d++) { int m = nb(c, d); if (m >= 0 && tid[m] == b && lab[m] < 0) { lab[m] = lab[i]; q[n++] = m; } } }
                regions.add(Arrays.copyOf(q, n)); if (n > largestA[b]) { largestA[b] = n; largest[b] = regions.size() - 1; }
            }
            boolean changed = false;
            for (int k = 0; k < regions.size(); k++) {
                int[] cells = regions.get(k); int b = tid[cells[0]]; if (largest[b] == k) continue;
                int[] border = new int[T]; int bestO = -1;
                for (int c : cells) for (int d = 0; d < 4; d++) { int m = nb(c, d); if (m >= 0 && tid[m] >= 0 && tid[m] != b) border[tid[m]]++; }
                for (int o = 0; o < T; o++) if (border[o] > 0 && (bestO < 0 || border[o] > border[bestO])) bestO = o;
                if (bestO < 0) continue; for (int c : cells) tid[c] = bestO; changed = true;
            }
            if (!changed) break;
        }
    }

    /** Areas, hubs (most interior walkable tile), centroids, climate means, adjacency. */
    void stats() {
        area = new int[T]; cx = new float[T]; cy = new float[T]; tT = new float[T]; tM = new float[T]; tE = new float[T]; coastShare = new float[T];
        for (int i = 0; i < N; i++) {
            int t = tid[i]; if (t < 0) continue; area[t]++; cx[t] += i % W; cy[t] += i / W; tT[t] += g.temp[i]; tM[t] += g.moist[i];
            byte c = g.cls[i]; tE[t] += c == WorldGen.MOUNTAIN ? 1 : c == WorldGen.HILLS ? 0.5f : 0; if (g.coastDist[i] <= 4) coastShare[t]++;
        }
        for (int t = 0; t < T; t++) { float a = Math.max(1, area[t]); cx[t] /= a; cy[t] /= a; tT[t] /= a; tM[t] /= a; tE[t] /= a; coastShare[t] = Math.min(1, 2 * coastShare[t] / a); }
        rankNorm(tT); rankNorm(tM);
        int[] dt = new int[N], q = new int[N]; int qt = 0;
        for (int i = 0; i < N; i++) {
            dt[i] = Integer.MAX_VALUE; if (tid[i] < 0) continue;
            for (int d = 0; d < 4; d++) { int m = nb(i, d); if (m < 0 || tid[m] != tid[i]) { dt[i] = 0; q[qt++] = i; break; } }
        }
        for (int h = 0; h < qt; h++) { int c = q[h]; for (int d = 0; d < 4; d++) { int m = nb(c, d); if (m >= 0 && tid[m] == tid[c] && dt[m] == Integer.MAX_VALUE) { dt[m] = dt[c] + 1; q[qt++] = m; } } }
        hub = new int[T]; Arrays.fill(hub, -1); float[] bs = new float[T]; Arrays.fill(bs, -1);
        for (int i = 0; i < N; i++) {
            int t = tid[i]; if (t < 0 || !seedable(i, false)) continue;
            float sc2 = dt[i] - 0.02f * WorldGen.dist(i % W, i / W, cx[t], cy[t]);    // interior, then nearest the centroid
            if (sc2 > bs[t]) { bs[t] = sc2; hub[t] = i; }
        }
        for (int t = 0; t < T; t++) if (hub[t] < 0) hub[t] = seed[t];
        int[] border = new int[T * T];
        for (int i = 0; i < N; i++) {
            int a = tid[i]; if (a < 0) continue;
            if (i % W < W - 1) { int b = tid[i + 1]; if (b >= 0 && b != a) { border[a * T + b]++; border[b * T + a]++; } }
            if (i / W < H - 1) { int b = tid[i + W]; if (b >= 0 && b != a) { border[a * T + b]++; border[b * T + a]++; } }
        }
        adj = new int[T][]; adjBorder = new int[T][];
        for (int a = 0; a < T; a++) {
            int n = 0; for (int b = 0; b < T; b++) if (border[a * T + b] >= 3) n++;
            adj[a] = new int[n]; adjBorder[a] = new int[n]; n = 0;
            for (int b = 0; b < T; b++) if (border[a * T + b] >= 3) { adj[a][n] = b; adjBorder[a][n++] = border[a * T + b]; }
        }
    }

    static void rankNorm(float[] a) {
        Integer[] idx = new Integer[a.length]; for (int i = 0; i < a.length; i++) idx[i] = i;
        Arrays.sort(idx, (p, q) -> Float.compare(a[p], a[q]));
        float[] o = new float[a.length]; for (int k = 0; k < a.length; k++) o[idx[k]] = a.length > 1 ? k / (float) (a.length - 1) : 0.5f;
        System.arraycopy(o, 0, a, 0, a.length);
    }

    // =============================================================== stage 2: sets -> territories, plane realms, cores
    void assignStage() {
        int S = st.sets.size(), P = st.planes.size();
        setOf = new int[T]; Arrays.fill(setOf, -1);
        realmOf = new int[T]; Arrays.fill(realmOf, -1);
        float diam = (float) Math.sqrt(unit);
        // ---- plane realms on the NORMAL territory graph (used by the cohesion term) ----
        int[] normalSetsPerPlane = new int[P]; ArrayList<Integer> rowsN = new ArrayList<>(), rowsC = new ArrayList<>();
        ArrayList<Integer>[] rowsI = new ArrayList[P];
        for (int k = 0; k < S; k++) {
            SetTable.SetDef d = st.sets.get(k);
            if (d.core) rowsC.add(k);
            else if (hasIsle(d.plane)) { if (rowsI[d.plane] == null) rowsI[d.plane] = new ArrayList<>(); rowsI[d.plane].add(k); }
            else { rowsN.add(k); normalSetsPerPlane[d.plane]++; }
        }
        ArrayList<Integer> colsN = new ArrayList<>(), colsC = new ArrayList<>();
        for (int t = 0; t < T; t++) { if (kind[t] == NORMAL) colsN.add(t); else if (kind[t] == CORE) colsC.add(t); }
        SplittableRandom r = new SplittableRandom(s.seed * 29 + 3);
        if (s.cohesion > 0) growRealms(normalSetsPerPlane, r);
        // distance (in territory diameters) from each territory to each plane's realm
        float[][] dRealm = new float[P][T];
        for (int p = 0; p < P; p++) for (int t = 0; t < T; t++) {
            float m = 1e9f; for (int u = 0; u < T; u++) if (realmOf[u] == p) m = Math.min(m, WorldGen.dist(cx[t], cy[t], cx[u], cy[u]));
            dRealm[p][t] = m >= 1e9f ? 0 : Math.max(0, m / diam - 0.5f);
        }
        long h0 = System.nanoTime();
        assign(rowsN, colsN, dRealm, r);
        assign(rowsC, colsC, dRealm, r);
        for (int p = 0; p < P; p++) if (rowsI[p] != null) {
            ArrayList<Integer> cols = new ArrayList<>(); for (int t = 0; t < T; t++) if (kind[t] == ISLE && islePlane[t] == p) cols.add(t);
            assign(rowsI[p], cols, dRealm, r);
        }
        hungarianMs = (System.nanoTime() - h0) / 1e6f;
        cores();
        cohesionMetrics();
        int wild = 0; for (int t = 0; t < T; t++) if (setOf[t] < 0 && kind[t] != PLAYER) wild++;
        g.log("Sets", String.format(java.util.Locale.US, "assigned %d sets (%d unplaced, %d wild territories); Hungarian %.1f ms; cohesion %.2f -> same-plane-neighbour share %.2f, mean plane spread %.3f of map",
                S - unplaced, unplaced, wild, hungarianMs, s.cohesion, cohesionScore, meanPlaneSpread));
    }

    boolean hasIsle(int plane) { for (float[] o : g.isles) if ((int) o[0] == plane) for (int t = 0; t < T; t++) if (kind[t] == ISLE && islePlane[t] == plane) return true; return false; }

    float setCost(int k, int t, float[][] dRealm, SplittableRandom r) {
        SetTable.SetDef d = st.sets.get(k); SetTable.Plane p = st.planes.get(d.plane);
        float c = 3 * Math.abs(tT[t] - d.T) + 2 * Math.abs(tM[t] - d.M) + 1.2f * Math.abs(Math.min(1, tE[t] * 3) - p.E) + 0.6f * Math.abs(coastShare[t] - p.coast);
        boolean small = compSize[compOf[t]] < 9000 * sc * sc;
        if (p.islandPref > 0 && small) c -= 0.6f * p.islandPref; else if (p.islandPref >= 1 && !small) c += 1.5f;
        float co = s.cohesion;
        if (!p.exempt) c += COH_W * co * co * Math.min(3f, dRealm[d.plane][t]);
        c += RND_W * (1 - co) * (float) r.nextDouble();            // "randomly spread at the start"
        return c;
    }

    void assign(List<Integer> rows, List<Integer> cols, float[][] dRealm, SplittableRandom r) {
        if (rows.isEmpty()) return;
        if (cols.isEmpty()) { unplaced += rows.size(); return; }
        int n = rows.size(), m = Math.max(n, cols.size());
        double[][] a = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) a[i][j] = j < cols.size() ? setCost(rows.get(i), cols.get(j), dRealm, r) : 1e6;
        int[] as = hungarian(a);
        for (int i = 0; i < n; i++) { if (as[i] >= cols.size()) { unplaced++; continue; } setOf[cols.get(as[i])] = rows.get(i); }
    }

    /** Plane realms grown on the territory graph, largest plane first: anchor by climate fit, then best-fit frontier growth. */
    void growRealms(int[] need, SplittableRandom r) {
        int P = need.length; Integer[] order = new Integer[P]; for (int p = 0; p < P; p++) order[p] = p;
        Arrays.sort(order, (a, b) -> need[b] - need[a]);
        float diam = (float) Math.sqrt(unit);
        for (int p : order) {
            SetTable.Plane pl = st.planes.get(p); if (need[p] == 0 || pl.exempt) continue;
            int anchor = -1; float best = 1e9f;
            for (int t = 0; t < T; t++) {
                if (kind[t] != NORMAL || realmOf[t] >= 0) continue;
                float c = 3 * Math.abs(tT[t] - pl.T) + 2 * Math.abs(tM[t] - pl.M) + 0.5f * (float) r.nextDouble();
                int claimedNb = 0, freeNb = 0; for (int u : adj[t]) { if (realmOf[u] >= 0) claimedNb++; else if (kind[u] == NORMAL) freeNb++; }
                c += 0.3f * claimedNb; if (need[p] > 1 && freeNb == 0) c += 3;
                if (c < best) { best = c; anchor = t; }
            }
            if (anchor < 0) continue;
            int got = 0; realmOf[anchor] = p; got++;
            while (got < need[p]) {
                int pick = -1; float pb = 1e9f;
                for (int t = 0; t < T; t++) {
                    if (realmOf[t] != p) continue;
                    for (int u : adj[t]) {
                        if (kind[u] != NORMAL || realmOf[u] >= 0) continue;
                        float c = 3 * Math.abs(tT[u] - pl.T) + 2 * Math.abs(tM[u] - pl.M) + 0.6f * WorldGen.dist(cx[u], cy[u], cx[anchor], cy[anchor]) / diam;
                        if (c < pb) { pb = c; pick = u; }
                    }
                }
                if (pick < 0) {   // boxed in: jump to the nearest free territory
                    for (int t = 0; t < T; t++) if (kind[t] == NORMAL && realmOf[t] < 0) {
                        float c = WorldGen.dist(cx[t], cy[t], cx[anchor], cy[anchor]); if (c < pb) { pb = c; pick = t; } }
                }
                if (pick < 0) break;
                realmOf[pick] = p; got++;
            }
        }
    }

    /** Themed core: budgeted growth from the hub inside the territory; rivers and ridges stop it; urban planes grow square. */
    void cores() {
        coreOf = new int[N]; Arrays.fill(coreOf, -1); coreArea = new int[T];
        float[] best = new float[N]; Arrays.fill(best, Float.MAX_VALUE); Heap hp = new Heap(1 << 16);
        boolean[] square = new boolean[T];
        for (int t = 0; t < T; t++) {
            square[t] = setOf[t] >= 0 && st.planes.get(st.sets.get(setOf[t]).plane).square;
            if (setOf[t] < 0 && kind[t] != PLAYER) continue;
            int h = hub[t]; best[h] = 0; coreOf[h] = t; coreArea[t] = 1; hp.push(h, 0);
        }
        while (hp.size > 0) {
            float d = hp.topKey(); int c = hp.pop(); if (d > best[c]) continue; int t = coreOf[c];
            if (coreArea[t] >= s.coreFrac * area[t]) continue;
            int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) {
                int xx = x + WorldGen.DX[k], yy = y + WorldGen.DY[k]; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue; int n = yy * W + xx;
                if (tid[n] != t || coreOf[n] >= 0) continue;
                float step = (square[t] ? 1f : WorldGen.DD[k]) * (1 + (g.river[n] > 0 ? 25 : 0) + (g.cls[n] == WorldGen.MOUNTAIN ? 8 : 0)
                        + (square[t] ? 0.05f : 1.2f) * ((int) ((n * 2654435761L) >>> 7) % 1000 + 1000) % 1000 / 1000f);
                float nd = d + step;
                if (nd < best[n] && coreArea[t] < s.coreFrac * area[t]) { best[n] = nd; coreOf[n] = t; coreArea[t]++; hp.push(n, nd); }
            }
        }
    }

    void cohesionMetrics() {
        int P = st.planes.size(); int n = 0, ok = 0; double spread = 0; int np = 0;
        for (int t = 0; t < T; t++) {
            if (setOf[t] < 0) continue; int p = st.sets.get(setOf[t]).plane; SetTable.Plane pl = st.planes.get(p);
            if (pl.exempt || pl.nSets < 2) continue; n++;
            for (int u : adj[t]) if (setOf[u] >= 0 && st.sets.get(setOf[u]).plane == p) { ok++; break; }
        }
        cohesionScore = n == 0 ? 0 : ok / (float) n;
        for (int p = 0; p < P; p++) {
            SetTable.Plane pl = st.planes.get(p); if (pl.exempt || pl.nSets < 2) continue;
            float mx = 0, my = 0; int k = 0;
            for (int t = 0; t < T; t++) if (setOf[t] >= 0 && st.sets.get(setOf[t]).plane == p) { mx += cx[t]; my += cy[t]; k++; }
            if (k < 2) continue; mx /= k; my /= k; float d = 0;
            for (int t = 0; t < T; t++) if (setOf[t] >= 0 && st.sets.get(setOf[t]).plane == p) d += WorldGen.dist(cx[t], cy[t], mx, my);
            spread += d / k / W; np++;
        }
        meanPlaneSpread = np == 0 ? 0 : (float) (spread / np);
    }

    // ---------- Hungarian (rows <= cols), O(n^2 m), from prototype B ----------
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

    String label(int t) {
        if (kind[t] == PLAYER) return "PLAYER";
        if (setOf[t] < 0) return "wild";
        return st.sets.get(setOf[t]).code;
    }
}
