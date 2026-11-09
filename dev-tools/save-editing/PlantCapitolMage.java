import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Round 998 agent check (one-off, test copies only): writes the two keys WorldStage.save() writes for a mage waiting
 * at the Capitol (CapitolDefenseSave) into a save's "worldStage" data, so a load with the round 998 build can be seen
 * queuing the forced duel again. Same read/write shape as AddItem: header + main object, only "worldStage" replaced.
 *   java PlantCapitolMage <save.sav> "<enemy name>" <color> [--write]
 * Dry run unless --write; takes a .precapitol<N>.bak. The game must be closed.
 */
public class PlantCapitolMage {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0], name = args[1], color = args[2];
        boolean write = args.length > 3 && "--write".equals(args[3]);
        Object header;
        SaveFileData main;
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            header = ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData ws = main.readSubData("worldStage");
        System.out.println(Paths.get(path).getFileName() + ": worldStage has " + ws.size() + " keys; capitolDefenseMage="
                + (ws.containsKey("capitolDefenseMage") ? ws.readString("capitolDefenseMage") : "(none)")
                + " -> planting \"" + name + "\" (" + color + ")");
        if (!write) {
            System.out.println("  [DRY RUN] pass --write to apply");
            return;
        }
        Path src = Paths.get(path), bak = Paths.get(path + ".precapitol1.bak");
        for (int n = 1; Files.exists(bak); n++)
            bak = Paths.get(path + ".precapitol" + (n + 1) + ".bak");
        Files.copy(src, bak);
        ws.store("capitolDefenseMage", name);
        ws.store("capitolDefenseColor", color);
        main.store("worldStage", ws);
        Path tmp = Paths.get(path + ".tmp");
        try (ObjectOutputStream oos = new ObjectOutputStream(new DeflaterOutputStream(new FileOutputStream(tmp.toFile())))) {
            oos.writeObject(header);
            oos.writeObject(main);
        }
        Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING);
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            ois.readObject();
            SaveFileData ws2 = ((SaveFileData) ois.readObject()).readSubData("worldStage");
            System.out.println("  backup -> " + bak.getFileName() + "; VERIFY capitolDefenseMage="
                    + ws2.readString("capitolDefenseMage") + " capitolDefenseColor=" + ws2.readString("capitolDefenseColor")
                    + ", " + ws2.size() + " keys");
        }
    }
}
