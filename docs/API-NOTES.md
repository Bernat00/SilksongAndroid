# API notes — verified external surfaces

Rule for this repo: **no guessed APIs.** Look up the official documentation before
using anything outside the code we own; record what was verified (and every
mismatch with the spec) here. For game/InControl/Unity APIs the authoritative
source is the decompiled `ref/` tree (`ref/depot-managed/Assembly-CSharp.dll`).
For anything vendor-specific, the spec wins by default and OBSERVED behavior on
a real device is recorded next to it — plus a calibration path that resolves
ambiguity from data instead of a guess.

## Linux power_supply sysfs ABI
Source: https://www.kernel.org/doc/Documentation/ABI/testing/sysfs-class-power
(verified 2026-11-13). Facts used by `PerfOverlay.cs`:

- `current_now` / `current_avg`: **microamps**; negative = discharging,
  positive = charging. `current_avg` is the smoothed reading the ABI prefers
  for power accounting — probed *before* `current_now` (probe list:
  `current_avg`, `current_now`, then BatteryManager `BATTERY_PROPERTY_CURRENT_NOW` = 2).
- `voltage_now`: microvolts.
- `temp`: 1/10 °C (code divides by 10 ✓).
- `charge_counter`: microamp-hours; delta over a wall-clock window × cell
  voltage = average watts. Numerically verified: 30 W at 4 V → 2083 µAh/s →
  the CounterWatts expression returns 30.0.
- `status`: `Charging | Discharging | Not charging | Full | Unknown`. **"Full"
  is NOT "charging"** (charge complete, ~zero net draw). Fixed: only
  "Charging" reads as current into the battery.

## Known OEM deviation (recorded, not guessed)
Samsung fuel gauges (S916B among them) report `current_now` in **milliamps**,
against the ABI. Both readings coexist in one code path is impossible to
separate by magnitude alone in the ambiguous 10–100 raw zone (µA-speak would
mean 10–100 mA, mA-speak would mean 10–100 A). Resolution policy in
`ReadCurrentAmps`:
1. Bootstrap: ABI ladder — raw > 10 000 → µA; 5 < raw ≤ 10 000 → mA (Samsung's
   realistic gauge range: idle ≈ 0.1 A, 45 W charge ≈ 5 A); below → unresolved.
2. Calibrate from the charge-counter delta once ≥ 3 delta samples exist:
   ratio = (counterAmps·10⁶)/|raw| ≈ 1000 ⇒ the gauge speaks mA; ≈ 1 ⇒ µA.
   Cached in `_gaugeIsMilliA`; data beats heuristics after warm-up.
3. Unresolved (no raw unit, no calibration) → smoothed counter watts, which
   carry no unit ambiguity because they are measured, not inferred. The W
   column only uses V×I when the unit is resolved (`float.NaN` sentinel).

## BatteryManager property ids (developer.android.com, API 21+)
`BATTERY_PROPERTY_CHARGE_COUNTER = 1`, `CURRENT_NOW = 2`, `CURRENT_AVERAGE = 3`;
status values `BATTERY_STATUS_CHARGING = 2`. Used with these exact ids.

## GOG REST surface
Verified against the live endpoints from this machine (curl) and against the
`gogdl` reference implementation (`heroic-gogdl`), not guessed:
- OAuth: `auth.gog.com/token` (POST, one-time single-use code exchanged once
  against the **base** client; user id from the token response body).
- Game-scoped token: refresh grant against the **game clientId + secret**
  (gogdl `refresh_credentials` pattern); game tokens live ~1 h → cached
  re-mint at 20 min age (`gog_mint_at`).
- Remote config:
  `https://remote-config.gog.com/components/galaxy_client/clients/{clientId}?component_version=2.0.45`
  → shape `content.<Platform>.cloudStorage.locations[0].name` = bucket dir
  ("saves" for game 1558393671). The first implementation read a
  `save_location` field that **does not exist** in this shape — the sync
  silently did nothing; the shape now comes from the verified response.
- Object storage: `cloudstorage.gog.com/v1/{userId}/{gameClientId}/{bucket}/{file}`,
  raw (uncompressed) uploads with an `Etag` md5 hex of the stored bytes
  (desktop Galaxy convention); gzip-sniff on download for gogdl-style objects.

## Unity / game APIs
Every binding target and type used by the patches is verified against the
game's decompiled code in `ref/` (e.g. `InputDevice`,
`PlayerActionSet.IncludeDevices`, `GameManager.GameState`,
`HeroActions.MoveVector`) — including the Start/View vs Menu/Select binding
discrepancy (upstream issue #47 work) that motivated this file.

## On-screen pad and game action mappings (verified 2026-11-19)

- The game's controller mapping, read off the in-game **Controller** screen
  (screenshot 2026-11-21, `Options → Controller`): `LeftTrigger` Inventory,
  `LeftBumper` Quick Map, `RightTrigger` **Sprint**, `RightBumper` **Skill /
  Tool** (one control: the silk skill and the equipped tool share it), and the
  face buttons Bind / Jump / Attack / Needolin. `Action1` Jump, `Action2`
  Cast/Heal (tap casts the Silk Skill, hold heals/binds), `Action3` Attack,
  `Action4` Dream Nail/Needolin (only reacts to valid targets), `Select`
  Inventory, `Start` Pause.
- So `RightBumper` is *not* a separate "throw tools" control: pressing it uses
  the Skill, and a tool is thrown from the same button when one is equipped.
  The pad's pill is labelled **Tool** for that reason — a "Throw" label
  promised something the control cannot do on its own, and cost a device test
  to discover.
- `OnScreenDevice` creates every control it uses explicitly. `InputDevice.OnAttached`
  adds only alias controls (stick axes, DPad, Command, style commands); any
  `UpdateWithState` on a control that was not added is a silent no-op.
- The virtual device takes part in `InputManager.ActiveDevice` arbitration and
  is activated with `RequestActivation()`. `PlayerActionSet.Device` is left
  null; the game's `ControllerAttached/Activated -> MapControllerButtons` binds
  the actions and `FindActiveDevice()` follows the active device.
- The old failures (6-vs-8 arrays, missing controls, synthesized Android events,
  the `IncludeDevices`/`AddBinding` hammer) are recorded in
  `docs/SESSION-CHANGES.md`. Current layout: Dash top, Attack left, Jump bottom,
  Heal right; Throw/Cast above the diamond; Map/Inventory text pills stacked
  left; Pause top-right.
- Player classes: `UnityDex` dexes the Unity player `classes.jar` on device and
  grafts it into the app class loader. `inject` validates that the jar contains
  `UnityPlayerForActivityOrService`; the repair probe uses a boot-parent
  `DexClassLoader` so a stale graft in the launcher process cannot make a good
  rebuilt jar look bad. If the old popup ever returns, force-stop the app first
  (fresh process) and read `launcher.log`.
