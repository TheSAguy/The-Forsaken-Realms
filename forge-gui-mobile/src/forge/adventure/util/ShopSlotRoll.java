package forge.adventure.util;

import java.util.Random;

/**
 * Round 971 (Fable P2-11): the Random a town's shop slot rolls its tier (the rarity roll) and its shop type from.
 *
 * <p>MapStage drew both from the world Random in whatever state the previous slot's stock generation left it: every slot
 * reseeds that Random for its own stock, and a noRestock shop (each generic town's land shop, object 48) takes a weekly
 * seed. So the slots after it changed type - and with it their stock - every week, and a paid restock or an Armory
 * re-roll of an earlier slot re-rolled the types of the later ones on the next visit. Each slot now rolls from its own
 * seed: the world seed, the place and the object id, mixed (ResourceSpawns.mixSeed). A town keeps its shop types for the
 * life of the world, until the place itself changes (a pinned or re-rolled slot, a recolored town).
 */
public final class ShopSlotRoll {
    private static final long SALT = 0x53484F50L; // "SHOP": apart from the other seeds made from the world seed

    private ShopSlotRoll() {
    }

    /** The seed of one shop slot: the world seed, the place's id and the slot's object id. */
    public static long slotSeed(long worldSeed, String placeId, int objectId) {
        long h = ResourceSpawns.mixSeed(worldSeed ^ SALT);
        h = ResourceSpawns.mixSeed(h + (placeId == null ? 0 : placeId.hashCode()));
        return ResourceSpawns.mixSeed(h + objectId);
    }

    /** A fresh Random for one shop slot's tier and type rolls - the same draws on every visit. */
    public static Random slotRandom(long worldSeed, String placeId, int objectId) {
        return new Random(slotSeed(worldSeed, placeId, objectId));
    }
}
