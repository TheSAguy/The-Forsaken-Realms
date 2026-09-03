package forge.adventure.pointofintrest;

import forge.adventure.util.Current;
import forge.adventure.util.SaveFileContent;
import forge.adventure.util.SaveFileData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

/**
 * Class to save point of interest changes, like sold cards and dead enemies
 */
public class PointOfInterestChanges implements SaveFileContent  {
    private final HashSet<Integer> deletedObjects=new HashSet<>();
    private final HashMap<Integer, HashSet<Integer>> cardsBought = new HashMap<>();
    private final java.util.Map<String, Byte> mapFlags = new HashMap<>();
    private final java.util.Map<Integer, Long> shopSeeds = new HashMap<>();
    // Weekly shop refresh (item economy, 2026-08-10): the in-game day a noRestock shop's seed was
    // last (re)rolled - see getWeeklyShopSeed(). Separate from shopSeeds' own lazy-init so an
    // ordinary (non-noRestock) shop, which never calls getWeeklyShopSeed(), never gets an entry
    // here at all.
    private final java.util.Map<Integer, Integer> shopLastRefreshDay = new HashMap<>();
    // Manual "Re-roll" (2026-08-11) - deliberately a SEPARATE cooldown clock from
    // shopLastRefreshDay above, not a shared one (user spec: "The re-roll button in independent
    // from the weekly re-fresh"). A player who manually re-rolls on day 3 doesn't push the
    // automatic weekly refresh out to day 10 - that one still fires on its own schedule from
    // whenever it last did, and vice versa.
    // NOW DUAL-PURPOSE across two mutually-exclusive callers, briefly unified 2026-08-14 then
    // split back apart 2026-08-15 per user correction:
    //  - Armory "Re-roll Inventory" (canManuallyRerollShop()/manuallyRerollShop()): back to the
    //    ORIGINAL hard "wait 7 days" rolling-window gate, flat cost, no escalation. Only this
    //    field is used; shopRerollCountThisWeek below stays untouched for Armory objects.
    //  - The generic per-shop card-content restock button (rerollSurcharge()/recordReroll(),
    //    RewardScene.restockShop()): a WEEKLY-ESCALATING surcharge instead - +1 shard per restock
    //    already used since the last fixed calendar-week boundary (day a multiple of 7, same
    //    "which week" math Guard pay's processDaysPassed() uses), resetting each boundary.
    // Safe to share one pair of maps: a shop object is exclusively noRestock (Armory/land, uses
    // the cooldown gate) or restockable (ordinary card shop, uses the surcharge) - never both -
    // so the two call patterns never collide on the same objectID.
    private final java.util.Map<Integer, Integer> shopManualRerollLastDay = new HashMap<>();
    private final java.util.Map<Integer, Integer> shopRerollCountThisWeek = new HashMap<>();
    //private final java.util.Map<Integer, Float> shopModifiers = new HashMap<>();
    private final java.util.Map<Integer, Integer> reputation = new HashMap<>();
    private Boolean isBookmarked;
    private Boolean isVisited;
    // One entry per economy building TYPE actually built in this town (type -> Tiled object id
    // of the shop that became it) - a town can have at most one of each of the 6 special types,
    // but one of each simultaneously (a Bank AND a Gold Mine AND an Exchange, etc.), so this
    // can't be a single int the way it was originally. Kept as a real int->int map (not mapFlags
    // bytes) since Tiled object ids can exceed mapFlags' byte range.
    private final java.util.Map<Integer, Integer> economyBuildingObjectIds = new HashMap<>();
    // Mine weekly payouts (2026-08-16, user spec) - the in-game day each producing building type
    // (Gold/Shard Mine, Lumber Mill, Stone Mine) last paid out, keyed by TYPE same as
    // economyBuildingObjectIds (a town has at most one of each). Seeded to the construction/
    // migration day when a mine is built (EconomyBuildings.java), then advanced in fixed 7-day
    // steps by processDaysPassed() - the exact same "shared payday" pattern guardLastPaidDay
    // already uses below, not a per-building rolling timer. Missing entry (old saves predating
    // this field) is treated as "never paid" (day 0) by the getter, so an existing mine's first
    // weekly payout under the new system lands on the very next day-7-multiple - no save
    // migration needed, same as guardLastPaidDay's own graceful-default handling.
    private final java.util.Map<Integer, Integer> economyBuildingLastPayoutDay = new HashMap<>();
    private int bankBalance = 0;
    // AI guard dots (MOD_SCOPE #87, 2026-09-03). Only meaningful on AI-held color towns; a capture
    // (transformInto) re-keys the POI and starts from a fresh entry, so both reset to 0 / unset.
    private int aiGuardLevel = 0;
    private int aiHeldSinceDay = -1;
    private int aiLastAssaultDay = -1; // town assault cooldown (once a week, user spec 2026-09-03)
    // Pinned shop identity per Tiled shop object id (shop's ShopData name). Normally a shop
    // object's type is re-rolled from its tmx lists at every map load - the Capitol migration
    // pins each migrated slot to the exact shop the source town actually had (user report
    // 2026-08-09: "I got a different set of shops in the capitol from what I had in the town"),
    // and MapStage honors a pin over the random roll from then on.
    private final java.util.Map<Integer, String> pinnedShopNames = new HashMap<>();
    // Archaeologist expeditions (2026-08-11, user spec): the in-game day the current expedition
    // was sent, or -1 if none is active. A single field, not objectId-keyed like buildingLevels/
    // guardTiers, since the Archaeologist is a single fixed Capitol-only building - never more
    // than one per save.
    private int archaeologistExpeditionSentDay = -1;
    // Building upgrade level per Tiled shop object id (Arena/Armory L1->L2, 2026-08-11 - Task
    // #8/#13). Missing entry means level 1 (base) - a not-yet-upgraded building or a pre-existing
    // save needs no migration, getBuildingLevel() already defaults correctly.
    private final java.util.Map<Integer, Integer> buildingLevels = new HashMap<>();
    // Armory Guards (2026-08-11, MOD_SCOPE.md #22) - one entry per active guard at this town's/
    // capitol's Armory. Parallel lists (tier + the in-game day salary was last paid), matching
    // every other simple-collection field in this class rather than a custom GuardData POJO.
    // Tier strings match EnemyData.tier's own values (Common/Uncommon/Rare/Mythic) - "Apprentice/
    // Adept/Master/Archmage" is a display-only mapping, same convention as mage tiers already
    // use (see EnemyData.tierDisplayName(), 2026-08-13 rename from "Challenger").
    private final java.util.List<String> guardTiers = new ArrayList<>();
    private final java.util.List<Integer> guardLastPaidDay = new ArrayList<>();

