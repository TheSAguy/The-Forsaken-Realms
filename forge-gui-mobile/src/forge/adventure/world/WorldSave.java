package forge.adventure.world;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.OverlayText;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.MapViewScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.util.SignalList;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.localinstance.properties.ForgeConstants;
import forge.player.GamePlayerUtil;

import java.io.*;
import java.util.Date;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Represents everything that will be saved, like the player and the world.
 */
public class WorldSave {

    /**
     * Round 144 (code review 4.7). Every serializable class in the save is pinned, but until now
     * the save carried no version of its own and no migration hook - compatibility rested entirely
     * on nobody ever removing or retyping a field, and DecompressibleInputStream's local-descriptor
     * substitution (a good safety net for ADDED fields) would happily read a mismatched stream into
     * the wrong shape rather than refuse it.
     * <p>
     * Bump this ONLY for a change that older code cannot read correctly. Adding a field does not
     * qualify - that is exactly what the descriptor substitution handles - so this number is
     * expected to move rarely. A save written before this round has no entry at all, which reads
     * as version 1 (see load()), so nothing existing is invalidated.
     */
    static final public int SAVE_FORMAT_VERSION = 1;
    /** The oldest format this build still knows how to read. */
    static final public int MIN_READABLE_SAVE_FORMAT = 1;

    static final public int AUTO_SAVE_SLOT = -1;
    static final public int QUICK_SAVE_SLOT = -2;
    static final public int INVALID_SAVE_SLOT = -3;
    static final WorldSave currentSave = new WorldSave();
    public WorldSaveHeader header = new WorldSaveHeader();
    private final AdventurePlayer player = new AdventurePlayer();
    private final World world = new World();
    private final PointOfInterestChanges.Map pointOfInterestChanges = new PointOfInterestChanges.Map();


    private final SignalList onLoadList = new SignalList();

    public final World getWorld() {
        return world;
    }

    public AdventurePlayer getPlayer() {
        return player;
    }

    public void onLoad(Runnable run) {
        onLoadList.add(run);
    }

    public PointOfInterestChanges getPointOfInterestChanges(String id) {
        if (!pointOfInterestChanges.containsKey(id))
            pointOfInterestChanges.put(id, new PointOfInterestChanges());
        return pointOfInterestChanges.get(id);
    }

    // Read-only lookup, returns null if the POI has no recorded changes. Unlike the get-OR-CREATE
    // accessor above (the right semantics when something is about to record a change), this never
    // inserts - Territory Control's daily expansion sweep and the World Standings town count query
    // every POI on the map, and letting pure reads materialize an empty PointOfInterestChanges for
    // every dungeon/cave/town ever scanned would permanently grow this map (and the save file) for
    // no benefit. Callers must handle null (TownRestoration.isTownRestored(null) already does).
    public PointOfInterestChanges peekPointOfInterestChanges(String id) {
        return pointOfInterestChanges.get(id);
    }

    /**
     * Round 140 (code review S2-4, user decision 2026-09-07): destroy a POI's saved changes
     * outright. A capture re-keys the POI (PointOfInterest.transformInto() derives getID() from
     * data.name), and the entry under the OLD id used to be left behind - so a later revert to
     * that same name handed the player back every building, guard and flag they had lost. The
     * user's rule is that a town changing hands keeps nothing, so the entry is deleted rather
     * than migrated.
     */
    public void removePointOfInterestChanges(String id) {
        pointOfInterestChanges.remove(id);
    }

    // Lets a global per-day sweep (see EconomyBuildings.processDailyTick()) find every built
    // mine/bank across every town without needing to know their POI ids in advance.
    public java.util.Collection<PointOfInterestChanges> getAllPointOfInterestChanges() {
        return pointOfInterestChanges.values();
    }

    /** Round 183: true once a game exists in this session (a load or a new game succeeded) - only then is
     *  there a running game worth snapshotting before a load. */
    private static boolean liveGame = false;
    /** Round 183: set by readSave() the moment it starts overwriting the live header / player / world. */
    private static boolean loadTouchedLiveState = false;
    /** Round 183: a load failed after touching the live game and the rollback failed too - the in-memory
     *  game is a hybrid of two saves, so nothing may be written until a load or a new game succeeds. */
    private static boolean saveBlocked = false;

