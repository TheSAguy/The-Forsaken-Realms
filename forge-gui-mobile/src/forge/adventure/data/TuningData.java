package forge.adventure.data;

/**
 * Tunable game-balance numbers for "The Forsaken Realms" mod (user request 2026-08-14: "Create
 * another 'config' file... for things like: How long a day is. How fast Capitol spreads. How fast
 * Towns spread. Max Capitol Spread. Speed-up: how fast this should speed up the game."). Loaded
 * the same way ConfigData is (Config.java's constructor, plane-local settings.json falling back to
 * common/settings.json, falling back to a plain `new TuningData()` - i.e. these exact defaults - if
 * neither file exists). Every field's default below matches whatever the equivalent hardcoded
 * constant already was in Java before this file existed, EXCEPT aiCastleExpansionTilesPerDay
 * (was a flat 9, user-reported "AI spread way too fast" - fixed to 1 here) and speedUpMultiplier
 * (was 100, user asked for a 50 default) and townMaxTerritoryRadius (was 15, user asked +5) -
 * see each field's own comment. Stock planes (Shandalar etc.) have no settings.json at all, so
 * they silently get these same defaults too - harmless, since none of these fields are read unless
 * territoryControlEnabled (or the relevant other feature flag) is already on for that plane.
 * <p>
 * Backing file relocated 2026-08-16 (user request): was res/adventure/&lt;plane&gt;/tuning.json,
 * now res/adventure/&lt;plane&gt;/config tables/settings.json - see Config.java's load block. Class
 * name kept as TuningData throughout the Java side; only the JSON filename/location changed.
 */
public class TuningData {
    // World.java's DAY_LENGTH_SECONDS - real-world seconds per in-game day.
    public float dayLengthSeconds = 10 * 60f;

    // TerritoryControl.java's territory-expansion pacing, split 2026-08-14 (pulled in from another
    // machine) into 3 independently-tunable rates - AI castles were left at the old flat testing
    // pace (9/day) in that same round "not requested to change" at the time; the user has since
    // asked for that explicitly, so its default here is 1, not 9.
    public int capitolExpansionTilesPerDay = 1;
    public int townExpansionDaysPerTile = 7; // 1 tile earned per this many days, not a per-day rate
    public int aiCastleExpansionTilesPerDay = 1; // was hardcoded 9 - user: "should expand 1 tile a day max"

    // Shared cap both Capitol and AI castle radius growth are clamped to ("Max Capitol Spread").
    public int maxTerritoryRadius = 450;
    // Per-town (not castle) territory radius cap - how far a captured/restored town's territory
    // can grow outward. Raised 20 -> 25 (2026-08-24, following the town-count reduction to 50/
    // color): with roughly 65-71 towns per color cut to 50, each remaining town needs to reach
    // further to keep total map coverage from shrinking. Sizing check: treating total covered
    // area as townCount x radius^2 and solving for the radius that keeps that product roughly
    // constant per color gives ~22-24 tiles across all 5 colors (e.g. White 65 towns@20 ->
    // 49 towns@~23); 25 is a round number in that range. Since this is a plain tunable, retune
    // directly in settings.json if actual playtesting wants it higher/lower - no code change
    // needed for the number itself.
    public int townMaxTerritoryRadius = 25;
    // Decouples the "protected core" (hard-protect radius rivals can never touch, TerritoryControl.
    // buildPullSources()'s `radius / 2`) from the growth cap above (2026-08-24 user spec: "All I
    // want to do is increase how far out the max distance the town can grow to... The starting
    // radius and protected radius should stay unchanged"). Without this, protect = liveRadius / 2
    // would silently grow right along with townMaxTerritoryRadius, since it was never a separate
    // number - just half of whatever the current radius happens to be. Pinned at the ORIGINAL
    // townMaxTerritoryRadius (20) so the protected core's ceiling (20/2=10) stays exactly what it
    // is today, even though the outer territory disc now reaches further.
    public int townProtectedRadiusCap = 20;

    // WorldStage.java's FAST_TIME_MULTIPLIER, backing the "Speed-Up" HUD checkbox (renamed
    // 2026-08-14 from "100x Speed" - see en-US.properties lblFastTimeToggle). User: "Current
    // default 50x (It's currently 100x)" - i.e. change the real default down to 50, not just the
    // label.
    public float speedUpMultiplier = 50f;

