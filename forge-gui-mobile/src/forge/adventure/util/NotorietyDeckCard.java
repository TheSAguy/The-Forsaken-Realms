package forge.adventure.util;

import forge.StaticData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Round 793 (the user: "Swap as proposed"; design: C:\TFR\notes\notoriety-deck-permanents-proposal.md, a Discord idea
 * of Vr01's), reworked in round 799 (the user: "The additional spawn will not replace a wall, it will just be
 * additional. On Insane, this should move up to Win 40+, Hard Win 45+ and Normal 50+. A win Ratio of 95%+ (After 50
 * duels) will drop the needed notoriety by 1 level ... Not in Arenas."): from the difficulty's notoriety
 * (TuningData.notorietyDeckCardWinsByDifficulty - Easy never), an enemy also starts with one random permanent from its
 * OWN deck, moved out of the deck onto the battlefield, on top of every Wall.
 * <ul>
 *   <li>The gate: Normal 50, Hard 45, Insane 40; notorietyDeckCardRatioLevels levels (x notorietyWinsPerLevel) less
 *   while the player's last duels (AdventurePlayer.recentDuels, round 613's record - the duels notoriety counts) hold
 *   at least notorietyDeckCardRatioMinDuels of them and notorietyDeckCardRatioAtLeast of them won (50 duels, 95%).
 *   Never in an Arena duel, nor with Notoriety off in Settings (round 772).</li>
 *   <li>Eligible: a creature, artifact or enchantment of mana value notorietyDeckCardMinCost-MaxCost (1-3, the front
 *   face), no X; not a land, planeswalker, battle, Aura, Saga or Room; not 0 toughness; not banned
 *   (CommanderCards.restricted - commander-only cards are already stripped from enemy decks); not in
 *   notorietyDeckCardExclude. A non-creature needs something that works from the battlefield - a static, activated or
 *   other-than-its-own-zone-change trigger, or a keyword (so no Oblivion Ring). notorietyDeckCardEtb "noHostile" also
 *   drops a card whose own enter trigger targets, sacrifices or reaches an opponent (Reclamation Sage, Accursed
 *   Centaur): round 793 found that start-in-play cards do not fire those triggers; the rule guards against an engine
 *   change that would make them hit the player's start items on turn 0.</li>
 *   <li>Rarity by rank (notorietyDeckCardRarityByRank): Apprentice commons, Adept up to uncommon, Master up to rare,
 *   Archmage up to mythic; a boss by its tier. A card's rarity is its lowest over its normal printings (core,
 *   expansion, starter, reprint, boxed set, duel deck, draft, commander, multiplayer), Special read as mythic.</li>
 *   <li>The pick: every eligible COPY is one ticket (a 4-of is four times as likely), sorted by name, drawn with a seed
 *   of the world seed, the enemy's save name ("Name|Tier"), the player's duels fought and the seat - so a reload
 *   brings the same card, and the next duel may bring another. No eligible card: no extra card.</li>
 *   <li>The deck is copied first (a precon, the player's deck for a mirror match or Deck Tester's deck is never
 *   touched), then the copy loses that one card.</li>
 * </ul>
 * Logs [TFR-Notoriety] "deck card:" per seat it applies to, and "deck card: not ..." for a seat within reach of it.
 */
public final class NotorietyDeckCard {
    private NotorietyDeckCard() {
    }

    /** What choose() decided for one seat: the deck to play (one card fewer) and the card to put in play. */
    public static final class Pick {
        public final Deck deck;
        public final PaperCard card;

        Pick(Deck deck, PaperCard card) {
            this.deck = deck;
            this.card = card;
        }
    }

    /** The notoriety a deck card needs right now, and why. wins 0 = never (Easy, no table). */
    static final class Gate {
        String difficulty = "";
        int base, wins, drop, duels, won;
        float ratio;
        boolean dropped;

        String describe() {
            String record = duels == 0 ? "no duels on record" : "win ratio " + Math.round(ratio * 100) + "% over " + duels
                    + " duels";
            if (wins <= 0)
                return "off on " + difficulty;
            return difficulty + " " + wins + (dropped ? " (" + base + " - " + drop + " for " + record + ")" : " (" + record
                    + ", no drop)");
        }
    }