    static public boolean load(int currentSlot) {
        Forge.invokeWorldSave = true; // This is for dispose method check
        String fileName = WorldSave.getSaveFile(currentSlot);
        if (!new File(fileName).exists())
            return false;
        new File(getSaveDir()).mkdirs();
        // Round 183 (code review E4): a load overwrites the live player and world IN PLACE. One that failed
        // halfway left a hybrid - the loaded save's player with a half-applied world - that the menu then
        // returned to, and the next of WorldStage's autosaves wrote it over auto_save.sav. A running game is
        // now snapshotted first (the same serializers a save uses, into memory) and restored if the load
        // fails after touching anything. A RuntimeException inside player.load() also escaped both catches.
        byte[] rollback = liveGame ? currentSave.snapshotForRollback() : null;
        loadTouchedLiveState = false;
        boolean loaded;
        try (FileInputStream in = new FileInputStream(fileName)) {
            loaded = readSave(in, currentSlot);
        } catch (Exception e) {
            if (lastLoadError == null)
                lastLoadError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            System.err.println("[TFR-Load] load of slot " + currentSlot + " failed: " + e);
            e.printStackTrace();
            loaded = false;
        }
        if (loaded) {
            liveGame = true;
            saveBlocked = false;
            return true;
        }
        if (loadTouchedLiveState)
            rollBack(rollback);
        return false;
    }

    /** Round 183: puts the pre-load snapshot back, or - when there is none or it will not load - blocks saving. */
    private static void rollBack(byte[] snapshot) {
        if (snapshot != null) {
            try {
                if (readSave(new ByteArrayInputStream(snapshot), INVALID_SAVE_SLOT)) {
                    System.err.println("[TFR-Load] rolled back to the game that was running before the failed load");
                    lastLoadError = (lastLoadError == null ? "" : lastLoadError + "\n\n")
                            + "Your current game was restored exactly as it was.";
                    return;
                }
            } catch (Exception e) {
                System.err.println("[TFR-Load] rollback failed: " + e);
                e.printStackTrace();
            }
        }
        saveBlocked = true;
        System.err.println("[TFR-Load] the running game could NOT be restored - saving is OFF until a load or a new game succeeds");
        lastLoadError = (lastLoadError == null ? "" : lastLoadError + "\n\n")
                + "Your current game could not be restored, so saving is switched off until you load a save or start a new game.";
    }

