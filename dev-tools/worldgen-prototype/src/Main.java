import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Debug CLI: runs the presets, times every stage (median of 3 warm runs), writes renders + a metrics JSON per run. */
public final class Main {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        String out = args.length > 0 ? args[0] : "out", data = args.length > 1 ? args[1] : "data";
        String mode = args.length > 2 ? args[2] : "all";
        SetTable full = SetTable.load(data, false), folded = SetTable.load(data, true);
        System.out.printf("sets: %d raw, %d territories unfolded (%d core), %d folded%n", full.rawCount, full.sets.size(), full.coreCount, folded.sets.size());
        int runs = mode.equals("quick") ? 1 : 3;
        if (!mode.equals("quick")) for (int k = 0; k < 2; k++) new WorldGen(Settings.preset("balanced"), full).generate();   // JIT warm-up
        List<Settings> todo = new ArrayList<>();
        todo.add(Settings.preset("balanced"));
        todo.add(Settings.preset("archipelago"));
        todo.add(Settings.preset("pangaea"));
        Settings fold = Settings.preset("balanced"); fold.key = "balanced_fold"; fold.name = "Balanced, fold reprints, no player area"; fold.foldReprints = true; fold.playerArea = false; todo.add(fold);
        if (!mode.equals("quick")) { Settings big = Settings.preset("balanced"); big.key = "balanced_1000"; big.name = "Balanced (default) 1000x1000"; big.size = 1000; todo.add(big); }
        if (mode.startsWith("only=")) { String k = mode.substring(5); todo.removeIf(s -> !s.key.equals(k)); runs = 1; }
        if (!mode.equals("sweep")) for (Settings s : todo) run(s, s.foldReprints ? folded : full, out, runs);
        if (mode.equals("all") || mode.equals("sweep")) cohesionSweep(full, out);
    }

    static void run(Settings s, SetTable st, String out, int runs) throws Exception {
        long[][] stt = new long[WorldGen.STAGES.length][runs]; long[] tot = new long[runs]; WorldGen g = null;
        for (int r = 0; r < runs; r++) {
            g = new WorldGen(s.copy(), st); long t = System.nanoTime(); g.generate(); tot[r] = System.nanoTime() - t;
            for (int k = 0; k < stt.length; k++) stt[k][r] = g.ns[k];
        }
        String key = s.key;
        Render.write(Render.terrain(g, 1), out + "/" + key + "_terrain.png");
        Render.write(Render.territories(g, 2, 300, s.name), out + "/" + key + "_territories.png");
        Render.write(Render.crop2x(g), out + "/" + key + "_crop2x.png");
        String json = metrics(g, stt, tot);
        try (FileWriter fw = new FileWriter(out + "/" + key + "_metrics.json")) { fw.write(json); }
        System.out.println("=== " + s.name + " [" + key + "] " + s.sliders());
        System.out.printf("total median %.0f ms%n", med(tot) / 1e6);
        for (String l : g.log) System.out.println("  " + l);
    }

    static void cohesionSweep(SetTable st, String out) throws Exception {
        float[] cs = {0f, 0.5f, 1f}; int T = 560;
        java.awt.image.BufferedImage sheet = new java.awt.image.BufferedImage(3 * T, T + 22, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D gr = sheet.createGraphics(); gr.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
        StringBuilder sb = new StringBuilder("{\"cohesionSweep\": [");
        for (int k = 0; k < 3; k++) {
            Settings s = Settings.preset("balanced"); s.cohesion = cs[k]; WorldGen g = new WorldGen(s, st); g.generate();
            java.awt.Image th = Render.territories(g, 2, 0, "").getScaledInstance(T, T, java.awt.Image.SCALE_AREA_AVERAGING);
            gr.drawImage(th, k * T, 22, null); gr.setColor(java.awt.Color.WHITE);
            gr.drawString(String.format("cohesion %.1f: same-plane neighbour %.2f, plane spread %.3f, AI min travel %.0f", cs[k], g.terr.cohesionScore, g.terr.meanPlaneSpread, g.ai.spacingTravel), k * T + 6, 16);
            sb.append(String.format("%s{\"cohesion\": %.1f, \"samePlaneNeighbourShare\": %.3f, \"meanPlaneSpread\": %.4f, \"aiMinRivalTravel\": %.1f, \"aiShareBalance\": %.3f}",
                    k > 0 ? ", " : "", cs[k], g.terr.cohesionScore, g.terr.meanPlaneSpread, g.ai.spacingTravel, g.ai.shareBalance));
        }
        gr.dispose(); Render.write(sheet, out + "/cohesion_sweep.png");
        try (FileWriter fw = new FileWriter(out + "/cohesion_sweep.json")) { fw.write(sb.append("]}\n").toString()); }
    }

    static long med(long[] a) { long[] b = a.clone(); Arrays.sort(b); return b[b.length / 2]; }

    static String stats(List<Integer> v) {
        if (v.isEmpty()) return "{\"n\": 0}";
        Integer[] a = v.toArray(new Integer[0]); Arrays.sort(a); double mean = 0; for (int x : a) mean += x; mean /= a.length;
        return String.format("{\"n\": %d, \"min\": %d, \"p10\": %d, \"median\": %d, \"p90\": %d, \"max\": %d, \"mean\": %.0f, \"medianSide\": %d}",
                a.length, a[0], a[a.length / 10], a[a.length / 2], a[a.length * 9 / 10], a[a.length - 1], mean, (int) Math.sqrt(a[a.length / 2]));
    }
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static String metrics(WorldGen g, long[][] stt, long[] tot) {
        Territories tr = g.terr; Harbors hb = g.harbors; AiStarts ai = g.ai; Roads rd = g.roads; Validator v = g.valid;
        int oc = 0, rv = 0, mt = 0, land = 0; for (int i = 0; i < g.N; i++) { if (g.ocean[i]) oc++; if (g.isLand(i)) { land++; if (g.river[i] > 0) rv++; if (g.cls[i] == WorldGen.MOUNTAIN) mt++; } }
        List<Integer> core = new ArrayList<>(), other = new ArrayList<>(), isle = new ArrayList<>(), wild = new ArrayList<>(); int player = 0; long coreTiles = 0, terrTiles = 0;
        for (int t = 0; t < tr.T; t++) {
            terrTiles += tr.area[t]; coreTiles += tr.coreArea[t];
            if (tr.kind[t] == Territories.PLAYER) player = tr.area[t];
            else if (tr.setOf[t] < 0) wild.add(tr.area[t]);
            else if (g.sets.sets.get(tr.setOf[t]).core) core.add(tr.area[t]);
            else if (tr.kind[t] == Territories.ISLE) isle.add(tr.area[t]);
            else other.add(tr.area[t]);
        }
        int riders = 0; for (SetTable.SetDef d : g.sets.sets) riders += d.riders.size();
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"preset\": ").append(q(g.s.name)).append(", \"key\": ").append(q(g.s.key)).append(",\n");
        b.append("  \"sliders\": ").append(q(g.s.sliders())).append(",\n");
        b.append(String.format("  \"sets\": {\"rawEditions\": %d, \"territorySets\": %d, \"coreSets\": %d, \"foldedRiders\": %d},%n", g.sets.rawCount, g.sets.sets.size(), g.sets.coreCount, riders));
        b.append(String.format("  \"world\": {\"oceanPct\": %.1f, \"landTiles\": %d, \"riverTilesPctLand\": %.2f, \"mountainPctLand\": %.2f, \"themeIsles\": %d},%n", 100f * oc / g.N, land, 100f * rv / land, 100f * mt / land, g.isles.size()));
        b.append("  \"territories\": {\"count\": ").append(tr.T).append(", \"unitTiles\": ").append(Math.round(tr.unit))
         .append(", \"coreSet\": ").append(stats(core)).append(", \"otherSet\": ").append(stats(other)).append(", \"isleSet\": ").append(stats(isle))
         .append(", \"wild\": ").append(stats(wild)).append(", \"playerArea\": ").append(player)
         .append(String.format(", \"smallest\": {\"set\": %s, \"tiles\": %d, \"landmassTiles\": %d}", q(tr.label(smallest(tr))), tr.area[smallest(tr)], tr.compSize[tr.compOf[smallest(tr)]]))
         .append(String.format(", \"coreToOtherMedianRatio\": %.2f", core.isEmpty() || other.isEmpty() ? 0 : median(core) / (float) median(other)))
         .append(String.format(", \"isletsLeftWild\": %d, \"isletTiles\": %d, \"partitionErr\": %.3f, \"partitionPasses\": %d", tr.isletCount, tr.isletTiles, tr.partErr, tr.partIters))
         .append(String.format(", \"themedCoreShareOfLand\": %.3f, \"landCoveredPct\": %.1f", coreTiles / (float) Math.max(1, terrTiles), 100f * terrTiles / land))
         .append(String.format(", \"cohesion\": %.2f, \"samePlaneNeighbourShare\": %.3f, \"meanPlaneSpread\": %.4f, \"unplacedSets\": %d, \"hungarianMs\": %.1f},%n", g.s.cohesion, tr.cohesionScore, tr.meanPlaneSpread, tr.unplaced, tr.hungarianMs));
        b.append(String.format("  \"aiStarts\": {\"count\": %d, \"combinationsScored\": %d, \"minRivalTravel\": %.0f, \"minRivalEuclidTiles\": %.0f, \"landWithinKBalance\": %.3f, \"voronoiShareBalance\": %.3f, \"neighbourBalance\": %.3f, \"harborAccess\": %.2f, \"K_travel\": %.0f, \"score\": %.3f, \"capitals\": [",
                ai.cap.length, ai.combos, ai.spacingTravel, ai.spacingEuclid, ai.landBalance, ai.shareBalance, ai.neighborBalance, ai.harborAccess, ai.K * ai.f, ai.score));
        for (int k = 0; k < ai.cap.length; k++) {
            int h = tr.hub[ai.cap[k]];
            b.append(String.format("%s%n    {\"ai\": %d, \"set\": %s, \"x\": %d, \"y\": %d, \"territoryTiles\": %d, \"landWithinK\": %.0f, \"neighbours\": %d, \"harborTravel\": %.0f, \"nearestRivalTravel\": %.0f, \"nearestRivalEuclid\": %.0f, \"voronoiShare\": %.3f}",
                    k > 0 ? "," : "", k + 1, q(tr.label(ai.cap[k])), h % g.W, h / g.W, tr.area[ai.cap[k]], ai.landK[k], ai.neighbors[k], ai.harborCost[k], ai.rival[k], ai.rivalEuclid[k], ai.share[k]));
        }
        b.append(String.format("], \"playerVoronoiShare\": %.3f},%n", ai.playerShare));
        float tl = 0, ml = 0, sh = 0, hd = 0; for (int[] f : hb.ferries) { tl += f[2]; ml = Math.max(ml, f[2]); }
        for (float[] d : hb.dockScore) { sh += d[1]; hd += d[2]; }
        int n = Math.max(1, hb.docks.size());
        b.append(String.format("  \"harbors\": {\"docks\": %d, \"landmassesWithDock\": %d, \"linkDocks\": %d, \"extraDocks\": %d, \"lanelessDocksDropped\": %d, \"playableLandmasses\": %d, \"ferryLanes\": %d, \"mstLanes\": %d, \"shortcutLanes\": %d, \"spurLanes\": %d, \"shortcutEligible\": %d, \"seaDelaunayEdges\": %d, \"urquhartEdges\": %d, \"totalLaneTiles\": %.0f, \"longestLane\": %.0f, \"allLandmassesLinked\": %b, \"meanShelter\": %.2f, \"meanDockToHubTiles\": %.1f},%n",
                hb.docks.size(), hb.compsWithDock, hb.linkRepairs, hb.extra, hb.deadDocks, hb.playableComps, hb.ferries.size(), hb.countKind(0), hb.countKind(1), hb.countKind(2), hb.extraEligible, hb.candEdges, hb.urquhartEdges, tl, ml, hb.allLandmassesLinked, sh / n, hd / n));
        b.append(String.format("  \"roads\": {\"hubLinks\": %d, \"loops\": %d, \"dockRoads\": %d, \"failedRoutes\": %d, \"roadTiles\": %d, \"bridges\": %d, \"bridgeTiles\": %d, \"passTiles\": %d},%n",
                rd.edges, rd.loops, rd.dockRoads, rd.failed, rd.cells, rd.bridges, rd.bridgeTiles, rd.passTiles));
        b.append(String.format("  \"reachability\": {\"hubs\": %d, \"reachedBeforeRepair\": %d, \"repairRoads\": %d, \"reachedAfter\": %d, \"ok\": %b, \"ferryOnlyHubs\": %d, \"walkableLandReachedPct\": %.1f, \"unreachedBefore\": %s, \"unreachedAfter\": %s},%n",
                v.hubs, v.reachedBefore, v.repairs, v.reachedAfter, v.ok, v.ferryHubsOnly, 100f * v.walkReached / Math.max(1, v.walkable), list(v.unreachedBefore), list(v.unreachedAfter)));
        b.append("  \"timingsMsMedianOf").append(tot.length).append("\": {");
        for (int k = 0; k < stt.length; k++) b.append(String.format("%s%s: %.1f", k > 0 ? ", " : "", q(WorldGen.STAGES[k]), med(stt[k]) / 1e6));
        b.append(String.format(", \"TOTAL\": %.1f},%n", med(tot) / 1e6));
        b.append("  \"log\": ").append(list(g.log)).append("\n}\n");
        return b.toString();
    }

    static int smallest(Territories tr) { int b = 0; for (int t = 1; t < tr.T; t++) if (tr.area[t] < tr.area[b]) b = t; return b; }
    static int median(List<Integer> v) { Integer[] a = v.toArray(new Integer[0]); Arrays.sort(a); return a[a.length / 2]; }
    static String list(List<String> l) { StringBuilder b = new StringBuilder("["); for (int k = 0; k < l.size(); k++) b.append(k > 0 ? ", " : "").append(q(l.get(k))); return b.append("]").toString(); }
}
