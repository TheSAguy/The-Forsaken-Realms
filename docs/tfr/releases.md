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