    // Territory Effects (MOD_SCOPE.md #17, user spec 2026-08-14 - the first concrete numbers for
    // this item; nothing here existed in code before this round). Player movement-speed modifiers,
    // multiplicative with the existing road/sprint modifiers (PlayerSprite.setMoveModifier()) -
    // +15% on the player's own territory; on an AI color's territory, scaled by ColorReputation
    // status with that specific color: +5% Happy, +10% Partner, -5% Unhappy, -10% War (Neutral:
    // no effect, same as territory the player doesn't recognize as anyone's in particular).
    public float playerTerritorySpeedBonus = 0.15f;
    public float aiTerritoryHappySpeedBonus = 0.05f;
    public float aiTerritoryPartnerSpeedBonus = 0.10f;
    public float aiTerritoryUnhappySpeedPenalty = 0.05f;
    public float aiTerritoryWarSpeedPenalty = 0.10f;

    // Mine weekly payouts (2026-08-16, user spec: "Mines: let's have them all pay-out weekly,
    // instead of the currently daily. Same schedule we're using for most of our other things,
    // 7,14,21,etc." + "Gold: 50g/week. Wood: 25/w Stone 25/w Shards 20/w"). Replaces the old flat
    // RESOURCE_PRODUCTION_PER_DAY=5-for-every-type constant - each resource now has its own
    // weekly amount. See EconomyBuildings.processDaysPassed()'s mine-payout pass (mirrors the
    // Guard weekly-salary "fixed shared payday" boundary math exactly, NOT the shop-reroll
    // "rolling N days since last time" pattern - a mine built day 3 still pays first on day 7).
    public int mineWeeklyGoldPayout = 50;
    public int mineWeeklyWoodPayout = 25;
    public int mineWeeklyStonePayout = 25;
    public int mineWeeklyShardPayout = 20;

    // Ante Re-roll (2026-08-16, user spec: "Costs 50 Shards... add +50% shards per re-roll,
    // starting at the 50 shards we currently have"). Escalates multiplicatively within one ante
    // reveal (reroll 1 = base, reroll 2 = base*rate, reroll 3 = base*rate^2, ...); resets to the
    // base cost fresh at the next duel's ante roll. Difficulty-scaled via the same scaledCost()
    // every other shard cost in this mod already uses - see MatchController.revealAnteCards().
    public int anteRerollBaseShardCost = 50;
    public float anteRerollEscalationRate = 1.5f;

    // Ante Buy Back (2026-08-16, user spec: "Let's use 150% Shop value"). Multiplies
    // AdventurePlayer.cardSellPrice() (already difficulty-scaled via sellFactor) - see
    // DuelScene.showAnteCardPopup().
    public float anteBuyBackMultiplier = 1.5f;

    // Ante Buy Back price FLOOR, by rarity (2026-08-17 user report: on Insane, 150% of a Common's
    // heavily sellFactor-scaled sell price rounded to "3 gold" - unreasonably cheap for buying a
    // card back mid-duel). The actual buy-back price is max(150% * cardSellPrice, the floor for
    // that card's rarity) - the floor only ever RAISES the price, never lowers it below what the
    // 150% formula would already charge on an easier difficulty.
    public int anteBuyBackMinCommon = 50;
    public int anteBuyBackMinUncommon = 100;
    public int anteBuyBackMinRare = 300;
    public int anteBuyBackMinMythic = 500;

    // Overworld resource pickups, max simultaneous on the map at once (2026-08-17 user request:
    // "we have 20 currently... let's make that 30", raised again 30 -> 50 -> 60, see
    // settings.json's own comment for the Chest loot spawn type this most recently made room for).
    // Was a hardcoded ResourceSpawns.MAX_SPAWNS constant; moved here so it's re-tunable without a
    // code change, same as every other world-balance number in this file. This default only
    // matters if settings.json is deleted (its own comment says that resets to these built-in
    // defaults) - kept equal to the live settings.json value so a reset doesn't silently undo the
    // balance changes above. See ResourceSpawns.maxSpawns().
    public int maxResourceSpawns = 60;

    // Shop card-price baseline by town ownership (2026-08-17 user spec: "cards bought at AI shops
    // 25% more expensive... 25% cheaper at player shops... before any other discounts/increases
    // like reputation, relations, etc" - to push the player toward building their own shops and
    // researching set unlocks rather than just buying everywhere). Applied as one more
    // multiplicative factor in ShopActor.getPriceModifier(), alongside (not replacing) the
    // existing ColorReputation/town-reputation modifiers. Neutral towns (Spawn) get neither -
    // see ShopActor.ownershipBaseModifier().
    public float aiShopPriceMultiplier = 1.25f;
    public float playerShopPriceMultiplier = 0.75f;

    // Side-quest expiry window in in-game days (2026-08-20 user request: make QuestExpiry's
    // hardcoded 30 tunable; the plane's settings.json sets 20). Story quests never expire.
    public int sideQuestDays = 30;

