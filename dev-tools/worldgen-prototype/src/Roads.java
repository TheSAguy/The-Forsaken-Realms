import java.util.ArrayList;
import java.util.Arrays;

/**
 * Roads between territory hubs (prototype A's terrain-aware A*): MST over shared-border adjacency plus loop links
 * where the network detour exceeds 1.6x, plus one road from every dock to its territory hub. Roads may climb
 * mountains (a pass) at high cost and cross rivers only by a BRIDGE (counted per crossing). Pure Java.
 */
final class Roads {
    final WorldGen g; final Territories tr; final int W, H, N;
    final boolean[] road, bridge, pass; final float[] wobble;
    int edges, loops, dockRoads, cells, bridgeTiles, bridges, passTiles, failed; long expanded;
    final Astar as;

    Roads(WorldGen g) {
        this.g = g; tr = g.terr; W = g.W; H = g.H; N = g.N; road = new boolean[N]; bridge = new boolean[N]; pass = new boolean[N]; wobble = new float[N];
        Noise n = new Noise(g.s.seed + 61);
        for (int i = 0; i < N; i++) wobble[i] = 0.6f + 0.9f * (0.5f + 0.5f * n.fbm(i % W * 0.05f / g.sc, i / W * 0.05f / g.sc, 3, 0.5f));
        as = new Astar();
    }

    static float terrain(byte c) {
        switch (c) {
            case WorldGen.OCEAN: case WorldGen.DEEP: case WorldGen.LAKE: return Float.POSITIVE_INFINITY;
            case WorldGen.MOUNTAIN: return 14;
            case WorldGen.SWAMP: return 3.5f;
            case WorldGen.JUNGLE: return 2.8f;
            case WorldGen.HILLS: case WorldGen.SNOW: return 2.5f;
            case WorldGen.FOREST: return 2f;
            case WorldGen.TUNDRA: return 1.8f;
            case WorldGen.DESERT: return 1.4f;
            default: return 1;
        }
    }

    void build() {
        int T = tr.T;
        ArrayList<int[]> cand = new ArrayList<>();
        for (int a = 0; a < T; a++) for (int k = 0; k < tr.adj[a].length; k++) { int b = tr.adj[a][k]; if (b > a && tr.adjBorder[a][k] >= 6) cand.add(new int[]{a, b}); }
        cand.sort((p, q) -> Float.compare(hd(p[0], p[1]), hd(q[0], q[1])));
        int[] uf = new int[T]; for (int i = 0; i < T; i++) uf[i] = i;
        ArrayList<int[]> chosen = new ArrayList<>(), rest = new ArrayList<>();
        for (int[] e : cand) { int ra = find(uf, e[0]), rb = find(uf, e[1]); if (ra != rb) { uf[ra] = rb; chosen.add(e); } else rest.add(e); }
        @SuppressWarnings("unchecked") ArrayList<int[]>[] al = new ArrayList[T]; for (int t = 0; t < T; t++) al[t] = new ArrayList<>();
        for (int[] e : chosen) { al[e[0]].add(e); al[e[1]].add(e); }
        for (int[] e : rest) if (graphDist(al, e[0], e[1]) > 1.6f * hd(e[0], e[1])) { chosen.add(e); al[e[0]].add(e); al[e[1]].add(e); loops++; }
        for (int[] e : chosen) { if (as.route(tr.hub[e[0]], tr.hub[e[1]])) edges++; else failed++; }
        for (int[] d : g.harbors.docks) { if (as.route(d[0], tr.hub[d[2]])) dockRoads++; else failed++; }
        finish();
        g.log("Roads", String.format(java.util.Locale.US, "%d hub links (%d loops) + %d dock roads, %d failed; %d road tiles, %d bridges (%d tiles), %d pass tiles; A* expanded %d",
                edges, loops, dockRoads, failed, cells, bridges, bridgeTiles, passTiles, expanded));
    }