    public static class Map extends HashMap<String,PointOfInterestChanges> implements SaveFileContent {
        @Override
        public void load(SaveFileData data) {
            this.clear();
            if(data==null || !data.containsKey("keys")) return;

            String[] keys= (String[]) data.readObject("keys");
            for(int i=0;i<keys.length;i++) {
                SaveFileData elementData = data.readSubData("value_"+i);
                PointOfInterestChanges newChanges=new PointOfInterestChanges();
                newChanges.load(elementData);
                this.put(keys[i],newChanges);
            }
        }

        @Override
        public SaveFileData save() {
            SaveFileData data=new SaveFileData();
            ArrayList<String> keys=new ArrayList<>();
            ArrayList<PointOfInterestChanges> items=new ArrayList<>();
            for (Map.Entry<String,PointOfInterestChanges> entry : this.entrySet()) {
                keys.add(entry.getKey());
                items.add(entry.getValue());
            }
            data.storeObject("keys",keys.toArray(new String[0]));
            for(int i=0;i<items.size();i++)
                data.store("value_"+i,items.get(i).save());
            return data;
        }
    }

    @Override
    public void load(SaveFileData data) {
        deletedObjects.clear();
        deletedObjects.addAll((HashSet<Integer>) data.readObject("deletedObjects"));
        cardsBought.clear();
        cardsBought.putAll((HashMap<Integer, HashSet<Integer>>) data.readObject("cardsBought"));
        shopSeeds.clear();
        shopSeeds.putAll((java.util.Map<Integer, Long>) data.readObject("shopSeeds"));
        mapFlags.clear();
        mapFlags.putAll((java.util.Map<String, Byte>) data.readObject("mapFlags"));
        reputation.clear();
        if (data.containsKey("reputation")) {
            reputation.putAll((java.util.Map<Integer, Integer>) data.readObject("reputation"));
        }
        isBookmarked = (Boolean) data.readObject("isBookmarked");
        isVisited = (Boolean) data.readObject("isVisited");
        economyBuildingObjectIds.clear();
        if (data.containsKey("economyBuildingObjectIds")) {
            Object obj = data.readObject("economyBuildingObjectIds");
            if (obj instanceof java.util.Map)
                economyBuildingObjectIds.putAll((java.util.Map<Integer, Integer>) obj);
        } else if (data.containsKey("economyBuildingObjectId")) {
            // Older save with the single-building field - migrate it forward. We don't know
            // which type it was without re-reading mapFlags' old shared ECONOMY_TYPE_FLAG value,
            // which EconomyBuildings.load-time migration below handles via getMapFlags() directly.
            int legacyId = data.readInt("economyBuildingObjectId");
            Byte legacyType = mapFlags.get("economyBuildingType");
            if (legacyId != -1 && legacyType != null)
                economyBuildingObjectIds.put((int) legacyType, legacyId);
        }
        economyBuildingLastPayoutDay.clear();
        if (data.containsKey("economyBuildingLastPayoutDay")) {
            Object obj = data.readObject("economyBuildingLastPayoutDay");
            if (obj instanceof java.util.Map)
                economyBuildingLastPayoutDay.putAll((java.util.Map<Integer, Integer>) obj);
        }
        bankBalance = data.containsKey("bankBalance") ? data.readInt("bankBalance") : 0;
        aiGuardLevel = data.containsKey("aiGuardLevel") ? data.readInt("aiGuardLevel") : 0;
        aiHeldSinceDay = data.containsKey("aiHeldSinceDay") ? data.readInt("aiHeldSinceDay") : -1;
        aiLastAssaultDay = data.containsKey("aiLastAssaultDay") ? data.readInt("aiLastAssaultDay") : -1;
        archaeologistExpeditionSentDay = data.containsKey("archaeologistExpeditionSentDay")
                ? data.readInt("archaeologistExpeditionSentDay") : -1;
        pinnedShopNames.clear();
        if (data.containsKey("pinnedShopNames")) {
            Object obj = data.readObject("pinnedShopNames");
            if (obj instanceof java.util.Map)
                pinnedShopNames.putAll((java.util.Map<Integer, String>) obj);
        }
        shopLastRefreshDay.clear();
        if (data.containsKey("shopLastRefreshDay")) {
            Object obj = data.readObject("shopLastRefreshDay");
            if (obj instanceof java.util.Map)
                shopLastRefreshDay.putAll((java.util.Map<Integer, Integer>) obj);
        }
        buildingLevels.clear();
        if (data.containsKey("buildingLevels")) {
            Object obj = data.readObject("buildingLevels");
            if (obj instanceof java.util.Map)
                buildingLevels.putAll((java.util.Map<Integer, Integer>) obj);
        }
        guardTiers.clear();
        if (data.containsKey("guardTiers")) {
            Object obj = data.readObject("guardTiers");
            if (obj instanceof java.util.List)
                guardTiers.addAll((java.util.List<String>) obj);
        }
        guardLastPaidDay.clear();
        if (data.containsKey("guardLastPaidDay")) {
            Object obj = data.readObject("guardLastPaidDay");
            if (obj instanceof java.util.List)
                guardLastPaidDay.addAll((java.util.List<Integer>) obj);
        }
        shopManualRerollLastDay.clear();
        if (data.containsKey("shopManualRerollLastDay")) {
            Object obj = data.readObject("shopManualRerollLastDay");
            if (obj instanceof java.util.Map)
                shopManualRerollLastDay.putAll((java.util.Map<Integer, Integer>) obj);
        }
        shopRerollCountThisWeek.clear();
        if (data.containsKey("shopRerollCountThisWeek")) {
            Object obj = data.readObject("shopRerollCountThisWeek");
            if (obj instanceof java.util.Map)
                shopRerollCountThisWeek.putAll((java.util.Map<Integer, Integer>) obj);
        }
    }