    // Attacking mages each AI color can field at once, NORMAL-difficulty base (2026-08-20 user
    // spec: "base number of attacking mages per color... for normal, and easy is -1, hard +1,
    // insane +2"). TerritoryControl.maxActiveMagesPerColor() applies those fixed per-difficulty
    // offsets plus the town-count and Color Defeat bonuses on top. Default 3 reproduces the old
    // hardcoded 2+index ladder (2/3/4/5) exactly.
    public int baseAttackingMagesPerColor = 3;

    // Progressive Set Unlocks (MOD_SCOPE.md #4) research eligibility threshold (2026-08-22 user
    // request to make ResearchScene's hardcoded THRESHOLD_FRACTION tunable). Fraction of an
    // edition's own real card count you must have found before the Research Lab offers to unlock
    // it - was a fixed 0.10f (10%, the user's original 2026-08-12 spec: "10% of an expansion vs.
    // 10 cards... standard across the different expansions and card counts"). The 5-card floor
    // (ResearchScene.THRESHOLD_MIN, keeping a tiny supplemental set from becoming a 1-2 card
    // unlock) stays a fixed constant - not asked to be tunable, and unlike the fraction it isn't a
    // single "how hard should this be" knob.
    public float researchThresholdFraction = 0.10f;
    // Research Lab timing and price (user request 2026-09-03: "add to settings.json the 7 days and
    // the shard cost so people can change that"). Days are counted from each edition's OWN start
    // day - several editions can be researched at once. Shards are the base price before the
    // difficulty scaling every other building cost gets (EconomyBuildings.scaledCost()).
    public int researchDays = 7;
    public int researchShardCost = 100;
    // AI town guard dots (MOD_SCOPE #87, user spec 2026-09-03): an AI-held color town gains one
    // guard level every this many in-game days of unbroken AI ownership (default 4 weeks), up to
    // level 4. The clock starts when the town is first seen held (save load / capture), never
    // retroactively. Level -> assault defender: 0 Apprentice, 1 Adept, 2 Master, 3 Archmage,
    // 4 Archmage with two starting lands.
    public int aiTownGuardDaysPerLevel = 28;
    // A given AI town can be attacked once per this many in-game days (user spec 2026-09-03: "once
    // a week"); the barred dialog tells the player how many days remain.
    public int aiTownAssaultCooldownDays = 7;

    // Inn Tournament Re-roll (2026-08-24 user spec: "let the player be able to re-roll the
    // tournament draft set. for 15 gems"). Flat cost, no difficulty scaling - the user gave an
    // exact number, and the closest existing precedent (EconomyBuildings.SHOP_TYPE_REROLL_
    // SHARD_COST, a shop's card-shop-TYPE re-roll) is likewise a flat, unscaled cost. See
    // InnScene.rerollEvent().
    public int innTournamentRerollShardCost = 15;

    // Functioning Neutral Towns (2026-08-24 user spec, raised 10 -> 20 same day after the first
    // playtest found one: "that's a big map"). How many neutral ("Waste Town") POIs get
    // pre-seeded as functioning at world-gen - see TownRestoration.seedFunctioningNeutralTowns().
    // Per-color lockout on attacking the player's Capitol, in in-game days (user spec 2026-08-31:
    // "once a week... from each color"). Rolling window, not a calendar week: a dispatch on day 6
    // blocks that color until day 13. 0 disables the cooldown entirely.
    public int capitolTargetCooldownDays = 7;
    public int functioningNeutralTownCount = 20;
    // Roaming-spawn duplicate limiting (user report 2026-09-01), gated by
    // ConfigData.spawnDuplicateLimitEnabled. How many of the SAME enemy may already be alive
    // within sameEnemyNearbyRadius before a fresh roll of that same enemy gets re-rolled. 2 means
    // a pair is fine and a third is what gets pushed away - the user's report was three.
    public int maxSameEnemyNearby = 2;
    // World units around the player counted for the rule above. Roaming spawns land 45-180 units
    // out (WorldStage.spawn(): unit = intendedHeight/6, distance = unit..unit*4), so 220 covers
    // the whole spawn annulus plus a margin, i.e. everything that could read as "close to each
    // other" on one screen. Enemies further out than this are not competing for the same space.
    public float sameEnemyNearbyRadius = 220f;
    // How many times to re-roll the biome's weighted pick looking for a different enemy before
    // giving up and spawning the duplicate anyway. Deliberately finite and deliberately NOT a
    // skipped spawn: a biome whose list is genuinely one or two entries long must still populate.
    public int sameEnemySpawnRerolls = 4;
}
