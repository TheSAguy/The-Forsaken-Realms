package forge.adventure.util;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * TFR round 971 (Fable P2-11): a town's shop slots roll their tier and type from their own seed. Before, both came from the
 * world Random as the previous slot's stock left it, so a weekly land-shop seed or a paid restock of an earlier slot
 * changed the later slots' types.
 */
public class ShopSlotRollTest {
    private static final long WORLD = 123456789L;
    private static final String TOWN = "1234567Town/maps/map/town_generic.tmx";
    private static final int[] SLOTS = {41, 42, 43, 44, 45, 46, 48, 49, 50, 51, 52, 53};
    private static final int LAND_SHOP = 48; // noRestock: a weekly stock seed
    private static final int CANDIDATES = 9;

    /** One visit's slot types, as MapStage rolls them: rarity, then type, then the slot's stock reseeds the world Random. */
    private static List<String> visit(boolean seededPerSlot, Random world, long week, long restockedSlot41) {
        List<String> types = new ArrayList<>();
        for (int id : SLOTS) {
            Random roll = seededPerSlot ? ShopSlotRoll.slotRandom(WORLD, TOWN, id) : world;
            int rarity = roll.nextInt(100);
            int type = roll.nextInt(CANDIDATES);
            types.add(id + ":" + rarity + "/" + type);
            long stockSeed = id == LAND_SHOP ? 1000 + week : id == 41 ? restockedSlot41 : id;
            world.setSeed(stockSeed);
            for (int i = 0; i < 40 + id % 7; i++) // the stock generation's draws
                world.nextInt(1000);
        }
        return types;
    }

    @Test
    public void typesStayTheSameAcrossWeeksAndAfterARestock() {
        List<String> first = visit(true, new Random(7), 1, 41);
        AssertJUnit.assertEquals("next week", first, visit(true, new Random(99), 2, 41));
        AssertJUnit.assertEquals("after slot 41's paid restock", first, visit(true, new Random(7), 1, 77777));
        AssertJUnit.assertEquals("a later week and a restock", first, visit(true, new Random(3), 9, 31337));
    }

    @Test
    public void theOldSharedRollMovedTheLaterSlots() {
        // The mechanism this round removes: the same visit with the world Random re-rolled the slots after the land shop.
        List<String> week1 = visit(false, new Random(7), 1, 41);
        List<String> week2 = visit(false, new Random(7), 2, 41);
        AssertJUnit.assertEquals("slots before the land shop", week1.subList(0, 7), week2.subList(0, 7));
        AssertJUnit.assertFalse("slots after it changed", week1.subList(7, SLOTS.length).equals(week2.subList(7, SLOTS.length)));
    }

    @Test
    public void eachSlotTownAndWorldHasItsOwnSeed() {
        Set<Long> seeds = new HashSet<>();
        for (int id : SLOTS)
            seeds.add(ShopSlotRoll.slotSeed(WORLD, TOWN, id));
        AssertJUnit.assertEquals(SLOTS.length, seeds.size());
        AssertJUnit.assertFalse(ShopSlotRoll.slotSeed(WORLD, TOWN, 41) == ShopSlotRoll.slotSeed(WORLD, TOWN + "x", 41));
        AssertJUnit.assertFalse(ShopSlotRoll.slotSeed(WORLD, TOWN, 41) == ShopSlotRoll.slotSeed(WORLD + 1, TOWN, 41));
        AssertJUnit.assertEquals(ShopSlotRoll.slotSeed(WORLD, TOWN, 41), ShopSlotRoll.slotSeed(WORLD, TOWN, 41));
        AssertJUnit.assertEquals("no place still rolls", ShopSlotRoll.slotSeed(WORLD, null, 41), ShopSlotRoll.slotSeed(WORLD, null, 41));
    }

    @Test
    public void neighboringSlotsDoNotRollAlike() {
        // java.util.Random's first draws from neighboring raw seeds are close; the mixed seeds spread the rarity rolls.
        Set<Integer> rarities = new HashSet<>();
        int mythic = 0;
        for (int id = 1; id <= 200; id++) {
            int rarity = ShopSlotRoll.slotRandom(WORLD, TOWN, id).nextInt(100);
            rarities.add(rarity);
            if (rarity > 95)
                mythic++;
        }
        AssertJUnit.assertTrue("spread: " + rarities.size(), rarities.size() > 60);
        AssertJUnit.assertTrue("mythic share near 4%: " + mythic, mythic > 0 && mythic < 25);
    }
}
