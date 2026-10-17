import java.util.ArrayList;
import java.util.Arrays;

/**
 * AI START LAYER (a separate pass after territories). Each AI starts on ONE core-set territory. Every combination of
 * core territories is scored (spacing by travel cost + fairness: land within K travel cost, neighbour count, harbor
 * access), the top 40 are re-scored with a travel-cost Voronoi land share, and the best is kept. Pure Java.
 */
final class AiStarts {
    final WorldGen g; final Territories tr; final int W, H, N; final float sc;
    int[] cap = new int[0];                 // territory per AI
    float[] landK, share, rival, rivalEuclid, harborCost; int[] neighbors;
    float playerShare, spacingTravel, spacingEuclid, landBalance, shareBalance, neighborBalance, harborAccess, score, K; long combos;
    int f, cw, ch; float[] cost; int[] landCnt;

    AiStarts(WorldGen g) { this.g = g; tr = g.terr; W = g.W; H = g.H; N = g.N; sc = g.sc; }

    void build() {
        ArrayList<Integer> cand = new ArrayList<>();
        for (int t = 0; t < tr.T; t++) if (tr.kind[t] == Territories.CORE && tr.setOf[t] >= 0) cand.add(t);
        int n = Math.min(g.s.aiCount, cand.size()); if (n == 0) return;
        // coarse travel-cost grid: land terrain cost, rivers dearer, sea crossings slow (ferry), 4x4 tiles per cell
        f = Math.max(3, Math.round(4 * sc)); cw = W / f; ch = H / f; cost = new float[cw * ch]; landCnt = new int[cw * ch];
        for (int y = 0; y < ch * f; y++) for (int x = 0; x < cw * f; x++) {
            int i = y * W + x, c = (y / f) * cw + x / f; byte k = g.cls[i];
            cost[c] += g.ocean[i] || g.lake[i] ? 5 : k == WorldGen.MOUNTAIN ? 7 : k == WorldGen.HILLS ? 2 : (k == WorldGen.SWAMP || k == WorldGen.JUNGLE) ? 1.6f : 1;
            if (g.river[i] > 0) cost[c] += 2; if (tr.tid[i] >= 0) landCnt[c]++;
        }
        for (int c = 0; c < cost.length; c++) cost[c] /= f * f;
        int C = cand.size(); float[][] dist = new float[C][]; for (int k = 0; k < C; k++) dist[k] = dijkstra(new int[]{cell(tr.hub[cand.get(k)])}, null);
        float[] pd = tr.playerT >= 0 ? dijkstra(new int[]{cell(tr.hub[tr.playerT])}, null) : null;
        K = 3.2f * (float) Math.sqrt(tr.unit) / f;   // in coarse cost units (~3 territory radii)
        float[] lk = new float[C], hc = new float[C]; int[] nbs = new int[C];
        for (int k = 0; k < C; k++) {
            for (int c = 0; c < cost.length; c++) if (dist[k][c] <= K) lk[k] += landCnt[c];
            hc[k] = Float.MAX_VALUE; for (int[] d : g.harbors.docks) hc[k] = Math.min(hc[k], dist[k][cell(d[0])]);
            nbs[k] = tr.adj[cand.get(k)].length;
        }
        long landTot = 0; for (int i = 0; i < N; i++) if (tr.tid[i] >= 0) landTot++;
        float ideal = (float) (2 * Math.sqrt(landTot / (double) (n + (pd != null ? 1 : 0)) / Math.PI)) / f;
        // exhaustive cheap score over all combinations
        int[] cur = new int[n]; ArrayList<float[]> top = new ArrayList<>();
        combos = 0; enumerate(cur, 0, 0, C, dist, pd, lk, hc, nbs, cand, ideal, top);
        // top 40 re-scored with the Voronoi land share
        float bestS = -1; int[] bestSet = null; float bestShare = 0;
        for (float[] e : top) {
            int[] set = new int[n]; for (int k = 0; k < n; k++) set[k] = (int) e[1 + k];
            float sb = voronoi(set, cand, null);
            float s2 = 0.7f * e[0] + 0.3f * sb;
            if (s2 > bestS) { bestS = s2; bestSet = set; bestShare = sb; }
        }
        score = bestS; cap = new int[n];
        landK = new float[n]; share = new float[n]; rival = new float[n]; rivalEuclid = new float[n]; harborCost = new float[n]; neighbors = new int[n];
        voronoi(bestSet, cand, share);
        for (int k = 0; k < n; k++) {
            int j = bestSet[k]; cap[k] = cand.get(j); landK[k] = lk[j]; harborCost[k] = hc[j] * f; neighbors[k] = nbs[j];
            rival[k] = Float.MAX_VALUE; rivalEuclid[k] = Float.MAX_VALUE;
            for (int m = 0; m < n; m++) if (m != k) {
                rival[k] = Math.min(rival[k], dist[j][cell(tr.hub[cand.get(bestSet[m])])] * f);
                int a = tr.hub[cap[k]], b = tr.hub[cand.get(bestSet[m])];
                rivalEuclid[k] = Math.min(rivalEuclid[k], WorldGen.dist(a % W, a / W, b % W, b / W));
            }
        }
        float lmin = 1e9f, lmax = 0, nmin = 1e9f, nmax = 0, rmin = 1e9f, emin = 1e9f; int ha = 0;
        for (int k = 0; k < n; k++) { lmin = Math.min(lmin, landK[k]); lmax = Math.max(lmax, landK[k]); nmin = Math.min(nmin, neighbors[k]); nmax = Math.max(nmax, neighbors[k]);
            rmin = Math.min(rmin, rival[k]); emin = Math.min(emin, rivalEuclid[k]); if (harborCost[k] <= K * f) ha++; }
        landBalance = lmin / Math.max(1, lmax); neighborBalance = nmin / Math.max(1, nmax); spacingTravel = rmin; spacingEuclid = emin; harborAccess = ha / (float) n; shareBalance = bestShare;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append(String.format(java.util.Locale.US, " AI%d=%s(land %.0f, nb %d, harbor %.0f)", k + 1, tr.label(cap[k]), landK[k], neighbors[k], harborCost[k]));
        g.log("AiStarts", String.format(java.util.Locale.US, "%d of %d core territories chosen from %d combinations; min rival travel %.0f (euclid %.0f tiles); land-within-K balance %.2f, Voronoi share balance %.2f, neighbour balance %.2f, harbor access %.0f%%;%s",
                n, C, combos, spacingTravel, spacingEuclid, landBalance, shareBalance, neighborBalance, 100 * harborAccess, sb));
    }