    /** Console "notorietycard": a card name forced as the pick (if the deck has it, eligible or not), "none" forces
     *  no card, null = the normal draw. Session only, for testing. */
    private static String forced;

    private static final EnumSet<CardEdition.Type> NORMAL_SETS = EnumSet.of(CardEdition.Type.CORE,
            CardEdition.Type.EXPANSION, CardEdition.Type.STARTER, CardEdition.Type.REPRINT, CardEdition.Type.BOXED_SET,
            CardEdition.Type.DUEL_DECK, CardEdition.Type.DRAFT, CardEdition.Type.COMMANDER, CardEdition.Type.MULTIPLAYER);
    private static final Map<String, Integer> rarityCache = new HashMap<>();

    static Gate gate(TuningData tuning, AdventurePlayer player) {
        Gate g = new Gate();
        String name = player.getDifficultyData() == null ? "" : player.getDifficultyData().name;
        g.difficulty = name == null || name.trim().isEmpty() ? "Easy" : name.trim();
        int index;
        switch (g.difficulty.toLowerCase()) {
            case "normal": index = 1; break;
            case "hard":   index = 2; break;
            case "insane": index = 3; break;
            default:       index = 0; // Easy and anything unknown: the fewest, as TuningData.notorietyMinRankFor does
        }
        int[] table = tuning.notorietyDeckCardWinsByDifficulty;
        g.base = table == null || index >= table.length ? 0 : Math.max(0, table[index]);
        String record = player.recentDuels() == null ? "" : player.recentDuels();
        g.duels = record.length();
        for (int i = 0; i < record.length(); i++)
            if (record.charAt(i) == 'W')
                g.won++;
        g.ratio = g.duels == 0 ? 0f : g.won / (float) g.duels;
        g.drop = Math.max(0, tuning.notorietyDeckCardRatioLevels) * Math.max(0, tuning.notorietyWinsPerLevel);
        g.dropped = g.base > 0 && g.drop > 0 && g.duels >= Math.max(1, tuning.notorietyDeckCardRatioMinDuels)
                && g.ratio >= tuning.notorietyDeckCardRatioAtLeast - 1e-6f;
        g.wins = g.base <= 0 ? 0 : Math.max(1, g.base - (g.dropped ? g.drop : 0));
        return g;
    }

    /** The lowest notoriety at which any difficulty's gate can open (with the drop) - "not ..." lines start there. */
    private static int logFloor(TuningData tuning) {
        int floor = Integer.MAX_VALUE;
        if (tuning.notorietyDeckCardWinsByDifficulty != null)
            for (int w : tuning.notorietyDeckCardWinsByDifficulty)
                if (w > 0)
                    floor = Math.min(floor, w);
        return floor == Integer.MAX_VALUE ? Integer.MAX_VALUE
                : floor - Math.max(0, tuning.notorietyDeckCardRatioLevels) * Math.max(0, tuning.notorietyWinsPerLevel);
    }

    /**
     * Before the seat registers (DuelScene): null when no deck card goes to this seat (the gate shut, an Arena duel,
     * Notoriety off, or nothing eligible); otherwise the Pick. Call only for the duels that get Walls (not Inn events,
     * not a guard's fight).
     */
    public static Pick choose(EnemyData enemy, Deck deck, int seat, boolean arena) {
        return choose(enemy, deck, seat, arena, false);
    }

