package forge.adventure.data;

import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.GameFormat;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.MyRandom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information of enemies
 */
public class EnemyData implements Serializable {
    private static final long serialVersionUID = -3317270785183936320L;
    public String name;
    public String nameOverride;
    public String sprite;
    public String[] deck;
    public boolean copyPlayerDeck = false;
    public String ai;
    public boolean boss = false;
    public boolean flying = false;
    public boolean randomizeDeck = false;
    public float spawnRate;
    public float difficulty;
    // Common/Uncommon/Rare/Mythic - deck-rarity-derived difficulty tier (2026-08-10, user
    // request), parallel to ItemData.rarity's naming. `difficulty` stays the mechanical gating
    // value BiomeData.getEnemy() actually compares against (0.1/1/2/3, matching this tier);
    // `tier` is the readable label other systems (town-fight capture odds) switch on directly
    // instead of comparing floats.
    public String tier = "Common";
    public float speed;
    public float scale = 1.0f;
    public int life;
    public RewardData[] rewards;
    public String[] equipment;
    public String colors = "";

    public EnemyData nextEnemy;
    public int teamNumber = -1;

    public String[] questTags = new String[0];
    public float lifetime;
    public int gamesPerMatch = 1;
    public String bossInsult;
    public String bossIntro;
    // Mod addition (The Forsaken Realms, 2026-08-11): Arena matches disable the ante mechanic
    // (on globally by default, DuelScene reads UI_ANTE) without touching that global preference -
    // set true only on a per-fight clone (see ArenaScene.loadArenaData()), same pattern the
    // Capitol-defense duel already uses for a one-off gamesPerMatch override.
    public boolean noAnte = false;
    // Round 178: a named legend (commander) that roams nowhere by design - kept out of the cave-champion pool
    // and eligible for the frontier spawns. Set in enemies.json on the spawnRate-0 entries whose old hand-set
    // scale was over 1.5: round 178's one-size-per-tier rule retired "scale" as the "is it a huge model" signal
    // CaveChampions and FrontierSpawns used to read.
    public boolean legend = false;
    // Round 178: a hand-placed set piece (an Eldrazi Prison's titan, a lair's legend) that keeps its authored size
    // like a boss does - dev-tools/enemy_scale.py leaves its scale alone. Read by the tool only.
    public boolean keepSize = false;
    // Mod addition (Deck Tester, 2026-08-11): when set, DuelScene uses this exact Deck for the AI
    // side instead of resolving one from `deck`/`randomizeDeck` by name or via `copyPlayerDeck` -
    // lets the AI pilot one of the PLAYER's own saved decks (not the one they're currently
    // piloting) for deck-testing purposes. Never set in enemies.json data; only on a per-fight
    // synthetic clone (see ArenaScene.launchDeckTester()). transient - never meant to survive a
    // save/load, and Deck isn't a type this class's declared Serializable contract should carry.
    public transient Deck fixedDeck = null;

    public EnemyData() {
    }

    public EnemyData(EnemyData enemyData) {
        name            = enemyData.name;
        sprite          = enemyData.sprite;
        deck            = enemyData.deck;
        ai              = enemyData.ai;
        boss            = enemyData.boss;
        flying          = enemyData.flying;
        randomizeDeck   = enemyData.randomizeDeck;
        spawnRate       = enemyData.spawnRate;
        copyPlayerDeck  = enemyData.copyPlayerDeck;
        difficulty      = enemyData.difficulty;
        tier            = enemyData.tier;
        speed           = enemyData.speed;
        scale           = enemyData.scale;
        life            = enemyData.life;
        equipment       = enemyData.equipment;
        colors          = enemyData.colors;
        teamNumber      = enemyData.teamNumber;
        bossInsult      = enemyData.bossInsult;
        bossIntro       = enemyData.bossIntro;
        nextEnemy       = enemyData.nextEnemy == null ? null : new EnemyData(enemyData.nextEnemy);
        nameOverride    = enemyData.nameOverride == null ? "" : enemyData.nameOverride;
        questTags       = enemyData.questTags.clone();
        lifetime        = enemyData.lifetime;
        gamesPerMatch   = enemyData.gamesPerMatch;
        noAnte          = enemyData.noAnte;
        legend          = enemyData.legend;
        keepSize        = enemyData.keepSize;
        if (enemyData.scale == 0.0f) {
            scale = 1.0f;
        }
        if (enemyData.rewards == null) {
            rewards = null;
        } else {
            rewards = new RewardData[enemyData.rewards.length];
            for (int i = 0; i < rewards.length; i++)
                rewards[i] = new RewardData(enemyData.rewards[i]);
        }
    }

