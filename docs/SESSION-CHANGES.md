# Session changes — 2026-11-14

Summary of the changes made in this session. Everything below is in the current
tree; the APK release built from it is `2.0.2`.

## On-screen pad (rewritten)

The previous pad never worked at play time: it threw `IndexOutOfRangeException`
in three places (arrays sized 6 against 8 controls), so `RouteTouches`,
the label refresh and `OnGUI` all aborted. On top of that it added no InControl
controls, so every `UpdateWithState`/`UpdateWithValue` call was a no-op on
`InputControl.Null`.

- `OnScreenDevice` is now a proper `InputDevice` with every control the game
  binds created in its constructor: `Action1..Action4`, `LeftBumper`,
  `RightBumper`, `Select`, `Start`, `RightStickButton` and the left-stick
  directions. It feeds state only; `InputManager` owns `Commit`.
- Removed the synthesized Android `MotionEvent`/`KeyEvent` injector. On this
  build Android uses `NewUnityInputDeviceManager` (Unity Input System), not the
  legacy `UnityInputDeviceManager`, and the decor-view dispatch bypassed the
  shell `PlayerActivity`.
- Removed the `PlayerActionSet.Device` pin, the `IncludeDevices` list and the
  per-0.5 s binding hammer. Attaching a known device makes the game's own
  `ControllerAttached`/`ControllerActivated` → `MapControllerButtons` bind the
  actions, and leaving `PlayerActionSet.Device` null is what keeps the physical
  controller and the pad arbitrated by `InputManager.ActiveDevice`.
- Activation uses `RequestActivation()` at attach and on input; idle frames
  publish zeros, so a physical controller takes over on its next input.
- Fixed the 6-vs-8 array bugs; all loops and arrays are sized from
  `OnScreenDevice.ButtonCount`.
- One `ButtonRect` is now shared by drawing and hit-testing.

## Pad layout and look

- Face diamond: Attack left, Cast right, Dream above, Jump below; the cluster is
  raised so the bottom button is not against the screen edge.
- Top row reduced to three pills: Map, Inventory, Throw (the dash and
  super-dash pills were removed). Pause is a small pill in the top-right corner.
- Buttons use proper rounded-rect pills and dark-filled rings with a white
  border, a brighter pressed state, and text with a shadow. This fixes the
  stretched/line-like appearance of the old pill texture.

## Inventory, pause and back

- The pad now has three modes: hidden, play, and inventory.
- Inventory mode shows **Exit** (Select, closes the inventory) in the top-right
  corner and pane **&lt;** / **&gt;** buttons (LeftBumper/RightBumper) in the
  bottom corners. Item selection and tab taps remain the existing touch path.
- Back handling: `PlayerActivity.dispatchKeyEvent` intercepts `KEYCODE_BACK`
  before Unity's view consumes it, and `onBackPressed` covers gesture back.
  Both inject Escape, which the game binds to Pause and MenuCancel, instead of
  finishing the Activity.
- Visibility is gated on `GameState`, `isPaused` and `isInventoryOpen`; pause,
  menus and cutscenes hide the pad, inventory gets its own overlay.

## Screen-on

- `KeepScreenOn.cs` was deleted. The setting is passed from the launcher as an
  intent extra to the shell, and `PlayerActivity` sets
  `FLAG_KEEP_SCREEN_ON` on the real game window (and re-asserts it on resume and
  focus changes). `UnityPlayer.currentActivity` is not populated for a
  shell-hosted player, which is why the old patch did nothing in-game.

## Build and launcher

- On-device il2cpp budget: a 2.5 GB heap is now the first tier on devices with
  ≥6.6 GB total RAM (previously unlimited, which peaks around 3.3 GB and gets
  reclaimed by Android on a loaded 7 GB phone). Retrying from a higher tier
  drops to the known 1792 MB tier instead of halving immediately.
- Launcher landscape layout: the menu buttons are in a `ScrollView`, so the
  **Launch** button stays pinned at the bottom instead of being laid out below
  the visible area on short/landscape windows.
- The README now has fork-specific install steps, recommended device
  requirements, and an explicit note that GOG and the on-screen controls are
  experimental.

## Verification status

- `make check` / `preview-compile`: passes with 0 errors.
- The InControl device was verified on the S23+ with `InputProbe`: Action1..4,
  Left/RightBumper, Left/RightTrigger and Select all produced edges.
- The new layout, back handling and inventory overlay are **not yet
  device-verified in a completed build**; the last on-device conversion was
  interrupted at 935/1162 files by a phone restart. They compile, but expect to
  finish one port and test.
- Dash and super-dash were removed from the pad at the user's request; the game's
  own controller mapping still binds them for physical controllers.
