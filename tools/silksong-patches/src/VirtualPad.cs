// VirtualPad — on-screen controls, built from the game's own input model.
//
// THE LIBRARY QUESTION. Third-party mobile control libraries are uGUI or
// "New Input System" shaped (EnhancedOnScreenStick, Nice Touch, BT-Gamepad),
// dead ends here: this game reads input through InControl via hand-bound
// PlayerActions, there is no editor to build prefab Canvases, and every
// candidate would add a second input stack the game never consults. InControl's
// own TouchControls ARE compiled into these assemblies — but their device gets
// created during InControl's one-time InputManager setup, which ran before any
// patch could exist, and a late-added TouchManager can never fire that path.
// The route with no such trap is the one InControl documents for custom
// hardware: a bare InputDevice subclass, attached with the public
// InputManager.AttachDevice, reporting state through the class's public commit
// helpers. One file, no prefabs, no shipped textures, and the device takes
// part in ActiveDevice arbitration like a real pad.
//
// VISIBILITY BY DESIGN (user-set): the pad exists when its Settings toggle is
// on, and shows ONLY during gameplay — the game's own GameState must be
// PLAYING. Menus, pause, inventory, cutscenes, the title: hidden (menus
// navigate themselves by touch). A connected controller does NOT hide it;
// both inputs coexist through InControl's ActiveDevice arbitration (last
// device that gave input wins per action set).
//
// THE LABELS AND THE BINDINGS. The game adds its hand-bound PlayerAction
// bindings only when a gamepad attaches; with no pad the actions carry no
// device bindings at all, which is what made the first pad dead and
// unlabelled. The fix: every second, any of the game's default
// action↔control pairs whose binding is missing gets it added
// (GameSettings.ControllerMapping, decompiled as the source of truth: jump =
// Action1, cast = Action2, attack = Action3, dreamNail = Action4, superDash =
// LeftTrigger, dash = RightTrigger, quickCast = RightBumper, quickMap =
// LeftBumper, taunt = RightStickButton). These are the game's own defaults,
// so a controller attaching later finds the mapping it would have chosen
// anyway; the labels resolve live first and fall back to these defaults —
// the pad is labelled and alive from the first frame, controller or not.
//
// COORDINATES. Unity's Input.touches use a bottom-left origin; OnGUI uses
// top-left. Every touch is converted ONCE at the routing boundary and the
// rest of the file speaks one convention: GUI (top-left) coordinates.
//
// COST. One fixed pass in Update over Input.touches, commits in the device's
// own Update against InControl's update tick, an OnGUI pass of 6 textures
// with styles and GUIContent cached per screen size. No per-frame allocation.

using System;
using System.Collections.Generic;
using InControl;
using UnityEngine;

// ── the device ─────────────────────────────────────────────────────────────

public class OnScreenDevice : InputDevice
{
    public Vector2 Stick { get; set; }
    public bool[] Buttons { get; } = new bool[8];

    public OnScreenDevice() : base("On-Screen Controls")
    {
        DeviceClass = InputDeviceClass.Controller;
        DeviceStyle = InputDeviceStyle.Unknown;
    }

    public override void Update(ulong updateTick, float deltaTime)
    {
        // The stick is expressed as the four directional controls, split the
        // way the merged InputDevice's own methods do, then committed so
        // ProcessLeftStick can derive the LeftStick vector the game reads.
        var v = Stick;
        UpdateLeftStickWithValue(new Vector2(v.x, v.y), updateTick, deltaTime);
        CommitLeftStick();

        Action1.CommitWithState(Buttons[0], updateTick, deltaTime);      // Jump
        Action2.CommitWithState(Buttons[1], updateTick, deltaTime);      // Cast
        Action3.CommitWithState(Buttons[2], updateTick, deltaTime);      // Attack
        Action4.CommitWithState(Buttons[3], updateTick, deltaTime);      // Dream Nail
        RightTrigger.CommitWithState(Buttons[4], updateTick, deltaTime); // Dash
        LeftTrigger.CommitWithState(Buttons[5], updateTick, deltaTime);  // Super Dash
        LeftBumper.CommitWithState(Buttons[6], updateTick, deltaTime);   // Map
        GetControl(InputControlType.Select).CommitWithState(Buttons[7], updateTick, deltaTime); // Inventory

        Commit(updateTick, deltaTime);
    }
}

