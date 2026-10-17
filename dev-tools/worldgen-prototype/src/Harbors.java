import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/**
 * HARBORS AND BOATS. Docks on coasts (at least one per playable landmass, extras by the harbors slider, preferring
 * sheltered bays near territory hubs), then ferry routes: a geodesic sea "Delaunay" graph from a multi-source Dijkstra
 * over water, a minimum spanning tree over landmasses, and Urquhart-style shortcut lanes. Pure Java.
 */
final class Harbors {
    final WorldGen g; final Territories tr; final int W, H, N; final float sc;
    final ArrayList<int[]> docks = new ArrayList<>();     // {landTile, berthTile, territory, component}
    final ArrayList<float[]> dockScore = new ArrayList<>(); // {score, shelter, hubDist}
    final ArrayList<int[]> ferries = new ArrayList<>();   // {dockA, dockB, length, kind 0=MST 1=extra}
    final ArrayList<int[]> paths = new ArrayList<>();     // sea path tiles per ferry
    int[] waterBody; int extra, linkRepairs, deadDocks, compsWithDock; int candEdges, urquhartEdges, extraEligible; boolean allLandmassesLinked; int linkedComps, playableComps;
    boolean[] isDock;

    Harbors(WorldGen g) { this.g = g; tr = g.terr; W = g.W; H = g.H; N = g.N; sc = g.sc; }

