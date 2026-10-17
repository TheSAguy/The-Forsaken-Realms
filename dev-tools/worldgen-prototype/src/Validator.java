import java.util.ArrayList;
import java.util.Arrays;

/**
 * Reachability validator: BFS from the first AI capital over walkable land (not mountain, not river, not lake), road
 * tiles (passes and bridges), and ferry edges between docks. Every territory hub must be reached. Unreached hubs get
 * a repair road to the nearest reached hub (bridges/passes appear automatically) and the BFS re-runs. Pure Java.
 */
final class Validator {
    final WorldGen g; final Territories tr; final int W, H, N;
    int hubs, reachedBefore, reachedAfter, repairs, walkable, walkReached; boolean ok;
    final ArrayList<String> unreachedBefore = new ArrayList<>(), unreachedAfter = new ArrayList<>();
    int ferryHubsOnly;   // hubs that need a boat to reach

    Validator(WorldGen g) { this.g = g; tr = g.terr; W = g.W; H = g.H; N = g.N; }

    boolean walk(int i) {
        if (!g.isLand(i)) return false;
        if (g.roads.road[i]) return true;
        return g.cls[i] != WorldGen.MOUNTAIN && g.river[i] == 0;
    }

    boolean[] bfs(int start, boolean ferries) {
        boolean[] seen = new boolean[N]; int[] q = new int[N]; int qt = 0;
        if (!walk(start)) return seen;
        seen[start] = true; q[qt++] = start;
        int D = g.harbors.docks.size(); int[] dockTile = new int[D]; for (int k = 0; k < D; k++) dockTile[k] = g.harbors.docks.get(k)[0];
        int[] dockAt = new int[N]; Arrays.fill(dockAt, -1); for (int k = 0; k < D; k++) dockAt[dockTile[k]] = k;
        for (int h = 0; h < qt; h++) {
            int c = q[h], x = c % W, y = c / W;
            for (int d = 0; d < 4; d++) { int nx = x + WorldGen.DX[d], ny = y + WorldGen.DY[d]; if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int n = ny * W + nx; if (!seen[n] && walk(n)) { seen[n] = true; q[qt++] = n; } }
            if (ferries && dockAt[c] >= 0) for (int[] f : g.harbors.ferries) {
                int o = f[0] == dockAt[c] ? f[1] : f[1] == dockAt[c] ? f[0] : -1; if (o < 0) continue;
                int n = dockTile[o]; if (!seen[n] && walk(n)) { seen[n] = true; q[qt++] = n; }
            }
        }
        return seen;
    }

    void run() {
        int start = g.ai.cap.length > 0 ? tr.hub[g.ai.cap[0]] : tr.hub[0];
        hubs = tr.T;
        boolean[] seen = bfs(start, true);
        boolean[] seenLand = bfs(start, false);
        for (int t = 0; t < tr.T; t++) { if (seen[tr.hub[t]]) reachedBefore++; else unreachedBefore.add(tr.label(t)); if (seen[tr.hub[t]] && !seenLand[tr.hub[t]]) ferryHubsOnly++; }
        // repair: road from each unreached hub to the nearest reached hub on the same landmass
        for (int round = 0; round < 3 && reachedBefore + repairs < hubs; round++) {
            boolean any = false;
            for (int t = 0; t < tr.T; t++) {
                if (seen[tr.hub[t]]) continue; int h = tr.hub[t], best = -1; float bd = Float.MAX_VALUE;
                for (int u = 0; u < tr.T; u++) if (seen[tr.hub[u]] && tr.compOf[u] == tr.compOf[t]) {
                    float d = WorldGen.dist(h % W, h / W, tr.hub[u] % W, tr.hub[u] / W); if (d < bd) { bd = d; best = tr.hub[u]; } }
                if (best >= 0 && g.roads.as.route(h, best)) { repairs++; any = true; }
            }
            if (!any) break;
            g.roads.finish(); seen = bfs(start, true);
        }
        for (int t = 0; t < tr.T; t++) if (seen[tr.hub[t]]) reachedAfter++; else unreachedAfter.add(tr.label(t));
        for (int i = 0; i < N; i++) if (tr.tid[i] >= 0 && walk(i)) { walkable++; if (seen[i]) walkReached++; }
        ok = reachedAfter == hubs;
        g.log("Reach", String.format(java.util.Locale.US, "hubs reached %d/%d before repair (%s), %d repair roads, %d/%d after%s; %d hubs need a ferry; walkable land reached %.1f%%",
                reachedBefore, hubs, unreachedBefore.isEmpty() ? "none missing" : "missing " + unreachedBefore, repairs, reachedAfter, hubs,
                unreachedAfter.isEmpty() ? "" : " (still missing " + unreachedAfter + ")", ferryHubsOnly, 100f * walkReached / Math.max(1, walkable)));
    }
}
