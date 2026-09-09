import forge.adventure.util.SaveFileData;
import forge.adventure.data.ItemData;
import java.io.*;
import java.util.zip.InflaterInputStream;

/** Read-only: dumps the player's inventory and equipped slots exactly as stored. */
public class Inv {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(args[0]);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");
        Object o = p.containsKey("inventory") ? p.readObject("inventory") : null;
        if (o instanceof ItemData[]) {
            ItemData[] inv = (ItemData[]) o;
            System.out.println("inventory: " + inv.length + " item(s)");
            for (ItemData i : inv)
                if (i != null)
                    System.out.printf("   %-26s slot=%-10s cmd=%s%n", i.name,
                            i.equipmentSlot == null ? "-" : i.equipmentSlot,
                            i.commandOnUse == null ? "-" : i.commandOnUse);
        } else {
            System.out.println("inventory is " + (o == null ? "absent" : o.getClass()));
        }
        for (String key : new String[]{"equippedAbility1", "equippedAbility2", "equippedBody",
                "equippedNeck", "equippedLeft", "equippedRight", "equippedBoots"}) {
            if (p.containsKey(key))
                System.out.println("   [" + key + "] " + p.readString(key));
        }
    }
}