    void build() {
        // water bodies (open ocean vs inland seas)
        boolean[] oc = g.ocean; waterBody = new int[N]; Arrays.fill(waterBody, -1); int[] q = new int[N];
        ArrayList<Integer> bodySize = new ArrayList<>();
        for (int i = 0; i < N; i++) if (oc[i] && waterBody[i] < 0) bodySize.add(g.flood(oc, true, i, waterBody, q, bodySize.size()));
        // summed-area table of land for the shelter score
        int[] sat = new int[(W + 1) * (H + 1)];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            sat[(y + 1) * (W + 1) + x + 1] = (oc[y * W + x] ? 0 : 1) + sat[y * (W + 1) + x + 1] + sat[(y + 1) * (W + 1) + x] - sat[y * (W + 1) + x];
        int R = Math.max(4, Math.round(8 * sc));
        // best dock candidate per (territory, water body): {score, tile, berth, shelter, territory, body}
        int T = tr.T; int nb = bodySize.size();
        HashMap<Long, float[]> cand = new HashMap<>();
        for (int i = 0; i < N; i++) {
            int t = tr.tid[i]; if (t < 0 || g.river[i] > 0 || g.cls[i] == WorldGen.MOUNTAIN || g.lake[i]) continue;
            int x = i % W, y = i / W; int berth = -1;
            for (int d = 0; d < 4; d++) { int nx = x + WorldGen.DX[d], ny = y + WorldGen.DY[d]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int n = ny * W + nx; if (oc[n] && bodySize.get(waterBody[n]) > 400 * sc * sc) { berth = n; break; } }
            if (berth < 0) continue;
            int bx = berth % W, by = berth / W, x0 = Math.max(0, bx - R), x1 = Math.min(W, bx + R + 1), y0 = Math.max(0, by - R), y1 = Math.min(H, by + R + 1);
            int landN = sat[y1 * (W + 1) + x1] - sat[y0 * (W + 1) + x1] - sat[y1 * (W + 1) + x0] + sat[y0 * (W + 1) + x0];
            float shelter = landN / (float) ((x1 - x0) * (y1 - y0));
            float bay = Math.max(0, 1 - Math.abs(shelter - 0.55f) / 0.45f);
            float hd = WorldGen.dist(x, y, tr.hub[t] % W, tr.hub[t] / W);
            float hubClose = (float) Math.exp(-hd / (30 * sc));
            byte c = g.cls[i]; float flat = c == WorldGen.BEACH || c == WorldGen.GRASS ? 1 : c == WorldGen.HILLS || c == WorldGen.SWAMP ? 0.2f : 0.6f;
            float sc2 = 0.45f * bay + 0.40f * hubClose + 0.15f * flat;
            long key = (long) t * nb + waterBody[berth]; float[] e = cand.get(key);
            if (e == null || sc2 > e[0]) cand.put(key, new float[]{sc2, i, berth, shelter, t, waterBody[berth]});
        }
        float[][] bestT = new float[T][];
        for (float[] e : cand.values()) { int t = (int) e[4]; if (bestT[t] == null || e[0] > bestT[t][0]) bestT[t] = e; }
        isDock = new boolean[N];
        // 1) one dock per playable landmass (incl. every playable island): its best candidate, core/player favoured
        boolean[] compHas = new boolean[tr.nComp];
        for (int c = 0; c < tr.nComp; c++) {
            if (!tr.compPlayable[c]) continue; playableComps++;
            int bt = -1; float bs = -1e9f;
            for (int t = 0; t < T; t++) if (tr.compOf[t] == c && bestT[t] != null) {
                float v = bestT[t][0] + (tr.kind[t] == Territories.CORE || tr.kind[t] == Territories.PLAYER ? 0.15f : 0);
                if (v > bs) { bs = v; bt = t; } }
            if (bt >= 0) { addDock(bestT[bt]); compHas[c] = true; }
        }
        // 1b) water-body links: a landmass group whose docks share no water body with the main group (inland seas,
        //     enclosed bays) gets a partner dock pair on a body both sides touch
        for (int round = 0; round < 12; round++) {
            int[] uf = new int[tr.nComp]; for (int i = 0; i < uf.length; i++) uf[i] = i;
            int[] firstOnBody = new int[nb]; Arrays.fill(firstOnBody, -1);
            for (int[] d : docks) { int bd = waterBody[d[1]]; if (firstOnBody[bd] < 0) firstOnBody[bd] = d[3]; else { int ra = find(uf, d[3]), rb = find(uf, firstOnBody[bd]); uf[ra] = rb; } }
            long[] gsize = new long[tr.nComp]; for (int c = 0; c < tr.nComp; c++) if (tr.compPlayable[c]) gsize[find(uf, c)] += tr.compSize[c];
            int main = 0; for (int c = 0; c < tr.nComp; c++) if (gsize[c] > gsize[main]) main = c;
            float[] pick = null; int grp = -1;
            for (int[] d : docks) {
                if (find(uf, d[3]) == main) continue; grp = find(uf, d[3]);
                for (float[] e : cand.values()) {
                    int ce = tr.compOf[(int) e[4]]; if (!tr.compPlayable[ce] || find(uf, ce) != main) continue;
                    boolean touches = false;
                    for (float[] e2 : cand.values()) if ((int) e2[5] == (int) e[5] && find(uf, tr.compOf[(int) e2[4]]) == grp) { touches = true; break; }
                    if (touches && (pick == null || e[0] > pick[0])) pick = e;
                }
                if (pick != null) break;
            }
            if (pick == null) break;
            addDock(pick); linkRepairs++;
            int body = (int) pick[5]; boolean has = false; float[] own = null;
            for (int[] d : docks) if (waterBody[d[1]] == body && find(uf, d[3]) == grp) has = true;
            if (!has) for (float[] e : cand.values()) if ((int) e[5] == body && find(uf, tr.compOf[(int) e[4]]) == grp && (own == null || e[0] > own[0])) own = e;
            if (own != null) { addDock(own); linkRepairs++; }
        }
        // 2) extras: best-scoring coastal territories, spaced, core/player territories favoured (AI harbor access)
        int coastal = 0; for (int t = 0; t < T; t++) if (bestT[t] != null) coastal++;
        int extraMax = Math.round(g.s.harbors * coastal * 0.35f);
        Integer[] ord = new Integer[T]; for (int t = 0; t < T; t++) ord[t] = t;
        Arrays.sort(ord, (p, q2) -> Float.compare(bonus(q2, bestT), bonus(p, bestT)));
        float minSp = 55 * sc;
        for (int t : ord) {
            if (extra >= extraMax || bestT[t] == null || isDock[(int) bestT[t][1]]) continue;
            int ct = (int) bestT[t][1]; boolean ok = true;
            for (int[] d : docks) if (WorldGen.dist(ct % W, ct / W, d[0] % W, d[0] / W) < minSp) { ok = false; break; }
            if (!ok) continue; addDock(bestT[t]); extra++;
        }
        ferryGraph();
        // docks that ended up with no ferry lane (alone on their water body) are dead weight: drop them
        int[] deg = new int[docks.size()]; for (int[] f : ferries) { deg[f[0]]++; deg[f[1]]++; }
        int[] remap = new int[docks.size()]; ArrayList<int[]> keptD = new ArrayList<>(); ArrayList<float[]> keptS = new ArrayList<>();
        for (int k = 0; k < docks.size(); k++) {
            boolean soleOfComp = true; for (int m = 0; m < docks.size(); m++) if (m != k && docks.get(m)[3] == docks.get(k)[3]) soleOfComp = false;
            if (deg[k] == 0 && !soleOfComp) { remap[k] = -1; isDock[docks.get(k)[0]] = false; deadDocks++; continue; }
            remap[k] = keptD.size(); keptD.add(docks.get(k)); keptS.add(dockScore.get(k));
        }
        docks.clear(); docks.addAll(keptD); dockScore.clear(); dockScore.addAll(keptS);
        for (int[] f : ferries) { f[0] = remap[f[0]]; f[1] = remap[f[1]]; }
        boolean[] has = new boolean[tr.nComp]; for (int[] d : docks) has[d[3]] = true;
        for (int c = 0; c < tr.nComp; c++) if (tr.compPlayable[c] && has[c]) compsWithDock++;
        g.log("Harbors", String.format(java.util.Locale.US, "%d docks on %d/%d playable landmasses (placed: 1 per landmass + %d water-body link + %d extra of max %d; %d lane-less dropped); %d ferry lanes (%d MST + %d shortcut + %d spur) from %d sea-Delaunay edges, %d Urquhart; all landmasses linked=%b",
                docks.size(), compsWithDock, playableComps, linkRepairs, extra, extraMax, deadDocks, ferries.size(), countKind(0), countKind(1), countKind(2), candEdges, urquhartEdges, allLandmassesLinked));
    }

