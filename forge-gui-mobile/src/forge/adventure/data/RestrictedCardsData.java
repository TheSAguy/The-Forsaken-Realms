package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/restricted_cards.json" (user request 2026-08-22:
 * "create a Restricted card list in the settings folder that we can add more cards to if needed" -
 * part of the RoL/Commander card-mixing fix, MOD_CHANGELOG.md). Same tiny-dedicated-class pattern
 * as TuningData for "config tables/settings.json" - a single field so the backing file can stay a
 * plain, hand-editable, comment-friendly list rather than folding into the much larger config.json.
 * Loaded by Config.java and merged into ConfigData.restrictedCards (which already existed and was
 * already wired into RewardData's main reward-pool filter and cardPackShop's edition filter) -
 * this file is just a second, easier-to-maintain source for that same field, not a new mechanism.
 */
public class RestrictedCardsData {
    public String[] restrictedCards;
}
