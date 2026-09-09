import forge.adventure.util.SaveFileData;
import forge.adventure.data.ItemData;

import java.io.*;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Renames an item already sitting in a save's inventory.
 *
 *   java RenameItem &lt;save.sav&gt; "&lt;Old Name&gt;" "&lt;New Name&gt;" [--write]
 *
 * Inventory items are stored as whole serialized ItemData objects, never re-resolved by name on
 * load, so renaming the item in items.json leaves an already-owned copy showing its old name
 * forever. This rewrites the stored object's name (and refreshes its description from items.json if
 * a matching definition is passed with --desc). Dry run unless --write; takes a .prerename&lt;N&gt;.bak.
 */
public class RenameItem {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0], oldName = args[1], newName = args[2];
        boolean write = false;
        String desc = null;
        for (int i = 3; i < args.length; i++) {
            if ("--write".equals(args[i])) write = true;
            else if (args[i].startsWith("--desc=")) desc = args[i].substring(7);
        }

        Object header;
        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            header = ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");
        Object o = p.containsKey("inventory") ? p.readObject("inventory") : null;
        if (!(o instanceof ItemData[])) { System.out.println("no ItemData[] inventory - nothing to do"); return; }
        ItemData[] inv = (ItemData[]) o;
        int hits = 0;
        for (ItemData i : inv) {
            if (i != null && oldName.equals(i.name)) {
                i.name = newName;
                if (desc != null) i.description = desc;
                hits++;
            }
        }
        System.out.println(Paths.get(path).getFileName() + ": " + hits + " copy/copies of \"" + oldName
                + "\" -> \"" + newName + "\"");
        if (hits == 0 || !write) {
            if (hits > 0) System.out.println("  [DRY RUN] pass --write to apply");
            return;
        }
        Path src = Paths.get(path), bak = Paths.get(path + ".prerename1.bak");
        for (int n = 1; Files.exists(bak); n++) bak = Paths.get(path + ".prerename" + (n + 1) + ".bak");
        Files.copy(src, bak);
        p.storeObject("inventory", inv);
        main.store("player", p);
        Path tmp = Paths.get(path + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(header);
            oos.writeObject(main);
        }
        Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("  backup -> " + bak.getFileName() + ", wrote " + Files.size(src) + " bytes");
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();
            SaveFileData m2 = (SaveFileData) ois.readObject();
            for (ItemData i : (ItemData[]) m2.readSubData("player").readObject("inventory"))
                if (i != null && newName.equals(i.name))
                    System.out.println("  VERIFY \"" + i.name + "\" cmd=" + i.commandOnUse);
        }
    }
}
