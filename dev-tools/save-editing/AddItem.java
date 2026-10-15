import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.ItemData;
import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Adds one item from a plane's items.json to a save's inventory (round 639: the Silver Challenge Coin the user lost to
 * a first tournament the tutorial's refund never covered).
 *
 *   java AddItem &lt;save.sav&gt; &lt;items.json&gt; "&lt;Item Name&gt;" [--write]
 *
 * The item is built as ItemListData.getItem() builds it - a clone of the catalog entry with a fresh longID - and
 * appended to the stored ItemData[] inventory. Same read/write shape as RenameItem: header + main object, only the
 * player's "inventory" replaced. Dry run unless --write; takes a .preadd&lt;N&gt;.bak. The game must be closed.
 */
public class AddItem {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0], itemsJson = args[1], name = args[2];
        boolean write = args.length > 3 && "--write".equals(args[3]);

        @SuppressWarnings("unchecked")
        Array<ItemData> catalog = new Json().fromJson(Array.class, ItemData.class,
                new String(Files.readAllBytes(Paths.get(itemsJson)), StandardCharsets.UTF_8));
        ItemData template = null;
        for (ItemData i : catalog)
            if (name.equalsIgnoreCase(i.name))
                template = i;
        if (template == null) {
            System.out.println("no \"" + name + "\" in " + itemsJson);
            return;
        }
        ItemData item = template.clone();

        Object header;
        SaveFileData main;
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            header = ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");
        ItemData[] inv = (ItemData[]) p.readObject("inventory");
        int before = 0;
        for (ItemData i : inv)
            if (i != null && name.equalsIgnoreCase(i.name))
                before++;
        System.out.println(Paths.get(path).getFileName() + ": " + inv.length + " items, " + before + " \"" + name
                + "\" now -> adding one (longID " + item.longID + ")");
        if (!write) {
            System.out.println("  [DRY RUN] pass --write to apply");
            return;
        }
        Path src = Paths.get(path), bak = Paths.get(path + ".preadd1.bak");
        for (int n = 1; Files.exists(bak); n++)
            bak = Paths.get(path + ".preadd" + (n + 1) + ".bak");
        Files.copy(src, bak);
        ItemData[] out = Arrays.copyOf(inv, inv.length + 1);
        out[inv.length] = item;
        p.storeObject("inventory", out);
        main.store("player", p);
        Path tmp = Paths.get(path + ".tmp");
        try (ObjectOutputStream oos = new ObjectOutputStream(new DeflaterOutputStream(new FileOutputStream(tmp.toFile())))) {
            oos.writeObject(header);
            oos.writeObject(main);
        }
        Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("  backup -> " + bak.getFileName() + ", wrote " + Files.size(src) + " bytes");
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            ois.readObject();
            SaveFileData m2 = (SaveFileData) ois.readObject();
            int after = 0;
            ItemData[] check = (ItemData[]) m2.readSubData("player").readObject("inventory");
            for (ItemData i : check)
                if (i != null && name.equalsIgnoreCase(i.name))
                    after++;
            System.out.println("  VERIFY " + check.length + " items, " + after + " \"" + name + "\"");
        }
    }
}
