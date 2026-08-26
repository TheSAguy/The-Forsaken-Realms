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
    // Mod addition (The Forgotten Realms, 2026-08-11): Arena matches disable the ante mechanic
    // (on globally by default, DuelScene reads UI_ANTE) without touching that global preference -
    // set true only on a per-fight clone (see ArenaScene.loadArenaData()), same pattern the
    // Capitol-defense duel already uses for a one-off gamesPerMatch override.
    public boolean noAnte = false;
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
        boolean canUseGeneticAI = useGeneticAI && life > 16;

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
        if (base.startsWith(tierLabel + " ") && base.length() > tierLabel.length() + 1)
            base = base.substring(tierLabel.length() + 1);
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
