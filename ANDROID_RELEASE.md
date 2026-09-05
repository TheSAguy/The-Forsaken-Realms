# Android Release Procedure — The Forsaken Realms

Written 2026-08-27 (first Android release, v1.03). This is the authoritative checklist for
every Android release. The design rationale behind each rule lives in MOD_CHANGELOG.md round 61
and CORE_ENGINE_CHANGES.md; this file is the "do this, in this order" version. Follow it
exactly — most steps exist because skipping them produced (or would have produced) a concrete,
diagnosed failure.

## How the Android app works (30-second model)

The APK is small (~15MB): engine code only. On first launch the app downloads `assets.zip`
(~180MB) from **this repo's GitHub release whose tag matches the APK's own versionName**
(`tfr-v` + versionName), extracts it to its asset dir, and restarts. After that it plays fully
offline (card art still downloads lazily from Card-Forge's image CDNs, same as desktop).
The app also checks the repo's releases.atom feed and offers self-update when a newer
`tfr-v*` release exists.

Consequences:
- **The APK and assets.zip are a matched pair.** `AssetsDownloader` compares the build
  timestamp baked into both (`build.txt`, `${maven.build.timestamp}`). They match only when
  both artifacts come from the **same `mvn` invocation**. Never rebuild one and re-upload it
  alone; if anything changes, rebuild both and re-upload both.
- **The release tag must be `tfr-v<versionName>`** or first-run asset download 404s.

## One-time machine prerequisites (already true on the F:-drive dev machine)

1. Android SDK at `%LOCALAPPDATA%\Android\Sdk` with `build-tools;35.0.0` and
   `platforms;android-35` (installed via Android Studio). `forge-gui-android/local.properties`
   points `sdk.dir` at it.
2. **Card-Forge's PATCHED android-maven-plugin 4.6.2** manually placed in the local Maven repo
   (the stock 4.6.2 fails with "Unknown packaging: apk"):
   ```
   mkdir -p ~/.m2/repository/com/simpligility/maven/plugins/android-maven-plugin/4.6.2
   cd ~/.m2/repository/com/simpligility/maven/plugins/android-maven-plugin/4.6.2
   curl -L -O https://github.com/Card-Forge/android-maven-plugin/releases/download/4.6.2/android-maven-plugin-4.6.2.jar
   curl -L -O https://github.com/Card-Forge/android-maven-plugin/releases/download/4.6.2/android-maven-plugin-4.6.2.pom
   ```
   (Delete any `*.lastUpdated` files in that directory — they are Maven's negative cache and
   make it pretend the plugin still doesn't exist.)
3. **The signing keystore** — see next section.

## The signing keystore (CRITICAL — read before every release)

- Location used by the build: `forge-gui-android/forge.keystore` (gitignored — NEVER commit;
  the repo is public and the passwords are in the pom).
- Backup: `F:\FORGE\TFR-Standalone\forsaken-realms-android.keystore`. Keep at least one copy
  off the F: drive too.
- Parameters (must match `forge-gui-android/pom.xml`'s SignV2 exec): alias `Forge`,
  storepass/keypass `forge72`. Generated 2026-08-27, RSA 2048, valid ~27 years,
  CN=The Forsaken Realms. SHA-256 fingerprint starts `EE:60:39:25`.
- **Every future APK must be signed with THIS keystore.** Android refuses updates signed with
  a different key — players would have to uninstall (losing saves) and reinstall. If the
  keystore is ever lost, that is what every existing player must do. Guard it accordingly.

## Per-release steps

0. Desktop release first: the normal desktop flow (build, `build_standalone.py --zip`
   - add `--out C:\Users\User\TFR-Release` when the live folder is being played; round 119 - publish
   the `tfr-vX.YZ` GitHub release with the desktop zip). The Android artifacts attach to the
   SAME release.
1. Bump **`<tfr.version>`** in `forge-gui-android/pom.xml` to match the new `modVersion`
   (config.json). These two must always be equal; `tfr.version` feeds versionName, which feeds
   the assets URL.
2. Bump **`<manifestVersionCode>`** in the same pom (android-release-build profile,
   update-manifest execution). Scheme: two digits per segment — 1.03 → 10300, 1.04 → 10400,
   2.00 → 20000. It must strictly increase every release or devices refuse the update.
   (The profile sets `android.manifest.versionCodeUpdateFromVersion=false` so this explicit
   value is what ships — don't remove that override.)
3. Build. **On Windows this MUST run with shortened paths** — the plugin launches d8 through
   `cmd.exe /C "<whole command>"`, and the dex classpath is ~10,000 characters against
   cmd.exe's hard 8,191 limit. The failure is maddening: "ANDROID-040-001 ... Result = 1" with
   no error text, while the identical command run directly (no cmd.exe) succeeds. Upstream
   never sees this because their CI is Linux. Fix = shrink every path in the command:
   ```
   cmd //c "mklink /J C:\m2 C:\Users\User\.m2\repository"     # junction, no admin needed (once per machine)
   cmd //c "subst R: F:\FORGE\C--Users-vicwaver-MTG-Forge"     # short drive alias (after every reboot)
   export PATH="/c/Users/User/.claude/skills/apache-maven-3.9.16/bin:$PATH"
   cd /r/
   mvn -pl forge-gui-android -am clean install -P android-release-build -DskipTests \
       "-Dmaven.repo.local=C:/m2" \
       "-Dandroid.sdk.path=C:/Users/User/AppData/Local/Android/Sdk" \
       -Dandroid.buildToolsVersion=35.0.0
   ```
   `-am` is NOT optional: building `-pl forge-gui-android` alone fails at compile with
   "cannot access com.badlogic.gdx.Application" — this project versions modules with
   `${revision}` placeholders, so the poms Maven installs to the local repo don't resolve
   transitive dependencies; only in-reactor resolution works. (Tried and failed 2026-08-27.)
   Additional landmines: a killed or partial earlier build leaves a poisoned ~/.m2 install and
   a dirty target/ producing misleading failures ("cannot access com.badlogic.gdx.Application"
   at compile) — hence the mandatory `clean`. And if a future upstream merge adds enough new
   dependencies, the command can outgrow the limit AGAIN even via R:\ — re-measure with the
   failing command's length if Result=1-with-no-output ever returns.
   Outputs in `forge-gui-android/target/`: the signed+aligned APK (uber-apk-signer names it
   from `finalName`, look for `*aligned*.apk` with "signed" in the name) and `assets.zip`.
4. Verify BEFORE uploading (no device needed):
   ```
   # identity + version: expect package=com.thesaguy.forsakenrealms,
   # versionCode matching step 2, versionName matching tfr.version,
   # label "The Forsaken Realms"
   "$LOCALAPPDATA/Android/Sdk/build-tools/35.0.0/aapt" dump badging <apk> | head -5
   # signature: expect our keystore's CN / EE:60:39:25 fingerprint
   "$LOCALAPPDATA/Android/Sdk/build-tools/35.0.0/apksigner" verify --print-certs <apk>
   # assets.zip layout: MUST have top-level res/, res/adventure/ containing EXACTLY
   # common + "The Forsaken Realms" (no Shandalar etc.), res/build.txt present,
   # res/cardsfolder/cardsfolder.zip present
   python -c "import zipfile;[print(n) for n in zipfile.ZipFile(r'<assets.zip>').namelist()[:20]]"
   ```
5. Upload BOTH to the `tfr-vX.YZ` release, with the APK renamed to exactly
   **`forsaken-realms-<version>-signed-aligned.apk`** (AssetsDownloader constructs this exact
   filename for self-update) and the zip named exactly **`assets.zip`**:
   ```
   gh release upload tfr-vX.YZ -R TheSAguy/The-Forsaken-Realms \
       "forsaken-realms-X.YZ-signed-aligned.apk" "assets.zip"
   ```
   **ALWAYS pass `-R TheSAguy/The-Forsaken-Realms`** — bare `gh` in this repo resolves to
   upstream Card-Forge/forge via the `upstream` remote.
6. Release notes: keep the Android section with sideload instructions (copy from v1.03's).

## Landmines (each of these was real — do not rediscover them)

- **Never build the APK and assets.zip separately** (see matched-pair rule above). The
  in-app guard tolerates it in one direction only; the clean path is one `mvn` run.
- **`android:authorities` in AndroidManifest.xml are LITERAL strings** — the leading-dot
  shorthand only works for `android:name`. Any provider added in future must use a
  `com.thesaguy.forsakenrealms.*` authority or the APK stops installing alongside real Forge
  (`INSTALL_FAILED_CONFLICTING_PROVIDER`). The Sentry providers were deleted for this reason;
  if an upstream merge resurrects them, delete them again (Sentry stays disabled via
  `io.sentry.auto-init=false`).
- **Upstream merges will try to revert the Android identity.** After every engine merge, grep
  the merged tree for: `forge.app` reappearing in AndroidManifest.xml (package), `"Forge"` as
  app_name in strings.xml, `com.mydomain.publicfileprovider` (manifest AND Main.java), Sentry
  provider/dsn entries in the manifest, `Card-Forge/forge` in ForgeConstants.GITHUB_FORGE_URL,
  `"forge"`/`"forge-"` tag markers in GitLogs.getLatestReleaseTag and AssetsDownloader, and
  `/Forge/` in Main.java's ASSETS_DIR. CORE_ENGINE_CHANGES.md has the full edit list.
- **The app's package identity trio must stay in sync** (all three say
  `com.thesaguy.forsakenrealms`): manifest `package`, `Main.RES_PKG_FALLBACK`,
  `Forge.java`'s `setUsingAppDirectory(assetDir0.contains(...))` sniff.
- **The Android data root is `/ForsakenRealms/`** (Main.java ASSETS_DIR), not `/Forge/` —
  renaming it again orphans every existing player's downloaded assets and saves.
- **cardPicsDir**: the desktop "share stock Forge's card art" default is desktop-gated in
  ForgeProfileProperties — on Android it produced an uncreatable path. Keep the
  `isRunningOnDesktop()` gate if that code is ever touched.
- **Desktop AutoUpdater is force-disabled** (AutoUpdater.attemptToUpdate early-return) — the
  fork pins its engine, and the updater would 404 against our repo (or worse, before the URL
  retarget, install stock Forge over the game). Keep it dead.
- The android-test-build / android-dev-build profiles were NOT retargeted (they still stamp
  engine-SNAPSHOT versionNames, which route AssetsDownloader to a nonexistent snapshot
  channel). Only ship from **android-release-build**.
- JDK: the build currently runs on JDK 22 with the patched plugin. Upstream CI pins JDK 17
  for sdkmanager; if a future JDK upgrade breaks the plugin ("Unknown packaging: apk" or
  dex errors), install Temurin 17 and set JAVA_HOME for this build only.

## Player-facing install instructions (canonical copy)

> **Android (sideload):**
> 1. On your Android device (Android 8.0+), download
>    `forsaken-realms-<version>-signed-aligned.apk` from the GitHub release.
> 2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
>    prompted (the game is signed by us, not the Play Store).
> 3. Grant the storage permission the app asks for (it stores the game data it downloads).
> 4. On first launch, tap Download when offered the resource files (~180MB — use Wi-Fi).
>    The app restarts itself when done. After that it plays offline.
> 5. Problems? Report on Discord with your device model + Android version.

## AFTER the Android build: reset the desktop build state (added 2026-09-02)

The release build (`-pl forge-gui-android -am clean install -Dmaven.repo.local=C:/m2`) succeeds and
produces correct artifacts, but it **leaves the repo unable to compile normally**. It cleans the
shared modules and rebuilds them against `C:/m2` instead of the default local repository, so the
dependency modules' `target/classes` are left partial - `forge-game` was measured at 83 `.class`
files afterwards - and the next ordinary desktop compile fails with `cannot find symbol: class Card
in package forge.game.card` in files nobody touched (`CardZoom.java`, `CardRenderer.java`).

This is a near-perfect imitation of concurrent-Maven corruption. It is not that. Before any further
desktop work:

```
mvn -pl forge-gui-mobile -am clean compile -DskipTests
```

The `clean` is the part that matters. Do this as the final step of every Android release.