    public Deck generateDeck(boolean isFantasyMode, boolean useGeneticAI) {
        // Round 130: one plane flag switches off BOTH genetic substitutions below - the LDA
        // archetype branch immediately after this, and the random-precon branch inside
        // CardUtil.getDeck() that canUseGeneticAI is passed into. See
        // ConfigData.disableGeneticDeckOverrides for what each one did and why this plane wants
        // neither: an enemy should play the deck it was authored with.
        boolean canUseGeneticAI = useGeneticAI && life > 16;
        if (canUseGeneticAI && Config.instance().getConfigData().disableGeneticDeckOverrides) {
            canUseGeneticAI = false;
            if (!loggedGeneticSuppression) {
                loggedGeneticSuppression = true;
                System.out.println("[TFR-DeckOverride] disableGeneticDeckOverrides is on for this plane -"
                        + " enemies play their own decks on Hard/Insane (first suppressed: " + getName() + ")");
            }
        }

        if (canUseGeneticAI && Config.instance().getSettingData().generateLDADecks) {
            GameFormat fmt = FModel.getFormats().getStandard();
            int rand = MyRandom.getRandom().nextInt(100);
            if (rand > 90) {
                fmt = FModel.getFormats().getLegacy();
            } else if (rand > 50) {
                fmt = FModel.getFormats().getModern();
            }
            return DeckgenUtil.buildLDACArchetypeDeck(fmt, true);
        }

        if (randomizeDeck) {
            return CardUtil.getDeck(Aggregates.random(deck), true, isFantasyMode, colors, life > 13, canUseGeneticAI);
        }
        return CardUtil.getDeck(deck[Current.player().getEnemyDeckNumber(this.getName(), deck.length)], true, isFantasyMode, colors, life > 13, canUseGeneticAI);
    }

    // Session-local, transient by design: one line per run is enough to prove the gate is
    // active in a log, and a line per duel would be noise (most duels on Insane qualify).
    private static boolean loggedGeneticSuppression = false;

    public String getName(){
        //todo: make this the default accessor for anything seen in UI
        if (nameOverride != null && !nameOverride.isEmpty())
            return nameOverride;
        if (name != null && !name.isEmpty())
            return name;
        return "(Unnamed Enemy)";
    }

    // Enemy tier naming convention: Apprentice -> Adept -> Master -> Archmage, the display
    // mapping for EnemyData.tier's internal Common/Uncommon/Rare/Mythic values. Single source of
    // truth - guard tier labels (EconomyBuildings.guardTierDisplayName()) delegate here too, so
    // guards and enemies can't drift apart. "Grandmaster" replaced the original "Challenger"
    // label (user request 2026-08-13), then "Archmage" replaced "Grandmaster" (user request
    // 2026-08-25) - deliberately distinct from the Arena's "Challenger 20/21/22" champion
    // enemies and the "Challenging Arena" mode, which kept their names and were never tier
    // labels.
    /** Round 135: every tier label, for stripping a stale one off a display name below. */
    private static final String[] TIER_LABELS = {"Apprentice", "Adept", "Master", "Archmage"};

