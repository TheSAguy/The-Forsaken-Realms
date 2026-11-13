package forge.adventure.agent;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntry;
import forge.game.GameObject;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardUtil;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round 1023: what the manual seat sees in a duel - the board, the stack, combat, the game log since the last decision
 * and the legal plays. Built on the GAME thread while the seat waits for the bridge, so it is one consistent moment.
 * Fair play: the opponent's hand and library are counts only.
 */
final class DuelView {
    private DuelView() {}

    /** One thing the seat can do with priority: a land, a spell or an activated ability (never a mana ability). */
    static final class Play {
        final int n;
        final SpellAbility sa;
        final Card host;

        Play(int n, SpellAbility sa) {
            this.n = n;
            this.sa = sa;
            this.host = sa.getHostCard();
        }

        String label() {
            return host.getName() + (sa.isLandAbility() ? " (land)" : sa.isSpell() ? "" : ": " + shorten(sa.getDescription(), 60));
        }
    }

    private static final Keyword[] COMBAT_KEYWORDS = {Keyword.FLYING, Keyword.REACH, Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE,
            Keyword.DEATHTOUCH, Keyword.TRAMPLE, Keyword.LIFELINK, Keyword.VIGILANCE, Keyword.HASTE, Keyword.MENACE,
            Keyword.INDESTRUCTIBLE, Keyword.HEXPROOF, Keyword.SHROUD, Keyword.DEFENDER, Keyword.FLASH, Keyword.FEAR,
            Keyword.INTIMIDATE, Keyword.SHADOW, Keyword.WITHER, Keyword.INFECT, Keyword.PROTECTION};

    // ------------------------------------------------------------------ legal plays

    /** Everything the player could put on the stack or play right now and pay for, in a stable order. */
    static List<Play> legalPlays(Player p) {
        Game game = p.getGame();
        List<SpellAbility> all;
        try {
            all = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                    ComputerUtilAbility.getSpellAbilities(ComputerUtilAbility.getAvailableCards(game, p), p), p);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        List<Play> out = new ArrayList<>();
        for (SpellAbility sa : all) {
            try {
                if (sa.isManaAbility() || sa.isTrigger())
                    continue;
                sa.setActivatingPlayer(p);
                if (!sa.canPlay())
                    continue;
                if (!ComputerUtilCost.canPayCost(sa, p, false))
                    continue;
                if (!targetsPossible(sa))
                    continue;
                out.add(new Play(out.size(), sa));
            } catch (RuntimeException e) {
                // a card whose checks throw is not offered
            }
        }
        return out;
    }