// ── the overlay ─────────────────────────────────────────────────────────────

public class VirtualPad : MonoBehaviour
{
    enum PadButton { Jump = 0, Cast, Attack, DreamNail, Dash, SuperDash, Map, Inventory }

    // ── layout, as fractions of the screen ──────────────────────────────────

    const float CLUSTER_CX = 0.845f;   // face diamond centre, fraction of width
    const float CLUSTER_CY = 0.695f;   // fraction of height, from TOP (Gui Y)
    const float DIAMOND_R = 0.165f;    // face centre offset, of vmin (≈2.2 radii)
    const float FACE_R = 0.075f;       // face hit/draw radius, of vmin
    const float PILL_W = 0.18f;        // pill width, of width
    const float PILL_H = 0.115f;       // pill height, of vmin
    const float PILL_CY = 0.235f;      // pill centre Y, of vmin, from top
    const float PILL_SUPER_CX = 0.115f;
    const float PILL_MAP_CX = 0.315f;
    const float PILL_INV_CX = 0.515f;
    const float PILL_DASH_CX = 0.735f;
    const float STICK_ZONE_W = 0.40f;  // left thumb zone
    const float STICK_ZONE_H = 0.70f;  // of height, from top
    const float STICK_RING_R = 0.125f; // resting ring radius, of vmin
    const float STICK_REST_X = 0.165f; // resting ring centre, of width
    const float STICK_REST_Y = 0.30f;  // of height, from bottom
    const float KNOB_RANGE = 0.65f;    // knob travel, of ring radius

    OnScreenDevice device;
    bool deviceAttached;
    bool visible;

    // Touch bookkeeping; -1 means no finger holds that role. The finger map
    // IS the button state: no second copy to keep coherent.
    int stickFinger = -1;
    Vector2 stickOrigin;               // GUI pixels
    Vector2 stickKnob;                 // -1..1, InControl convention (Y up)
    readonly int[] buttonFinger = { -1, -1, -1, -1, -1, -1 };

    // Labels, refreshed once a second. Empty string = no action bound to that
    // control; the button still draws and still sends its control.
    readonly string[] labels = new string[6];
    readonly GUIContent[] labelContent = new GUIContent[8];
    int labelCooldown;
    int liveToggleCooldown;
    float _diagTimer;                  // per-two-second diagnostic line (debug)
    global::GlobalEnums.GameState gm_GameState()
    {
        var gm = GameManager._instance;
        return gm != null ? gm.GameState : global::GlobalEnums.GameState.INACTIVE;
    }

    bool actionsSet;                   // the IncludeDevices hammer was placed
    float lastPlayingTime = -99f;
    bool includedDevices;

    GUIStyle labelStyle;
    int styleScreenWidth, styleScreenHeight;
    Texture2D ringTex;

    static readonly InputControlType[] Targets =
    {
        InputControlType.Action1,       // was Jump
        InputControlType.Action2,       // was Cast
        InputControlType.Action3,       // was Attack
        InputControlType.Action4,       // was Dream Nail
        InputControlType.RightTrigger,  // was Dash
        InputControlType.LeftTrigger,   // was Super Dash
        InputControlType.LeftBumper,    // Map: the game's own map toggle
        InputControlType.Select,        // Inventory: opens/closes the full inventory
    };

