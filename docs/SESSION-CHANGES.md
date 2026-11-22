# Session changes — 2026-11-14 → 2026-11-19

Current source: **2.0.4** (APK built and installed for testing; not yet
released). Last published release: **v2.0.2**.

## On-screen pad

The previous pad never ran at play time: arrays sized 6 against 8 controls threw
`IndexOutOfRangeException`, and `OnScreenDevice` added no InControl controls, so
every state call was a no-op on `InputControl.Null`.

- `OnScreenDevice` is a real `InputDevice` with the controls the game binds:
  `Action1`, `Action2`, `Action3`, `RightTrigger`, `LeftBumper`, `Select`,
  `RightBumper`, `Start`, the left stick directions and `RightStickButton`.
  The manager owns `Commit`; the device only feeds state.
- Removed the synthesized Android `MotionEvent`/`KeyEvent` injector. Android
  uses `NewUnityInputDeviceManager` (Unity Input System); the legacy
  `UnityInputDeviceManager` path is not wired, and decor-view dispatch bypasses
  the shell.
- Removed the `PlayerActionSet.Device` pin, `IncludeDevices`, and the 0.5 s
  `AddBinding` hammer. Attaching a known device lets the game's own
  `ControllerAttached/Activated -> MapControllerButtons` bind, and
  `InputManager.ActiveDevice` arbitrates pad vs physical controller.
- `RequestActivation()` at attach and on input; idle frames publish zeros so a
  physical controller takes over on its next input.
- One `ButtonRect` is shared by drawing and hit-testing. Arrays/loops are sized
  from `OnScreenDevice.ButtonCount`.
- Modes: Hidden / Play / Inventory. Inventory shows Exit (`Select`, top-right)
  and pane arrows (`LeftBumper`/`RightBumper`, bottom corners); item selection
  and tab taps stay on `InventoryTouchInput`. Pause, menus and cutscenes hide
  the pad.

### Current play layout

- Diamond: **Dash top**, **Attack left**, **Jump bottom**, **Heal right**.
  Centre 0.845W/0.60H, face offset 0.155 `vmin`, face radius 0.080 `vmin`.
- **Throw and Cast** pills above the diamond: 0.110W × 0.090 `vmin`, centres
  0.784W/0.906W, 0.012W gap (≈257 px each on the S23+; the pair is ~23% of the
  screen width).
- **Map and Inventory**: text pills stacked on the left under the health HUD,
  0.130W × 0.075 `vmin`, centre 0.100W, y 0.30/0.40.
- **Pause**: small pill in the top-right corner, 0.055W × 0.075 `vmin`.
- **Cast and Heal send the same `Action2`**: tap = Silk Skill, hold = heal.
  `Action4` (Dream Nail/Needolin) is no longer on the pad; it only works on
  valid targets, which is why it looked dead.
- Buttons use rounded-rect pills/rings with a dark fill, white border, brighter
  pressed state and shadowed labels.

## Input / shell

- `PlayerActivity.dispatchKeyEvent` intercepts `KEYCODE_BACK` before Unity's
  view and injects Escape (Pause/MenuCancel); `onBackPressed` covers gesture
  back. The Activity is no longer finished by the back key.
- Keep-screen-on moved to the real shell window: the launcher passes
  `keep_screen_on` in the launch intent and `PlayerActivity` sets
  `FLAG_KEEP_SCREEN_ON` (re-applied on resume/focus). `KeepScreenOn.cs` was
  deleted.

## Launcher / build

- Launcher landscape fix: the menu is a `ScrollView` with **Launch pinned at the
  bottom**, so it stays visible on short/landscape windows.
- `MonoRuntime.il2cppBudget` starts **SM-S916\*** (Galaxy S23+) at 6
  cores/1792 MB instead of the 2.5 GB first tier, which was reclaimed near the
  end. One reliable attempt instead of a failed attempt plus retry.
- Exit-code parsing fixed: monojni writes the status with `%u`, so `-1` arrives
  as `4294967295`; `MonoRuntime` now parses as a long and narrows to `Int`
  instead of reporting `FAILED`/120.
- Player-class dialog fix:
  - `UnityDex.inject` refuses a jar that does not contain
    `UnityPlayerForActivityOrService`.
  - The repair probe uses a boot-parent `DexClassLoader`, so a stale graft in
    the launcher process cannot make a good rebuilt jar look bad.
- README: fork install steps, device requirements, prominent debug-signing
  warning, physical-controller support, experimental GOG/touch controls note.

## Verification status

- Patch compile check (`preview-compile.sh`): 0 errors.
- 2.0.3 was installed and tested on the S23+: most buttons worked, Cast healed,
  Throw required an up direction, Dream did nothing. That test drove the Heal
  replacement and the layout changes.
