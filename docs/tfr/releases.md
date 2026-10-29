# TFR release ledger

One block per release, newest first: tag, commit, assets in `C:\TFR\release\v<ver>`, Android identity
(versionCode, signer), release-notes file, what the user changed. Add the new release's block at the top when it is
published. Releases before v1.19 are in `docs/tfr/history/claude-md-rounds.md`. The procedure is in
`docs/tfr/build-and-deploy.md` and `ANDROID_RELEASE.md`.

## STATE 2026-10-08 (00:15 UTC): **v1.19 "Titans & Tides" RELEASED** - tag `tfr-v1.19` @ `9eba8fa822a`, published
00:15:04 UTC, Latest. Rounds 442-472 on top of v1.18, engine = the 10.07 daily (upstream `e7fca289313`). Assets in
`C:\TFR\release\v1.19`: `The-Forsaken-Realms-v1.19.zip` 329.9 MB, `forsaken-realms-1.19-signed-aligned.apk` 13.6 MB
(com.thesaguy.forsakenrealms, versionCode 11900, signer EE:60:39:25), `assets.zip` 281.9 MB (build.txt pair 2026-10-08
00:11:15; Android from `C:\TFR-build` via R: in 2:42). Notes = `RELEASE_NOTES_v1.19.md` (the name was my pick). Quick
agent test before release: a new world, a duel (loss path), Settings, an old save loaded - log clean. Live + agent = the
release. NOTHING is unreleased. Next merge from upstream after `e7fca289313`. Next version: 1.20 / 12000. Open for the
user: the lost-treasure design's four decisions (`docs/design/2026-10-07-lost-treasure.md`).

## STATE 2026-10-09 (16:18 UTC): **v1.20 "Power & Plunder" RELEASED** - tag `tfr-v1.20` @ `4a5633ea005`, published
16:18:22 UTC, Latest. Rounds 473-518 on top of v1.19. The engine is still the 10.07 daily (upstream `e7fca289313`): the
user chose to ship on it, so the 34 upstream commits since then (the 10.08 and 10.09 dailies, incl. an Adventure duel
soft-lock fix and the boss-dialog/coin-flip clash fix in DuelScene/MatchController) are the first work of v1.21.
Assets in `C:\TFR\release\v1.20`:
- `The-Forsaken-Realms-v1.20.zip` 351.0 MB;
- `forsaken-realms-1.20-signed-aligned.apk` 13.6 MB (com.thesaguy.forsakenrealms, versionCode 12000, signer EE:60:39:25);
- `assets.zip` 302.8 MB (build.txt pair 2026-10-09 16:14:56; Android from `C:\TFR-build` via R: in under 2 min).

Notes = `RELEASE_NOTES_v1.20.md`; the name was my pick, the user cut the Fixed and Art sections and five lines, and added
the "bigger release / share your logs" note and "You must start a new game for all the new features!". Live + agent =
the release. NOTHING is unreleased. Next version: 1.21 / 12100. Next merge from upstream after `e7fca289313`.

## STATE 2026-10-15 (16:26 UTC): **v1.21 "Faces & Fates" RELEASED** - tag `tfr-v1.21` @ `5d2157e96cc`, published
16:26:05 UTC, Latest. Rounds 519-620 on top of v1.20 (519-524 had shipped only in the Android hotfix 1.20.1). Engine =
the 10.14 daily (upstream `34a8662b796`, round 612). Assets in `C:\TFR\release\v1.21`:
- `The-Forsaken-Realms-v1.21.zip` 434.3 MB;
- `forsaken-realms-1.21-signed-aligned.apk` 13.1 MB (com.thesaguy.forsakenrealms, versionCode 12100, signer EE:60:39:25);
- `assets.zip` 388.0 MB (build.txt pair 2026-10-15 16:21:59; Android from `C:\TFR-build` via R:).

Notes = `RELEASE_NOTES_v1.21.md` (the user trimmed "New looks" to two lines and cut the raid and flee lines, added
"New game recommended"); title and Discord blurb mine. Guide/README brought up to 1.21 in round 618. Live + agent = the
release. NOTHING is unreleased. Next version: 1.22 / 12200. Next merge from upstream after `34a8662b796`.