    /**
     * Round 814 (the user: "Let's have the Wrath Mage, start with the new Deck spawns we added, regardless of Notoriety
     * level", and "Disable Notoriety should not affect the Wrath mage"): {@code wrath} - the Temple's Wrath hunter
     * (util/WrathHunt) - skips the gate and the Notoriety-off check; the eligibility, rarity cap, seed and "none
     * eligible" fallback are the same. One pick per seat, so never two cards.
     */
    public static Pick choose(EnemyData enemy, Deck deck, int seat, boolean arena, boolean wrath) {
        TuningData tuning = Config.instance().getTuningData();
        AdventurePlayer player = Current.player();
        if (tuning == null || player == null || enemy == null || deck == null
                || (!wrath && AdventurePlayer.notorietyDisabled()))
            return null;
        int notoriety = player.notorietyStreak();
        String who = enemy.getName() + " (" + EnemyData.tierDisplayName(enemy.tier) + ")";
        Gate g = gate(tuning, player);
        boolean near = notoriety >= logFloor(tuning);
        if (!wrath && (g.wins <= 0 || notoriety < g.wins || arena)) {
            if (near)
                System.out.println("[TFR-Notoriety] " + who + " deck card: not "
                        + (g.wins <= 0 ? "on " + g.difficulty : arena && notoriety >= g.wins ? "in the Arena" : "yet")
                        + " - notoriety " + notoriety + (g.wins <= 0 ? "" : ", needs " + g.describe()));
            return null;
        }
        int rank = EnemyData.tierRank(enemy.tier);
        int cap = rarityCap(tuning, rank);
        String capName = rarityName(cap);
        int size = deck.getMain().countAll();

        List<PaperCard> tickets = new ArrayList<>();
        PaperCard forcedCard = null;
        for (Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (forced != null && pc.getName().equalsIgnoreCase(forced))
                forcedCard = pc;
            if (eligible(pc, tuning) && rarity(pc) <= cap)
                for (int n = 0; n < e.getValue(); n++)
                    tickets.add(pc);
        }
        tickets.sort((a, b) -> {
            int c = a.getName().compareTo(b.getName());
            return c != 0 ? c : String.valueOf(a.getEdition()).compareTo(String.valueOf(b.getEdition()));
        });
        int distinct = (int) tickets.stream().map(PaperCard::getName).distinct().count();

        PaperCard card;
        String how;
        String whose = wrath ? " (the Wrath's card" + (AdventurePlayer.notorietyDisabled() ? ", Notoriety off" : "") + ")" : "";
        if ("none".equalsIgnoreCase(forced)) {
            card = null;
            how = " (forced by the console)";
        } else if (forced != null && forcedCard != null) {
            card = forcedCard;
            how = " (forced by the console)";
        } else if (tickets.isEmpty()) {
            card = null;
            how = forced != null ? " (console card " + forced + " not in this deck)" : "";
        } else {
            card = tickets.get(new Random(seed(enemy, seat)).nextInt(tickets.size()));
            how = forced != null ? " (console card " + forced + " not in this deck - drawn instead)" : "";
        }
        if (card == null) {
            System.out.println("[TFR-Notoriety] " + who + " deck card: none eligible (" + deck.getName() + ", " + size
                    + " cards, cap " + capName + ", MV " + tuning.notorietyDeckCardMinCost + "-" + tuning.notorietyDeckCardMaxCost
                    + ")" + how + " - no extra card; notoriety " + notoriety + ", needs " + g.describe() + whose);
            return null;
        }
        Deck played = new Deck(deck);
        played.getMain().remove(card, 1);
        ICardFace face = card.getRules().getMainPart();
        System.out.println("[TFR-Notoriety] " + who + " deck card: " + card.getName() + " (" + rarityName(rarity(card))
                + ", MV " + face.getManaCost().getCMC() + ", " + kind(face.getType()) + ") added - "
                + distinct + " eligible (" + tickets.size() + " copies) of " + size + ", cap " + capName + ", deck " + size
                + " -> " + played.getMain().countAll() + ", notoriety " + notoriety + ", needs " + g.describe() + how + whose);
        return new Pick(played, card);
    }

    /**
     * The notice-log line (AdventurePlayer.logNotorietyStage) when notoriety crosses the deck card's gate, or null. The
     * gate is the current one - the win ratio may move it later.
     */
    public static String noticeLine(int before, int after) {
        TuningData tuning = Config.instance().getTuningData();
        AdventurePlayer player = Current.player();
        if (tuning == null || player == null || after <= before)
            return null;
        Gate g = gate(tuning, player);
        if (g.wins <= 0 || before >= g.wins || after < g.wins)
            return null;
        return "Notoriety " + g.wins + ": every foe also starts with one of its own cards in play (cost "
                + tuning.notorietyDeckCardMinCost + "-" + tuning.notorietyDeckCardMaxCost + "; an Apprentice's is a common,"
                + " an Adept's up to uncommon, a Master's up to rare, an Archmage's any), on top of its Walls"
                + (g.dropped ? " - one level early for your " + Math.round(g.ratio * 100) + "% win ratio." : ".")
                + " Not in the Arena.";
    }

