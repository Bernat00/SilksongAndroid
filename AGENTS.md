# Silksong Android — agent notes

Android port of *Hollow Knight: Silksong*: the APK is a **build system, not the
game**. No game content and nothing Unity-made is in the repo — the phone fetches
Unity's toolchain, takes the user's own Linux depot, converts IL to C++ and
compiles `libil2cpp.so` on-device. `COPILOT.md` is the upstream dev loop;
`CLAUDE.md` is the same material for Claude Code. This file is the operational
delta — commands, the memory failure mode, traps and paths. Every claim below
was checked against the code (2026-11-20, v2.0.4 tree), not copied from a doc.

## Commands

`make dev` = Gradle launcher → repackage → `adb install` (~3 min);
`make dev-fast` skips Gradle (only for `tools/depot-to-apk/shell/` or
`build.sh` edits). The APK is `build/SilksongAndroid-<VERSION>.apk`. `VERSION`
ends in a newline, so every consumer strips it
(`tr -d ' \t\r\n' < VERSION`, as the Makefile does) — a bare `$(cat VERSION)`
yields a broken filename. Everything else the build produces or fetches lives
under `~/.cache/silksong` (`SILKSONG_CACHE`, `BUILD_ROOT`, `PLAYER_ROOT`), by
design — don't move it into the tree. First build needs `make player` (~642 MB),
`make surgery`, `make weaver`.

Container builds (image is x86-64 only; arm64 needs `tonistiigi/binfmt`):

```sh
make docker-apk                 # or: docker compose -f tools/docker/compose.yaml run --rm apk
make docker-up && make docker-dev   # persistent container, warm Gradle
```

The container has no USB bus: it writes `build/` and the host installs it
(`make install`). It signs with its own debug keystore, kept in the
`silksong-android-home` volume at `/root/.android`, so container builds install
over each other — but not over a laptop-signed APK, which needs an uninstall
first (`adb uninstall -k` keeps the app data, and `hasFragileUserData` is what
makes Android offer to). `LAUNCHER=0` skips Gradle; `DEBUGGABLE` defaults to 1 in the
container (that is what makes `run-as` work) and 0 in a host build. There is no
`build/debug.keystore` in the checkout, so `KEYSTORE=/workspace/build/debug.keystore`
fails with "no keystore at …" — leave `KEYSTORE` unset to use the generated one.

After installing an APK whose staged `ondevice` assets changed, the game image
must be rebuilt on the phone (or press **Start porting** / **Resume build**):

```sh
adb shell am start -n com.jakobkhansen.silksong/dev.silksong.launcher.SetupActivity \
  --ez dev.silksong.launcher.extra.REBUILD true
```

Measured on the S23+: ~13 min of real work (10.5 min conversion + ~1.5 min
native), and 30–60 min when attempts are reclaimed (see below). The phone has to
stay idle. `SetupActivity` is only declared when the launcher is in the APK —
its declaration is in the manifest `build.sh` generates, not the AAR's.

A conversion tier can ride along with that rebuild, which is the loop where the
port screen is skipped because the launcher already has a build:
`--es dev.silksong.launcher.extra.TIER 6:1792` (`8:2560`, `3:896`, `1:448`, or
`auto` to clear it).

## Verification, cheapest first

| | |
| --- | --- |
| `make check` (~10 s) | compiles `tools/silksong-patches/src` against the depot exactly as the device does. Needs `pwsh`, `make player`, and a **Linux** depot (`SILKSONG_DEPOT`, `-Depot`, or `silksong-install/` beside the repo). Run it bare — piping its output deadlocks MSBuild |
| `pwsh tools/silksong-patches/test.ps1` | dualscreen gesture/layout/JVM regressions; needs player module + Android SDK + JDK, no depot |
| `dotnet run --project tools/mod-weaver/tests/ModWeaver.RegressionTests.csproj -c Release` | weaver tests; no game content |
| `:app:testReleaseUnitTest --tests dev.silksong.launcher.BuildInstallationTest` | Kotlin checkpoint tests; only via the player module's Gradle (see `COPILOT.md`) |