    @Override
    public SaveFileData save() {
        SaveFileData data=new SaveFileData();
        data.storeObject("deletedObjects",deletedObjects);
        data.storeObject("cardsBought",cardsBought);
        data.storeObject("mapFlags", mapFlags);
        data.storeObject("shopSeeds", shopSeeds);
        data.storeObject("reputation", reputation);
        data.storeObject("isBookmarked", isBookmarked);
        data.storeObject("isVisited", isVisited);
        data.storeObject("economyBuildingObjectIds", economyBuildingObjectIds);
        data.storeObject("economyBuildingLastPayoutDay", economyBuildingLastPayoutDay);
        data.store("bankBalance", bankBalance);
        data.store("aiGuardLevel", aiGuardLevel);
        data.store("aiHeldSinceDay", aiHeldSinceDay);
        data.store("aiLastAssaultDay", aiLastAssaultDay);
        data.store("archaeologistExpeditionSentDay", archaeologistExpeditionSentDay);
        data.storeObject("pinnedShopNames", new HashMap<>(pinnedShopNames));
        data.storeObject("shopLastRefreshDay", new HashMap<>(shopLastRefreshDay));
        data.storeObject("buildingLevels", new HashMap<>(buildingLevels));
        data.storeObject("guardTiers", new ArrayList<>(guardTiers));
        data.storeObject("guardLastPaidDay", new ArrayList<>(guardLastPaidDay));
        data.storeObject("shopManualRerollLastDay", new HashMap<>(shopManualRerollLastDay));
        data.storeObject("shopRerollCountThisWeek", new HashMap<>(shopRerollCountThisWeek));
        return data;
    }

