# Discord #faq

The game's Discord FAQ as ready-to-paste messages (each under Discord's 2,000 characters), and the copy-helper page
published as the claude.ai artifact "Forsaken Realms FAQ" (https://claude.ai/artifact/XRwbV8Vit3XmHkjQ3aw62y).

- `messages.py` - the messages: `(topic, short label, text)` in posting order. Run it to see each length.
- `build_page.py <out.html>` - builds the page from them (copy buttons, length meters, copied marks).

**Standing rule: Support is always the last message.** When a release adds topics, they go before Support in
`messages.py`; in Discord the user posts them and then reposts Support at the end. Edits to existing messages stay in
place.

v1.22 (2026-10-17): 21 messages, rebuilt from GUIDE.md - Ascendance, gear sets, treasure maps and castle keys, enemy
ranks and adapting enemies, legends and the level places, raids and war mages, the Capitol and Temple, the History
replay, plus the v1.15 answers brought up to date. Keep it in step with GUIDE.md when a release changes a rule.

2026-10-18 (round 696): message 21's saves-and-logs answer leads with Export Logs (Settings from v1.23, Classic >
Settings > Files in v1.22) and gives the Android log folders - 1,991 characters, near the limit. Artifact version 5.

2026-10-26 (round 757): 22 messages. Message 22 is the support post - Ko-fi (https://ko-fi.com/thesaguy) plus the
ways to help without tipping - 857 characters, short label "Support". Message 21 is unchanged (its v1.22 Classic
workaround stays until the user says otherwise). Artifact version 6.

2026-10-26 (round 762): the page header names v1.23, the released version the FAQ describes. It reads `FAQ_VERSION`
at the top of `messages.py`, so the next release changes one line. The "New for v1.22" note is gone: v1.23 added no new
FAQ topics. Message 21 is unchanged (its "v1.22 runs on Forge 2.0.16" line and the Classic workaround wait for the
user), and so is message 1's "(as of v1.22)" header. Artifact version 7.

2026-10-26 (round 776): message 1's header reads `FAQ_VERSION` too ("(as of v1.23)"). Message 21 is still unchanged.
Artifact version 8.

2026-10-27 (round 786): v1.24. 24 messages. Edited in place: 1 (the header's version), 2 (Arena Power), 5 (Witch-Maw
Slippers), 6 (round 780's six new enemies), 7 (Notoriety as a score, Disable Notoriety), 9 (no week-1 gold minimum; reloads, mid-duel quits, the
difficulty hints), 11 (the Arena's Archmage brackets), 12 (research v2), 15 (capital hearts once per game) and 21 (the
v1.22 Classic workaround and Forge version line gone; the PC update notice). New, posted after Support: 23 (town
specialties and realm milestones) and 24 (achievements, Alt enemy plates, the loop breaker, duel settings).
`messages.py`'s `EDITED` and `ADDED` sets tag them on the page ("Edit" / "New" and a header note); reset both at the
next release. The copied marks are kept per `FAQ_VERSION`. Artifact version 10. Paste list:
`C:\CLAUDE\PROJECTS\TFR1\notes\faq-1.24-edits.md`.

2026-10-27 (round 788): the order now matches the channel. When posting v1.24 the user moved Support to the end, so
22 is Specialties & milestones, 23 Achievements & duels and 24 Support. No text changed. `EDITED` and `ADDED` are
reset (everything is posted). Artifact version 11.

2026-10-27 (round 790): message 3 (Perks & limits) says **Far Sight** (up to +2 tiles of sight); it was a bare name.
`EDITED = {3}` tags it on the page. Edit it in Discord when 1.25 ships, since v1.24 still has the old +15%. Artifact
version 12.

2026-10-28 (round 837): message 6 (Enemy ranks) says the early-weeks floor never raises a weaker monster (a 9-life
Geist stays at 9) and gives the difficulty multipliers; a v1.24 player read "never under 20" as a minimum for every
monster. `EDITED = {3, 6}`. True of v1.24 too, so it can be edited in Discord now. The artifact page is not republished.
