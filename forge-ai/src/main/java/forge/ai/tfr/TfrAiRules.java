package forge.ai.tfr;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * TFR round 1018: the TFR AI rules, an amended set of rules that take priority over Forge's AI decisions.
 *
 * <p>Forge decides first (heuristics plus Flat Monte Carlo); then the enabled rules review the decision in the order of
 * {@code res/ai/tfr-rules.json} and may keep, amend or replace it. Every change logs
 * {@code [TFR-AiRule] <rule id> <before> -> <after>}. Rules run only for a seat whose controller is a
 * {@link TfrPlayerControllerAi} (every seat made by {@code GamePlayerUtil.createAiPlayer}), and never inside the game
 * copies the AI plays out (Flat Monte Carlo rollouts, simulations): there a rule would cost time on every rollout,
 * flood the log, and could overwrite the attack a rollout is testing.</p>
 *
 * <p>Which rules run comes from the rules file (switchable without a build) and the duel's difficulty, which the
 * adventure hands over with {@link #setDifficultySource}; a game outside the adventure (deck tester, desktop modes,
 * benches) counts as the difficulty {@value #OTHER}. {@code -Dtfr.airules=on|off} (or {@link #force(Boolean)}) runs
 * every rule in the file, or none, whatever the difficulty: the switch the puzzle test and the benches use.</p>
 *
 * <p>Stock hooks: {@code GamePlayerUtil.createAiPlayer} (the seat), one line in {@code AiController.saSideEffects}
 * (rule etb-drawback-allow) and one in {@code AiController.refineAttackWithFlatMonteCarlo} (rule mc-attack-cut-guard).
 * All three are in {@code CORE_ENGINE_CHANGES.md}.</p>
 */
public final class TfrAiRules {
    public static final String TAG = "[TFR-AiRule]";
    /** The difficulty of a game that is not an adventure duel. */
    public static final String OTHER = "Other";
    public static final String FILE_NAME = "tfr-rules.json";

    /** One rule's entry in the rules file. */
    public static final class Entry {
        public final String id;
        public final String kind;
        public final Set<String> enabled;
        public final Map<String, Object> params;

        Entry(final String id, final String kind, final Set<String> enabled, final Map<String, Object> params) {
            this.id = id;
            this.kind = kind;
            this.enabled = enabled;
            this.params = params;
        }

        public int intParam(final String name, final int dflt) {
            final Object v = params.get(name);
            return v instanceof Number n ? n.intValue() : dflt;
        }

        public boolean boolParam(final String name, final boolean dflt) {
            final Object v = params.get(name);
            return v instanceof Boolean b ? b : dflt;
        }
    }

    /** The rules this build knows, by id. A rule in the file that is not here is ignored (with a log line). */
    static final Set<String> KNOWN = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            EtbDrawbackAllow.ID, AlphaStrikeCount.ID, McAttackCutGuard.ID)));

    private static volatile Map<String, Entry> rules = Collections.emptyMap();
    private static volatile String version = "none";
    private static volatile String difficulty;
    private static volatile java.util.function.Supplier<String> difficultySource;
    private static volatile Boolean forced = parseSwitch(System.getProperty("tfr.airules"));
    private static File loadedFile;
    private static long loadedStamp = Long.MIN_VALUE;
    private static final Map<String, AtomicInteger> FIRES = new ConcurrentHashMap<>();

    private TfrAiRules() {
    }

    // ---- the seat ----

    /**
     * The stock hook in {@code GamePlayerUtil.createAiPlayer}: (re)reads the rules file when it changed, then makes
     * the seat. {@code aiDir} is the {@code res/ai/} folder ({@code ForgeConstants.AI_PROFILE_DIR}).
     */
    public static LobbyPlayerAi newLobbyPlayer(final String name, final Set<AIOption> options, final String aiDir) {
        try {
            loadIfChanged(new File(aiDir, FILE_NAME));
        } catch (final RuntimeException e) {
            System.out.println(TAG + " rules file not read: " + e); // the seat must be made whatever the file says
        }
        return new TfrLobbyPlayerAi(name, options);
    }

    // ---- configuration ----

    /** Reads the rules file if it is new or changed since the last read. A missing or broken file runs no rule. */
    public static synchronized void loadIfChanged(final File f) {
        final long stamp = f.isFile() ? f.lastModified() : -1;
        if (f.equals(loadedFile) && stamp == loadedStamp) {
            return;
        }
        loadedFile = f;
        loadedStamp = stamp;
        if (stamp < 0) {
            rules = Collections.emptyMap();
            version = "none";
            System.out.println(TAG + " no rules file at " + f.getPath() + ": no rule runs");
            return;
        }
        try {
            load(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), f.getName());
        } catch (final Exception e) {
            rules = Collections.emptyMap();
            version = "broken";
            System.out.println(TAG + " rules file " + f.getPath() + " could not be read, no rule runs: " + e);
        }
    }

    /** Parses a rules document (also used by tests). */
    @SuppressWarnings("unchecked")
    public static synchronized void load(final String json, final String source) {
        final Map<String, Object> doc = (Map<String, Object>) TfrJson.parse(json);
        final Map<String, Entry> out = new LinkedHashMap<>();
        final Object list = doc.get("rules");
        if (list instanceof List<?> l) {
            for (final Object o : l) {
                if (!(o instanceof Map<?, ?>)) {
                    continue;
                }
                final Map<String, Object> m = (Map<String, Object>) o;
                final String id = String.valueOf(m.get("id"));
                if (!KNOWN.contains(id)) {
                    System.out.println(TAG + " " + source + ": unknown rule '" + id + "' ignored");
                    continue;
                }
                final Set<String> en = new LinkedHashSet<>();
                if (m.get("enabled") instanceof List<?> el) {
                    for (final Object d : el) {
                        en.add(String.valueOf(d));
                    }
                }
                final Map<String, Object> params = m.get("params") instanceof Map<?, ?> pm
                        ? (Map<String, Object>) pm : Collections.emptyMap();
                out.put(id, new Entry(id, String.valueOf(m.get("kind")), en, params));
            }
        }
        final Object v = doc.get("version");
        version = v instanceof Number n ? String.valueOf(n.intValue()) : String.valueOf(v);
        rules = Collections.unmodifiableMap(out);
        System.out.println(TAG + " rules v" + version + " loaded from " + source + ": " + describe());
    }

    /**
     * Where the difficulty comes from: the adventure registers a supplier that reads the loaded save's difficulty when a
     * seat is asked (DuelScene, once). Read live, so a guard duel, the deck tester or a duel after loading another save
     * never sees a stale value; it returns null (= {@value #OTHER}) when no adventure is loaded.
     */
    public static void setDifficultySource(final java.util.function.Supplier<String> source) {
        difficultySource = source;
    }

    /** A fixed difficulty that overrides the source (tests and benches); null to follow the source again. */
    public static void setDifficulty(final String name) {
        difficulty = name == null || name.isEmpty() ? null : name;
    }

    /** true runs every rule in the file at every difficulty, false runs none, null follows the file. */
    public static void force(final Boolean on) {
        forced = on;
    }

    public static Boolean forced() {
        return forced;
    }

    public static String version() {
        return version;
    }

    public static String difficultyName() {
        final String fixed = difficulty;
        if (fixed != null) {
            return fixed;
        }
        final java.util.function.Supplier<String> src = difficultySource;
        if (src != null) {
            try {
                final String d = src.get();
                if (d != null && !d.isEmpty()) {
                    return d;
                }
            } catch (final RuntimeException e) {
                // no adventure loaded (the main menu's deck tester): not an adventure duel
            }
        }
        return OTHER;
    }

    static Entry entry(final String id) {
        return rules.get(id);
    }

    /** Whether a rule is switched on for this difficulty (or by the force switch), whoever the player is. */
    public static boolean enabled(final String id) {
        final Boolean f = forced;
        if (Boolean.FALSE.equals(f)) {
            return false;
        }
        final Entry e = rules.get(id);
        if (e == null) {
            return false;
        }
        return Boolean.TRUE.equals(f) || e.enabled.contains(difficultyName());
    }

    /** Whether a rule reviews this player's decisions now: a TFR seat, the real game (not a copy), rule enabled. */
    static boolean isOn(final String id, final Player p) {
        return p != null && p.getController() instanceof TfrPlayerControllerAi
                && !p.getGame().isSimulationCopy() && enabled(id);
    }

    static String enabledHere() {
        final List<String> on = new ArrayList<>();
        for (final String id : rules.keySet()) {
            if (enabled(id)) {
                on.add(id);
            }
        }
        final Boolean f = forced;
        return (on.isEmpty() ? "no rule" : String.join(", ", on))
                + (f == null ? "" : " (forced " + (f ? "on" : "off") + ")");
    }

    private static String describe() {
        final List<String> parts = new ArrayList<>();
        for (final Entry e : rules.values()) {
            parts.add(e.id + " at " + (e.enabled.isEmpty() ? "no difficulty" : String.join("/", e.enabled)));
        }
        return parts.isEmpty() ? "no rules" : String.join("; ", parts);
    }

    /** The line a seat logs once at the start of a duel. */
    static String duelStartLine(final Player p) {
        return TAG + " rules v" + version + " difficulty=" + difficultyName() + " seat=" + p.getName() + ": " + enabledHere();
    }

    private static Boolean parseSwitch(final String v) {
        if (v == null) {
            return null;
        }
        return switch (v.trim().toLowerCase()) {
            case "on", "true", "1" -> Boolean.TRUE;
            case "off", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ---- the reviews ----

    /** The stock hook in {@code AiController.saSideEffects}: may lift a BadEtbEffects veto (rule etb-drawback-allow). */
    public static boolean allowEtbDrawback(final Player ai, final Card card, final SpellAbility sa) {
        return EtbDrawbackAllow.allow(ai, card, sa);
    }

    /**
     * The stock hook in {@code AiController.refineAttackWithFlatMonteCarlo} (round 1019): Flat Monte Carlo chose
     * {@code result.attackers} over the heuristic's {@code heuristic} attack, which is still in the combat. True keeps
     * the heuristic's attack (rule mc-attack-cut-guard). Never throws: a rule must not break Forge's attack.
     */
    public static boolean keepHeuristicAttack(final Player ai, final java.util.Map<Card, forge.game.GameEntity> heuristic,
                                              final forge.ai.simulation.FlatMonteCarloPicker.Result result) {
        try {
            return McAttackCutGuard.keepHeuristic(ai, heuristic, result);
        } catch (final RuntimeException e) {
            System.out.println(TAG + " " + McAttackCutGuard.ID + " error, Flat Monte Carlo's attack stands: " + e);
            return false;
        }
    }

    /** Called by {@link TfrPlayerControllerAi} after Forge declared its attackers. */
    static void reviewAttack(final Player attacker, final Combat combat) {
        AlphaStrikeCount.review(attacker, combat);
    }

    // ---- logging and counts ----

    static void fired(final String id, final String before, final String after) {
        FIRES.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        System.out.println(TAG + " " + id + " " + before + " -> " + after);
    }

    /**
     * A rule in shadow mode ({@code "shadow": true} in its params) logs the change it would make and makes none:
     * {@code [TFR-AiRule] <id> (shadow) <before> -> <after>}. Counted under {@code <id> (shadow)}.
     */
    static void shadow(final String id, final String before, final String after) {
        FIRES.computeIfAbsent(id + " (shadow)", k -> new AtomicInteger()).incrementAndGet();
        System.out.println(TAG + " " + id + " (shadow) " + before + " -> " + after);
    }

    /** How often each rule changed a decision since the last reset (benches and tests). */
    public static Map<String, Integer> fireCounts() {
        final Map<String, Integer> m = new TreeMap<>();
        FIRES.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public static void resetFireCounts() {
        FIRES.clear();
    }
}
