package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import forge.adventure.data.EnemyData;

import java.util.HashMap;
import java.util.Map;

/**
 * Round 897: the baked sheet of enemy faces (dev-tools/enemy_avatars/bake_avatars.py). An enemy's own atlas holds all its
 * animation, and nothing unloads it, so a list of 195 enemies cost 800 MB (the Statistics screen until now). The sheet is
 * three 2048 pages, read one at a time as a face on them is asked for and freed by {@link #release()} when the scene that
 * asked leaves. A plane with no sheet, or an atlas not on it, answers null: the caller goes on to the enemy's own sprite.
 * Logs [TFR-Journal] for each page read and each release.
 */
public final class EnemyAvatars {
    private static final String DIR = "sprites/enemy_avatars/";

    private static Map<String, int[]> index;
    private static final Map<Integer, Texture> pages = new HashMap<>();

    private EnemyAvatars() {
    }

    private static Map<String, int[]> index() {
        if (index != null)
            return index;
        Map<String, int[]> read = new HashMap<>();
        try {
            FileHandle handle = Config.instance().getFile(DIR + "avatars.index");
            if (handle != null && handle.exists()) {
                for (String line : handle.readString("UTF-8").split("\\r?\\n")) { // git may hand it over with either
                    String[] p = line.split("\t");
                    if (p.length == 6)
                        read.put(p[0], new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                                Integer.parseInt(p[4]), Integer.parseInt(p[5])});
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("[TFR-Journal] could not read the avatar index: " + ex);
            read.clear();
        }
        index = read;
        return index;
    }

    /** This enemy's face, or null when it is not on the sheet. Render thread: it may read a page. */
    public static TextureRegion region(EnemyData data) {
        int[] cell = data == null || data.sprite == null ? null : index().get(data.sprite);
        if (cell == null)
            return null;
        Texture page = pages.get(cell[0]);
        if (page == null) {
            page = new Texture(Config.instance().getFile(DIR + "avatars_" + cell[0] + ".png"));
            page.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pages.put(cell[0], page);
            System.out.println("[TFR-Journal] avatar page " + cell[0] + " read, " + pages.size() + " held");
        }
        return new TextureRegion(page, cell[1], cell[2], cell[3], cell[4]);
    }

    /** Frees the pages. A scene that drew faces calls this when it leaves, and builds its rows again when it enters. */
    public static void release() {
        if (pages.isEmpty())
            return;
        System.out.println("[TFR-Journal] avatar pages freed: " + pages.size());
        for (Texture page : pages.values())
            page.dispose();
        pages.clear();
    }
}
