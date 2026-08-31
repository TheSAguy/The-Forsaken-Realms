package forge.adventure.data;

/**
 * One race's assigned starting SHOP TYPES (user spec 2026-08-30: "Depending on the race. Give each
 * race [2] Tribal classes... using lore, use your best judgement"). Loaded from the plane
 * config.json's "raceShops" array; `race` matches heroes.json's RAW hero name
 * (HeroListData.getRawRaceName()), `shops` are exact ShopData names as they appear in shops.json
 * and the town shop lists.
 * <p>
 * Deliberately data-driven rather than hardcoded in Java, mirroring {@link RaceEditionData}: the
 * lore mapping is a balance/flavour decision the plane author should be able to retune without a
 * rebuild. Unlike raceEditions, an unmatched race name is LOGGED rather than silently falling
 * through to a default - a typo in a race key here would otherwise cost that race its entire
 * starting tribal grant with no visible symptom (see AdventurePlayer.seedStartingShopTypes()).
 * <p>
 * Shop names must be exact and must exist in a tier list the chooser actually offers - notably
 * NOT the mythic tier, which MapStage's tier pools deliberately omit. Plurals in this game's shop
 * names do not match their creature subtype ("Spiders" sells Spider, "Gods" sells God/Demigod,
 * "SmallCats" sells Cat), so these cannot be derived programmatically from a tribe name.
 */
public class RaceShopData {
    public String race;
    public String[] shops;
}