    /** The console "notorietycard": no argument reports, "off" clears, "none" forces no card, a name forces a card. */
    public static String console(String[] args) {
        if (args.length < 1) {
            TuningData tuning = Config.instance().getTuningData();
            AdventurePlayer player = Current.player();
            Gate g = tuning == null || player == null ? null : gate(tuning, player);
            String needs = g == null ? "" : g.wins <= 0 ? " Off on " + g.difficulty + "." : " Needs " + g.describe() + ".";
            return "Notoriety deck card: " + (forced == null ? "drawn normally" : "none".equalsIgnoreCase(forced)
                    ? "forced to none" : "forced to " + forced) + "." + needs + " Give a card name, none, or off.";
        }
        String arg = String.join(" ", args).trim();
        forced = "off".equalsIgnoreCase(arg) ? null : arg;
        System.out.println("[TFR-Notoriety] console: deck card " + (forced == null ? "drawn normally" : "forced to " + forced));
        return forced == null ? "Notoriety deck card drawn normally again."
                : "none".equalsIgnoreCase(forced) ? "Notoriety deck card forced to none (no extra card)."
                : "Notoriety deck card forced to " + forced + " when the enemy's deck has it.";
    }

    /** The seed: world, enemy (with its tier), the duels fought so far, the seat. All four come back with a reload. */
    private static long seed(EnemyData enemy, int seat) {
        WorldSave save = WorldSave.getCurrentSave();
        long world = save == null || save.getWorld() == null ? 0L : save.getWorld().getSeed();
        forge.adventure.player.PlayerStatistic stats = Current.player().getStatistic();
        long duels = stats == null ? 0 : stats.totalWins() + stats.totalLoss();
        String name = EnemyTiers.saveName(enemy, enemy.getName());
        long h = world;
        h = h * 31 + name.hashCode();
        h = h * 31 + duels;
        h = h * 31 + seat;
        return h ^ 0x5DEECE66DL;
    }

    static boolean eligible(PaperCard pc, TuningData tuning) {
        if (pc == null || pc.getRules() == null)
            return false;
        String name = pc.getName();
        if (CommanderCards.restricted().contains(name))
            return false;
        if (tuning.notorietyDeckCardExclude != null)
            for (String x : tuning.notorietyDeckCardExclude)
                if (name.equalsIgnoreCase(x))
                    return false;
        ICardFace face = pc.getRules().getMainPart();
        if (face == null)
            return false;
        CardType type = face.getType();
        if (type == null || !(type.isCreature() || type.isArtifact() || type.isEnchantment()) || type.isLand()
                || type.isPlaneswalker() || type.isBattle() || type.isAura() || type.hasSubtype("Saga")
                || type.hasSubtype("Room"))
            return false;
        ManaCost cost = face.getManaCost();
        if (cost == null || cost.isNoCost() || cost.countX() > 0)
            return false;
        int mv = cost.getCMC();
        if (mv < tuning.notorietyDeckCardMinCost || mv > tuning.notorietyDeckCardMaxCost)
            return false;
        if ("noHostile".equalsIgnoreCase(tuning.notorietyDeckCardEtb) && hostileEnterTrigger(face))
            return false;
        if ("none".equalsIgnoreCase(tuning.notorietyDeckCardEtb) && hasEnterTrigger(face))
            return false;
        if (type.isCreature()) {
            String t = face.getToughness();
            String oracle = face.getOracleText() == null ? "" : face.getOracleText();
            return !"0".equals(t) || oracle.contains("+1/+1 counter") || oracle.toLowerCase().contains("toughness");
        }
        // A non-creature needs something that works from the battlefield.
        if (any(face.getStaticAbilities()) || any(face.getAbilities()) || any(face.getKeywords()))
            return true;
        if (face.getTriggers() != null)
            for (String trigger : face.getTriggers())
                if (!trigger.contains("Mode$ ChangesZone") || !trigger.contains("ValidCard$ Card.Self"))
                    return true;
        return false;
    }

