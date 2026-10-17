import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The set list and the plane table, read from data files (data over Java): planes.tsv (climate affinity, island preference,
 * color, core shape) and sets.tsv (code, type, date, plane, core flag, fold target). Pure java.io, no AWT.
 * With foldReprints, Alchemy sets and plane remasters ride on their parent territory and generic masters ride on the
 * core set nearest in date, so 189 editions become 146 territories.
 */
final class SetTable {
    static final class Plane {
        String name; float T, M, E, coast, islandPref; int color; boolean square, exempt; int nSets;
    }
    static final class SetDef {
        String code, type, date, name; int plane; boolean core; float T, M; final List<String> riders = new ArrayList<>();
    }

    final List<Plane> planes = new ArrayList<>();
    final List<SetDef> sets = new ArrayList<>();
    int rawCount, coreCount;

    static SetTable load(String dir, boolean fold) throws Exception {
        SetTable t = new SetTable();
        Map<String, Integer> pIdx = new HashMap<>();
        for (String[] r : rows(dir + "/planes.tsv")) {
            Plane p = new Plane(); p.name = r[0]; p.T = f(r[1]); p.M = f(r[2]); p.E = f(r[3]); p.coast = f(r[4]); p.islandPref = f(r[5]);
            p.color = Integer.parseInt(r[6], 16); p.square = r[7].equals("square"); p.exempt = r[8].equals("1");
            pIdx.put(p.name, t.planes.size()); t.planes.add(p);
        }
        List<String[]> raw = rows(dir + "/sets.tsv"); t.rawCount = raw.size();
        Map<String, SetDef> byCode = new HashMap<>(); List<String[]> folded = new ArrayList<>();
        for (String[] r : raw) {
            if (fold && !r[6].equals("-")) { folded.add(r); continue; }
            SetDef s = new SetDef(); s.code = r[0]; s.type = r[1]; s.date = r[2]; s.name = r[3]; s.plane = pIdx.get(r[4]); s.core = r[5].equals("1");
            t.sets.add(s); byCode.put(s.code, s);
        }
        for (String[] r : folded) {
            SetDef host = null;
            if (r[6].equals("@CORE")) {           // generic masters ride on the core set closest in release date
                long best = Long.MAX_VALUE;
                for (SetDef s : t.sets) if (s.core) { long d = Math.abs(days(s.date) - days(r[2])); if (d < best) { best = d; host = s; } }
            } else host = byCode.get(r[6]);
            if (host != null) host.riders.add(r[0]);
        }
        for (SetDef s : t.sets) {
            t.planes.get(s.plane).nSets++; if (s.core) t.coreCount++;
            s.T = t.planes.get(s.plane).T; s.M = t.planes.get(s.plane).M;
        }
        for (String[] r : rows(dir + "/set_climate.tsv")) { SetDef s = byCode.get(r[0]); if (s != null) { s.T = f(r[1]); s.M = f(r[2]); } }
        return t;
    }

    static long days(String iso) { String[] p = iso.split("-"); return Long.parseLong(p[0]) * 372 + Long.parseLong(p[1]) * 31 + Long.parseLong(p[2]); }
    static float f(String s) { return Float.parseFloat(s); }

    static List<String[]> rows(String path) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            for (String l; (l = br.readLine()) != null; ) { if (l.isEmpty() || l.startsWith("#")) continue; out.add(l.split("\t")); }
        }
        return out;
    }
}