    public int getBuildingLevel(int objectId) {
        Integer level = buildingLevels.get(objectId);
        return level == null ? 1 : level;
    }

    public void setBuildingLevel(int objectId, int level) {
        buildingLevels.put(objectId, level);
    }

    // ---- Archaeologist expeditions (2026-08-11, user spec) ----

    public int getArchaeologistExpeditionSentDay() {
        return archaeologistExpeditionSentDay;
    }

    public void setArchaeologistExpeditionSentDay(int day) {
        archaeologistExpeditionSentDay = day;
    }

    // ---- Armory Guards (2026-08-11, MOD_SCOPE.md #22) ----

    public int getAiGuardLevel() { return aiGuardLevel; }
    public void setAiGuardLevel(int level) { aiGuardLevel = Math.max(0, level); }
    public int getAiHeldSinceDay() { return aiHeldSinceDay; }
    public void setAiHeldSinceDay(int day) { aiHeldSinceDay = day; }
    public int getAiLastAssaultDay() { return aiLastAssaultDay; }
    public void setAiLastAssaultDay(int day) { aiLastAssaultDay = day; }

    public int getGuardCount() {
        return guardTiers.size();
    }

    public String getGuardTier(int index) {
        return guardTiers.get(index);
    }

    public int getGuardLastPaidDay(int index) {
        return guardLastPaidDay.get(index);
    }

    public void setGuardLastPaidDay(int index, int day) {
        guardLastPaidDay.set(index, day);
    }

    public void hireGuard(String tier, int currentDay) {
        guardTiers.add(tier);
        guardLastPaidDay.add(currentDay);
    }

    /** Removes a guard by tier fight order (dismissed by the player, killed in combat, or
     *  disbanded for missed salary) - callers resolve the index themselves (e.g. strongest-first
     *  for combat, see TerritoryControl). */
    public void removeGuardAt(int index) {
        guardTiers.remove(index);
        guardLastPaidDay.remove(index);
    }

    public String getPinnedShopName(int objectId) {
        return pinnedShopNames.get(objectId);
    }

    public void setPinnedShopName(int objectId, String shopName) {
        pinnedShopNames.put(objectId, shopName);
    }

    // Reverts a slot to its tmx-derived shop type - used by TownRestoration.repairCapitolState()
    // to undo a pin an older, buggier Capitol migration left on a now-reserved slot (Armory/
    // Booster) before that slot was excluded from the migration pool.
    public void removePinnedShopName(int objectId) {
        pinnedShopNames.remove(objectId);
    }