    float bonus(int t, float[][] bestT) { return bestT[t] == null ? -9 : bestT[t][0] + (tr.kind[t] == Territories.CORE || tr.kind[t] == Territories.PLAYER ? 0.25f : 0); }

    void addDock(float[] e) {
        int tile = (int) e[1], berth = (int) e[2], t = (int) e[4]; if (isDock[tile]) return;
        docks.add(new int[]{tile, berth, t, tr.compOf[t]}); isDock[tile] = true;
        int h = tr.hub[t]; dockScore.add(new float[]{e[0], e[3], WorldGen.dist(tile % W, tile / W, h % W, h / W)});
    }

    int countKind(int k) { int n = 0; for (int[] f : ferries) if (f[3] == k) n++; return n; }

    void ferryGraph() {
        int D = docks.size(); if (D < 2) { allLandmassesLinked = true; return; }
        float[] dist = new float[N]; int[] src = new int[N], par = new int[N]; Arrays.fill(dist, Float.MAX_VALUE); Arrays.fill(src, -1);
        Heap hp = new Heap(1 << 16);
        for (int k = 0; k < D; k++) { int b = docks.get(k)[1]; dist[b] = 0; src[b] = k; par[b] = -1; hp.push(b, 0); }
        while (hp.size > 0) {
            float d = hp.topKey(); int c = hp.pop(); if (d > dist[c]) continue; int x = c % W, y = c / W;
            for (int k = 0; k < 8; k++) {
                int nx = x + WorldGen.DX[k], ny = y + WorldGen.DY[k]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue; int n = ny * W + nx;
                if (!g.ocean[n]) continue;
                if (k >= 4 && (!g.ocean[y * W + nx] || !g.ocean[ny * W + x])) continue;
                float cost = 1 + (g.seaDist[n] <= 1 ? 1.5f : 0);       // lanes keep off the shore
                float nd = d + WorldGen.DD[k] * cost;
                if (nd < dist[n]) { dist[n] = nd; src[n] = src[c]; par[n] = c; hp.push(n, nd); }
            }
        }
        // sea-Delaunay edges = adjacent water tiles owned by different docks
        HashMap<Long, float[]> best = new HashMap<>();
        for (int i = 0; i < N; i++) {
            if (src[i] < 0) continue;
            for (int k = 0; k < 2; k++) {
                int j = k == 0 ? (i % W < W - 1 ? i + 1 : -1) : (i / W < H - 1 ? i + W : -1);
                if (j < 0 || src[j] < 0 || src[j] == src[i]) continue;
                int a = Math.min(src[i], src[j]), b = Math.max(src[i], src[j]); long key = (long) a * D + b;
                float len = dist[i] + dist[j] + 1; float[] e = best.get(key);
                if (e == null || len < e[0]) best.put(key, new float[]{len, src[i] == a ? i : j, src[i] == a ? j : i});
            }
        }
        candEdges = best.size();
        ArrayList<long[]> edges = new ArrayList<>();
        for (java.util.Map.Entry<Long, float[]> e : best.entrySet()) edges.add(new long[]{e.getKey(), (long) (e.getValue()[0] * 16)});
        edges.sort((p, q2) -> Long.compare(p[1], q2[1]));
        // Urquhart: drop the longest edge of every triangle
        HashMap<Integer, HashSet<Integer>> nbr = new HashMap<>();
        for (long[] e : edges) { int a = (int) (e[0] / D), b = (int) (e[0] % D); nbr.computeIfAbsent(a, z -> new HashSet<>()).add(b); nbr.computeIfAbsent(b, z -> new HashSet<>()).add(a); }
        HashSet<Long> removed = new HashSet<>();
        for (long[] e : edges) {
            int a = (int) (e[0] / D), b = (int) (e[0] % D);
            for (int c : nbr.get(a)) {
                if (c == b || !nbr.get(b).contains(c)) continue;
                long kab = key(a, b, D), kac = key(a, c, D), kbc = key(b, c, D);
                float lab = best.get(kab)[0], lac = best.get(kac)[0], lbc = best.get(kbc)[0];
                removed.add(lab >= lac && lab >= lbc ? kab : lac >= lbc ? kac : kbc);
            }
        }
        urquhartEdges = edges.size() - removed.size();
        // MST over landmasses (Kruskal on components)
        int[] uf = new int[tr.nComp]; for (int i = 0; i < uf.length; i++) uf[i] = i;
        boolean[] used = new boolean[edges.size()];
        float maxMst = 0;
        for (int k = 0; k < edges.size(); k++) {
            long[] e = edges.get(k); int a = (int) (e[0] / D), b = (int) (e[0] % D);
            int ca = find(uf, docks.get(a)[3]), cb = find(uf, docks.get(b)[3]); if (ca == cb) continue;
            uf[ca] = cb; used[k] = true; addFerry(a, b, best.get(e[0]), 0, src, par); maxMst = Math.max(maxMst, best.get(e[0])[0]);
        }
        HashSet<Integer> roots = new HashSet<>(); linkedComps = 0;
        for (int[] d : docks) roots.add(find(uf, d[3]));
        allLandmassesLinked = roots.size() <= 1;
        // shortcut lanes: Urquhart edges between different landmasses that cut the dock-graph detour by > 1.6x
        ArrayList<long[]> cands = new ArrayList<>();
        for (int k = 0; k < edges.size(); k++) {
            long[] e = edges.get(k); if (used[k] || removed.contains(e[0])) continue;
            int a = (int) (e[0] / D), b = (int) (e[0] % D); if (docks.get(a)[3] == docks.get(b)[3]) continue;
            float len = best.get(e[0])[0]; if (len > Math.max(1.4f * maxMst, 140 * sc)) continue;
            float via = dockGraphDist(a, b);
            if (via > 1.6f * len) cands.add(new long[]{e[0], (long) ((via - len) * 16)});
        }
        extraEligible = cands.size();
        cands.sort((p, q2) -> Long.compare(q2[1], p[1]));
        int keep = Math.round(g.s.ferryExtras * cands.size());
        for (int k = 0; k < cands.size() && k < keep; k++) {
            long key = cands.get(k)[0]; int a = (int) (key / D), b = (int) (key % D);
            if (dockGraphDist(a, b) > 1.6f * best.get(key)[0]) addFerry(a, b, best.get(key), 1, src, par);   // re-check after earlier additions
        }
        // spurs: every dock still without a lane gets its shortest sea-Delaunay edge to another landmass (kind 2)
        int[] deg = new int[D]; for (int[] f : ferries) { deg[f[0]]++; deg[f[1]]++; }
        for (int k = 0; k < D; k++) {
            if (deg[k] > 0) continue; long bk = -1; float bl = Float.MAX_VALUE;
            for (long[] e : edges) { int a = (int) (e[0] / D), b = (int) (e[0] % D); if (a != k && b != k) continue;
                int o = a == k ? b : a; if (docks.get(o)[3] == docks.get(k)[3]) continue;
                float l = best.get(e[0])[0]; if (l < bl) { bl = l; bk = e[0]; } }
            if (bk < 0 || bl > Math.max(1.4f * maxMst, 140 * sc)) continue;
            int a = (int) (bk / D), b = (int) (bk % D); addFerry(a, b, best.get(bk), 2, src, par); deg[a]++; deg[b]++;
        }
    }

