# CLAUDE.md

Personal fork of [jakobkhansen/SilksongAndroid](https://github.com/jakobkhansen/SilksongAndroid):
an Android port of Hollow Knight: Silksong that builds the game image **on the
device** from the user's own Linux depot. The repo is public:
`github.com/Bernat00/SilksongAndroid`.

## Repo state

- `origin` = the public fork; `upstream` = jakobkhansen/SilksongAndroid.
- `master` carries the personal history (squashed base + follow-ups) plus an
  ours-merge connecting it to upstream.
- Latest published release: **v2.0.2**. The 2.0.4 source is in the tree; the
  APK is built locally and installed for testing, not yet released.
- `VERSION` is the single source of truth for the APK name/version.
- Never commit `ref/` (decompiled game/depot files), `build/`, keystores,
  `dev.env`, tokens or account ids. See `docs/SECURITY-NOTES.md`. The GOG
  Galaxy *public* client credential in `GogCloud.kt` is documented there.

## Build / test

APK (needs Docker Desktop and the Android toolchain image):

```bash
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker compose -f tools/docker/compose.yaml run --rm \
  -e KEYSTORE=/workspace/build/debug.keystore -e KS_PASS=android -e KEY_PASS=android \
  -e KEY_ALIAS=androiddebugkey -e DEBUGGABLE=0 apk
adb install -r build/SilksongAndroid-$(tr -d ' \t\r\n' < VERSION).apk
```

Patch-only compile check (same compiler the device uses):

```bash
MSYS_NO_PATHCONV=1 docker compose -f tools/docker/compose.yaml run --rm apk bash /workspace/ref/preview-compile.sh
```

After installing an APK whose `ondevice` assets changed, the game image must be
rebuilt on the device (or press **Start porting** / **Resume build** in the app):

```bash
adb shell am start -n com.jakobkhansen.silksong/dev.silksong.launcher.SetupActivity \
  --ez dev.silksong.launcher.extra.REBUILD true
```

The rebuild takes 15–30 min on the S23+ and the phone must stay idle. Logs:

- `adb logcat -s SilksongLauncher`
- `/sdcard/Android/data/com.jakobkhansen.silksong/files/launcher.log` (external)
- `files/game-boot.log` via the launcher's View logs screen.

USB debugging may be `unauthorized` after a reboot; accept the phone prompt.

## Where things live

- `src/SilksongLauncher.Launcher/` — Kotlin launcher, settings, GOG/Steam cloud,
  on-device build pipeline (`Il2cppConverter`, `MonoRuntime`, `UnityDex`,
  `PackageCompiler`, `PlayerImage`).
- `tools/depot-to-apk/shell/` — `PlayerActivity`/`GameActivity`; the game window
  is owned here. Keep-screen-on is set from a launch intent extra; back is
  intercepted in `dispatchKeyEvent` and injected as Escape.
- `tools/silksong-patches/src/` — C# patches, compiled on the device. Registration
  is `tools/silksong-patches/entrypoints.json` (the `[RuntimeInitializeOnLoadMethod]`
  attributes are documentation in a player build).
- `ref/` — decompiled game/InControl reference (untracked). Use it to verify
  APIs; do not guess.
- `docs/SESSION-CHANGES.md` — current change log.
- `docs/API-NOTES.md` — verified external surfaces and pad/game mappings.

## On-screen pad (`VirtualPad.cs`)

- `OnScreenDevice` is a real InControl `InputDevice` with every control the game
  binds. `InputManager` owns `Commit`; the device only feeds state.
- Controls: `Action1` Jump, `Action2` Cast **and** Heal, `Action3` Attack,
  `RightTrigger` Dash, `LeftBumper` Map, `Select` Inventory, `RightBumper`
  Throw (QuickCast), `Start` Pause. No `Action4`: the game's Dream Nail/Needolin
  is not on the pad.
- Cast and Heal are the same game action: tap casts the Silk Skill, hold heals.
  Both buttons OR into `Action2`.
- No `PlayerActionSet.Device` pin, `IncludeDevices`, or binding hammer. The game's
  own `ControllerAttached/Activated -> MapControllerButtons` binds, and
  `InputManager.ActiveDevice` arbitrates between the pad, a physical controller
  and the keyboard.
- Modes: Hidden / Play / Inventory. Inventory opens on
  `PlayerData.instance.isInventoryOpen`; it shows Exit (`Select`) top-right and
  pane arrows (`LeftBumper`/`RightBumper`) bottom corners. Pause, menus and
  cutscenes hide the pad.
- Play layout (fractions of screen):
  - diamond: Dash top, Attack left, Jump bottom, Heal right; centre 0.845W /
    0.60H, face offset 0.155 vmin, face radius 0.080 vmin;
  - Throw/Cast pills above the diamond: 0.110W × 0.090 vmin, centres 0.784W and
    0.906W, 0.012W gap;
  - Map/Inventory text pills stacked left under the health HUD: 0.130W ×
    0.075 vmin, centre 0.100W, y 0.30/0.40;
  - Pause pill top-right: 0.055W × 0.075 vmin at 0.965W / 0.05.
- One `ButtonRect` is shared by drawing and hit-testing. Diagnostics are gated by
  `Verbose` (`Debug.isDebugBuild` or the `touch_debug` setting).

## On-device build memory

- `MonoRuntime.budget` chooses from total RAM: ≥6.6 GB → 2560 MB heap; ≥5.2 →
  4 cores/1024; ≥3.2 → 2 cores/640.
- **SM-S916\* (Galaxy S23+) overrides the starting budget to 6 cores/1792 MB**:
  the 2560 MB tier consistently reaches ~99% and is reclaimed, so starting at
  the proven 1792 MB retry tier avoids a wasted attempt.
- Reclaimed runs retry lower (`tighter()`); the floor is 384 MB. 3.6 GB devices
  are below the practical minimum.
- Do not keep partial output between attempts: Unity's il2cpp has no resume and
  a half-written C++ tree would be compiled into the game.

## Player classes / classloader

- `UnityDex` dexes the Unity player `classes.jar` on the device and grafts it
  into the app class loader. `inject` now refuses a jar that does not contain
  `com.unity3d.player.UnityPlayerForActivityOrService`.
- The repair probe uses a `DexClassLoader` with a **boot parent**, so a stale
  graft in the launcher process cannot make a good rebuilt jar look bad.
- If the “player classes were rebuilt but still do not load” dialog appears
  anyway: force-stop the app (fresh process) and read `launcher.log`. The
  “Reset the build” path is heavy and usually unnecessary.

## Conventions

- Plan before code; verify APIs against `ref/` or official docs. Record verified
  surfaces in `docs/API-NOTES.md`.
- One writer per UI state; one settings path (`SettingsStore.changed()` exports
  once, the game reads once). Do not add fake “live” re-reads.
- Windows/Git Bash: `MSYS_NO_PATHCONV=1` for device paths; write scripts to files
  instead of inline heredocs; keep files LF where the build expects it.
- The repo is public: commits and releases are visible. Do not add personal
  tokens, account ids or keystores.