    public boolean isObjectDeleted(int objectID) { return deletedObjects.contains(objectID); }
    public boolean deleteObject(int objectID)    { return deletedObjects.add(objectID); }

    public java.util.Map<String, Byte> getMapFlags() {
        return mapFlags;
    }

    public void buyCard(int objectID, int cardIndex) {
        if( !cardsBought.containsKey(objectID)) {
            cardsBought.put(objectID,new HashSet<>());
        }
        cardsBought.get(objectID).add(cardIndex);
    }
    public boolean wasCardBought(int objectID, int cardIndex) {
        if( !cardsBought.containsKey(objectID)) {
            return false;
        }
        return cardsBought.get(objectID).contains(cardIndex);
    }

    public long getShopSeed(int objectID){
        if (!shopSeeds.containsKey(objectID))
        {
            generateNewShopSeed(objectID);
        }
        return shopSeeds.get(objectID);
    }

    public void generateNewShopSeed(int objectID){
        shopSeeds.put(objectID, shopSeeds.containsKey(objectID)? new Random(shopSeeds.get(objectID)).nextLong() : Current.world().getRandom().nextLong());
        cardsBought.put(objectID, new HashSet<>()); //Allows cards to appear in slots of previous purchases
    }

    /**
     * Item economy (2026-08-10): the seed for a "noRestock" shop (the Armory, land shops) that
     * would otherwise never change - no restock button exists for these (see MapStage's shop-load
     * case, restockPrice forced to 0 whenever noRestock is set), so without this they'd roll their
     * stock exactly once, ever, per shop instance. Auto-reseeds once every 7 in-game days instead
     * of on player-paid demand - same generateNewShopSeed() under the hood, just triggered by the
     * calendar rather than a button. First call for a given shop both seeds and stamps the day, so
     * a freshly-discovered shop doesn't immediately "expire" on its very next 7-day boundary.
     */
    public long getWeeklyShopSeed(int objectID, int currentDay) {
        Integer lastRefresh = shopLastRefreshDay.get(objectID);
        if (lastRefresh == null || currentDay - lastRefresh >= 7) {
            generateNewShopSeed(objectID);
            shopLastRefreshDay.put(objectID, currentDay);
        }
        return getShopSeed(objectID);
    }

    /** Weekly-escalating restock surcharge (2026-08-15, moved here from the Card Shop Type/Armory
     *  reroll buttons per user correction - this belongs on the button that re-rolls a shop's CARD
     *  CONTENTS, i.e. RewardScene.restockShop(), not the ones that change a shop's type/identity)
     *  - the shard SURCHARGE to add on top of a restock's base (rarity-tier) cost for the NEXT
     *  restock of this object. 0 for the first restock since the last fixed calendar-week boundary
     *  (day a multiple of 7); +1 per restock already used since then. See recordReroll(). */
    public int rerollSurcharge(int objectID, int currentDay) {
        Integer lastDay = shopManualRerollLastDay.get(objectID);
        if (lastDay == null || lastDay / 7 != currentDay / 7)
            return 0; // never restocked, or a weekly boundary has passed since - fresh start
        Integer count = shopRerollCountThisWeek.get(objectID);
        return count == null ? 0 : count;
    }

    /** Records that a paid restock just happened, for the NEXT call to rerollSurcharge() on this
     *  object. Does not touch the shop's own seed/stock - restockShop() regenerates that itself. */
    public void recordReroll(int objectID, int currentDay) {
        int surcharge = rerollSurcharge(objectID, currentDay); // resets to 0 if a week boundary passed
        shopRerollCountThisWeek.put(objectID, surcharge + 1);
        shopManualRerollLastDay.put(objectID, currentDay);
    }

    /** Armory "Re-roll Inventory" cooldown gate (original #33 spec, restored 2026-08-15 - briefly
     *  replaced 2026-08-14 by the escalating-surcharge/no-cooldown model above, which the user
     *  asked to revert for the Armory specifically: "can only have it's inventory re-set once a
     *  week... Back to what it was before"). A rolling 7-day window, same shape as
     *  getWeeklyShopSeed()'s own day-diff check - true if this object has never been manually
     *  rerolled, or at least 7 in-game days have passed since it last was. */
    public boolean canManuallyRerollShop(int objectID, int currentDay) {
        Integer lastDay = shopManualRerollLastDay.get(objectID);
        return lastDay == null || currentDay - lastDay >= 7;
    }