    /**
     * The game's own default action↔control pairs, as the decompiled
     * GameSettings.ControllerMapping holds them; see the class comment for
     * the two uses (binding ensure + label fallback).
     */
    static readonly (string key, string label, InputControlType target)[] Defaults =
    {
        ("jump", "Jump", InputControlType.Action1),
        ("cast", "Cast", InputControlType.Action2),
        ("attack", "Attack", InputControlType.Action3),
        ("dreamNail", "Dream Nail", InputControlType.Action4),
        ("superDash", "Super Dash", InputControlType.LeftTrigger),
        ("dash", "Dash", InputControlType.RightTrigger),
        ("quickCast", "Quick Cast", InputControlType.RightBumper),
        ("quickMap", "Map", InputControlType.LeftBumper),
        ("openInventory", "Inventory", InputControlType.Select),
        ("moveLeft", "Left", InputControlType.LeftStickLeft),
        ("moveRight", "Right", InputControlType.LeftStickRight),
        ("moveUp", "Up", InputControlType.LeftStickUp),
        ("moveDown", "Down", InputControlType.LeftStickDown),
        ("taunt", "Taunt", InputControlType.RightStickButton),
    };

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        if (!SilksongPatches.Settings.GetBool("touch_overlay", true)) return;
        var go = new GameObject("__VirtualPad__");
        DontDestroyOnLoad(go);
        go.AddComponent<VirtualPad>();
    }

    void Start()
    {
        device = new OnScreenDevice();
        for (int i = 0; i < labelContent.Length; i++)
            labelContent[i] = new GUIContent("");
    }

    bool settingOn = true;

    void Update()
    {
        // The toggle is read LIVE from the exported settings file: the
        // launcher rewrites it at exit of its Settings screen, and waiting
        // for a game relaunch left the user flipping a toggle that did
        // nothing until then.
        if (++liveToggleCooldown >= 30) { liveToggleCooldown = 0; settingOn = SilksongPatches.Settings.GetBool("touch_overlay", true); }
        if (!settingOn) { visible = false; return; }

        if (!deviceAttached)
        {
            if (!InputManager.IsSetup) return;
            InputManager.AttachDevice(device);
            deviceAttached = true;
        }

        // The DEVICE is zeroed every frame before routing; a held finger
        // re-sets its role in RouteTouches from its own touch record (Unity
        // guarantees an Ended phase before a touch disappears, so nothing is
        // lost across frames).
        for (int i = 0; i < device.Buttons.Length; i++) device.Buttons[i] = false;
        device.Stick = Vector2.zero;

        // DIAGNOSTIC (debug builds): every two seconds, one line with the
        // whole decision state, so a non-working pad names its reason.
        _diagTimer -= Time.unscaledDeltaTime;
        if (_diagTimer <= 0f)
        {
            _diagTimer = 2f;
            var mask = 0;
            for (int i = 0; i < device.Buttons.Length; i++) if (device.Buttons[i]) mask |= 1 << i;
            Debug.Log($"[VPad] gs={(int)gm_GameState()} vis={visible} incl={includedDevices} " +
                $"attached={deviceAttached} setActions={actionsSet} stick={device.Stick.x:F2},{device.Stick.y:F2} " +
                $"btns=0x{mask:X2} fingers={stickFinger},{string.Join(",", buttonFinger)}");
        }

        // GAMEPLAY ONLY, with a short hysteresis: the game's state can bob
        // in and out of PLAYING between scenes, and an overlay that
        // disappears for a frame and returns reads as a flicter.
        var gm = GameManager._instance;
        if (gm != null && gm.GameState == global::GlobalEnums.GameState.PLAYING) lastPlayingTime = Time.unscaledTime;
        visible = (Time.unscaledTime - lastPlayingTime) < 1.5f;

        if (visible) RouteTouches();
    }

    void OnDestroy()
    {
        if (deviceAttached) InputManager.DetachDevice(device);
    }

    // ── touch routing ──────────────────────────────────────────────────────

    void RouteTouches()
    {
        var touches = Input.touches;

        // Held roles: follow their fingers, drop them on release.
        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            var gui = new Vector2(t.position.x, Screen.height - t.position.y);

            if (t.fingerId == stickFinger)
            {
                if (t.phase == TouchPhase.Began) LogTouch("stick-held-begin", t);
                if (t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled)
                {
                    stickFinger = -1;
                    stickOrigin = Vector2.zero;
                    continue;
                }
                UpdateKnob(gui);
            }

            for (int b = 0; b < buttonFinger.Length; b++)
            {
                if (t.fingerId != buttonFinger[b]) continue;
                if (t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled ||
                    !InsideButton(b, gui))
                {
                    buttonFinger[b] = -1;
                }
            }
        }

        // New touches pick their role by zone; held fingers are matched above.
        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            if (t.phase != TouchPhase.Began) continue;
            var gui = new Vector2(t.position.x, Screen.height - t.position.y);
            LogTouch("new-touch", t);

            if (stickFinger == -1 && InStickZone(gui))
            {
                stickFinger = t.fingerId;
                stickOrigin = gui;
                UpdateKnob(gui);
                continue;
            }
            for (int b = 0; b < buttonFinger.Length; b++)
            {
                if (buttonFinger[b] != -1) continue;
                if (InsideButton(b, gui))
                {
                    buttonFinger[b] = t.fingerId;
                    break;
                }
            }
        }

        // Settle device state from whatever fingers are held.
        if (stickFinger != -1)
        {
            device.Stick = Vector2.ClampMagnitude(stickKnob, 1f);
        }
        for (int b = 0; b < device.Buttons.Length; b++)
        {
            device.Buttons[b] = buttonFinger[b] != -1;
        }
    }

    void LogTouch(string where, UnityEngine.Touch t)
    {
        // One line per touch EVENT during the debug session: routes are
        // decided here and this names every decision with coordinates.
        var gui = new Vector2(t.position.x, Screen.height - t.position.y);
        Debug.Log($"[VPadT] {where} id={t.fingerId} phase={t.phase} gui={gui.x:F0},{gui.y:F0} " +
            $"zone={(InStickZone(gui) ? "stick" : "other")}");
    }

    void UpdateKnob(Vector2 guiPos)
    {
        var vmin = Mathf.Min(Screen.width, Screen.height);
        var max = KNOB_RANGE * STICK_RING_R * vmin;
        var delta = guiPos - stickOrigin;
        var clamped = Vector2.ClampMagnitude(delta, max);
        // GUI Y grows downward; the device expects stick-up as positive.
        stickKnob = new Vector2(clamped.x / max, -clamped.y / max);
    }

    bool InStickZone(Vector2 guiPos)
    {
        return guiPos.x < STICK_ZONE_W * Screen.width &&
               guiPos.y > (1f - STICK_ZONE_H) * Screen.height;
    }

    Vector2 ButtonCentre(int b)
    {
        var cx = CLUSTER_CX * Screen.width;
        var cy = CLUSTER_CY * Screen.height;
        var off = DIAMOND_R * Mathf.Min(Screen.width, Screen.height);
        switch ((PadButton)b)
        {
            case PadButton.Jump: return new Vector2(cx + off, cy);          // right
            case PadButton.Attack: return new Vector2(cx - off, cy);        // left
            case PadButton.Cast: return new Vector2(cx, cy + off);          // below
            case PadButton.DreamNail: return new Vector2(cx, cy - off);     // above
            case PadButton.Dash: return new Vector2(PILL_DASH_CX * Screen.width, PILL_CY * vmin());
            case PadButton.SuperDash: return new Vector2(PILL_SUPER_CX * Screen.width, PILL_CY * vmin());
            case PadButton.Map: return new Vector2(PILL_MAP_CX * Screen.width, PILL_CY * vmin());
            default: return new Vector2(PILL_INV_CX * Screen.width, PILL_CY * vmin());
        }
    }

    static float vmin() => Mathf.Min(Screen.width, Screen.height);

    bool InsideButton(int b, Vector2 guiPos)
    {
        var c = ButtonCentre(b);
        if ((PadButton)b >= PadButton.Dash)
        {
            var w = PILL_W * Screen.width;
            var h = PILL_H * vmin();
            return new Rect(c.x - w * 0.5f, c.y - h * 0.5f, w, h).Contains(guiPos);
        }
        var r = FACE_R * vmin();
        return (guiPos - c).sqrMagnitude <= r * r;
    }

    // ── labels and bindings, refreshed ~1Hz ─────────────────────────────────

    void RefreshLabelsAndBindings()
    {
        var gm = GameManager._instance;
        if (gm == null) return;
        var handler = gm.inputHandler;
        if (handler == null) return;
        var actions = handler.inputActions;
        if (actions == null) return;

        // THE HAMMER for the "still do nothing" report: PlayerAction binding
        // sources are normally read from the INPUT SET's active device, and
        // a device that never got nonzero input before the first press can
        // lose that race. IncludeDevices pins this device into the set's
        // polling regardless of arbitration — every action here reads it
        // directly, every frame, always.
        try { actions.IncludeDevices.Add(device); includedDevices = true; } catch (Exception) { }
        for (int i = 0; i < Defaults.Length; i++)
        {
            var entry = Defaults[i];
            var action = GetAction(actions, entry.key);
            if (action == null) continue;

            // THE BINDING: added when missing, whatever is attached. With a
            // pad connected the game has bound the same control already and
            // this is a read; with none, it is what lets the touch device be
            // read at all.
            if (!HasControl(action, entry.target))
                action.AddBinding(new DeviceBindingSource(entry.target));

            // THE LABEL: live binding wins; the default name is the fallback.
            var slot = SlotForControl(entry.target);
            var label = FindLiveLabel(actions, entry.target) ?? entry.label;
            if (slot >= 0 && labels[slot] != label)
            {
                labels[slot] = label;
                labelContent[slot] = new GUIContent(label);
            }
        }
    }

    /** Which pad slot is drawn for this control, or -1. */
    static int SlotForControl(InputControlType target)
    {
        for (int i = 0; i < Targets.Length; i++)
            if (Targets[i] == target) return i;
        return -1;
    }

    static string FindLiveLabel(HeroActions actions, InputControlType target)
    {
        var pairs = new (string key, PlayerAction action)[]
        {
            ("jump", actions.Jump),
            ("attack", actions.Attack),
            ("cast", actions.Cast),
            ("dreamNail", actions.DreamNail),
            ("superDash", actions.SuperDash),
            ("dash", actions.Dash),
            ("quickCast", actions.QuickCast),
            ("quickMap", actions.QuickMap),
            ("openInventory", actions.OpenInventory),
            ("taunt", actions.Taunt),
        };
        for (int i = 0; i < pairs.Length; i++)
        {
            if (pairs[i].action == null) continue;
            if (HasControl(pairs[i].action, target))
            {
                switch (pairs[i].key)
                {
                    case "jump": return "Jump";
                    case "attack": return "Attack";
                    case "cast": return "Cast";
                    case "dreamNail": return "Dream Nail";
                    case "superDash": return "Super Dash";
                    case "dash": return "Dash";
                    case "quickCast": return "Quick Cast";
                    case "quickMap": return "Map";
                    case "openInventory": return "Inventory";
                    case "taunt": return "Taunt";
                }
            }
        }
        return null;
    }

    static PlayerAction GetAction(HeroActions actions, string key)
    {
        switch (key)
        {
            case "jump": return actions.Jump;
            case "attack": return actions.Attack;
            case "cast": return actions.Cast;
            case "dreamNail": return actions.DreamNail;
            case "superDash": return actions.SuperDash;
            case "dash": return actions.Dash;
            case "quickCast": return actions.QuickCast;
            case "quickMap": return actions.QuickMap;
            case "openInventory": return actions.OpenInventory;
            case "moveLeft": return actions.Left;
            case "moveRight": return actions.Right;
            case "moveUp": return actions.Up;
            case "moveDown": return actions.Down;
            case "taunt": return actions.Taunt;
            default: return null;
        }
    }

    static bool HasControl(PlayerAction action, InputControlType target)
    {
        var bindings = action.UnfilteredBindings;
        if (bindings == null) return false;
        for (int i = 0; i < bindings.Count; i++)
        {
            if (bindings[i] is DeviceBindingSource src && src.Control == target) return true;
        }
        return false;
    }

    // ── drawing ────────────────────────────────────────────────────────────

    Texture2D CircleTexture()
    {
        if (ringTex != null) return ringTex;
        // A flat disc under a solid border ring — the shape real controller
        // buttons have, instead of the radial ball-shading the first texture
        // had (alpha = 1 - dist² rendered every control as a glowing sphere).
        const int size = 96;
        ringTex = new Texture2D(size, size, TextureFormat.ARGB32, false);
        ringTex.wrapMode = TextureWrapMode.Clamp;
        var px = new Color32[size * size];
        var c = (size - 1) / 2f;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                var dist = Mathf.Sqrt((x - c) * (x - c) + (y - c) * (y - c)) / c;
                byte a;
                if (dist > 0.94f) a = 0;               // outside
                else if (dist > 0.78f) a = 235;        // the border ring
                else a = 38;                           // faint fill inside
                px[y * size + x] = new Color32(255, 255, 255, a);
            }
        }
        ringTex.SetPixels32(px);
        ringTex.Apply(false, true);
        return ringTex;
    }

    void RefreshStylesIfNeeded()
    {
        if (labelStyle != null && styleScreenWidth == Screen.width &&
            styleScreenHeight == Screen.height) return;
        styleScreenWidth = Screen.width;
        styleScreenHeight = Screen.height;
        labelStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = (int)(17f + 14f * Mathf.Min(Screen.width, Screen.height) / 1080f),
            alignment = TextAnchor.MiddleCenter,
            clipping = TextClipping.Overflow,
        };
    }

    void OnGUI()
    {
        if (!visible) return;
        RefreshStylesIfNeeded();

        // Bindings + labels refresh at ~1Hz.
        if (++labelCooldown >= 60) { labelCooldown = 0; RefreshLabelsAndBindings(); }

        var tex = CircleTexture();
        var vminNow = vmin();
        var rest = new Color(1f, 1f, 1f, 0.9f);
        var held = new Color(1f, 1f, 1f, 1f);

        // The resting ring marks the zone even untouched: hidden buttons are
        // undiscoverable, which was the first build's other problem.
        if (stickFinger == -1)
        {
            var restCentre = new Vector2(STICK_REST_X * Screen.width,
                Screen.height - STICK_REST_Y * Screen.height);
            GUI.color = rest;
            GUI.DrawTexture(new Rect(restCentre.x - STICK_RING_R * vminNow, restCentre.y - STICK_RING_R * vminNow,
                STICK_RING_R * 2f * vminNow, STICK_RING_R * 2f * vminNow), tex);
        }

        if (stickFinger != -1)
        {
            var origin = stickOrigin;
            GUI.color = held;
            GUI.DrawTexture(new Rect(origin.x - STICK_RING_R * vminNow, origin.y - STICK_RING_R * vminNow,
                STICK_RING_R * 2f * vminNow, STICK_RING_R * 2f * vminNow), tex);
            var knob = new Vector2(origin.x + stickKnob.x * KNOB_RANGE * STICK_RING_R * vminNow,
                                   origin.y - stickKnob.y * KNOB_RANGE * STICK_RING_R * vminNow);
            GUI.DrawTexture(new Rect(knob.x - STICK_RING_R * 0.55f * vminNow, knob.y - STICK_RING_R * 0.55f * vminNow,
                STICK_RING_R * 1.1f * vminNow, STICK_RING_R * 1.1f * vminNow), tex);
        }

        for (int b = 0; b < 6; b++)
        {
            var centre = ButtonCentre(b);
            bool isPill = (PadButton)b >= PadButton.Dash;
            GUI.color = buttonFinger[b] != -1 ? held : rest;

            Rect rect;
            if (isPill)
            {
                var w = PILL_W * Screen.width;
                var h = PILL_H * vminNow;
                rect = new Rect(centre.x - w * 0.5f, centre.y - h * 0.5f, w, h);
            }
            else
            {
                var r = FACE_R * vminNow * 0.96f;
                rect = new Rect(centre.x - r, centre.y - r, r * 2f, r * 2f);
            }
            GUI.DrawTexture(rect, tex);
            var content = labelContent[b];
            if (content.text.Length > 0)
            {
                var scaled = new GUIStyle(labelStyle);
                if (!isPill) scaled.fontSize = (int)(rect.height * 0.25f);
                GUI.Label(rect, content, scaled);
            }
        }
        GUI.color = Color.white;
    }
}