`ref/preview-compile.sh` (CLAUDE.md's patch check) lives in the untracked `ref/`
tree: that command exists only on a machine that has the decompiled reference,
and in the container only if `ref/` is under the bind mount. `make check` is the
in-git equivalent. The toolchain (JDK 17, Android SDK, .NET 8, `pwsh`) is not in
the repo; without it only adb-side work is possible.

## On-device memory — why a re-port "takes ages"

The S23+ (SM-S916B, 7.24 GB total) loses most conversions to Android's
low-memory killer, not to an allocation failure:

- In `launcher.log` (11-13 → 11-19: 30 starts, 15 mid-run reclaims, 9 installs)
  every attempt that began with **≥3.2 GB `MemAvailable`** finished, and every
  one at **≤2.9 GB** was killed around file 900 of 1162 — where the big
  translation units are.
- The kill is lmkd's SIGKILL, reported by `ActivityManager` as
  `REASON_LOW_MEMORY` (sometimes `REASON_EXCESSIVE_RESOURCE_USAGE`);
  `convert.log` stays 0 bytes and `err.log` holds only clang warnings. An
  `OutOfMemoryException` trace in `convert.log` would mean something else.
- There is no resume ("any previous attempt is cleared"), so a kill costs the
  entire run. The "N GB free" on a reclaim line is the memory it *gave back*,
  not what it had — the useful number is on the `starting with …` line.
- Before a re-port: reboot the phone. Closing the app is not enough.

`MonoRuntime.budget()` tiers from **total** RAM: ≥6.6 GB → 2560 MB heap,
≥5.2 → 4 cores/1024 MB, ≥3.2 → 2/640, else 1/`FLOOR_HEAP_MB` (384).
`il2cppBudget()` overrides `SM-S916*` to **6 cores/1792 MB** because the 2560 MB
tier reliably reaches ~99% and is reclaimed there. Reclaimed runs retry through
`tighter()`, which halves heap and cores. Because the tier comes from total RAM,
a phone with 2 GB actually free still starts at the large tier — the single
biggest source of wasted attempts. `android:largeHeap="true"` (written by
`build.sh`, visible as `LARGE_HEAP` in `dumpsys package`) only raises ART's Java
heap: it does nothing for native IL2CPP or lmkd, and `:builder` is a bound
service with no foreground service declared.

`tools/ondevice-il2cpp/build-il2cpp.sh` sizes compile jobs as
`(MemAvailable - 512 MB) / 400 MB`, so a cold rebuild right after a conversion
(≈1.3 GB free) runs 2 jobs on 8 cores; `BUILD_JOBS` overrides it.

The port screen lets the user override the starting tier: chips `Auto | 8·2560 |
6·1792 | 3·896 | 1·448`, stored as `il2cpp_budget` (`"auto"` or `"cores:heapMb"`)
in `launcher_settings`. `Auto` is the free-RAM tier capped by the total-RAM tier
and by the S23+ 1792 MB limit, so a smaller phone can never be handed more than
it used to get; an explicit choice is honoured, clamped to real cores, and the
chosen rule is logged. The same screen shows live `MemAvailable` once a second
while it is resumed, red under 3.4 GB. A built game whose staged-asset digest no
longer matches shows as **"The patches are newer than the game"** (Rebuild /
Play anyway) rather than as a device with no game on it.

## Traps

- **Every patch entry point must be in `tools/silksong-patches/entrypoints.json`.**
  A `[RuntimeInitializeOnLoadMethod]` alone is never called in a player build; it
  fails silently.
- **The shipping manifest is generated in `tools/depot-to-apk/build.sh`**, not in
  `src/SilksongLauncher.Launcher/app/src/main/AndroidManifest.xml`. Activities
  (`SetupActivity` exported/Main, `LauncherActivity`, `GameActivity`, …),
  permissions, `largeHeap` and `hasFragileUserData` all come from there — adding
  an activity to the AAR manifest alone changes nothing.
- Don't touch `targetSdk` 28 (`TARGET_SDK` in `build.sh`). It is what puts the
  app in `untrusted_app_27`, the only SELinux domain that lets the fetched
  clang/.NET binaries exec.
- No Gradle wrapper, and one must not be added: the gitignored wrapper pins a
  version that cannot configure this project. `dev.sh` drives the player
  module's Gradle 8.11.
- Patches ship as **source**, staged by Gradle from `tools/silksong-patches/`;
  `src/main/assets/ondevice/` is generated and gitignored. Editing the staged
  copy edits nothing.
- Only the APK belongs in `build/`. Never commit the depot, `ref/`, keystores,
  `dev.env`, tokens or account ids — the repo is public
  (`docs/SECURITY-NOTES.md`; the GOG Galaxy *public* client credential in
  `GogCloud.kt` is deliberate and documented there).
- `VERSION` is the single source of truth: `versionName` verbatim, `versionCode =
  major*10000 + minor*100 + patch`. Release = bump `VERSION` in a commit, then run
  the **Release APK** workflow manually; it refuses to republish a version, and
  `dry_run` needs no signing secret. The APK is debug-signed, so a differently
  signed build needs an uninstall first.
- Docs drift: the launcher README's layout mentions `java/…/dualscreen` and
  `assets/ondevice/`, neither of which is in the tree. The DualScreen runtime is
  C# in `tools/silksong-patches/src/dualscreen/`.
- `origin` is the Bernat00 fork, ahead of the `jakobkhansen` `upstream` remote
  (fork-only: Steam/GOG auth, settings, virtual pad). Don't reset to upstream.
  Last published release is v2.0.2; the 2.0.4 tree is built locally, unreleased.
- `make logcat` filters `SilksongLauncher` and `DualScreen`, but the shell's Java
  tag is `SilksongDualScreen` — add it (`adb logcat SilksongDualScreen:V`) when
  the second display is the suspect.

## On the device

Package `com.jakobkhansen.silksong`. Everything lives in
`/sdcard/Android/data/<pkg>/files/`:

- build root `files/build/` — `compile.log`, `err.log`, `convert.log`, `cpp/`,
  `obj/` (mode 700, so read it with `adb shell run-as <pkg>`), and the stamps
  `cpp.done` (signature of the conversion inputs), `image.stamp`,
  `content.stamp`, `mods.stamp`/`mods.converted`. Installed marker:
  `files/pkg/.built`.
- `files/launcher.log` is the app's log; `files/game-boot.log` is Unity's boot
  log (also reachable through the launcher's View logs screen).
- `make logcat` (our tags), `make game-logcat` (`Unity`), `make build-log`.

Stamps gate the expensive steps — delete the one whose step you want redone.
`make game-reset` removes only `files/pkg/.built`, `data.apk` and
`lib/arm64/libil2cpp.so`, so it re-assembles and reinstalls the image but
**keeps the conversion**. To redo the conversion itself:
`adb shell run-as <pkg> rm -f files/build/cpp.done`. `make device-wipe`
uninstalls and deletes app storage.

Dualscreen diagnostics are switched by a file, not a rebuild: write
`/sdcard/Android/data/<pkg>/files/dualscreen_v2` with `probe=1`, `testcard=1`,
`map_diag=1` or `sprite_dump=<name>`, force-stop the app, then read logcat. They
are read once per process; new knobs go in `DsConfig` (`FILE = "dualscreen_v2"`).

Player classes / classloader: `UnityDex` dexes Unity's `classes.jar` on the
device and grafts it into the app's class loader; `inject` refuses a jar without
`com.unity3d.player.UnityPlayerForActivityOrService`, and the repair probe uses a
`DexClassLoader` with a **boot parent**, so a stale graft cannot make a good
rebuilt jar look bad. If "player classes were rebuilt but still do not load"
appears anyway: force-stop for a fresh process and read `launcher.log`; "Reset
the build" is heavy and usually unnecessary.

## The on-screen pad (`tools/silksong-patches/src/VirtualPad.cs`)

- **The layout is a file, not code**: `pad-layout.json` in the app's external
  files dir, written by the launcher's **Settings → "On-screen pad"** editor
  (`PadEditorActivity`, `PadLayout.kt`) and read once per process by the pad.
  A key can be moved, scaled (0.5–2×) or rebound without another IL2CPP
  conversion, which a code change always costs. Units: `x` fraction of width,
  `y` fraction of height from the top, `w`/`h` fraction of vmin. Inventory-mode
  rects are deliberately **not** in the file — they are placed to match the
  game's own inventory buttons.
- The stick zone is a rectangle (`zoneX0/Y0/X1/Y1`) plus a ring (`cx`,`cy`,`r`)
  and a `mode`: `floating` (ring jumps to the touch) or `pinned` (ring stays,
  knob leans — re-touching resumes from the same centre). Keys win touches over
  the zone, so a key deliberately placed inside it still works; the editor is
  what keeps that from being an accident, and it refuses overlapping keys.
- `OnScreenDevice` takes its `InputControlType` per slot from the file, so
  rebinding is data too. Two slots may name the same target on purpose (Cast and
  Heal are both `Action2`; the device ORs them). Only the ~12 controls the game
  binds are offered — anything else would be a button that does nothing.
- Defaults now keep Map/Inventory above the stick zone (they used to sit at
  0.40/0.30 of the height, inside it) and give Pause a real margin from the
  corner.

- `OnScreenDevice` is a real InControl `InputDevice` with every control the game
  binds; `InputManager` owns `Commit` and `ActiveDevice` arbitrates between the
  pad, a physical controller and the keyboard. No `PlayerActionSet.Device` pin,
  `IncludeDevices` or binding hammer.
- Controls: `Action1` Jump, `Action2` Cast **and** Heal (the game calls it
  *Bind*), `Action3` Attack, `RightTrigger` Dash (in-game *Sprint*),
  `LeftBumper` Map, `Select` Inventory, `RightBumper` Skill / Tool — one control
  that both casts the silk skill and throws an equipped tool, `Start` Pause. No
  `Action4` — Dream Nail/Needolin is not on the pad.
- Cast and Heal are one game action (tap casts the Silk Skill, hold heals), and
  both buttons OR into `Action2`.
- Modes: Hidden / Play / Inventory. Inventory follows
  `PlayerData.instance.isInventoryOpen`; pause, menus and cutscenes hide the pad.
  One `ButtonRect` serves both drawing and hit-testing; the named layout
  constants (`CLUSTER_CX`, `DIAMOND_R`, `CAST_THROW_*`, `MAP_INV_*`,
  `PILL_PAUSE_*`) are the source of truth. Diagnostics are gated by `Verbose`
  (`Debug.isDebugBuild` or the `touch_debug` setting).

## Conventions

- Plan before code; verify every external API against `ref/` or official docs —
  no guessed APIs — and record what was verified in `docs/API-NOTES.md`.
  `docs/SESSION-CHANGES.md` is the change log.
- One writer per UI state; one settings path (`SettingsStore.changed()` exports
  once, the game reads once). Don't add fake "live" re-reads.
- Windows/Git Bash: `MSYS_NO_PATHCONV=1` for device paths; write scripts to files
  instead of inline heredocs; keep LF where the build parses the file
  (`.gitattributes` pins `*.sh`).
- `tools/depot-to-apk/shell/PlayerActivity.java` owns the game window:
  keep-screen-on from the `keep_screen_on` launch extra (default true), and Back
  is intercepted in `dispatchKeyEvent` then injected as Escape.