- 2.0.4 is installed; the on-device rebuild is pending. The player-class fix,
  SM-S916 budget override, bigger/lower diamond and text Map/Inventory pills
  still need a device pass.
- Known: dash works once unlocked; Throw needs a tool equipped and aims with the
  stick; Dream Nail/Needolin only reacts to valid targets.

# Session changes — 2026-11-20 → 2026-11-22

Current source: **2.0.4**, unreleased. Everything below is on-device and
committed; the pad entries need the rebuild that carries them.

## On-screen pad

- **The layout is data.** `pad-layout.json` in the app's external files dir,
  written by the launcher's *Settings → On-screen pad* editor and read once per
  process. Moving or scaling a key no longer costs a 10-minute IL2CPP
  conversion: a JSON write and a restart.
- Every layout constant became a field: per-key `x/y/w/h/scale/shape/label/
  target`, and a stick spec with `mode` (floating | pinned), ring centre and
  radius, and the touch zone as four corners. Defaults are computed from the
  screen, so a device that never opens the editor keeps the old arrangement.
- The editor: full-screen landscape canvas at the game's aspect, chrome as a
  translucent overlay (Hide/peek), drag to move, pinch to scale 0.5–2×, the
  stick zone resizable **per axis** from its four corner handles, floating/
  pinned toggle, Reset, Save. It refuses overlapping keys — compared as
  circles, with a 0.02 vmin tolerance, because the built-in diamond overlaps by
  design (0.155 vmin apart, 0.080 radius) and a strict test made the default
  layout unsaveable.
- Hit areas stay bounding squares (a forgiving target for a thumb), but a tie
  between two of them is now resolved by **nearest centre**, not by slot order:
  "lowest slot that contains this point" made Jump win every edge case by
  being slot 0.
- Map and Inventory moved above the stick zone (they sat at 0.40/0.30 of the
  height, inside a zone starting at 0.30), and Pause got a real corner margin.
- `OnScreenDevice` takes its control per slot from the file, so rebinding is
  data too. Two slots may name the same control on purpose.
- **Names now match the game's own Controller screen** (screenshot, 11-21): the
  pill was emitting `Action2`, which is *Bind* — it healed while promising a
  cast. It is now `RightBumper`, labelled **Skill**, and casts the Silk Skill;
  the diamond's Heal is labelled **Bind**; **Tool** is also `RightBumper` but
  carries `aimUp`, a per-key flag that supplies (0,1) while it is held and the
  stick is near neutral — the game only throws a tool while aiming, which cost
  two thumbs on a touchscreen. A direction the player gives always wins.

## Launcher / build

- **Saves are backed up before every launch** to
  `<depot>/../silksong-saves-backup/<timestamp>/`, newest five kept. The game
  writes to the app's own external files dir, so an uninstall deletes them
  (`pm uninstall` does not even raise the `hasFragileUserData` prompt) — one
  hour of play was lost that way before this existed. Restore is still to come.
- **Tier chips** `Auto | 8·2560 | 6·1792 | 3·896 | 1·448` on the port screen,
  persisted as `il2cpp_budget`, with the rule that picked logged. `Auto` reads
  free memory live; an explicit chip is the user's call and is never lowered.
- The port screen shows `MemAvailable` once a second (red under 2.5 GB), the
  running tier during a conversion, battery temperature and charger state, and
  zram usage — the last because RSS growth alone cannot tell a hungry tool from
  a leaking one, but pages stuck in compressed swap can.
- **A reclaimed tier is remembered on disk** (`il2cpp_reclaimed`) and the next
  *Auto* start begins one step below it. Learned the hard way: reclaimed at
  `3/896`, retried at `1/448`, the launcher was killed in the same sweep, and
  the next attempt read the stored chip and restarted at the tier that had just
  died.
- `Auto` now offers the largest tier from **2.5 GB** free (was 3.4 GB), stepping
  down at 1.9 and 1.4. The old thresholds assumed peak memory decides; the log
  says **time near the threshold** decides. On 11-22 the 8-core/2560 MB tier
  finished all 1162 files in 8.5 min from a 2.4 GB start, while 3/896 was
  reclaimed at 926 files after 10.5 min on the same phone in the same hour.
- Rebuild paths from the launcher (`REBUILD` extra, "patches are newer") still
  start immediately and skip the memory readout; that is a known gap.

## Verification status

- 24 Kotlin unit tests green; `VirtualPad.cs` type-checks inside the container
  against Unity's own managed assemblies (stubs for InControl and the game).
- Full 1162-file conversion completed on the S23+ at 8 cores/2560 MB in 8.5
  minutes (11-22), including the current patch sources.
- Pad behaviour after the rebuild (Skill/Bind/Tool names, the aim assist, a
  saved layout round-trip through the editor) is installed but not yet
  confirmed by a device play test.