## STATE 2026-10-17 (15:01 UTC): **v1.22 "Legends & Lairs" RELEASED** - tag `tfr-v1.22` @ `bd70a26ecb1`, published
15:01:03 UTC, Latest. Rounds 621-688 on top of v1.21 (682 reverted). Engine = the 10.17 daily (upstream `ea5fe47050d`,
round 687), packaged on the 10.16 BASE_INSTALL with round 688's base-gap overlay (Forge had not published the 10.17
daily yet; `--allow-base-mismatch`). The user play-tested targeting (#12259) before publishing. Assets in
`C:\TFR\release\v1.22`:
- `The-Forsaken-Realms-v1.22.zip` 474.8 MB;
- `forsaken-realms-1.22-signed-aligned.apk` 13.8 MB (com.thesaguy.forsakenrealms, versionCode 12200, signer EE:60:39:25);
- `assets.zip` 426.0 MB (Android from `C:\TFR-build` via R:).

Notes = `RELEASE_NOTES_v1.22.md` (the user's own wording; numbers, lists and the Android section filled in). Promo
artifact v11 updated (stats 2,150+ / 360+ / 34,000+ / 730+, three new system cards). Live + agent = the release.
NOTHING is unreleased. Next version: 1.23 / 12300. Next merge from upstream after `ea5fe47050d`; reinstall BASE_INSTALL
once Forge publishes a daily at or past it (then the base gap is empty again).

## STATE 2026-10-21 (20:00 UTC): **v1.23 "Fixes & Engine update" RELEASED** - tag `tfr-v1.23` @ `69797542f59`, published
20:00:53 UTC, Latest. Rounds 689-701 on top of v1.22. Engine = the 10.21 daily (upstream `bc67f70ffbc`, round 701),
packaged on the matching 10.21 BASE_INSTALL (no base gap). Assets in `C:\TFR\release\v1.23`:
- `The-Forsaken-Realms-v1.23.zip` 474.9 MB;
- `forsaken-realms-1.23-signed-aligned.apk` 13.8 MB (com.thesaguy.forsakenrealms, versionCode 12300, signer EE:60:39:25);
- `assets.zip` 426.0 MB (build.txt 2026-10-21 19:56:04; Android from `C:\TFR-build` via R:; carries `log_share.txt`).

Notes = `RELEASE_NOTES_v1.23.md`, kept high level at the user's request ("Just say a few bug fixes and engine update");
title mine. GUIDE What's New 1.23 is one line above 1.22's list. The webhook token was checked absent from git before
the push. Live + agent = the release. NOTHING is unreleased. Next version: 1.24 / 12400. Next merge from upstream after
`bc67f70ffbc`.

## STATE 2026-10-27 (16:13 UTC): **v1.24 "Towns & Trophies" RELEASED** - tag `tfr-v1.24` @ `6412d1a6cb6`, published
16:13:51 UTC, Latest (round 787). Rounds 702-783, 785 and 786 on top of v1.23 (761 and 784 not used). Engine = the 10.26
daily (upstream `0dc8c66996e`, round 769) on the matching 10.26 BASE_INSTALL; the user skipped a further engine merge
for 1.24. The user passed the live package of 2026-10-27 08:58. Assets in `C:\TFR\release\v1.24`:
- `The-Forsaken-Realms-v1.24.zip` 483.6 MB;
- `forsaken-realms-1.24-signed-aligned.apk` 15.6 MB (com.thesaguy.forsakenrealms, versionCode 12400, signer EE:60:39:25);
- `assets.zip` 433.9 MB (build.txt pair 2026-10-27 16:08:41, the APK's `assets/build.txt` - round 704's
  `StartScene.programBuildTime()` reads it, so the Android log prints a real build time; Android from `C:\TFR-build`
  via R:; carries `log_share.txt`).

Notes = `RELEASE_NOTES_v1.24.md`, from `notes/release-notes-1.24-draft.md` with the user's trims (no death dialog,
font, start-menu background or splash art, boss labels or Ko-fi ask) and rounds 777-785 added; title mine. GUIDE
What's New 1.24 is a short linked list above a one-line 1.23 note and 1.22's list. `modVersion` 1.24 / 10.27, so round
782's update notice stays quiet on this build (GitHub's latest is `tfr-v1.24`). The webhook token was checked absent
from git (tree and the unpushed history) before the push. The same zip and APK go to itch.io (the user). Live = the
release. NOTHING is unreleased. Next version: 1.25 / 12500. Next merge from upstream after `0dc8c66996e`.

## STATE 2026-10-29 (19:12 UTC): **v1.25 "Gauntlets & Wrath" RELEASED** - tag `tfr-v1.25` @ `8eeb5b7f5b8`, published
19:12:40 UTC, Latest. Rounds 788-864 on top of v1.24, all packaged into the user's game (2026-10-29 12:00, HEAD
`460301d3662`) and tested; the release commit takes no round number (next free stays 865). Engine = the 10.28 daily
(upstream `875333b7b78`, round 856) on the matching 10.28 BASE_INSTALL. Revert-watch after the merge clean (nothing
under `forge-gui-android/` changed since v1.24). Assets in `C:\CLAUDE\PROJECTS\TFR1\release\v1.25`:
- `The-Forsaken-Realms-v1.25.zip` 484.0 MB;
- `forsaken-realms-1.25-signed-aligned.apk` 15.7 MB (com.thesaguy.forsakenrealms, versionCode 12500, signer EE:60:39:25);
- `assets.zip` 434.1 MB (build.txt pair 2026-10-29 19:07:41; Android from `C:\TFR-build` via R:, fast-forwarded to the
  release commit; carries `log_share.txt`). Round 792's prompts ("430MB" / "16MB") still fit, so unchanged.

Notes = `RELEASE_NOTES_v1.25.md`, the user-approved content plus v1.24's Android section; the name is the user's. GUIDE
What's New 1.25 is a short linked list above 1.24's. The Discord FAQ says v1.25 (`c861d34054b`; paste list
`notes/faq-1.25-edits.md`). The webhook token was checked absent from git (tree and the unpushed history) before the
push. Live = the release (package 2026-10-29 12:07, from the release commit), agent synced 12:10. The same zip and APK
go to itch.io (the Art thread). NOTHING is unreleased. Next version: 1.26 / 12600. Next merge from upstream after
`875333b7b78`.