    static long key(int a, int b, int D) { return (long) Math.min(a, b) * D + Math.max(a, b); }
    static int find(int[] uf, int x) { while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x]; } return x; }

    void addFerry(int a, int b, float[] e, int kind, int[] src, int[] par) {
        ferries.add(new int[]{a, b, Math.round(e[0]), kind});
        ArrayList<Integer> p = new ArrayList<>();
        for (int c = (int) e[1]; c >= 0; c = par[c]) p.add(0, c);
        for (int c = (int) e[2]; c >= 0; c = par[c]) p.add(c);
        int[] arr = new int[p.size()]; for (int k = 0; k < arr.length; k++) arr[k] = p.get(k); paths.add(arr);
    }

    /** Travel estimate between docks over chosen ferries plus overland legs (1.4x straight line) inside one landmass. */
    float dockGraphDist(int s, int t) {
        int D = docks.size(); float[] d = new float[D]; Arrays.fill(d, Float.MAX_VALUE); boolean[] done = new boolean[D]; d[s] = 0;
        for (int it = 0; it < D; it++) {
            int u = -1; for (int v = 0; v < D; v++) if (!done[v] && d[v] < Float.MAX_VALUE && (u < 0 || d[v] < d[u])) u = v;
            if (u < 0 || u == t) break; done[u] = true; int[] du = docks.get(u);
            for (int v = 0; v < D; v++) { if (done[v] || docks.get(v)[3] != du[3]) continue; int[] dv = docks.get(v);
                float w = 1.4f * WorldGen.dist(du[0] % W, du[0] / W, dv[0] % W, dv[0] / W); if (d[u] + w < d[v]) d[v] = d[u] + w; }
            for (int[] f : ferries) { int v = f[0] == u ? f[1] : f[1] == u ? f[0] : -1; if (v >= 0 && d[u] + f[2] < d[v]) d[v] = d[u] + f[2]; }
        }
        return d[t];
    }
}