    int cell(int tile) { return Math.min(ch - 1, (tile / W) / f) * cw + Math.min(cw - 1, (tile % W) / f); }

    void enumerate(int[] cur, int depth, int start, int C, float[][] dist, float[] pd, float[] lk, float[] hc, int[] nbs, ArrayList<Integer> cand, float ideal, ArrayList<float[]> top) {
        int n = cur.length;
        if (depth == n) {
            combos++;
            float md = Float.MAX_VALUE, lmin = 1e9f, lmax = 0, nmin = 1e9f, nmax = 0; int ha = 0;
            for (int a = 0; a < n; a++) {
                for (int b = a + 1; b < n; b++) md = Math.min(md, dist[cur[a]][cell(tr.hub[cand.get(cur[b])])]);
                if (pd != null) md = Math.min(md, pd[cell(tr.hub[cand.get(cur[a])])]);
                lmin = Math.min(lmin, lk[cur[a]]); lmax = Math.max(lmax, lk[cur[a]]); nmin = Math.min(nmin, nbs[cur[a]]); nmax = Math.max(nmax, nbs[cur[a]]);
                if (hc[cur[a]] <= K) ha++;
            }
            float s = 0.40f * Math.min(1f, md / (0.8f * ideal)) + 0.25f * lmin / Math.max(1, lmax) + 0.15f * nmin / Math.max(1, nmax) + 0.20f * ha / n;
            if (top.size() < 40 || s > top.get(top.size() - 1)[0]) {
                float[] e = new float[n + 1]; e[0] = s; for (int k = 0; k < n; k++) e[1 + k] = cur[k];
                int pos = top.size(); while (pos > 0 && top.get(pos - 1)[0] < s) pos--; top.add(pos, e); if (top.size() > 40) top.remove(40);
            }
            return;
        }
        for (int j = start; j <= C - (n - depth); j++) { cur[depth] = j; enumerate(cur, depth + 1, j + 1, C, dist, pd, lk, hc, nbs, cand, ideal, top); }
    }

    /** Travel-cost Voronoi among the chosen capitals (and the player area as a competitor): min/max land share. */
    float voronoi(int[] set, ArrayList<Integer> cand, float[] outShare) {
        int n = set.length; int extra = tr.playerT >= 0 ? 1 : 0; int[] src = new int[n + extra];
        for (int k = 0; k < n; k++) src[k] = cell(tr.hub[cand.get(set[k])]); if (extra == 1) src[n] = cell(tr.hub[tr.playerT]);
        int[] lab = new int[cost.length]; dijkstra(src, lab);
        float[] sh = new float[n + extra]; float tot = 0;
        for (int c = 0; c < cost.length; c++) if (lab[c] >= 0) { sh[lab[c]] += landCnt[c]; tot += landCnt[c]; }
        float mn = 1e9f, mx = 0; for (int k = 0; k < n; k++) { sh[k] /= Math.max(1, tot); mn = Math.min(mn, sh[k]); mx = Math.max(mx, sh[k]); }
        if (outShare != null) { System.arraycopy(sh, 0, outShare, 0, n); playerShare = extra == 1 ? sh[n] / Math.max(1, tot) : 0; }
        return mn / Math.max(1e-6f, mx);
    }

    float[] dijkstra(int[] src, int[] lab) {
        int n = cost.length; float[] d = new float[n]; Arrays.fill(d, Float.MAX_VALUE); if (lab != null) Arrays.fill(lab, -1);
        Heap hp = new Heap(n); for (int k = 0; k < src.length; k++) { d[src[k]] = 0; if (lab != null) lab[src[k]] = k; hp.push(src[k], 0); }
        while (hp.size > 0) {
            float k = hp.topKey(); int c = hp.pop(); if (k > d[c]) continue; int x = c % cw, y = c / cw;
            for (int dd = 0; dd < 8; dd++) {
                int nx = x + WorldGen.DX[dd], ny = y + WorldGen.DY[dd]; if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
                int m = ny * cw + nx; float nc = d[c] + WorldGen.DD[dd] * 0.5f * (cost[c] + cost[m]);
                if (nc < d[m]) { d[m] = nc; if (lab != null) lab[m] = lab[c]; hp.push(m, nc); }
            }
        }
        return d;
    }
}