    /** Armory-only manual reroll (2026-08-15: simplified back to just stamping the cooldown clock
     *  - no longer feeds the weekly-surcharge counter above, since the Armory no longer uses it). */
    public void manuallyRerollShop(int objectID, int currentDay) {
        generateNewShopSeed(objectID);
        shopManualRerollLastDay.put(objectID, currentDay);
    }

    public void setRotatingShopSeed(int objectID, long seed){
        if (shopSeeds.containsKey(objectID) && shopSeeds.get(objectID) != seed) {
            cardsBought.put(objectID, new HashSet<>()); //Allows cards to appear in slots of previous purchases
        }
        shopSeeds.put(objectID, seed);
    }

    public float getShopPriceModifier(int objectID){
        int shopRep = reputation.getOrDefault(objectID, 0);

        shopRep = Integer.min(maxRepToApply, (Integer.max(-maxRepToApply, shopRep)));

        return 1.0f + (shopRep * priceModifierPerRep);
    }

    int maxRepToApply = 20;
    float priceModifierPerRep = 0.005f;

    public float getTownPriceModifier(){
        int townRep = reputation.getOrDefault(0, 0);

        townRep = Integer.min(maxRepToApply, (Integer.max(-maxRepToApply, townRep)));

        return 1.0f - Math.round((priceModifierPerRep * townRep) * 1000)/1000f;
    }

    public void addMapReputation(int delta)
    {
        addObjectReputation(0, delta);
    }

    public void addObjectReputation(int id, int delta)
    {
        reputation.merge(id, delta, Integer::sum);
    }

    public int getMapReputation()
    {
        return getObjectReputation(0);
    }

    public int getObjectReputation(int id)
    {
        return reputation.getOrDefault(id, 0);
    }
    public boolean hasDeletedObjects() {
        return deletedObjects != null && !deletedObjects.isEmpty();
    }
    public boolean isBookmarked() {
        if (isBookmarked == null)
            return false;
        return isBookmarked;
    }
    public void setIsBookmarked(boolean val) {
        isBookmarked = val;
    }

    public void clearDeletedObjects() {
        // reset map when assigning as a quest target that needs enemies
        deletedObjects.clear();
    }
    public boolean isVisited() {
        if (isVisited ==null)
            return false;
        return isVisited;
    }
    public void visit() {
        isVisited = true;
    }

    public boolean hasEconomyBuildingOfType(int type) {
        return economyBuildingObjectIds.containsKey(type);
    }
    /** The economy building type registered for this specific shop's objectId, or -1 if it isn't one. */
    public int getEconomyBuildingType(int objectId) {
        for (java.util.Map.Entry<Integer, Integer> entry : economyBuildingObjectIds.entrySet()) {
            if (entry.getValue() == objectId)
                return entry.getKey();
        }
        return -1;
    }
    public void setEconomyBuildingObjectId(int type, int objectId) {
        economyBuildingObjectIds.put(type, objectId);
    }
    public java.util.Map<Integer, Integer> getEconomyBuildingObjectIds() {
        return economyBuildingObjectIds;
    }
    /** Day this type's mine last paid out - 0 (never-paid default) for a type with no recorded
     *  entry, same graceful handling old saves need as guardLastPaidDay. */
    public int getEconomyBuildingLastPayoutDay(int type) {
        Integer day = economyBuildingLastPayoutDay.get(type);
        return day == null ? 0 : day;
    }
    public void setEconomyBuildingLastPayoutDay(int type, int day) {
        economyBuildingLastPayoutDay.put(type, day);
    }
    public int getBankBalance() {
        return bankBalance;
    }
    public void setBankBalance(int val) {
        bankBalance = Math.max(0, val);
    }
    public void addBankBalance(int delta) {
        bankBalance = Math.max(0, bankBalance + delta);
    }
}
