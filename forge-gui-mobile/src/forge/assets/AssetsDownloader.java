package forge.assets;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import forge.gui.GuiBase;
import forge.util.BuildInfo;
import forge.util.DateUtil;
import org.apache.commons.lang3.StringUtils;

import com.badlogic.gdx.Gdx;
import com.google.common.collect.ImmutableList;

import forge.Forge;
import forge.gui.FThreads;
import forge.gui.download.GuiDownloadZipService;
import forge.gui.util.SOptionPane;
import forge.util.FileUtil;

import static forge.localinstance.properties.ForgeConstants.ADV_TEXTURE_BG_FILE;
import static forge.localinstance.properties.ForgeConstants.ASSETS_DIR;
import static forge.localinstance.properties.ForgeConstants.GITHUB_SNAPSHOT_URL;
import static forge.localinstance.properties.ForgeConstants.DEFAULT_SKINS_DIR;
import static forge.localinstance.properties.ForgeConstants.GITHUB_COMMITS_ATOM;
import static forge.localinstance.properties.ForgeConstants.GITHUB_FORGE_URL;
import static forge.localinstance.properties.ForgeConstants.GITHUB_RELEASES_ATOM;
import static forge.localinstance.properties.ForgeConstants.RELEASE_URL;
import static forge.localinstance.properties.ForgeConstants.RES_DIR;

public class AssetsDownloader {
    private final static ImmutableList<String> downloadIgnoreExit = ImmutableList.of("Download", "Ignore", "Exit");
    private final static ImmutableList<String> downloadExit = ImmutableList.of("Download", "Exit");