    private static boolean any(Iterable<String> it) {
        return it != null && it.iterator().hasNext();
    }

    private static boolean isEnterTrigger(String trigger) {
        return trigger.contains("Mode$ ChangesZone") && trigger.contains("Destination$ Battlefield")
                && trigger.contains("ValidCard$ Card.Self");
    }

    private static boolean hasEnterTrigger(ICardFace face) {
        if (face.getTriggers() != null)
            for (String trigger : face.getTriggers())
                if (isEnterTrigger(trigger))
                    return true;
        return false;
    }

    /** An own-enter trigger whose effect chain (Execute$ and its SubAbility$ SVars) sacrifices, names an opponent, or
     *  targets anything but the controller's own cards. */
    static boolean hostileEnterTrigger(ICardFace face) {
        if (face.getTriggers() == null)
            return false;
        Map<String, String> svars = new HashMap<>();
        if (face.getVariables() != null)
            for (Map.Entry<String, String> v : face.getVariables())
                svars.put(v.getKey(), v.getValue());
        for (String trigger : face.getTriggers()) {
            if (!isEnterTrigger(trigger))
                continue;
            String next = param(trigger, "Execute$");
            for (int depth = 0; next != null && depth < 8; depth++) {
                String body = svars.get(next);
                if (body == null)
                    break;
                if (body.contains("Sacrifice") || body.contains("Opponent") || body.contains("Opp"))
                    return true;
                String tgts = param(body, "ValidTgts$");
                if (tgts != null && !tgts.contains("YouCtrl") && !tgts.contains("YouOwn") && !"You".equals(tgts))
                    return true;
                next = param(body, "SubAbility$");
            }
        }
        return false;
    }

    private static String param(String script, String key) {
        int at = script.indexOf(key);
        if (at < 0)
            return null;
        String rest = script.substring(at + key.length()).trim();
        int bar = rest.indexOf('|');
        return (bar < 0 ? rest : rest.substring(0, bar)).trim();
    }

    /** 0 common .. 3 mythic: the lowest over the card's normal printings, else this printing; Special and unknown = 3. */
    static int rarity(PaperCard pc) {
        String name = pc.getName();
        Integer cached = rarityCache.get(name);
        if (cached != null)
            return cached;
        int best = Integer.MAX_VALUE;
        try {
            List<PaperCard> all = StaticData.instance().getCommonCards().getAllCards(name);
            if (all != null)
                for (PaperCard p : all) {
                    CardEdition ed = StaticData.instance().getEditions().get(p.getEdition());
                    if (ed == null || !NORMAL_SETS.contains(ed.getType()))
                        continue;
                    best = Math.min(best, rank(p.getRarity()));
                }
        } catch (RuntimeException e) {
            System.err.println("[TFR-Notoriety] rarity lookup failed for " + name + ": " + e);
        }
        if (best == Integer.MAX_VALUE)
            best = rank(pc.getRarity());
        rarityCache.put(name, best);
        return best;
    }

    private static int rank(CardRarity r) {
        if (r == null)
            return 3;
        switch (r) {
            case BasicLand:
            case Common: return 0;
            case Uncommon: return 1;
            case Rare: return 2;
            default: return 3; // MythicRare, Special, Token, Unknown
        }
    }

    private static int rarityCap(TuningData tuning, int rank) {
        String[] caps = tuning.notorietyDeckCardRarityByRank;
        if (caps == null || caps.length == 0)
            return 3;
        String cap = caps[Math.max(0, Math.min(rank, caps.length - 1))];
        switch (cap == null ? "" : cap.trim().toLowerCase()) {
            case "common": return 0;
            case "uncommon": return 1;
            case "rare": return 2;
            default: return 3;
        }
    }

    private static String rarityName(int r) {
        switch (r) {
            case 0: return "Common";
            case 1: return "Uncommon";
            case 2: return "Rare";
            default: return "Mythic";
        }
    }

    private static String kind(CardType type) {
        if (type.isCreature())
            return "creature";
        if (type.isArtifact())
            return type.hasSubtype("Equipment") ? "equipment" : type.hasSubtype("Vehicle") ? "vehicle" : "artifact";
        return "enchantment";
    }
}
