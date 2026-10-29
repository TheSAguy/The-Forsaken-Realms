package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Round 822 (the user: "a very comprehensive custom difficulty. All those settings we added in config tables";
 * study notes/custom-difficulty-feasibility.md): a save's own difficulty, on top of its preset.
 * <p>
 * The preset (Easy/Normal/Hard/Insane) stays the save's difficulty and its name stays the key every difficulty rule
 * reads ({@code isHardorInsaneDifficulty}, the {@code ...For(name)} switches, {@code difficultyIndex()}). A custom save
 * also keeps a map of knob id -> value ({@code AdventurePlayer.customDifficulty}, saved as one "customDifficulty"
 * string; absent = the preset, so an older save plays exactly as before). The knobs are defined in
 * {@code config tables/custom_difficulty.json}: each names its targets, its range and its scope. A value with no knob
 * there is dropped and one outside its range is clamped, so a hand-edited save or a shared code can only reach what the
 * file exposes. A plane without the file (every stock plane) has no knobs, so nothing applies.
 * <p>
 * Targets:
 * <ul>
 * <li>{@code tuning.field}: a TuningData field. The save's values go into a per-save copy of the plane's settings.json
 * ({@link Config#setEffectiveTuningData}); every existing {@code getTuningData()} read sees it unchanged.</li>
 * <li>{@code tuning.field{D}}: the field's per-difficulty set (attackingMagesEasy ... Insane) and
 * {@code tuning.field[*]}: every entry of a per-difficulty array. The value goes into EVERY column, so the knob means
 * the same whatever preset the run uses - a New Game+ onto another preset keeps it, and world generation (which runs
 * before New Game+ applies its new preset) reads it too.</li>
 * <li>{@code difficulty.field}: a DifficultyData field on the player's own copy (life, enemy health, sell factor, the
 * starting kit). Also applied over the config preset wherever a run reads that (the Ring's gifts, New Game+).</li>
 * </ul>
 * A knob's mode is "set" (the value replaces) or "scale" (the value multiplies each target's own base value).
 * <p>
 * Applied on every load (before anything else in the player's load), every new game (before world generation) and
 * every New Game+ difficulty change; a save without overrides resets to the plane's own settings. {@code [TFR-Custom]}
 * logs the overrides on each load and new game, every drop or clamp, and every change mid-run. Console: {@code custom}.
 */
public final class CustomDifficulty {
    private CustomDifficulty() {
    }

    public static final String SAVE_KEY = "customDifficulty";
    static final String FILE = "config tables/custom_difficulty.json";
    public static final String SCOPE_NEW_GAME = "newGame";
    public static final String SCOPE_ANYTIME = "anytime";

    /** One exposed setting, from custom_difficulty.json. */
    public static final class Knob {
        public String id, label, category, group, tooltip, scope = SCOPE_ANYTIME, level = "basic", mode = "set";
        /** Round 823: how the panel shows the value - "percent" (1.2 shows as 120%) or "" as it is. */
        public String format = "";
        /** Round 831: a row column that gives way so each row of a spawn/armory table keeps its own total ("common"). */
        public String rebalance = "";
        public float min = Float.NaN, max = Float.NaN, step = Float.NaN;
        /** "int", "float" or "bool" - from the first target's field ("float" for a scale knob). */
        public String type;
        final List<String> targets = new ArrayList<>();

        public boolean isNewGameOnly() {
            return SCOPE_NEW_GAME.equals(scope);
        }

        boolean isScale() {
            return "scale".equals(mode);
        }
    }

    /** A resolved target: its source ("tuning", "difficulty", "rule"), a field, and an array index (-1 = the field
     *  itself, ALL = every entry). */
    private static final class Target {
        final String source;
        final boolean tuning;
        final Field field;
        final int index;
        /** Round 831: the root field holding the rows (an array, a map of arrays, or one object) the leaf {@code field}
         *  is read on - "spawn.weekBrackets.rare" - or null for a plain field of the root. */
        final Field rows;

        Target(String source, Field field, int index) {
            this(source, field, index, null);
        }

        Target(String source, Field field, int index, Field rows) {
            this.source = source;
            this.tuning = "tuning".equals(source);
            this.field = field;
            this.index = index;
            this.rows = rows;
        }

        /** Round 831: a TFR table of its own (spawn_tier_weighting.json, armory_rarity.json), per save. */
        boolean table() {
            return "spawn".equals(source) || "armory".equals(source);
        }

        boolean difficulty() {
            return "difficulty".equals(source);
        }

        boolean rule() {
            return "rule".equals(source);
        }

        boolean config() {
            return "config".equals(source);
        }
    }

    private static final int ALL = -2;

    private static LinkedHashMap<String, Knob> knobs;
    /** Round 823: the panel's tab order (the file's "categories"; a knob's category missing there goes after them). */
    private static final List<String> categories = new ArrayList<>();
    private static Config knobsFor;
    /** What the next new game starts with (the console's "custom next"; the Advanced panel later). */
    private static final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    private static int changesThisLaunch;

    // ---- metadata -----------------------------------------------------------------------------------------------

    /** The plane's knobs, in file order (read once per Config). Empty when the plane has no custom_difficulty.json. */
    public static synchronized Map<String, Knob> knobs() {
        Config config = Config.instance();
        if (knobs != null && knobsFor == config)
            return knobs;
        knobsFor = config;
        knobs = new LinkedHashMap<>();
        FileHandle file = config.getFile(FILE);
        if (file == null || !file.exists())
            return knobs;
        int skipped = 0;
        try {
            JsonValue root = new JsonReader().parse(file);
            JsonValue cats = root.get("categories");
            categories.clear();
            for (JsonValue c = cats == null ? null : cats.child; c != null; c = c.next)
                categories.add(c.asString());
            JsonValue list = root.get("knobs");
            for (JsonValue v = list == null ? null : list.child; v != null; v = v.next) {
                Knob k = new Knob();
                k.id = v.getString("id", null);
                k.label = v.getString("label", k.id);
                k.category = v.getString("category", "Other");
                k.group = v.getString("group", "");
                k.tooltip = v.getString("tooltip", "");
                k.scope = v.getString("scope", SCOPE_ANYTIME);
                k.level = v.getString("level", "basic");
                k.mode = v.getString("mode", "set");
                k.min = v.getFloat("min", Float.NaN);
                k.max = v.getFloat("max", Float.NaN);
                k.step = v.getFloat("step", Float.NaN);
                k.format = v.getString("format", "");
                k.rebalance = v.getString("rebalance", "");
                JsonValue t = v.get("targets");
                if (t != null && t.isArray())
                    for (JsonValue e = t.child; e != null; e = e.next)
                        k.targets.add(e.asString());
                else if (v.has("target"))
                    k.targets.add(v.getString("target"));
                String problem = validate(k);
                if (problem != null) {
                    skipped++;
                    System.err.println("[TFR-Custom] " + FILE + ": knob " + k.id + " skipped - " + problem);
                    continue;
                }
                knobs.put(k.id, k);
            }
        } catch (Exception e) {
            System.err.println("[TFR-Custom] " + FILE + " could not be read - no custom difficulty knobs: " + e);
            knobs.clear();
            return knobs;
        }
        System.out.println("[TFR-Custom] " + knobs.size() + " knob(s) from " + FILE + (skipped > 0 ? ", " + skipped + " skipped" : ""));
        return knobs;
    }

    /** Null when the knob is usable; else why not. Also sets its type. */
    private static String validate(Knob k) {
        if (k.id == null || k.id.isEmpty() || !k.id.matches("[A-Za-z0-9_.]+"))
            return "no id, or an id with characters other than letters, digits, '_' and '.'";
        if (knobs.containsKey(k.id))
            return "a second knob with this id";
        if (k.targets.isEmpty())
            return "no target";
        String type = null;
        for (String spec : k.targets) {
            List<Target> resolved = resolve(spec, null);
            if (resolved.isEmpty())
                return "target " + spec + " names no field";
            for (Target t : resolved) {
                String tt = typeOf(t);
                if (tt == null)
                    return "target " + spec + " is not a number or a true/false field";
                if (type == null)
                    type = tt;
                else if (!type.equals(tt) && !(isNumber(type) && isNumber(tt)))
                    return "targets of different kinds (" + type + ", " + tt + ")";
            }
        }
        if (k.isScale() && "bool".equals(type))
            return "a scale knob on a true/false field";
        k.type = k.isScale() ? "float" : type;
        return null;
    }

    private static boolean isNumber(String type) {
        return "int".equals(type) || "float".equals(type);
    }

    private static String typeOf(Target t) {
        Class<?> c = t.field.getType();
        if (t.index != -1 && t.rows == null) { // round 831: a row field is the row's own number
            if (!c.isArray())
                return null;
            c = c.getComponentType();
        }
        if (c == int.class || c == long.class)
            return "int";
        if (c == float.class || c == double.class)
            return "float";
        if (c == boolean.class)
            return "bool";
        return null;
    }

    /**
     * The fields a target spec names. presetName null = every difficulty's column ({D} and [*] in full, the rest as
     * named); a preset name = only that preset's column (for the preset's own value).
     */
    private static List<Target> resolve(String spec, String presetName) {
        List<Target> out = new ArrayList<>();
        int dot = spec.indexOf('.');
        if (dot < 0)
            return out;
        String source = spec.substring(0, dot);
        String name = spec.substring(dot + 1);
        // round 825: "rule" - the player's own switches (SettingData), per save - see rule()
        Class<?> cls = "tuning".equals(source) ? TuningData.class : "difficulty".equals(source) ? DifficultyData.class
                : "rule".equals(source) ? forge.adventure.data.SettingData.class
                : "config".equals(source) ? forge.adventure.data.ConfigData.class // round 825: plane flags
                : tableClass(source); // round 831
        if (cls == null)
            return out;
        // round 831: "spawn.weekBrackets.rare" - a field of every row the root field holds
        int dotInside = name.indexOf('.');
        if (dotInside > 0 && tableClass(source) != null) {
            Field rows = field(cls, name.substring(0, dotInside));
            Class<?> rowClass = rows == null ? null : rowClass(rows);
            Field leaf = rowClass == null ? null : field(rowClass, name.substring(dotInside + 1));
            if (leaf != null)
                out.add(new Target(source, leaf, ALL, rows));
            return out;
        }
        DifficultyData[] all = Config.instance().getConfigData().difficulties;
        if (name.endsWith("{D}")) {
            String stem = name.substring(0, name.length() - 3);
            if (all != null)
                for (DifficultyData d : all)
                    if (d != null && d.name != null && (presetName == null || d.name.equals(presetName))) {
                        Field f = field(cls, stem + d.name);
                        if (f != null)
                            out.add(new Target(source, f, -1));
                    }
            return out;
        }
        int index = -1;
        int bracket = name.indexOf('[');
        if (bracket > 0 && name.endsWith("]")) {
            String inside = name.substring(bracket + 1, name.length() - 1);
            name = name.substring(0, bracket);
            if ("*".equals(inside)) {
                index = ALL;
                if (presetName != null)
                    index = Math.max(0, presetIndex(presetName));
            } else {
                try {
                    index = Integer.parseInt(inside);
                } catch (NumberFormatException e) {
                    return out;
                }
            }
        }
        Field f = field(cls, name);
        if (f != null)
            out.add(new Target(source, f, index));
        return out;
    }

    /** Round 831: the root class of a table source, or null. */
    private static Class<?> tableClass(String source) {
        return "spawn".equals(source) ? forge.adventure.data.SpawnTierWeightData.class
                : "armory".equals(source) ? forge.adventure.data.ArmoryRarityData.class : null;
    }

    /** Round 831: the class of one row a table field holds (an array's element, a map's array element, an object). */
    private static Class<?> rowClass(Field rows) {
        Class<?> c = rows.getType();
        if (c.isArray())
            return c.getComponentType();
        if (com.badlogic.gdx.utils.ObjectMap.class.isAssignableFrom(c)) // armory_rarity's venueBrackets
            return forge.adventure.data.ArmoryRarityData.WeekBracket.class;
        return c;
    }

    private static Field field(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            int m = f.getModifiers();
            return Modifier.isStatic(m) || Modifier.isFinal(m) ? null : f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static int presetIndex(String presetName) {
        DifficultyData[] all = Config.instance().getConfigData().difficulties;
        if (all != null && presetName != null)
            for (int i = 0; i < all.length; i++)
                if (all[i] != null && presetName.equals(all[i].name))
                    return i;
        return -1;
    }

    /** The plane's own preset of this name (config.json), or null. */
    public static DifficultyData configPreset(String presetName) {
        int i = presetIndex(presetName);
        return i < 0 ? null : Config.instance().getConfigData().difficulties[i];
    }

    // ---- values -------------------------------------------------------------------------------------------------

    /** The value in its canonical text, clamped to the knob's range; null when it does not read as the knob's type. */
    static String clean(Knob k, String raw) {
        if (raw == null)
            return null;
        String s = raw.trim();
        if ("bool".equals(k.type)) {
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equals("1"))
                return "true";
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off") || s.equals("0"))
                return "false";
            return null;
        }
        float f;
        try {
            f = Float.parseFloat(s.endsWith("%") ? s.substring(0, s.length() - 1) : s) / (s.endsWith("%") ? 100f : 1f);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Float.isNaN(f) || Float.isInfinite(f))
            return null;
        if (!Float.isNaN(k.min))
            f = Math.max(k.min, f);
        if (!Float.isNaN(k.max))
            f = Math.min(k.max, f);
        return "int".equals(k.type) ? String.valueOf(Math.round(f)) : trim(f);
    }

    private static String trim(float f) {
        String s = String.format(Locale.ROOT, "%.4f", f);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Known knobs only, each clamped; logs every drop and clamp. */
    public static LinkedHashMap<String, String> sanitize(Map<String, String> in, String why) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (in == null)
            return out;
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : in.entrySet()) {
            Knob k = all.get(e.getKey());
            if (k == null) {
                System.out.println("[TFR-Custom] " + why + ": dropped " + e.getKey() + "=" + e.getValue() + " (no such knob in " + FILE + ")");
                continue;
            }
            String v = clean(k, e.getValue());
            if (v == null) {
                System.out.println("[TFR-Custom] " + why + ": dropped " + k.id + "=" + e.getValue() + " (not " + kind(k) + ")");
                continue;
            }
            if (!sameValue(k, v, e.getValue()))
                System.out.println("[TFR-Custom] " + why + ": clamped " + k.id + " " + e.getValue() + " -> " + v
                        + " (range " + range(k) + ")");
            out.put(k.id, v);
        }
        return out;
    }

    private static boolean sameValue(Knob k, String clean, String raw) {
        if ("bool".equals(k.type))
            return true;
        try {
            return Math.abs(Float.parseFloat(clean) - Float.parseFloat(raw.trim())) < 1e-4f;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The knob's type in words, for a refusal. */
    private static String kind(Knob k) {
        return "int".equals(k.type) ? "a whole number" : "bool".equals(k.type) ? "on or off" : "a number";
    }

    private static String range(Knob k) {
        return (Float.isNaN(k.min) ? "-" : trim(k.min)) + ".." + (Float.isNaN(k.max) ? "-" : trim(k.max));
    }

    public static String encode(Map<String, String> overrides) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : overrides.entrySet())
            sb.append(sb.length() == 0 ? "" : ";").append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }

    public static LinkedHashMap<String, String> decode(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (text == null)
            return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0)
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    // ---- New Game+ (round 839) ----------------------------------------------------------------------------------

    /** A slot's custom difficulty, read from its file without loading it: its overrides (sanitized), whether it keeps
     *  its own rules, and its preset's name. {@code ok} is false when the file could not be read. */
    public static final class SavePeek {
        public final LinkedHashMap<String, String> overrides = new LinkedHashMap<>();
        public boolean rulesPerSave, ok;
        public String preset;
    }

    /**
     * Round 839 (the user's OK, through the manager, on notes/r839-mock.png): New Game+'s Advanced... needs the chosen
     * slot's own settings before Start loads it. The same stream WorldSave.readSave reads (header, then the main data),
     * the player's part only, nothing applied. A save from before round 822 has no settings: none.
     */
    public static SavePeek peekSave(int slot) {
        SavePeek peek = new SavePeek();
        java.io.File file = new java.io.File(WorldSave.getSaveFile(slot));
        if (!file.exists())
            return peek;
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             java.util.zip.InflaterInputStream inf = new java.util.zip.InflaterInputStream(in);
             java.io.ObjectInputStream ois = new java.io.ObjectInputStream(inf)) {
            ois.readObject(); // the header
            forge.adventure.util.SaveFileData main = (forge.adventure.util.SaveFileData) ois.readObject();
            forge.adventure.util.SaveFileData player = main.readSubData("player");
            if (player != null) {
                peek.rulesPerSave = player.containsKey("rulesPerSave") && player.readBool("rulesPerSave");
                if (player.containsKey("difficultyName"))
                    peek.preset = player.readString("difficultyName");
                if (player.containsKey(SAVE_KEY))
                    peek.overrides.putAll(sanitize(decode(player.readString(SAVE_KEY)), "New Game+ (slot " + slot + ")"));
            }
            peek.ok = true;
        } catch (Exception e) {
            System.err.println("[TFR-Custom] New Game+: could not read slot " + slot + "'s settings: " + e);
        }
        return peek;
    }

    /**
     * Round 839: Start of a New Game+ whose settings were edited in its dialog - the loaded save's settings give way to
     * the edit before the new world is generated (so the world-generation knobs and updateDifficulty's overrides take
     * it). The rules come with the edit, so the save keeps its own from now on.
     */
    public static void replaceForNewGamePlus(AdventurePlayer p, Map<String, String> edit) {
        if (p == null || edit == null)
            return;
        String before = encode(p.getCustomDifficulty());
        LinkedHashMap<String, String> clean = sanitize(edit, "New Game+");
        p.getCustomDifficulty().clear();
        p.getCustomDifficulty().putAll(clean);
        p.setRulesPerSave(true);
        applyTuning(p.getCustomDifficulty());
        System.out.println("[TFR-Custom] New Game+: the panel's settings replace the save's: "
                + (before.isEmpty() ? "(none)" : before) + " -> " + (clean.isEmpty() ? "(none)" : encode(clean)));
    }

    // ---- share codes (round 827) --------------------------------------------------------------------------------

    public static final String CODE_PREFIX = "TFR1-";

    /**
     * Round 827 (the user's "Yes, all three" on notes/r827-mock.png): a setup as text to trade -
     * {@code TFR1-<preset>-<raw deflate + URL-safe base64 of id=value;...>}. The difficulty changes only: the rules
     * (rule.* knobs) are each player's own switches and stay out of a code.
     */
    public static String shareCode(String presetName, Map<String, String> overrides) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        if (overrides != null)
            for (Map.Entry<String, String> e : overrides.entrySet())
                if (!isRule(e.getKey()))
                    body.put(e.getKey(), e.getValue());
        byte[] text = encode(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9, true);
        deflater.setInput(text);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!deflater.finished())
            out.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        return CODE_PREFIX + (presetName == null ? "?" : presetName) + "-"
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** A pasted code, read: its preset and values (clamped), and what was clamped or dropped, in the panel's words. */
    public static final class Pasted {
        public String preset;
        public final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        public final List<String> clamped = new ArrayList<>();
        public final List<String> dropped = new ArrayList<>();
        /** Why the text is no code at all, or null. */
        public String error;
    }

    /** Reads a share code (whitespace and line breaks around or inside it are ignored). Never throws. */
    public static Pasted readCode(String text) {
        Pasted p = new Pasted();
        String s = text == null ? "" : text.replaceAll("\\s+", "");
        if (!s.startsWith(CODE_PREFIX)) {
            p.error = s.isEmpty() ? "the clipboard is empty" : "not a TFR1 code (it starts with " + CODE_PREFIX + ")";
            return p;
        }
        int dash = s.indexOf('-', CODE_PREFIX.length());
        if (dash < 0) {
            p.error = "the code is cut short";
            return p;
        }
        p.preset = s.substring(CODE_PREFIX.length(), dash);
        String body;
        try {
            byte[] data = java.util.Base64.getUrlDecoder().decode(s.substring(dash + 1));
            java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
            inflater.setInput(data);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    break;
                out.write(buf, 0, n);
                if (out.size() > 8192)
                    break;
            }
            boolean whole = inflater.finished();
            inflater.end();
            if (!whole)
                throw new java.util.zip.DataFormatException("cut short");
            body = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            p.error = "the code is damaged (a character missing or changed?)";
            return p;
        }
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : decode(body).entrySet()) {
            Knob k = all.get(e.getKey());
            if (k == null) {
                p.dropped.add("\"" + e.getKey() + "\" is not a setting in this version");
                continue;
            }
            if (isRule(k)) {
                p.dropped.add(k.label + " (a rule - your own switch)");
                continue;
            }
            String v = clean(k, e.getValue());
            if (v == null) {
                p.dropped.add(k.label + " \"" + e.getValue() + "\" is not " + kind(k));
                continue;
            }
            if (!sameValue(k, v, e.getValue()))
                p.clamped.add(k.label + " " + display(k, e.getValue()) + " -> " + display(k, v)
                        + (v.equals(clean(k, String.valueOf(k.max))) ? " (the most)" : " (the least)"));
            p.values.put(k.id, v);
        }
        return p;
    }

    // ---- applying -----------------------------------------------------------------------------------------------

    /** The run's TuningData: the plane's own when there are no tuning overrides, else a copy with them on top. */
    public static void applyTuning(Map<String, String> overrides) {
        Config.instance().setEffectiveTuningData(buildTuning(overrides));
        Config.instance().setEffectiveConfigData(buildConfig(overrides)); // round 825: the config.* knobs
        // round 831: spawn_tier_weighting.json and armory_rarity.json, per save
        Config.instance().setEffectiveSpawnTierWeightData((forge.adventure.data.SpawnTierWeightData) buildTable(overrides, "spawn",
                Config.instance().getBaseSpawnTierWeightData(), forge.adventure.data.SpawnTierWeightData.class));
        Config.instance().setEffectiveArmoryRarityData((forge.adventure.data.ArmoryRarityData) buildTable(overrides, "armory",
                Config.instance().getBaseArmoryRarityData(), forge.adventure.data.ArmoryRarityData.class));
    }

    /**
     * Round 831: a TFR table with the overrides that reach it, or null when none does. A deep copy (a libGDX Json round
     * trip - the rows are objects, and a field copy would share them with the plane's own). A plain field is written
     * as tuning's are (from the plane's value); a row field ("spawn.weekBrackets.rare") on every row, compounding with
     * other knobs on the same column (a scale multiplies the copy's value), and a zero stays zero, so a rank a week
     * closes stays closed. A knob with "rebalance" then gives each row its plane total back through that column.
     */
    private static Object buildTable(Map<String, String> overrides, String source, Object base, Class<?> cls) {
        if (overrides == null || overrides.isEmpty() || base == null)
            return null;
        Object effective = null;
        Map<Field, String> rebalance = new LinkedHashMap<>();
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            Knob k = all.get(e.getKey());
            if (k == null)
                continue;
            for (String spec : k.targets)
                for (Target t : resolve(spec, null))
                    if (source.equals(t.source)) {
                        if (effective == null)
                            effective = deepCopy(base, cls);
                        if (effective == null)
                            return null;
                        if (t.rows == null) {
                            write(effective, base, t, k, e.getValue());
                            continue;
                        }
                        for (Object row : rowsOf(effective, t.rows))
                            try {
                                double cur = ((Number) t.field.get(row)).doubleValue();
                                double v = Double.parseDouble(e.getValue());
                                t.field.set(row, convert(t.field.getType(), k.isScale() ? cur * v : (cur == 0 ? 0 : v)));
                            } catch (Exception ex) {
                                System.err.println("[TFR-Custom] could not apply " + k.id + " to " + t.rows.getName() + "." + t.field.getName() + ": " + ex);
                            }
                        if (!k.rebalance.isEmpty())
                            rebalance.put(t.rows, k.rebalance);
                    }
        }
        if (effective != null)
            for (Map.Entry<Field, String> r : rebalance.entrySet())
                rebalanceRows(rowsOf(effective, r.getKey()), rowsOf(base, r.getKey()), r.getValue());
        return effective;
    }

    /** Round 831: the rows a table field holds, in order (an array's, every array of a map's, or the one object). */
    private static List<Object> rowsOf(Object owner, Field rows) {
        List<Object> out = new ArrayList<>();
        try {
            Object v = rows.get(owner);
            if (v == null)
                return out;
            if (v.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(v); i++)
                    if (Array.get(v, i) != null)
                        out.add(Array.get(v, i));
            } else if (v instanceof com.badlogic.gdx.utils.ObjectMap) {
                com.badlogic.gdx.utils.Array<String> keys = ((com.badlogic.gdx.utils.ObjectMap<String, ?>) v).keys().toArray();
                keys.sort();
                for (String key : keys) {
                    Object a = ((com.badlogic.gdx.utils.ObjectMap<String, ?>) v).get(key);
                    for (int i = 0; a != null && a.getClass().isArray() && i < Array.getLength(a); i++)
                        if (Array.get(a, i) != null)
                            out.add(Array.get(a, i));
                }
            } else {
                out.add(v);
            }
        } catch (IllegalAccessException e) {
            System.err.println("[TFR-Custom] could not read " + rows.getName() + ": " + e);
        }
        return out;
    }

    private static final String[] RANKS = {"common", "uncommon", "rare", "mythic"};

    /** Round 831: each row back to its plane total through {@code column}; when the others outgrow the total, they are
     *  scaled down to fit it and the column is 0. Rows match the plane's by position. */
    private static void rebalanceRows(List<Object> rows, List<Object> baseRows, String column) {
        for (int i = 0; i < rows.size() && i < baseRows.size(); i++) {
            Object row = rows.get(i), baseRow = baseRows.get(i);
            try {
                Class<?> c = row.getClass();
                double total = 0, others = 0;
                for (String r : RANKS) {
                    total += c.getField(r).getFloat(baseRow);
                    if (!r.equals(column))
                        others += c.getField(r).getFloat(row);
                }
                if (others <= total) {
                    c.getField(column).setFloat(row, (float) (total - others));
                } else {
                    for (String r : RANKS)
                        if (!r.equals(column))
                            c.getField(r).setFloat(row, (float) (c.getField(r).getFloat(row) * total / others));
                    c.getField(column).setFloat(row, 0f);
                }
            } catch (Exception e) {
                System.err.println("[TFR-Custom] could not rebalance a row: " + e);
                return;
            }
        }
    }

    /** Round 831: a full copy of a TFR table through libGDX Json (both table classes round-trip, the -1 kept). */
    private static Object deepCopy(Object base, Class<?> cls) {
        try {
            com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json();
            return json.fromJson(cls, json.toJson(base, cls));
        } catch (Exception e) {
            System.err.println("[TFR-Custom] could not copy " + cls.getSimpleName() + " - its knobs are off for this save: " + e);
            return null;
        }
    }

    /** Round 825: the plane's ConfigData with the config.* overrides on (a field copy, arrays copied), or null when they
     *  change none. */
    private static forge.adventure.data.ConfigData buildConfig(Map<String, String> overrides) {
        forge.adventure.data.ConfigData base = Config.instance().getBaseConfigData();
        forge.adventure.data.ConfigData effective = null;
        if (overrides == null || overrides.isEmpty() || base == null)
            return null;
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            Knob k = all.get(e.getKey());
            if (k == null)
                continue;
            for (String spec : k.targets)
                for (Target t : resolve(spec, null))
                    if (t.config()) {
                        if (effective == null)
                            effective = (forge.adventure.data.ConfigData) copyFields(base, new forge.adventure.data.ConfigData(),
                                    forge.adventure.data.ConfigData.class);
                        write(effective, base, t, k, e.getValue());
                    }
        }
        return effective;
    }

    /** Round 823: the TuningData these overrides would give, without putting it in place (the new-game "?" dialog). */
    public static TuningData previewTuning(Map<String, String> overrides) {
        TuningData t = buildTuning(overrides);
        return t != null ? t : Config.instance().getBaseTuningData();
    }

    /** The plane's settings with the tuning overrides on, or null when they change none. */
    private static TuningData buildTuning(Map<String, String> overrides) {
        Config config = Config.instance();
        TuningData base = config.getBaseTuningData();
        TuningData effective = null;
        if (overrides != null && !overrides.isEmpty() && base != null) {
            Map<String, Knob> all = knobs();
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                Knob k = all.get(e.getKey());
                if (k == null)
                    continue;
                for (String spec : k.targets)
                    for (Target t : resolve(spec, null))
                        if (t.tuning) {
                            if (effective == null)
                                effective = copy(base);
                            write(effective, base, t, k, e.getValue());
                        }
            }
        }
        return effective;
    }

    /** Writes the difficulty.* overrides onto d (scale knobs scale the plane's preset of d's name). */
    public static void applyDifficulty(Map<String, String> overrides, DifficultyData d) {
        if (overrides == null || overrides.isEmpty() || d == null)
            return;
        DifficultyData preset = configPreset(d.name);
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            Knob k = all.get(e.getKey());
            if (k == null)
                continue;
            for (String spec : k.targets)
                for (Target t : resolve(spec, null))
                    if (t.difficulty())
                        write(d, preset != null ? preset : d, t, k, e.getValue());
        }
    }

    /** d itself without difficulty overrides; else a copy of d with them on (d is often config.json's own preset). */
    public static DifficultyData withOverrides(Map<String, String> overrides, DifficultyData d) {
        if (overrides == null || overrides.isEmpty() || d == null || !touchesDifficulty(overrides))
            return d;
        DifficultyData copy = (DifficultyData) copyFields(d, new DifficultyData(), DifficultyData.class);
        applyDifficulty(overrides, copy);
        return copy;
    }

    private static boolean touchesDifficulty(Map<String, String> overrides) {
        Map<String, Knob> all = knobs();
        for (String id : overrides.keySet()) {
            Knob k = all.get(id);
            if (k != null)
                for (String spec : k.targets)
                    if (spec.startsWith("difficulty."))
                        return true;
        }
        return false;
    }

    private static void write(Object obj, Object baseObj, Target t, Knob k, String value) {
        try {
            if (t.index == -1) {
                t.field.set(obj, convert(t.field.getType(), compute(k, value, t.field.get(baseObj))));
                return;
            }
            Object arr = t.field.get(obj);
            Object baseArr = t.field.get(baseObj);
            if (arr == null)
                return;
            int n = Array.getLength(arr);
            for (int i = 0; i < n; i++)
                if (t.index == ALL || t.index == i) {
                    Object baseValue = baseArr != null && i < Array.getLength(baseArr) ? Array.get(baseArr, i) : Array.get(arr, i);
                    Array.set(arr, i, convert(arr.getClass().getComponentType(), compute(k, value, baseValue)));
                }
        } catch (Exception e) {
            System.err.println("[TFR-Custom] could not apply " + k.id + "=" + value + " to " + t.field.getName() + ": " + e);
        }
    }

    private static Object compute(Knob k, String value, Object baseValue) {
        if ("bool".equals(k.type))
            return Boolean.parseBoolean(value);
        double v = Double.parseDouble(value);
        if (k.isScale() && baseValue instanceof Number)
            v = ((Number) baseValue).doubleValue() * v;
        return v;
    }

    private static Object convert(Class<?> c, Object v) {
        if (c == boolean.class)
            return v;
        double d = ((Number) v).doubleValue();
        if (c == int.class)
            return (int) Math.round(d);
        if (c == long.class)
            return Math.round(d);
        if (c == float.class)
            return (float) d;
        return d;
    }

    static TuningData copy(TuningData src) {
        return (TuningData) copyFields(src, new TuningData(), TuningData.class);
    }

    /** Every public instance field, arrays copied (so writing one never reaches the plane's own object). */
    private static Object copyFields(Object src, Object dst, Class<?> cls) {
        for (Field f : cls.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isFinal(m))
                continue;
            try {
                Object v = f.get(src);
                if (v != null && v.getClass().isArray()) {
                    int n = Array.getLength(v);
                    Object a = Array.newInstance(v.getClass().getComponentType(), n);
                    System.arraycopy(v, 0, a, 0, n);
                    v = a;
                }
                f.set(dst, v);
            } catch (IllegalAccessException e) {
                System.err.println("[TFR-Custom] could not copy " + f.getName() + ": " + e);
            }
        }
        return dst;
    }

    // ---- what a knob is now -------------------------------------------------------------------------------------

    /** The preset's own value of a knob, as text ("x1" for a scale knob). */
    public static String presetValue(Knob k, String presetName) {
        if (k.isScale())
            return "x1";
        for (String spec : k.targets)
            for (Target t : resolve(spec, presetName)) {
                if (t.rows != null)
                    continue; // round 831: row fields are scale knobs ("x1" above)
                Object owner = t.table() ? tableBase(t) : t.tuning ? Config.instance().getBaseTuningData() : t.rule() ? DEFAULT_SETTINGS
                        : t.config() ? Config.instance().getBaseConfigData() : configPreset(presetName);
                if (owner == null)
                    continue;
                try {
                    Object v = t.index == -1 ? t.field.get(owner) : Array.get(t.field.get(owner), Math.max(0, t.index));
                    return format(k, v);
                } catch (Exception e) {
                    return "?";
                }
            }
        return "?";
    }

    // ---- rules (round 825) --------------------------------------------------------------------------------------

    /** A fresh SettingData's values: what a rule is when nobody switched it (Notoriety on, leveling on, reloads judged). */
    private static final forge.adventure.data.SettingData DEFAULT_SETTINGS = new forge.adventure.data.SettingData();

    /** The knob whose target is rule.field, or null. */
    private static Knob ruleKnob(String field) {
        for (Knob k : knobs().values())
            for (String spec : k.targets)
                if (spec.equals("rule." + field))
                    return k;
        return null;
    }

    /** The profile's own switch (Settings, kept in the player profile) - the seed of a new game's rule. */
    public static boolean profileRule(String field) {
        forge.adventure.data.SettingData settings = Config.instance().getSettingData();
        try {
            return settings != null && settings.getClass().getField(field).getBoolean(settings);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean defaultRule(String field) {
        try {
            return DEFAULT_SETTINGS.getClass().getField(field).getBoolean(DEFAULT_SETTINGS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Round 825: the running save's own switch for a SettingData rule (notorietyDisabled, ascendanceDisabled,
     * reloadJabOptOut) - its custom value, else the default - when the save keeps its rules (created or taken into New
     * Game+ on round 825 or later, {@link AdventurePlayer#hasRulesPerSave}); null for an older save or a plane with no
     * knob for it, whose reader then uses the profile as before.
     */
    public static Boolean rule(String field) {
        AdventurePlayer p = AdventurePlayer.current();
        if (p == null || !p.hasRulesPerSave())
            return null;
        Knob k = ruleKnob(field);
        if (k == null)
            return null;
        String v = p.getCustomDifficulty().get(k.id);
        return v != null ? Boolean.parseBoolean(v) : defaultRule(field);
    }

    /** Round 825: a game is loaded or started (the start menu's own test for its Resume button). */
    public static boolean gameRunning() {
        try {
            return WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null
                    && WorldSave.getCurrentSave().getWorld().getData() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The profile's rules a new save starts with: every rule knob the profile has switched from its default. */
    private static LinkedHashMap<String, String> ruleSeeds() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Knob k : knobs().values())
            for (String spec : k.targets)
                if (spec.startsWith("rule.")) {
                    String field = spec.substring(5);
                    if (profileRule(field) != defaultRule(field))
                        out.put(k.id, String.valueOf(profileRule(field)));
                }
        return out;
    }

    /**
     * Round 825: Settings' switch for a rule while a game runs - the save's own value (an older save starts keeping its
     * rules here, seeded from the profile first), with round 822's change line. The profile keeps its own copy (the
     * caller writes it) as the next new game's seed. False when the plane has no knob for it.
     */
    public static boolean setRule(AdventurePlayer p, String field, boolean value, String source) {
        Knob k = ruleKnob(field);
        if (p == null || k == null)
            return false;
        if (!p.hasRulesPerSave()) {
            for (Map.Entry<String, String> e : ruleSeeds().entrySet())
                p.getCustomDifficulty().putIfAbsent(e.getKey(), e.getValue());
            p.setRulesPerSave(true);
            System.out.println("[TFR-Custom] rules: this save keeps its own rules from now on (seeded from the profile: "
                    + encode(ruleSeeds()) + ")");
        }
        change(p, k.id, value == defaultRule(field) ? null : String.valueOf(value), source);
        return true;
    }

    /** Round 825: New Game+ of a save from before per-save rules - it starts keeping them, seeded from the profile. */
    public static void onNewGamePlus(AdventurePlayer p) {
        if (p == null || p.hasRulesPerSave())
            return;
        for (Map.Entry<String, String> e : ruleSeeds().entrySet())
            p.getCustomDifficulty().putIfAbsent(e.getKey(), e.getValue());
        p.setRulesPerSave(true);
        System.out.println("[TFR-Custom] New Game+: the save keeps its own rules from now on (seeded from the profile)");
    }

    // ---- the panel (round 823) ----------------------------------------------------------------------------------

    /** The tab order: the file's "categories", then any category a knob names that the list leaves out. */
    public static List<String> categories() {
        Map<String, Knob> all = knobs();
        List<String> out = new ArrayList<>(categories);
        for (Knob k : all.values())
            if (!out.contains(k.category))
                out.add(k.category);
        return out;
    }

    /** The preset's own value in the knob's canonical text ("1" for a scale knob) - what "no change" means. */
    public static String presetRaw(Knob k, String presetName) {
        if (k.isScale())
            return "1";
        String v = presetValue(k, presetName);
        String c = clean(k, v);
        return c != null ? c : v;
    }

    /** The value as the panel shows it: 120% for a percent knob, x1.5 for a scale knob, On/Off, or the number. */
    public static String display(Knob k, String value) {
        if (value == null)
            return "?";
        if ("bool".equals(k.type))
            return Boolean.parseBoolean(value) ? "On" : "Off";
        if (k.isScale())
            return "x" + value;
        if ("percent".equals(k.format))
            try {
                return trim(Float.parseFloat(value) * 100f) + "%";
            } catch (NumberFormatException e) {
                return value;
            }
        return value;
    }

    /** One step up (dir 1) or down (dir -1) from value, clamped; the step is the knob's, else 1 or 0.05. */
    public static String step(Knob k, String value, int dir) {
        float step = !Float.isNaN(k.step) && k.step > 0 ? k.step : "int".equals(k.type) ? 1f : 0.05f;
        float v;
        try {
            v = Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return value;
        }
        // to the step's grid first, so 1.23 steps to 1.25 / 1.20 rather than 1.28 / 1.18
        float snapped = Math.round(v / step) * step;
        float next = Math.abs(snapped - v) > 1e-4f && Math.signum(snapped - v) == dir ? snapped : snapped + dir * step;
        String out = clean(k, trim(next));
        return out != null ? out : value;
    }

    /** True when the two values are the same setting (so a value stepped back to the preset is no change). */
    public static boolean same(Knob k, String a, String b) {
        if (a == null || b == null)
            return a == b;
        return "bool".equals(k.type) ? a.equals(b) : sameValue(k, a, b);
    }

    /** Search: the words of q, each found in the label, tooltip, group, category or id (case ignored). */
    public static boolean matches(Knob k, String q) {
        if (q == null || q.trim().isEmpty())
            return true;
        String hay = (k.label + " " + k.tooltip + " " + k.group + " " + k.category + " " + k.id).toLowerCase(Locale.ROOT);
        for (String word : q.toLowerCase(Locale.ROOT).trim().split("\\s+"))
            if (!hay.contains(word))
                return false;
        return true;
    }

    /** What the running game holds now (the first target, the preset's column) - the console's read-back. */
    static String currentValue(Knob k, AdventurePlayer p) {
        String preset = p == null || p.getDifficulty() == null ? null : p.getDifficulty().name;
        for (String spec : k.targets)
            for (Target t : resolve(spec, preset)) {
                if (t.rule()) {
                    Boolean r = rule(t.field.getName());
                    return t.field.getName() + "=" + (r == null ? "profile " + profileRule(t.field.getName()) : r);
                }
                if (t.rows != null) { // round 831: the first row's value
                    List<Object> rows = rowsOf(tableNow(t), t.rows);
                    try {
                        return t.rows.getName() + "[0]." + t.field.getName() + "=" + (rows.isEmpty() ? "-" : format(k, t.field.get(rows.get(0))));
                    } catch (IllegalAccessException e) {
                        return "?";
                    }
                }
                Object owner = t.table() ? tableNow(t) : t.tuning ? Config.instance().getTuningData() : t.config() ? Config.instance().getConfigData()
                        : p == null ? null : p.getDifficulty();
                if (owner == null)
                    continue;
                try {
                    Object v = t.index == -1 ? t.field.get(owner) : Array.get(t.field.get(owner), Math.max(0, t.index));
                    return t.field.getName() + (t.index == -1 ? "" : "[" + Math.max(0, t.index) + "]") + "=" + format(k, v);
                } catch (Exception e) {
                    return "?";
                }
            }
        return "?";
    }

    /** Round 831: a table source's plane object and its running one. */
    private static Object tableBase(Target t) {
        return "spawn".equals(t.source) ? Config.instance().getBaseSpawnTierWeightData() : Config.instance().getBaseArmoryRarityData();
    }

    private static Object tableNow(Target t) {
        return "spawn".equals(t.source) ? Config.instance().getSpawnTierWeightData() : Config.instance().getArmoryRarityData();
    }

    private static String format(Knob k, Object v) {
        if (v instanceof Number && !"int".equals(k.type))
            return trim(((Number) v).floatValue());
        return String.valueOf(v);
    }

    private static String shown(Knob k, String value) {
        return k.isScale() ? "x" + value : value;
    }

    public static boolean isCustom(AdventurePlayer p) {
        return p != null && count(p) > 0;
    }

    /** Round 825: a rule knob (rule.*) - the player's own switches, kept per save but never "Custom": the label means
     *  "difficulty values changed" (the manager's call - a profile with Notoriety off since round 772 must not make every
     *  new game Custom). Logged as "rules: ...". */
    public static boolean isRule(Knob k) {
        if (k == null)
            return false;
        for (String spec : k.targets)
            if (spec.startsWith("rule."))
                return true;
        return false;
    }

    /** Round 826: the SettingData field of a rule knob (rule.field), or null. */
    public static String ruleField(Knob k) {
        if (k != null)
            for (String spec : k.targets)
                if (spec.startsWith("rule."))
                    return spec.substring(5);
        return null;
    }

    private static boolean isRule(String id) {
        return isRule(knobs().get(id));
    }

    /** The difficulty changes of a map (its rules left out). */
    public static int countChanges(Map<String, String> overrides) {
        int n = 0;
        if (overrides != null)
            for (String id : overrides.keySet())
                if (!isRule(id))
                    n++;
        return n;
    }

    /** "notorietyOff, reloadAllowed" - the save's rules that are switched from their default, or "" for none. */
    public static String rules(AdventurePlayer p) {
        StringBuilder sb = new StringBuilder();
        if (p != null)
            for (Map.Entry<String, String> e : p.getCustomDifficulty().entrySet())
                if (isRule(e.getKey()))
                    sb.append(sb.length() == 0 ? "" : ",").append(e.getKey());
        return sb.toString();
    }

    /** "Custom (Hard)" for a custom save, else the preset's name. */
    public static String displayName(AdventurePlayer p) {
        String name = p == null || p.getDifficulty() == null ? "?" : p.getDifficulty().name;
        return isCustom(p) ? "Custom (" + name + ")" : name;
    }

    /** The difficulty for a log token: "Custom(Hard)" or "Hard". */
    public static String logName(AdventurePlayer p) {
        return displayName(p).replace(" ", "");
    }

    public static int count(AdventurePlayer p) {
        return p == null ? 0 : countChanges(p.getCustomDifficulty());
    }

    public static int changesThisLaunch() {
        return changesThisLaunch;
    }

    /** One line with every override and the preset's own value - what a log review reads first. */
    public static void logActive(String why, AdventurePlayer p) {
        if (p == null || p.getCustomDifficulty().isEmpty())
            return;
        String preset = p.getDifficulty() == null ? null : p.getDifficulty().name;
        StringBuilder sb = new StringBuilder();
        Map<String, Knob> all = knobs();
        for (Map.Entry<String, String> e : p.getCustomDifficulty().entrySet()) {
            Knob k = all.get(e.getKey());
            if (isRule(k))
                continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(' ')
                    .append(k == null ? "?" : presetValue(k, preset)).append(" -> ").append(k == null ? e.getValue() : shown(k, e.getValue()));
        }
        String rules = rules(p);
        System.out.println("[TFR-Custom] " + why + ": " + displayName(p) + ", " + count(p) + " override(s)"
                + (sb.length() > 0 ? ": " + sb : "") + (rules.isEmpty() ? "" : " | rules: " + rules)
                + (p.hasRulesPerSave() ? "" : " | rules from the profile (an older save)"));
    }

    // ---- new game -----------------------------------------------------------------------------------------------

    /** True once the Advanced panel handed its choice over (its rules then stand as chosen, seeds included). */
    private static boolean pendingFromPanel;

    /** What the next new game will start with (a copy) - round 825: with the profile's rules seeded in until the panel
     *  has handed over its own choice (the panel opens on this, so a rule switched back stays switched back). */
    public static LinkedHashMap<String, String> pending() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pending.entrySet())
            if (!isRule(e.getKey()))
                out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Round 825: the next new game's rules - the panel's choice once it has handed one over, else the profile's. The
     *  new-game screen sees pending() (difficulty only: its "Custom (Hard)" label); the panel opens on both. */
    public static LinkedHashMap<String, String> pendingRules() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (pendingFromPanel) {
            for (Map.Entry<String, String> e : pending.entrySet())
                if (isRule(e.getKey()))
                    out.put(e.getKey(), e.getValue());
        } else {
            out.putAll(ruleSeeds());
        }
        return out;
    }

    /** Everything the next new game starts with: the difficulty changes and the rules. */
    private static LinkedHashMap<String, String> pendingAll() {
        LinkedHashMap<String, String> out = pending();
        out.putAll(pendingRules());
        return out;
    }

    /** The Advanced panel (later rounds) hands its choice here; null or empty = the preset. */
    public static void setPending(Map<String, String> overrides) {
        pendingFromPanel = true;
        pending.clear();
        if (overrides != null)
            pending.putAll(sanitize(overrides, "next new game"));
    }

    /**
     * WorldSave.generateNewWorld, before world generation: takes the pending overrides (and clears them), applies their
     * tuning (or the plane's own), and returns the difficulty the player is created with - a copy of the preset with
     * the difficulty overrides on, or the preset itself. The map goes on the player with {@link #onCreated}.
     */
    public static DifficultyData beginNewGame(DifficultyData preset) {
        newGame = sanitize(pendingAll(), "new game");
        pending.clear();
        pendingFromPanel = false;
        applyTuning(newGame);
        return withOverrides(newGame, preset);
    }

    private static LinkedHashMap<String, String> newGame = new LinkedHashMap<>();

    /** WorldSave.generateNewWorld, right after AdventurePlayer.create(). */
    public static void onCreated(AdventurePlayer p) {
        p.getCustomDifficulty().clear();
        p.getCustomDifficulty().putAll(newGame);
        p.setRulesPerSave(true); // round 825: a new game keeps its own rules
        newGame = new LinkedHashMap<>();
        logActive("new game", p);
    }

    // ---- mid-run ------------------------------------------------------------------------------------------------

    /** Sets (value) or clears (null) one knob in the running game, and applies it now. Null = done, else why not. */
    public static String change(AdventurePlayer p, String id, String value, String source) {
        Knob k = knobs().get(id);
        if (k == null)
            return "No knob " + id + " (custom knobs lists them)";
        if (k.isNewGameOnly())
            return k.id + " is set at a new game only (custom next " + k.id + " <value>)";
        String preset = p.getDifficulty() == null ? null : p.getDifficulty().name;
        String before = p.getCustomDifficulty().containsKey(id) ? shown(k, p.getCustomDifficulty().get(id)) : presetValue(k, preset);
        String clean = null;
        if (value != null) {
            clean = clean(k, value);
            if (clean == null)
                return value + " is not " + kind(k);
            p.getCustomDifficulty().put(id, clean);
        } else {
            p.getCustomDifficulty().remove(id);
        }
        applyTuning(p.getCustomDifficulty());
        if (value == null)
            restoreDifficulty(k, p.getDifficulty());
        else
            applyDifficulty(p.getCustomDifficulty(), p.getDifficulty());
        changesThisLaunch++;
        int day = WorldSave.getCurrentSave() == null || WorldSave.getCurrentSave().getWorld() == null ? 0
                : WorldSave.getCurrentSave().getWorld().getCurrentDay();
        String clamped = clean != null && !sameValue(k, clean, value) ? " (clamped from " + value + ")" : "";
        System.out.println("[TFR-Custom] changed " + id + " " + before + " -> " + (clean == null ? presetValue(k, preset) + " (preset)" : shown(k, clean) + clamped)
                + " on day " + day + " (" + source + "), now " + displayName(p) + " with " + count(p) + " override(s)");
        return null;
    }

    /** A cleared difficulty knob goes back to the plane's preset value. */
    private static void restoreDifficulty(Knob k, DifficultyData d) {
        DifficultyData preset = d == null ? null : configPreset(d.name);
        if (preset == null)
            return;
        for (String spec : k.targets)
            for (Target t : resolve(spec, null))
                if (t.difficulty())
                    try {
                        t.field.set(d, t.field.get(preset));
                    } catch (IllegalAccessException e) {
                        System.err.println("[TFR-Custom] could not restore " + t.field.getName() + ": " + e);
                    }
    }

    // ---- console ------------------------------------------------------------------------------------------------

    /**
     * custom                     the running game's overrides, and the next new game's
     * custom knobs               every knob: id, type, range, scope, the preset's value
     * custom set ID VALUE        change an anytime knob now
     * custom reset ID|all        back to the preset (anytime knobs)
     * custom next ID VALUE       the next new game starts with it (any knob); custom next clear
     */
    public static String console(String[] s) {
        AdventurePlayer p = AdventurePlayer.current();
        String preset = p == null || p.getDifficulty() == null ? null : p.getDifficulty().name;
        if (s.length == 0 || "list".equals(s[0])) {
            StringBuilder sb = new StringBuilder(displayName(p) + ": ");
            if (!isCustom(p))
                sb.append("no overrides");
            for (Map.Entry<String, String> e : p.getCustomDifficulty().entrySet()) {
                Knob k = knobs().get(e.getKey());
                sb.append("\n ").append(e.getKey()).append(' ').append(k == null ? "?" : presetValue(k, preset)).append(" -> ")
                        .append(k == null ? e.getValue() : shown(k, e.getValue()))
                        .append(k == null ? "" : " (now " + currentValue(k, p) + ")");
            }
            sb.append("\nNext new game: ").append(pendingAll().isEmpty() ? "the preset" : encode(pendingAll()));
            return sb.toString();
        }
        if ("knobs".equals(s[0])) {
            StringBuilder sb = new StringBuilder(knobs().size() + " knob(s):");
            for (Knob k : knobs().values())
                sb.append("\n ").append(k.id).append(" (").append(k.type).append(' ').append(range(k)).append(", ")
                        .append(k.scope).append(", ").append(preset == null ? "" : preset + " " + presetValue(k, preset))
                        .append(", now ").append(currentValue(k, p)).append(')');
            return sb.toString();
        }
        if ("set".equals(s[0]) && s.length >= 3) {
            String problem = change(p, s[1], s[2], "console");
            return problem != null ? problem : s[1] + " set - " + displayName(p);
        }
        if ("reset".equals(s[0]) && s.length >= 2) {
            if ("all".equals(s[1])) {
                List<String> ids = new ArrayList<>(p.getCustomDifficulty().keySet());
                StringBuilder kept = new StringBuilder();
                for (String id : ids) {
                    String problem = change(p, id, null, "console");
                    if (problem != null)
                        kept.append(kept.length() == 0 ? "" : ", ").append(id);
                }
                return "Reset - " + displayName(p) + (kept.length() > 0 ? " (new-game knobs kept: " + kept + ")" : "");
            }
            String problem = change(p, s[1], null, "console");
            return problem != null ? problem : s[1] + " reset - " + displayName(p);
        }
        if ("next".equals(s[0]) && s.length >= 2) {
            if ("clear".equals(s[1])) {
                pending.clear();
                pendingFromPanel = false;
                return "The next new game uses its preset";
            }
            if (s.length < 3)
                return "custom next ID VALUE";
            Knob k = knobs().get(s[1]);
            if (k == null)
                return "No knob " + s[1];
            String v = clean(k, s[2]);
            if (v == null)
                return s[2] + " is not " + kind(k);
            pending.put(k.id, v);
            return "Next new game: " + encode(pendingAll());
        }
        if ("panel".equals(s[0])) // round 823: the new-game Advanced panel, for tests
            return forge.adventure.scene.CustomDifficultyScene.console(s);
        return "custom | custom knobs | custom set ID VALUE | custom reset ID|all | custom next ID VALUE | custom next clear"
                + " | custom panel [search TEXT]";
    }
}
