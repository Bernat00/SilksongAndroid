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