    /**
     * Round 282: is the GitHub release's version actually NEWER than the installed one?
     * <p>
     * Stock asks only whether the two DIFFER, which is true of any unreleased build: a 1.13 test APK
     * against a newest published tag of tfr-v1.12 was told it was "currently on an older version",
     * offered 1.12 as the update, and - because stock hands the APK to the package installer and then
     * calls {@code Forge.exitAnimation(false)} - closed itself on every launch, while Android quietly
     * refused the install as a version downgrade (versionCode 11200 under 11300). Reported from the
     * emulator, 2026-09-21.
     * <p>
     * Compared segment by segment as NUMBERS. A string compare happens to work while the scheme stays
     * zero-padded two-digit minors (1.09 < 1.13), which is exactly why it is not worth relying on -
     * it breaks silently the day someone writes 1.9 or 2.0, and the failure is an update prompt that
     * either nags forever or hides a real release. Anything unparseable (a "GIT" or SNAPSHOT version
     * name) falls back to stock's inequality, so the only behavior that changes is the one that was
     * wrong - a local version AHEAD of the published one no longer offers a downgrade, and equal
     * versions still offer nothing.
     */
    private static boolean isRemoteNewer(String remote, String local) {
        String[] r = remote.trim().split("\\."), l = local.trim().split("\\.");
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int rp, lp;
            try {
                rp = i < r.length ? Integer.parseInt(r[i].trim()) : 0;
                lp = i < l.length ? Integer.parseInt(l[i].trim()) : 0;
            } catch (NumberFormatException e) {
                return !local.equals(remote); // not a number pair - stock's test
            }
            if (rp != lp)
                return rp > lp;
        }
        return false; // identical
    }

    /**
     * Round 284: an assets.zip the tester placed on the device by hand, used INSTEAD of downloading.
     * <p>
     * User: *"Can you make the test version point to C:\\Users\\User\\Pictures\\Screenshots\\Android to get
     * the assets. I think it's trying to download it from the Repo and the Repo online is 1.12."* The
     * diagnosis is exactly right - an unreleased build asks for
     * `releases/download/tfr-v<version>/assets.zip`, which 404s until that release exists - but the
     * destination cannot be a Windows path: this code runs inside the emulator, where
     * `C:\Users\...` does not exist. The equivalent that does work is a file placed in the emulator's
     * own storage, which LDPlayer's shared folder and drag-and-drop both reach.
     * <p>
     * Several locations are tried because which one a given emulator exposes to the host varies, and
     * EVERY path checked is logged - so if it still is not found, the log says where to put it rather
     * than leaving anyone guessing.
     * <p>
     * **Safe to ship.** A zip is only accepted when its `res/build.txt` matches the timestamp baked
     * into this APK, which is the same matched-pair rule `AssetsDownloader` already enforces after a
     * download: the two artifacts match only when they came from one `mvn` run. A stale or unrelated
     * assets.zip left in Downloads is therefore ignored rather than extracted over good assets.
     *
     * @param expectedBuild this APK's own build.txt contents, or null if it has none
     * @return an absolute path to a usable zip, or null to download as usual
     */
    private static String localAssetsZip(String expectedBuild) {
        String[] candidates = {
                ASSETS_DIR + "assets.zip",
                "/sdcard/Download/assets.zip",
                "/sdcard/Pictures/assets.zip",
                "/sdcard/Documents/assets.zip",
                "/sdcard/Screenshots/assets.zip",
                "/sdcard/assets.zip",
                "/storage/emulated/0/Download/assets.zip",
        };
        for (String path : candidates) {
            File f = new File(path);
            if (!f.isFile()) {
                System.out.println("[TFR-Assets] no local assets.zip at " + path);
                continue;
            }
            String zipBuild = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
                java.util.zip.ZipEntry e = zf.getEntry("res/build.txt");
                if (e != null) {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                            zf.getInputStream(e), java.nio.charset.StandardCharsets.UTF_8))) {
                        zipBuild = r.readLine();
                    }
                }
            } catch (Exception ex) {
                System.out.println("[TFR-Assets] local assets.zip at " + path + " could not be read ("
                        + ex + ") - ignoring it");
                continue;
            }
            if (zipBuild == null) {
                System.out.println("[TFR-Assets] local assets.zip at " + path
                        + " has no res/build.txt, so it is not one of ours - ignoring it");
                continue;
            }
            if (expectedBuild == null || expectedBuild.trim().isEmpty()) {
                System.out.println("[TFR-Assets] this APK has no build.txt to compare against, so the local "
                        + "assets.zip at " + path + " cannot be verified as its pair - ignoring it");
                continue;
            }
            if (!expectedBuild.trim().equals(zipBuild.trim())) {
                System.out.println("[TFR-Assets] local assets.zip at " + path + " is a DIFFERENT build ("
                        + zipBuild.trim() + ", this APK is " + expectedBuild.trim() + ") - ignoring it rather "
                        + "than extracting a mismatched pair");
                continue;
            }
            System.out.println("[TFR-Assets] using the local assets.zip at " + path + " (build "
                    + zipBuild.trim() + " matches this APK) - no download needed");
            return f.getAbsolutePath();
        }
        return null;
    }

    public static void checkForUpdates(boolean exited, Runnable runnable) {
        if (exited)
            return;
        // The Forsaken Realms standalone: this fork is a pinned build of its own game, so the
        // stock-Forge updater must never run - accepting its prompt would download plain Forge
        // over the game and leave a broken half-updated install. Desktop-only shortcut: every
        // non-Android path below ends in run(runnable) anyway, so nothing else is skipped.
        // (Android keeps the stock asset pipeline below - it is the RETARGETED updater, pointed at
        // this fork's own tfr-v releases, plus round 282's "newer, not merely different" test. This
        // line used to say Android was not shipped; that stopped being true at v1.03 / round 61, and
        // round 282's bug was reported from exactly the pipeline it waved away.)
        if (!GuiBase.isAndroid()) {
            run(runnable);
            return;
        }
        final String versionString = Forge.getDeviceAdapter().getVersionString();
        Forge.getSplashScreen().getProgressBar().setDescription("Checking for updates...");
        if (versionString.contains("GIT")) {
            if (!GuiBase.isAndroid()) {
                run(runnable);
                return;
            }
        }

        final String packageSize = GuiBase.isAndroid() ? "160MB" : "270MB";
        final String apkSize = "12MB";

        final boolean isSnapshots = versionString.contains("SNAPSHOT");
        final String snapsURL = GITHUB_SNAPSHOT_URL;
        // desktop and mobile-dev share the same package
        final String guiChannel = GuiBase.isAndroid() ? "forge/forge-gui-android/" : "forge/forge-gui-desktop/";
        final String releaseURL = RELEASE_URL +  guiChannel;
        // desktop and mobile-dev uses maven-metadata.xml on earlier releases
        final String versionText = isSnapshots ? snapsURL + "version.txt" : releaseURL + "maven-metadata.xml";
        FileHandle assetsDir = Gdx.files.absolute(ASSETS_DIR);
        FileHandle resDir = Gdx.files.absolute(RES_DIR);
        FileHandle buildTxtFileHandle = GuiBase.isAndroid() ? Gdx.files.internal("build.txt") : Gdx.files.classpath("build.txt");
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        boolean verifyUpdatable = false;
        boolean mandatory = false;
        Date snapsTimestamp = null, buildTimeStamp = null;

        String message;
        boolean connectedToInternet = Forge.getDeviceAdapter().isConnectedToInternet();
        if (connectedToInternet) {
            //currently for desktop/mobile-dev release on github
            final String releaseTag = Forge.getDeviceAdapter().getReleaseTag(GITHUB_RELEASES_ATOM);
            try {
                URL versionUrl = new URL(versionText);
                // TFR (2026-08-27 Android round): release tags on this fork are "tfr-vX.YZ", not
                // upstream's "forge-X.Y.Z" - and both the update APK and assets.zip live on the
                // fork's own GitHub release (GITHUB_FORGE_URL now points there), NOT on
                // releases.cardforge.org. Left as-was, a fresh install would have offered
                // Card-Forge's newer "Forge" release as an update and installed stock Forge
                // over this game.
                String version = isSnapshots ? FileUtil.readFileToString(versionUrl) : releaseTag.replace("tfr-v", "");
                String filename = "";
                String installerURL = "";
                if (GuiBase.isAndroid()) {
                    filename = "forsaken-realms-" + version + "-signed-aligned.apk";
                    installerURL = GITHUB_FORGE_URL + "releases/download/" + releaseTag + "/" + filename;
                } else {
                    //current release on github is tar.bz2, update this to jar installer in the future...
                    filename = isSnapshots ? "forge-installer-" + version + ".jar" : releaseTag.replace("forge-", "forge-gui-desktop-") + ".tar.bz2";
                    String releaseBZ2URL = GITHUB_FORGE_URL + "releases/download/" + releaseTag + "/" + filename;
                    String snapsBZ2URL = GITHUB_SNAPSHOT_URL + filename;
                    installerURL = isSnapshots ? snapsBZ2URL : releaseBZ2URL;
                }
                String snapsBuildDate = "", buildDate = "";
                if (isSnapshots) {
                    URL url = new URL(snapsURL + "build.txt");
                    snapsTimestamp = format.parse(FileUtil.readFileToString(url));
                    snapsBuildDate = snapsTimestamp.toString();
                    if (!GuiBase.isAndroid()) {
                        buildDate = BuildInfo.getTimestamp().toString();
                        verifyUpdatable = BuildInfo.verifyTimestamp(snapsTimestamp);
                    } else {
                        if (buildTxtFileHandle.exists()) {
                            buildTimeStamp = format.parse(buildTxtFileHandle.readString());
                            buildDate = buildTimeStamp.toString();
                            // if morethan 23 hours the difference, then allow to update..
                            verifyUpdatable = DateUtil.getElapsedHours(buildTimeStamp, snapsTimestamp) > 23;
                        } else {
                            //fallback to old version comparison
                            verifyUpdatable = !StringUtils.isEmpty(version) && isRemoteNewer(version, versionString);
                        }
                    }
                } else {
                    verifyUpdatable = !StringUtils.isEmpty(version) && isRemoteNewer(version, versionString);
                }

                if (verifyUpdatable) {
                    Forge.getSplashScreen().prepareForDialogs();

                    message = "A new version of The Forsaken Realms is available.\n(v." + version + " | " + snapsBuildDate + ")\n" +
                            "You are currently on an older version.\n(v." + versionString + " | " + buildDate + ")\n" +
                            "Would you like to update to the new version now?";
                    if (!Forge.getDeviceAdapter().isConnectedToWifi()) {
                        message += " If so, you may want to connect to wifi first. The download is around " + (GuiBase.isAndroid() ? apkSize : packageSize) + ".";
                    }
                    if (isSnapshots) // this is for snaps initial info
                        message += Forge.getDeviceAdapter().getLatestChanges(GITHUB_COMMITS_ATOM, buildTimeStamp, snapsTimestamp);
                    //failed to grab latest github tag
                    if (!isSnapshots && releaseTag.isEmpty()) {
                        if (!GuiBase.isAndroid())
                            run(runnable);
                    } else if (SOptionPane.showConfirmDialog(message, "New Version Available", "Update Now", "Update Later", true, true)) {
                        String installer = new GuiDownloadZipService("", "update", installerURL,
                                Forge.getDeviceAdapter().getDownloadsDir(), null, Forge.getSplashScreen().getProgressBar()).download(filename);
                        if (installer != null) {
                            Forge.getDeviceAdapter().openFile(installer);
                            Forge.isMobileAdventureMode = Forge.advStartup;
                            Forge.exitAnimation(false);
                            return;
                        }
                        switch (SOptionPane.showOptionDialog("Could not download update. " +
                                "Press OK to proceed without update.", "Update Failed", null, ImmutableList.of("Ok"))) {
                            default:
                                if (!GuiBase.isAndroid()) {
                                    run(runnable);
                                    return;
                                }
                                break;
                        }
                    }
                } else {
                    if (!GuiBase.isAndroid()) {
                        run(runnable);
                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (!GuiBase.isAndroid()) {
                    run(runnable);
                    return;
                }
            }
        } else {
            if (!GuiBase.isAndroid()) {
                run(runnable);
                return;
            }
        }
        // non android don't have seperate package to check
        if (!GuiBase.isAndroid()) {
            run(runnable);
            return;
        }
        // Android assets fallback
        String build = "";

        //see if assets need updating
        FileHandle advBG = Gdx.files.absolute(DEFAULT_SKINS_DIR).child(ADV_TEXTURE_BG_FILE);
        if (!advBG.exists()) {
            FileHandle deleteVersion = assetsDir.child("version.txt");
            if (deleteVersion.exists())
                deleteVersion.delete();
            FileHandle deleteBuild = resDir.child("build.txt");
            if (deleteBuild.exists())
                deleteBuild.delete();
        }

        FileHandle versionFile = assetsDir.child("version.txt");
        if (!versionFile.exists()) {
            try {
                versionFile.file().createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                Forge.isMobileAdventureMode = Forge.advStartup;
                Forge.exitAnimation(false); //can't continue if this fails
                return;
            }
        } else if (versionString.equals(FileUtil.readFileToString(versionFile.file())) && FSkin.getSkinDir() != null) {
            run(runnable);
            return; //if version matches what had been previously saved and FSkin isn't requesting assets download, no need to download assets
        }

        FileHandle resBuildDate = resDir.child("build.txt");
        if (buildTxtFileHandle.exists() && resBuildDate.exists()) {
            String buildString = buildTxtFileHandle.readString();
            String target = resBuildDate.readString();
            try {
                Date buildDate = format.parse(buildString);
                Date targetDate = format.parse(target);
                // if res folder has same build date then continue loading assets
                if (buildDate.equals(targetDate) && versionString.equals(FileUtil.readFileToString(versionFile.file()))) {
                    run(runnable);
                    return;
                }
                mandatory = true;
                build += "\nInstalled resources date: " + target + "\n";
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Forge.getSplashScreen().prepareForDialogs(); //ensure colors set up for showing message dialogs

        boolean canIgnoreDownload = resDir.exists() && FSkin.getAllSkins() != null && !FileUtil.readFileToString(versionFile.file()).isEmpty(); //don't allow ignoring download if resource files haven't been previously loaded
        if (mandatory && connectedToInternet)
            canIgnoreDownload = false;

        if (!connectedToInternet) {
            message = "Updated resource files cannot be downloaded due to lack of internet connection.\n\n";
            if (canIgnoreDownload) {
                message += "You can continue without this download, but you may miss out on card fixes or experience other problems.";
            } else {
                message += "You cannot start the app since you haven't previously downloaded these files.";
            }
            switch (SOptionPane.showOptionDialog(message, "No Internet Connection", null, ImmutableList.of("Ok"))) {
                default: {
                    if (!canIgnoreDownload) {
                        Forge.isMobileAdventureMode = Forge.advStartup;
                        Forge.exitAnimation(false); //exit if can't ignore download
                    }
                }
            }
            return;
        }

        //prompt user whether they wish to download the updated resource files
        message = "There are updated resource files to download. " +
                "This download is around " + packageSize + ", ";
        if (Forge.getDeviceAdapter().isConnectedToWifi()) {
            message += "which shouldn't take long if your wifi connection is good.";
        } else {
            message += "so it's highly recommended that you connect to wifi first.";
        }
        // Round 284: a hand-placed, build-matched assets.zip makes the whole download moot - prompt
        // included. Computed here rather than at the extract site so the tester is not asked to approve
        // a download that is not going to happen.
        final String localZip = localAssetsZip(buildTxtFileHandle.exists() ? buildTxtFileHandle.readString() : null);

        final List<String> options;
        message += "\n\n";
        if (canIgnoreDownload) {
            message += "If you choose to ignore this download, you may miss out on card fixes or experience other problems.";
            options = downloadIgnoreExit;
        } else {
            message += "This download is mandatory to start the app since you haven't previously downloaded these files.";
            options = downloadExit;
        }

        if (localZip == null) {
            switch (SOptionPane.showOptionDialog(message + build, "", null, options)) {
                case 1:
                    if (!canIgnoreDownload) {
                        Forge.isMobileAdventureMode = Forge.advStartup;
                        Forge.exitAnimation(false); //exit if can't ignore download
                        return;
                    } else {
                        run(runnable);
                        return;
                    }
                case 2:
                    Forge.isMobileAdventureMode = Forge.advStartup;
                    Forge.exitAnimation(false);
                    return;
            }
        }

        //allow deletion on Android 10 or if using app-specific directory
        boolean allowDeletion = Forge.androidVersion < 30 || GuiBase.isUsingAppDirectory();
        // TFR: assets.zip is attached to the fork's own GitHub release whose tag matches THIS
        // APK's version ("tfr-v" + versionName) - the app and its assets always update together.
        String assetURL = isSnapshots ? snapsURL + "assets.zip"
                : GITHUB_FORGE_URL + "releases/download/tfr-v" + versionString + "/assets.zip";
        GuiDownloadZipService assets = new GuiDownloadZipService("", "resource files", assetURL,
                ASSETS_DIR, RES_DIR, Forge.getSplashScreen().getProgressBar(), allowDeletion);
        if (localZip == null) {
            assets.downloadAndUnzip();
        } else {
            // extract() DELETES the zip it is handed (it is normally a temp download), so the tester's
            // own file is copied first - otherwise a 210MB file has to be re-copied after every run,
            // and after any failure.
            String scratch = ASSETS_DIR + "local_assets.zip";
            try {
                java.nio.file.Files.copy(new File(localZip).toPath(), new File(scratch).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.out.println("[TFR-Assets] could not stage " + localZip + " (" + e
                        + ") - falling back to the download");
                assets.downloadAndUnzip();
                scratch = null;
            }
            if (scratch != null)
                assets.extract(scratch);
        }

        if (allowDeletion)
            FSkinFont.deleteCachedFiles(); //delete cached font files in case any skin's .ttf file changed

        //reload light version of skin after assets updated
        FThreads.invokeInEdtAndWait(() -> {
            FSkinFont.updateAll(); //update all fonts used by splash screen
            FSkin.loadLight(FSkin.getName(), Forge.getSplashScreen());
        });

        //save version string to file once assets finish downloading
        //so they don't need to be re-downloaded until you upgrade again
        // TFR guard (2026-08-27, relaxed same day after adversarial review): only skip the
        // version.txt stamp when we can PROVE the extracted assets don't belong to this APK
        // (both build.txt files present, parseable, and different timestamps - i.e. the download
        // 404'd and the old tree survived, the case that used to lock the app onto stale assets
        // forever). If either side is missing or unparseable, fall back to upstream's
        // unconditional stamp: a byte-exact requirement here turned any APK/assets.zip pair
        // built in separate mvn runs into a permanent forced-160MB-redownload loop with no
        // Ignore button. Corollary for releases: ALWAYS build the APK and assets.zip in the
        // same mvn invocation and upload them together.
        if (connectedToInternet) {
            boolean provenMismatch = false;
            try {
                FileHandle extractedBuild = resDir.child("build.txt");
                if (buildTxtFileHandle.exists() && extractedBuild.exists()) {
                    Date apkStamp = format.parse(buildTxtFileHandle.readString().trim());
                    Date resStamp = format.parse(extractedBuild.readString().trim());
                    provenMismatch = !apkStamp.equals(resStamp);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (versionFile.exists() && !provenMismatch)
                FileUtil.writeFile(versionFile.file(), versionString);
        }
        //final check if temp.zip exists then extraction is not complete...
        FileHandle check = assetsDir.child("temp.zip");
        if (check.exists()) {
            if (versionFile.exists())
                versionFile.delete();
            check.delete();
        }
        // auto restart after update
        Forge.isMobileAdventureMode = Forge.advStartup;
        Forge.exitAnimation(true);
    }

    private static void run(Runnable toRun) {
        if (toRun != null) {
            if (!GuiBase.isAndroid()) {
                Forge.getSplashScreen().getProgressBar().setDescription("Loading game resources...");
            }
            FThreads.invokeInBackgroundThread(toRun);
            return;
        }
        if (!GuiBase.isAndroid()) {
            Forge.isMobileAdventureMode = Forge.advStartup;
            Forge.exitAnimation(false);
        }
    }
}
