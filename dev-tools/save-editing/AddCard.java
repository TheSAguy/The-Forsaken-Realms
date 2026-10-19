import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Adds copies of one card printing to a save's collection (round 699: the Ethereal Investigator the user lost to a River
 * Cave scroll that round 373's set rule emptied).
 *
 *   java AddCard &lt;save.sav&gt; "&lt;Name|SET|[number]&gt;" [count] [--write]
 *
 * The collection is player.cards, a String[] of "&lt;count&gt; &lt;Name|SET|[number]...&gt;" rows (README.md). An existing row
 * for the same printing gets its count raised; otherwise a row is appended. Same read/write shape as AddItem: header +
 * main object, only the player's "cards" replaced. Dry run unless --write; takes a .precard&lt;N&gt;.bak. Game closed.
 */
public class AddCard {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0], printing = args[1];
        int count = 1;
        boolean write = false;
        for (int i = 2; i < args.length; i++) {
            if ("--write".equals(args[i]))
                write = true;
            else
                count = Integer.parseInt(args[i]);
        }

        Object header;
        SaveFileData main;
        try (ObjectInputStream ois = new ObjectInputStream(new InflaterInputStream(new FileInputStream(path)))) {
            header = ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");
        String[] cards = (String[]) p.readObject("cards");
        List<String> rows = new ArrayList<>(Arrays.asList(cards));
        int at = -1, have = 0;
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            int sp = row.indexOf(' ');
            if (sp > 0 && row.substring(sp + 1).equals(printing)) {
                at = i;
                have = Integer.parseInt(row.substring(0, sp));
            }
        }
        System.out.println(Paths.get(path).getFileName() + ": " + rows.size() + " collection rows, " + have + " x "
                + printing + " now -> " + (have + count));
        if (!write) {
            System.out.println("  [DRY RUN] pass --write to apply");
            return;
        }
        if (at >= 0)
            rows.set(at, (have + count) + " " + printing);
        else
            rows.add(count + " " + printing);
        Path src = Paths.get(path), bak = Paths.get(path + ".precard1.bak");
        for (int n = 1; Files.exists(bak); n++)
            bak = Paths.get(path + ".precard" + (n + 1) + ".bak");
        Files.copy(src, bak);
        p.storeObject("cards", rows.toArray(new String[0]));
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
            String[] check = (String[]) ((SaveFileData) ois.readObject()).readSubData("player").readObject("cards");
            int after = 0;
            for (String row : check) {
                int sp = row.indexOf(' ');
                if (sp > 0 && row.substring(sp + 1).equals(printing))
                    after += Integer.parseInt(row.substring(0, sp));
            }
            System.out.println("  VERIFY " + check.length + " rows, " + after + " x " + printing);
        }
    }
}