    /**
     * Tier as a comparable rank: 0 Apprentice, 1 Adept, 2 Master, 3 Archmage. Anything
     * unrecognised (null included) reads as Apprentice, matching {@link #tierDisplayName}'s own
     * default, so a hand-edited tier string can never sort above a real one. Round 190: added for
     * the Arena's AI-vs-AI bracket resolution, the first caller that needed to ask which of two
     * enemies outranks the other rather than just print a label.
     */
    public static int tierRank(String tier) {
        if (tier == null)
            return 0;
        switch (tier) {
            case "Uncommon": return 1;
            case "Rare":     return 2;
            case "Mythic":   return 3;
            default:         return 0;
        }
    }

    public static String tierDisplayName(String tier) {
        if (tier == null)
            return "Apprentice";
        switch (tier) {
            case "Uncommon": return "Adept";
            case "Rare": return "Master";
            case "Mythic": return "Archmage";
            default: return "Apprentice";
        }
    }

    /**
     * Display-only name with the tier appended, e.g. "Red Wizard (Adept)" (user spec 2026-08-13,
     * gated on showEnemyTierInName so stock planes are untouched). The tiered wizard enemies'
     * data names already carry their tier as a prefix ("Adept Red Wizard") - when that prefix
     * matches the enemy's OWN tier label, it's stripped so the name doesn't state the tier twice.
     * A non-matching prefix is left alone (it would be part of the actual name, not a tier
     * marker). Never used for identity - quest matching (EnemyData.match()), deck-number keys
     * (getEnemyDeckNumber), .tmx "enemy" references, and WorldData.getEnemy() lookups all use the
     * raw name/getName(), which this method never alters.
     */
    public String getTieredDisplayName() {
        String base = getName();
        ConfigData config = Config.instance().getConfigData();
        if (config == null || !config.showEnemyTierInName)
            return base;
        String tierLabel = tierDisplayName(tier);
        // Round 135 (user request, from a log review): strip ANY tier-word prefix, not only one
        // that already agrees with this enemy's tier. The round-116/118 tier slice re-tiered the
        // whole roster by deck strength WITHOUT renaming anything, so six names now contradict
        // their own tier - "Master Blue Wizard" is Adept, the three "Apprentice <colour> Wizard"s
        // are Adept, "Adept Necromancer" is Master. Stripping only a matching prefix left that
        // contradiction on screen as "Master Blue Wizard (Adept)"; stripping any of the four
        // leaves "Blue Wizard (Adept)", which is what the player should read.
        //
        // Deliberately fixed HERE and not by renaming the data: the raw name is IDENTITY - quest
        // targets, .tmx "enemy" references, deck-number keys, biome spawn lists, arena pools, and
        // the save's own enemyPermanentKillCount and coinRansomedEnemies maps are all keyed by it.
        // Renaming six enemies would touch 9-12 plane files each AND silently orphan those save
        // keys mid-run, including any in-flight quest bound to one of them. This also self-heals
        // the next time the slice re-tiers something without renaming it.
        for (String stale : TIER_LABELS) {
            if (base.startsWith(stale + " ") && base.length() > stale.length() + 1) {
                base = base.substring(stale.length() + 1);
                break;
            }
        }
        return base + " (" + tierLabel + ")";
    }
    public String getBossInsult(){
        return bossInsult;
    }
    public String getBossIntro(){
        return bossIntro;
    }

    public boolean match(EnemyData other) {
        //equals() does not cover cases where data is updated to override speed, displayname, etc
        if (this.equals(other))
            return true;
        if (!this.name.equals(other.name))
            return false;
        if (questTags.length != other.questTags.length)
            return false;
        ArrayList<String> myQuestTags = new ArrayList<>(Arrays.asList(questTags));
        ArrayList<String> otherQuestTags = new ArrayList<>(Arrays.asList(other.questTags));
        myQuestTags.removeAll(otherQuestTags);
        return myQuestTags.isEmpty();
    }
}