    /** Recount road tiles, bridge tiles, bridges (4-connected crossings) and mountain-pass tiles. */
    void finish() {
        cells = 0; bridgeTiles = 0; passTiles = 0; bridges = 0;
        for (int i = 0; i < N; i++) {
            bridge[i] = road[i] && g.river[i] > 0; pass[i] = road[i] && g.cls[i] == WorldGen.MOUNTAIN;
            if (road[i]) cells++; if (bridge[i]) bridgeTiles++; if (pass[i]) passTiles++;
        }
        int[] lab = new int[N], q = new int[N]; Arrays.fill(lab, -1);
        for (int i = 0; i < N; i++) if (bridge[i] && lab[i] < 0) { g.flood(bridge, true, i, lab, q, i); bridges++; }
    }

    float hd(int a, int b) { int p = tr.hub[a], q = tr.hub[b]; return WorldGen.dist(p % W, p / W, q % W, q / W); }
    static int find(int[] uf, int x) { while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x]; } return x; }

    float graphDist(ArrayList<int[]>[] al, int s, int t) {
        float[] d = new float[tr.T]; Arrays.fill(d, Float.MAX_VALUE); d[s] = 0; Heap hp = new Heap(64); hp.push(s, 0);
        while (hp.size > 0) {
            float k = hp.topKey(); int u = hp.pop(); if (k > d[u]) continue; if (u == t) return k;
            for (int[] e : al[u]) { int v = e[0] == u ? e[1] : e[0]; float nd = d[u] + hd(u, v); if (nd < d[v]) { d[v] = nd; hp.push(v, nd); } }
        }
        return Float.MAX_VALUE;
    }

    /** A* on the tile grid with an octile heuristic; generation stamps avoid clearing arrays per query. */
    final class Astar {
        final float[] gs = new float[N]; final int[] from = new int[N], stamp = new int[N]; final Heap pq = new Heap(1 << 16); int gen;

        boolean route(int s, int t) {
            gen++; pq.clear(); gs[s] = 0; stamp[s] = gen; from[s] = -1; pq.push(s, h(s, t));
            boolean found = false;
            while (pq.size > 0) {
                float k = pq.topKey(); int c = pq.pop(); expanded++;
                if (c == t) { found = true; break; }
                if (k - h(c, t) > gs[c] + 1e-3f) continue;
                int cx = c % W, cy = c / W;
                for (int d = 0; d < 8; d++) {
                    int nx = cx + WorldGen.DX[d], ny = cy + WorldGen.DY[d]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int n = ny * W + nx; float tc = terrain(g.cls[n]); if (tc == Float.POSITIVE_INFINITY) continue;
                    if (d >= 4) {   // diagonal steps: no corner cutting across water, and never diagonally onto/off a river (bridges stay straight)
                        if (!g.isLand(cy * W + nx) || !g.isLand(ny * W + cx)) continue;
                        if (g.river[n] > 0 || g.river[c] > 0) continue;
                    }
                    if (road[n]) tc = 0.35f; else if (g.river[n] > 0) tc += g.river[c] > 0 ? 4 : 12;   // bridges cost, then get reused
                    float slope = Math.abs(g.elev[n] - g.elev[c]) * 300f * g.sc;
                    float ng = gs[c] + WorldGen.DD[d] * (tc * wobble[n] + (road[n] ? 0 : slope));
                    if (stamp[n] != gen || ng < gs[n]) { stamp[n] = gen; gs[n] = ng; from[n] = c; pq.push(n, ng + h(n, t)); }
                }
            }
            if (!found) return false;
            for (int c = t; c >= 0; c = from[c]) {
                road[c] = true; int p = from[c]; if (p < 0) break;
                int dx = p % W - c % W, dy = p / W - c / W;
                if (dx != 0 && dy != 0) {
                    int a = c + dx, bb = c + dy * W;
                    road[terrain(g.cls[a]) + (g.river[a] > 0 ? 12 : 0) <= terrain(g.cls[bb]) + (g.river[bb] > 0 ? 12 : 0) ? a : bb] = true;
                }
            }
            return true;
        }

        float h(int a, int b) {
            int dx = Math.abs(a % W - b % W), dy = Math.abs(a / W - b / W);
            return 0.3f * (Math.max(dx, dy) + 0.4142f * Math.min(dx, dy));
        }
    }
}