    /** Every targeting step that needs a target has at least one candidate (modal spells are checked per mode later). */
    private static boolean targetsPossible(SpellAbility sa) {
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting())
                continue;
            TargetRestrictions tgt = cur.getTargetRestrictions();
            int min = tgt.getMinTargets(cur.getHostCard(), cur);
            if (min > 0 && candidates(cur).isEmpty())
                return false;
        }
        return true;
    }

    /** The legal targets of one targeting step, as tokens the bridge accepts back: card ids, "me", "opp". */
    static List<Object> candidates(SpellAbility cur) {
        List<Object> out = new ArrayList<>();
        Player me = cur.getActivatingPlayer();
        Game game = cur.getHostCard().getGame();
        for (Player pl : game.getPlayers())
            if (cur.canTarget(pl))
                out.add(pl == me ? "me" : "opp");
        CardCollection cards;
        try {
            cards = CardUtil.getValidCardsToTarget(cur);
        } catch (RuntimeException e) {
            cards = new CardCollection();
        }
        for (Card c : cards)
            if (!c.isInZone(ZoneType.Stack))
                out.add(c.getId());
        if (cur.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility s = si.getSpellAbility();
                if (s != null && s != cur && cur.canTarget(s))
                    out.add(s.getHostCard().getId());
            }
        }
        return out;
    }

    static Map<String, Object> playMap(Play play) {
        SpellAbility sa = play.sa;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", play.n);
        m.put("id", play.host.getId());
        m.put("card", play.host.getName());
        m.put("kind", sa.isLandAbility() ? "land" : sa.isSpell() ? "spell" : "ability");
        if (!sa.isLandAbility()) {
            m.put("cost", sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString());
            m.put("text", shorten(sa.getDescription().replace("CARDNAME", sa.getHostCard().getName()), 160));
        }
        if (sa.isSpell() && !sa.getHostCard().isInZone(ZoneType.Hand))
            m.put("from", String.valueOf(sa.getHostCard().getZone() == null ? "?" : sa.getHostCard().getZone().getZoneType()));
        if (sa.costHasManaX())
            m.put("x", true);
        if (sa.getApi() == forge.game.ability.ApiType.Charm) {
            List<Object> modes = new ArrayList<>();
            List<AbilitySub> opts = CharmEffect.makePossibleOptions(sa);
            for (int i = 0; i < opts.size(); i++) {
                Map<String, Object> mo = new LinkedHashMap<>();
                mo.put("i", i);
                mo.put("text", shorten(opts.get(i).getDescription(), 120));
                if (opts.get(i).usesTargeting()) {
                    opts.get(i).setActivatingPlayer(sa.getActivatingPlayer());
                    mo.put("targets", candidates(opts.get(i)));
                }
                modes.add(mo);
            }
            m.put("modes", modes);
            m.put("modeCount", sa.getParamOrDefault("CharmNum", "1"));
        }
        List<Object> groups = new ArrayList<>();
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting())
                continue;
            TargetRestrictions tgt = cur.getTargetRestrictions();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("for", shorten(cur.getDescription(), 80));
            g.put("min", tgt.getMinTargets(cur.getHostCard(), cur));
            g.put("max", tgt.getMaxTargets(cur.getHostCard(), cur));
            g.put("candidates", candidates(cur));
            groups.add(g);
        }
        if (!groups.isEmpty())
            m.put("targets", groups);
        return m;
    }

    // ------------------------------------------------------------------ the board

    /** The whole duel from {@code me}'s seat. {@code seen} holds the card names whose text was already sent. */
    static Map<String, Object> state(Player me, int[] logMark, Set<String> seen) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("turn", ph.getTurn());
        s.put("active", ph.getPlayerTurn() == me ? "me" : "opp");
        s.put("phase", String.valueOf(ph.getPhase()));
        Map<String, String> oracle = new LinkedHashMap<>();
        s.put("me", playerMap(me, true, oracle, seen));
        for (Player o : me.getOpponents()) {
            s.put("opp", playerMap(o, false, oracle, seen));
            break;
        }
        List<Object> stack = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            Map<String, Object> e = new LinkedHashMap<>();
            SpellAbility sa = si.getSpellAbility();
            Card src = sa == null ? null : sa.getHostCard();
            e.put("id", src == null ? -1 : src.getId());
            e.put("card", src == null ? "?" : src.getName());
            e.put("by", sa != null && sa.getActivatingPlayer() == me ? "me" : "opp");
            e.put("text", shorten(si.getStackDescription(), 160));
            stack.add(e);
        }
        if (!stack.isEmpty())
            s.put("stack", stack); // top first
        Combat combat = ph.getCombat();
        if (combat != null && !combat.getAttackers().isEmpty()) {
            List<Object> atk = new ArrayList<>();
            for (Card a : combat.getAttackers()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", a.getId());
                e.put("card", a.getName());
                GameEntity d = combat.getDefenderByAttacker(a);
                e.put("at", d instanceof Player ? (d == me ? "me" : "opp") : d == null ? "?" : d.toString());
                List<Integer> bl = new ArrayList<>();
                for (Card b : combat.getBlockers(a))
                    bl.add(b.getId());
                e.put("blockedBy", bl);
                atk.add(e);
            }
            s.put("combat", atk);
        }
        // What happened since the last decision, oldest first: the opponent's plays, attacks, blocks, damage.
        List<GameLogEntry> log = game.getGameLog().getAllEntries();
        List<String> events = new ArrayList<>();
        int from = Math.max(logMark[0], log.size() - 60);
        String last = null;
        for (int i = Math.max(0, from); i < log.size(); i++) {
            GameLogEntry le = log.get(i);
            if (le.type() == forge.game.GameLogEntryType.PHASE)
                continue; // the phase is in the state; every step's line only drowned the plays
            String line = le.type() + ": " + shorten(le.message(), 200);
            if (!line.equals(last))
                events.add(line); // the ante and mulligan lines arrive twice
            last = line;
        }
        logMark[0] = log.size();
        if (!events.isEmpty())
            s.put("events", events);
        if (!oracle.isEmpty())
            s.put("oracle", oracle);
        return s;
    }

    private static Map<String, Object> playerMap(Player p, boolean mine, Map<String, String> oracle, Set<String> seen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("life", p.getLife());
        if (p.getPoisonCounters() > 0)
            m.put("poison", p.getPoisonCounters());
        m.put("library", p.getCardsIn(ZoneType.Library).size());
        if (mine) {
            List<Object> hand = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                hand.add(cardMap(c, false));
                note(c, oracle, seen);
            }
            m.put("hand", hand);
            m.put("landsPlayed", p.getLandsPlayedThisTurn());
        } else {
            m.put("handSize", p.getCardsIn(ZoneType.Hand).size());
        }
        int untappedLands = 0;
        List<Object> bf = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped() && !c.isCreature())
                untappedLands++;
            bf.add(cardMap(c, true));
            note(c, oracle, seen);
        }
        m.put("untappedLands", untappedLands);
        m.put("battlefield", bf);
        List<String> gy = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Graveyard))
            gy.add(c.getName());
        if (!gy.isEmpty())
            m.put("graveyard", gy);
        List<String> ex = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Exile))
            ex.add(c.getName());
        if (!ex.isEmpty())
            m.put("exile", ex);
        return m;
    }

    private static void note(Card c, Map<String, String> oracle, Set<String> seen) {
        if (c.isBasicLand() || seen.contains(c.getName()))
            return;
        seen.add(c.getName());
        String t = c.getOracleText();
        if (t != null && !t.isBlank())
            oracle.put(c.getName(), shorten(t.replace("\\n", " ").replace('\n', ' '), 300));
    }

    static Map<String, Object> cardMap(Card c, boolean onBattlefield) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        if (!c.isLand() || c.isCreature())
            m.put("cost", c.getManaCost() == null ? "" : c.getManaCost().toString());
        m.put("type", typeLine(c));
        if (c.isCreature())
            m.put("pt", c.getNetPower() + "/" + c.getNetToughness());
        List<String> kw = new ArrayList<>();
        for (Keyword k : COMBAT_KEYWORDS)
            if (c.hasKeyword(k))
                kw.add(k.toString().toLowerCase());
        if (!kw.isEmpty())
            m.put("kw", kw);
        if (onBattlefield) {
            if (c.isTapped())
                m.put("tapped", true);
            if (c.isCreature() && c.isSick() && c.getGame().getPhaseHandler().isPlayerTurn(c.getController()))
                m.put("sick", true); // summoning sickness matters on its controller's turn only
            if (c.getDamage() > 0)
                m.put("damage", c.getDamage());
            Map<String, Integer> counters = new LinkedHashMap<>();
            for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet())
                if (e.getCount() > 0)
                    counters.put(e.getElement().toString(), e.getCount());
            if (!counters.isEmpty())
                m.put("counters", counters);
            if (c.isAttachedToEntity())
                m.put("attachedTo", c.getEntityAttachedTo() instanceof Card ac ? ac.getId() : String.valueOf(c.getEntityAttachedTo()));
            if (c.getController() != c.getOwner())
                m.put("stolen", true);
        }
        return m;
    }

    private static String typeLine(Card c) {
        if (c.isCreature())
            return c.isArtifact() ? "artifact creature" : "creature";
        if (c.isLand())
            return "land";
        if (c.isPlaneswalker())
            return "planeswalker";
        if (c.isInstant())
            return "instant";
        if (c.isSorcery())
            return "sorcery";
        if (c.isEnchantment())
            return c.isAura() ? "aura" : "enchantment";
        if (c.isArtifact())
            return c.isEquipment() ? "equipment" : "artifact";
        if (c.isBattle())
            return "battle";
        return "other";
    }

    /** A card or player for a choice list. */
    static Object entity(GameObject o, Player me) {
        if (o instanceof Player pl)
            return pl == me ? "me" : "opp";
        if (o instanceof Card c)
            return c.getId();
        return String.valueOf(o);
    }

    static Map<String, Object> option(GameObject o, Player me) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o instanceof Card c) {
            m.putAll(cardMap(c, c.isInZone(ZoneType.Battlefield)));
            if (!c.isInZone(ZoneType.Battlefield) && c.getZone() != null)
                m.put("zone", String.valueOf(c.getZone().getZoneType()));
            if (c.getController() != null)
                m.put("of", c.getController() == me ? "me" : "opp");
        } else {
            m.put("id", entity(o, me));
            m.put("name", String.valueOf(o));
        }
        return m;
    }

    static String shorten(String s, int max) {
        if (s == null)
            return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    static Set<String> newSeen() {
        return new HashSet<>();
    }
}