    /** Round 183: the running game, serialized exactly as save() would write it, into memory. Null when a
     *  serializer reports an error (then a failed load blocks saving instead of rolling back). */
    private byte[] snapshotForRollback() {
        try {
            SaveFileData playerData = player.save();
            SaveFileData worldData = world.save();
            SaveFileData worldStageData = WorldStage.getInstance().save();
            SaveFileData poiData = pointOfInterestChanges.save();
            if (!getExceptionMessage(playerData, worldData, worldStageData, poiData).isEmpty()) {
                System.err.println("[TFR-Load] no rollback snapshot - a serializer reported an error");
                return null;
            }
            SaveFileData mainData = new SaveFileData();
            mainData.store("saveFormatVersion", SAVE_FORMAT_VERSION);
            mainData.store("player", playerData);
            mainData.store("world", worldData);
            mainData.store("worldStage", worldStageData);
            mainData.store("pointOfInterestChanges", poiData);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DeflaterOutputStream def = new DeflaterOutputStream(bytes);
                 ObjectOutputStream oos = new ObjectOutputStream(def)) {
                oos.writeObject(header);
                oos.writeObject(mainData);
            }
            System.out.println("[TFR-Load] rollback snapshot taken (" + bytes.size() + " bytes)");
            return bytes.toByteArray();
        } catch (Exception e) {
            System.err.println("[TFR-Load] no rollback snapshot: " + e);
            return null;
        }
    }

    /** The body of a load, from any source - a save file, or the rollback snapshot. */
    private static boolean readSave(InputStream source, int currentSlot) throws IOException, ClassNotFoundException {
        {
            try (InputStream fos = source;
                 InflaterInputStream inf = new InflaterInputStream(fos);
                 ObjectInputStream oos = new ObjectInputStream(inf)) {
                WorldSaveHeader loadedHeader = (WorldSaveHeader) oos.readObject();
                SaveFileData mainData = (SaveFileData) oos.readObject();
                // Round 144 (code review 4.7): checked BEFORE any sub-object is read, which is the
                // whole point - a format this build cannot read must be refused while the save on
                // disk is still untouched, not discovered halfway through deserialising it. Saves
                // written before this round carry no entry and read as version 1.
                int savedFormat = mainData.containsKey("saveFormatVersion")
                        ? mainData.readInt("saveFormatVersion") : 1;
                if (savedFormat < MIN_READABLE_SAVE_FORMAT || savedFormat > SAVE_FORMAT_VERSION) {
                    lastLoadError = "save format version " + savedFormat + ", this build reads "
                            + MIN_READABLE_SAVE_FORMAT + " to " + SAVE_FORMAT_VERSION;
                    System.err.println("[TFR-Load] REFUSED slot " + currentSlot + ": " + lastLoadError
                            + ". The save on disk is unchanged.");
                    return false;
                }
                loadTouchedLiveState = true; // round 183: from here on the live game is being overwritten
                currentSave.header = loadedHeader;
                currentSave.player.load(mainData.readSubData("player"));
                GamePlayerUtil.getGuiPlayer().setName(currentSave.player.getName());
                try {
                    currentSave.world.load(mainData.readSubData("world"));
                    currentSave.pointOfInterestChanges.load(mainData.readSubData("pointOfInterestChanges"));
                    // After BOTH world and pointOfInterestChanges are loaded - the rebuild reads
                    // town-ownership flags from the latter, and World.load() alone runs too early
                    // (see its own comment). Caches which map areas count as fog-of-war Revealed
                    // around player-owned towns.
                    currentSave.world.rebuildPlayerTownVision();
                    // Same both-halves-loaded requirement: Capitol per-building state repair
                    // (inn auto-repaired, economy buildings moved off the fixed land shops) and
                    // the town-count life bonus both read pointOfInterestChanges flags. Both are
                    // idempotent, both inert unless the mod plane's config flags are on.
                    forge.adventure.util.TownRestoration.repairCapitolState(currentSave.world);
                    // FoW Stage-3 reveal gap (2026-08-13 fix, user report) - self-heals every
                    // player-owned restored town's vision-circle reveal, not just the Capitol's
                    // (repairCapitolState() above only covers the Capitol). Same both-halves-loaded
                    // requirement as the Capitol repair above (reads pointOfInterestChanges flags).
                    forge.adventure.util.TownRestoration.repairAllTownVisionReveal(currentSave.world);
                    forge.adventure.util.TownRestoration.updateTownLifeBonus(false);
                    forge.adventure.util.TownRestoration.updateRingLifeBonus(false);
                    // Player/AI edition-shard exclusivity (2026-08-16) - idempotent migration for
                    // saves whose shards were seeded before the race-edition reservation existed
                    // (same pattern as the two repair calls above): strips the player's race
                    // editions from the AI color shards on every load, no-op once clean.
                    forge.adventure.util.EditionProgression.reservePlayerEditions(currentSave.world, currentSave.player);
                    // Re-derive the minimap fog overlay now that the vision cache is real -
                    // World.load()'s own rebuild ran before pointOfInterestChanges loaded, so its
                    // Revealed tier (owned-town vision circles) was computed against an empty
                    // cache. No-op when fog of war is off.
                    currentSave.world.rebuildFogOfWarPixmap();
                    WorldStage.getInstance().load(mainData.readSubData("worldStage"));
                    // generateNew() never runs for a loaded save, so nothing has pre-built Territory
                    // Control's per-color WFC structure patterns yet - kick that off now, in the
                    // background, so the first in-game day that triggers expansion doesn't have to
                    // (see World.claimWastelandRing()'s own comment - building one of these cold, on
                    // the game thread, mid-play, is what caused a real, reported freeze).
                    currentSave.world.prewarmTerritoryControlCaches();

                    // WorldStage/WorldBackground are long-lived singletons for the whole app session
                    // (WorldStage.getInstance() never gets torn down between games) - loading a save
                    // mid-session (the in-game Load menu, no app restart) replaces World's own data
                    // (biomeMap/terrainMap/mapObjectIds) outright, but WorldBackground caches its
                    // per-chunk rendering in TWO independent places that are only ever built once
                    // (see WorldBackground.reloadChunkObjects()'s and invalidateChunkTexture()'s own
                    // comments) - nothing about a plain load tells either of them that's now stale.
                    // Real, reported bugs: loading an earlier save while standing at the same spot
                    // still showed decoration doodads from the *later* session (fixed first), then a
                    // follow-up report showed a captured town's "player"-recolored GROUND also stuck
                    // showing the later session's color after loading a save that should have reverted
                    // it to neutral - the ground-texture cache is separate from the doodad Actor cache
                    // and needed its own invalidation. Force every chunk to refresh both from the
                    // freshly-loaded data - both methods already no-op instantly for any chunk that
                    // was never loaded in the first place, so this is cheap even for a large map.
                    int chunksWide = currentSave.world.getWidthInChunks();
                    int chunksHigh = currentSave.world.getHeightInChunks();
                    for (int cx = 0; cx < chunksWide; cx++)
                        for (int cy = 0; cy < chunksHigh; cy++) {
                            WorldStage.getInstance().reloadBackgroundChunkObjects(cx, cy);
                            WorldStage.getInstance().invalidateBackgroundChunkTexture(cx, cy);
                        }

                } catch (Exception e) {
                    // Round 140 (code review S1-1, Critical). This used to print "Generating New
                    // World" - not the exception - and then REPLACE the player's world with a
                    // fresh random one, in place, on the save they had just asked to load. Any
                    // failure anywhere in the block above reached it: a serialization drift, a
                    // missing plane resource, an NPE in one of the mod's nine load hooks. The
                    // player kept their character and lost the map.
                    //
                    // It now refuses. Returning false is already the "load failed" contract every
                    // caller implements (SaveLoadScene and StartScene both clear the transition
                    // screen and stay on the menu), so the save is left untouched on disk and can
                    // be recovered or reported instead of being silently overwritten by the next
                    // autosave. Regeneration remains available deliberately, through New Game+.
                    lastLoadError = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    System.err.println("[TFR-Load] WORLD LOAD FAILED for slot " + currentSlot
                            + " - refusing to regenerate. The save on disk is unchanged.");
                    e.printStackTrace();
                    return false;
                }

                currentSave.onLoadList.emit();

            }
        }
        return true;
    }

    /** Round 140 (S1-1): why the last load() returned false, for the menu to show. Null when
     *  the last load succeeded or none has been attempted. */
    private static String lastLoadError = null;

    public static String getLastLoadError() {
        return lastLoadError;
    }

    public static void clearLastLoadError() {
        lastLoadError = null;
    }

    public static boolean isSafeFile(String name) {
        return filenameToSlot(name) != INVALID_SAVE_SLOT;
    }

    static public int filenameToSlot(String name) {
        if (name.equals("auto_save.sav"))
            return AUTO_SAVE_SLOT;
        if (name.equals("quick_save.sav"))
            return QUICK_SAVE_SLOT;
        if (!name.contains("_") || !name.endsWith(".sav"))
            return INVALID_SAVE_SLOT;
        return Integer.parseInt(name.split("_")[0]);
    }

    static public String filename(int slot) {
        if (slot == AUTO_SAVE_SLOT)
            return "auto_save.sav";
        if (slot == QUICK_SAVE_SLOT)
            return "quick_save.sav";
        return slot + "_save_slot.sav";
    }

    public static String getSaveDir() {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane();
    }

    public static String getSaveFile(int slot) {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane() + File.separator + filename(slot);
    }

    public static WorldSave getCurrentSave() {
        return currentSave;
    }

    /** @param startingColorId the plane color id the player actually PICKED ("W"/"U"/"B"/"R"/"G"),
     *  or null for modes with no real pick. Separate from startingColorIdentity, which is only a
     *  starter-deck lookup key and reports White for Chaos/Precon/Custom - see
     *  NewGameScene.getStartingColorId() and AdventurePlayer.seedStartingShopTypes(). */
    public static WorldSave generateNewWorld(String name, boolean male, int race, int avatarIndex, ColorSet startingColorIdentity, DifficultyData diff, AdventureModes mode, int customDeckIndex, CardEdition starterEdition, long seed, String startingColorId) {
        // Order fixed 2026-08-24 (real bug, user report: "10 working neutral towns" never
        // appeared in a fresh game) - this used to clear AFTER generateNew(), silently wiping any
        // pointOfInterestChanges writes generateNew() itself makes (TownRestoration.
        // seedFunctioningNeutralTowns(), called from inside World.generateNew(), writes its
        // NEUTRAL_SEEDED_FLAG here - confirmed via direct save-file inspection: the world-gen log
        // showed "seeded 10/10", but the saved pointOfInterestChanges had zero). Nothing in
        // generateNew() ever READS pointOfInterestChanges, so clearing first is behaviorally
        // identical for everything else and matches the already-correct order SaveLoadScene's
        // NewGamePlus path uses (clearChanges() before generateNew()).
        currentSave.pointOfInterestChanges.clear();
        currentSave.world.generateNew(seed);
        boolean chaos = mode == AdventureModes.Chaos;
        boolean custom = mode == AdventureModes.Custom;

        Deck starterDeck = Config.instance().starterDeck(startingColorIdentity, diff, mode, customDeckIndex, starterEdition, race);
        currentSave.player.create(name, starterDeck, male, race, avatarIndex, chaos, custom, diff, mode, startingColorId);
        // Player/AI edition exclusivity (2026-08-16 user spec) - the shard seeding inside
        // world.generateNew() above ran before this player existed, so the player's race
        // editions are pulled back OUT of the AI color shards here, right after create()
        // establishes who the player is.
        forge.adventure.util.EditionProgression.reservePlayerEditions(currentSave.world, currentSave.player);

        currentSave.player.setWorldPosY((int) (currentSave.world.getData().playerStartPosY * currentSave.world.getData().height * currentSave.world.getTileSize()));
        currentSave.player.setWorldPosX((int) (currentSave.world.getData().playerStartPosX * currentSave.world.getData().width * currentSave.world.getTileSize()));
        currentSave.onLoadList.emit();
        // [TFR-NeutralTowns] persistence check (2026-08-24, same user report as the clear()-order
        // fix above) - re-counts live neutralSeeded flags at the very end of new-game setup, not
        // just at the moment World.generateNew() wrote them. The original log line alone couldn't
        // have caught this bug: it logged "seeded 10/10" truthfully, then a later clear() silently
        // wiped it, and nothing downstream ever re-confirmed the count survived. This line is that
        // re-confirmation - if it ever again reads 0 despite World.generateNew() logging a
        // non-zero seed count, something between those two points is clearing
        // pointOfInterestChanges, the same failure mode as before.
        if (currentSave.world.isFunctioningNeutralTownsEnabled()) {
            long stillSeeded = currentSave.pointOfInterestChanges.values().stream()
                    .filter(c -> c.getMapFlags().get(forge.adventure.util.TownRestoration.NEUTRAL_SEEDED_FLAG) != null)
                    .count();
            System.out.println("[TFR-NeutralTowns] post-setup persistence check: " + stillSeeded
                    + " town(s) still flagged neutralSeeded in this save");
        }
        liveGame = true;     // round 183 (E4): a real game now exists; a blocked save state is over
        saveBlocked = false;
        return currentSave;
    }

    public boolean autoSave() {
        return save("auto save" + SaveLoadScene.instance().getSaveFileSuffix(), AUTO_SAVE_SLOT);
    }

    public boolean quickSave() {
        return save("quick save" + SaveLoadScene.instance().getSaveFileSuffix(), QUICK_SAVE_SLOT);
    }

    public boolean quickLoad() {
        return load(QUICK_SAVE_SLOT);
    }

    public boolean save(String text, int currentSlot) {
        if (saveBlocked) { // round 183 (E4): the in-memory game is two saves mixed - never write it over a real file
            System.err.println("[TFR-Save] refused \"" + text + "\": a failed load could not be rolled back -"
                    + " load a save or start a new game first");
            return false;
        }
        header.name = text;

        String fileName = WorldSave.getSaveFile(currentSlot);
        String oldFileName = fileName.replace(".sav", ".old");
        new File(getSaveDir()).mkdirs();
        File currentFile = new File(fileName);
        File backupFile = new File(oldFileName);
        if (currentFile.exists())
            currentFile.renameTo(backupFile);

        try {
            try (FileOutputStream fos = new FileOutputStream(fileName);
                 DeflaterOutputStream def = new DeflaterOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(def)) {
                SaveFileData player = currentSave.player.save();
                SaveFileData world = currentSave.world.save();
                SaveFileData worldStage = WorldStage.getInstance().save();
                SaveFileData poiChanges = currentSave.pointOfInterestChanges.save();

                String message = getExceptionMessage(player, world, worldStage, poiChanges);
                if (!message.isEmpty()) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish(message);
                    return true;
                }

                SaveFileData mainData = new SaveFileData();
                mainData.store("saveFormatVersion", SAVE_FORMAT_VERSION);
                mainData.store("player", player);
                mainData.store("world", world);
                mainData.store("worldStage", worldStage);
                mainData.store("pointOfInterestChanges", poiChanges);

                if (mainData.readString("IOException") != null) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish("Please check forge.log for errors.");
                    return true;
                }

                header.saveDate = new Date();
                oos.writeObject(header);
                oos.writeObject(mainData);
            }

        } catch (IOException e) {
            restoreBackup(oldFileName, fileName);
            finish("Please check forge.log for errors.");
            return true;
        } catch (RuntimeException e) {
            // Round 123 (2026-09-05 code review S1-2): a non-IO failure inside the serializers (an NPE in one of
            // the mod's save() hooks, an unserializable field) used to escape this method with the .sav already
            // renamed to .old and a truncated new file in its place - the next launch then failed to load it and
            // silently regenerated the world. Restore the backup exactly as the IOException path does.
            System.err.println("[TFR-Save] save failed with " + e + " - restoring the previous save file");
            e.printStackTrace();
            restoreBackup(oldFileName, fileName);
            announceError("Please check forge.log for errors.");
            return true;
        }

        Config.instance().getSettingData().lastActiveSave = WorldSave.filename(currentSlot);
        Config.instance().saveSettings();
        if (backupFile.exists())
            backupFile.delete();
        finish(null);
        return true;
    }

    private void finish(String errors) {
        if (errors != null)
            announceError(errors);
        Gdx.app.postRunnable(() -> {
            OverlayText.getInstance().update("");
        });
    }

    public void restoreBackup(String oldFilename, String currentFilename) {
        File f = new File(currentFilename);
        if (f.exists())
            f.delete();
        File b = new File(oldFilename);
        if (b.exists())
            b.renameTo(new File(currentFilename));
    }

    public String getExceptionMessage(SaveFileData... datas) {
        StringBuilder message = new StringBuilder();

        for (SaveFileData data : datas) {
          String s = data.readString("IOException");
          if (s != null)
              message.append(s).append("\n");
        }

        return message.toString();
    }

    private void announceError(String message) {
        currentSave.player.getCurrentGameStage().setExtraAnnouncement("Error Saving File!\n" + message);
    }

    public void clearChanges() {
        pointOfInterestChanges.clear();
    }

    public void clearBookmarks() {
        for (PointOfInterest poi : currentSave.world.getAllPointOfInterest()) {
            if (poi == null)
                continue;
            PointOfInterestMapSprite mapSprite = WorldStage.getInstance().getMapSprite(poi);
            if (mapSprite != null)
                mapSprite.setBookmarked(false, poi);
            PointOfInterestChanges p = pointOfInterestChanges.get(poi.getID());
            if (p == null)
                continue;
            p.setIsBookmarked(false);
            p.save();
        }
        MapViewScene.instance().clearBookMarks();
    }

    public static void dispose() {
        Forge.safeDispose(currentSave.world);
    }
}
