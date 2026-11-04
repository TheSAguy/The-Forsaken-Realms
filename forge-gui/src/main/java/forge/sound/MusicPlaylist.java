package forge.sound;

import forge.util.MyRandom;

import java.io.File;

public enum MusicPlaylist {
    BLACK       ("black/"),
    BLUE        ("blue/"),
    RED         ("red/"),
    GREEN       ("green/"),
    WHITE       ("white/"),
    COLORLESS   ("colorless/"),
    CASTLE      ("castle/"),
    CAVE        ("cave/"),
    TOWN        ("town/"),
    BOSS        ("boss/"),
    MENUS       ("menus/"),
    MATCH       ("match/");

    private final String subDir;
    private int mostRecentTrackIdx = -1;
    private File[] filenames;
    // TFR round 957: a generation count instead of stock's one shared boolean, which the first playlist to reload
    // cleared, so every other playlist kept its stale file list.
    private static int generation = 0;
    private int loadedGeneration = -1;

    MusicPlaylist(String subDir0) {
        subDir = subDir0;
    }

    public static void invalidateMusicPlaylist() {
        generation++;
    }

    public String getSubDir() {
        return this.subDir;
    }

    public String getRandomFilename() {
        if (filenames == null || loadedGeneration != generation) {
            try {
                filenames = SoundSystem.findMusicFiles(this);
            }
            catch (Exception e) {
                e.printStackTrace();
                filenames = new File[0];
            }
            loadedGeneration = generation;
            mostRecentTrackIdx = -1;
        }

        if (filenames.length == 0) { return null; }
        else if (filenames.length == 1) {
            mostRecentTrackIdx = 0;
        }
        else {
            int index = MyRandom.getRandom().nextInt(filenames.length - 1);
            if(index >= mostRecentTrackIdx)
                index += 1;
            mostRecentTrackIdx = index;
        }

        System.out.println("[TFR-Music] " + subDir + " plays " + filenames[mostRecentTrackIdx].getName());
        return filenames[mostRecentTrackIdx].getPath();
    }
}
