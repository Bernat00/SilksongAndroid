// VirtualPad — on-screen controls, built from the game's own input model.
//
// THE ROAD. InControl is the input stack this game reads; on this build the
// Android device manager is NewUnityInputDeviceManager (Unity Input System
// Gamepads), not the legacy UnityInputDeviceManager that reads
// Input.GetJoystickNames. A synthesized-Android-event layer therefore has no
// consumer, and dispatching to the decor view bypasses the shell
// PlayerActivity, which is what forwards input to Unity via injectEvent.
//
// What does work is the route InControl documents for custom hardware: an
// InputDevice subclass attached with the public InputManager.AttachDevice.
// The device must own every control the game binds — OnAttached only adds
// alias controls (LeftStickX/Y, DPadX/Y, Command, style commands), and
// UpdateWithState on a control that was never added is a silent no-op.
//
// BINDINGS COME FROM THE GAME. Attaching a known device raises
// InputHandler.ControllerAttached/ControllerActivated, which run
// SetActiveGamepadType -> MapControllerButtons: inputActions.Reset() and the
// game's own ControllerMapping (jump=Action1, cast=Action2, attack=Action3,
// dreamNail=Action4, superDash=LeftTrigger, dash=RightTrigger,
// quickCast=RightBumper, quickMap=LeftBumper, taunt=RightStickButton,
// OpenInventory=Select, Pause=Start). This file binds nothing and never
// touches PlayerActionSet.Device or IncludeDevices: leaving Device null is
// what lets InputManager.ActiveDevice arbitration decide between the pad, a
// physical controller and the keyboard, last input wins.
//
// ACTIVITY. A device is active only while one of its controls has non-zero
// committed state; idle frames publish zeros, so a physical controller takes
// over on its next input. RequestActivation() is called once at attach and
// on every frame that carries input, which flips ActiveDevice in the same
// InputManager pass that consumes it.
//
// VISIBILITY. The pad exists when its Settings toggle is on (read once at
// game start) and draws only during gameplay: GameState PLAYING, not paused,
// inventory closed. Menus navigate themselves by touch; InventoryTouchInput
// owns every touch while the inventory is open. A connected controller does
// not hide the pad.
//
// COORDINATES. Unity's Input.touches use a bottom-left origin; OnGUI uses
// top-left. Every touch is converted ONCE at the routing boundary and the
// rest of the file speaks GUI coordinates. Draw and hit-test share one
// ButtonRect, so what is drawn is what is hit.
//
// COST. One pass over Input.touches per visible frame, one OnGUI pass over
// cached textures/styles, no per-frame allocation.

#if UNITY_ANDROID && !UNITY_EDITOR

using InControl;
using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;

// ── the device ─────────────────────────────────────────────────────────────

public class OnScreenDevice : InputDevice
{
    public const int ButtonCount = 9;
    public const int Jump = 0, Cast = 1, Attack = 2, Heal = 3,
                     Dash = 4, Map = 5, Inventory = 6, Throw = 7, Pause = 8;

    readonly bool[] buttons = new bool[ButtonCount];

    // Which InControl control each slot feeds, from the layout file. Slots are
    // the pad's own vocabulary; targets are what the game binds, and two slots
    // may name the same target on purpose (Cast and Heal both feed Action2 --
    // tap casts, hold heals -- and the OR below is what makes that work).
    readonly InputControlType[] targets;
    readonly InputControlType[] controls;
    Vector2 stick;

    public OnScreenDevice(InputControlType[] targets, string[] names) : base("On-Screen Controls")
    {
        this.targets = (InputControlType[])targets.Clone();
        var distinct = new List<InputControlType>();
        for (int i = 0; i < targets.Length; i++)
        {
            if (targets[i] == InputControlType.None) continue;
            if (!distinct.Contains(targets[i])) distinct.Add(targets[i]);
        }
        controls = distinct.ToArray();

        Meta = "On-Screen Controls";
        DeviceClass = InputDeviceClass.Controller;
        DeviceStyle = InputDeviceStyle.Unknown; // IsKnown stays true -> IsUnknown false

        // These must be created here, in the ctor. GetControl returns
        // InputControl.Null for anything not added, and every Update/Commit
        // helper on a Null control is a silent no-op.
        AddControl(InputControlType.LeftStickLeft, "Left Stick Left", 0.2f, 0.9f);
        AddControl(InputControlType.LeftStickRight, "Left Stick Right", 0.2f, 0.9f);
        AddControl(InputControlType.LeftStickUp, "Left Stick Up", 0.2f, 0.9f);
        AddControl(InputControlType.LeftStickDown, "Left Stick Down", 0.2f, 0.9f);
        for (int c = 0; c < controls.Length; c++)
        {
            // The triggers carry the same dead zone they always had; every
            // other target is a plain button.
            if (controls[c] == InputControlType.RightTrigger || controls[c] == InputControlType.LeftTrigger)
                AddControl(controls[c], NameFor(controls[c], names), 0.2f, 0.9f);
            else
                AddControl(controls[c], NameFor(controls[c], names));
        }
    }

    string NameFor(InputControlType control, string[] names)
    {
        for (int s = 0; s < targets.Length; s++)
            if (targets[s] == control && names != null && s < names.Length) return names[s];
        return control.ToString();
    }

    /** One atomic snapshot per frame; the router is the only writer. */
    public void SetInput(Vector2 value, int buttonMask)
    {
        stick = new Vector2(Mathf.Clamp(value.x, -1f, 1f), Mathf.Clamp(value.y, -1f, 1f));
        for (int i = 0; i < ButtonCount; i++)
            buttons[i] = (buttonMask & (1 << i)) != 0;
    }

    /** Feeds control state only. InputManager.CommitDevices calls Commit. */
    public override void Update(ulong updateTick, float deltaTime)
    {
        UpdateLeftStickWithValue(stick, updateTick, deltaTime);
        for (int c = 0; c < controls.Length; c++)
        {
            var on = false;
            for (int s = 0; s < ButtonCount && !on; s++)
                on = targets[s] == controls[c] && buttons[s];
            UpdateWithState(controls[c], on, updateTick, deltaTime);
        }
    }
}

// ── the overlay ─────────────────────────────────────────────────────────────

public class VirtualPad : MonoBehaviour
{
    enum PadButton
    {
        Jump = OnScreenDevice.Jump,
        Cast = OnScreenDevice.Cast,
        Attack = OnScreenDevice.Attack,
        Heal = OnScreenDevice.Heal,
        Dash = OnScreenDevice.Dash,
        Map = OnScreenDevice.Map,
        Inventory = OnScreenDevice.Inventory,
        Throw = OnScreenDevice.Throw,
        Pause = OnScreenDevice.Pause,
    }

    const int ButtonCount = OnScreenDevice.ButtonCount;

    // ── layout: a file, not constants ───────────────────────────────────────

    // Everything that used to be a const here is now a PadLayoutFile, read
    // from pad-layout.json in the app's own files directory. The launcher
    // writes it and this reads it once, in Start.
    //
    // That indirection is the point: the pad is compiled INTO the built game
    // (it is one of the C# patches), so a number written in this file is the
    // only way to move a button without paying for another conversion on the
    // phone. The constants below survive as Defaults(), which is exactly what
    // this file would contain on a device that has never opened the editor.
    //
    // Units: [x] is a fraction of Screen.width, [y] a fraction of
    // Screen.height measured from the TOP (GUI convention, since every touch
    // is converted at the routing boundary), and [w]/[h] are fractions of
    // vmin() so a circle stays circular at any aspect.

    PadLayoutFile layout;

    /** The InControl control each slot feeds, parsed from the layout once. */
    readonly InputControlType[] targets = new InputControlType[ButtonCount];

    // Inventory overlay: exit in the top-right, pane arrows in the bottom
    // corners. Deliberately NOT in the layout file: the inventory is the
    // game's own screen, these three arrows are placed to match its buttons,
    // and a layout that moved them would misalign them.
    const float INV_W = 0.10f;
    const float INV_H = 0.075f;
    const float INV_EXIT_CX = 0.93f;
    const float INV_EXIT_CY = 0.085f;
    const float INV_LEFT_CX = 0.09f;
    const float INV_RIGHT_CX = 0.91f;
    const float INV_ARROW_CY = 0.92f;

    const float KNOB_RANGE = 0.65f;    // knob travel, of ring radius

    /** Where each slot goes when the layout file names no target. */
    static readonly InputControlType[] FallbackTargets =
    {
        InputControlType.Action1,       // Jump
        InputControlType.RightBumper,   // Skill (casts the Silk Skill)
        InputControlType.Action3,       // Attack
        InputControlType.Action2,       // Bind (hold to heal)
        InputControlType.RightTrigger,  // Dash
        InputControlType.LeftBumper,    // Map
        InputControlType.Select,        // Inventory
        InputControlType.RightBumper,   // Throw (tools / pins)
        InputControlType.Start,         // Pause / back
    };

    static readonly string[] FallbackLabels =
    {
        "Jump", "Skill", "Attack", "Bind", "Dash",
        "Map", "Inventory", "Tool", "II",
    };

    static bool Verbose;

    enum PadMode { Hidden, Play, Inventory }

    OnScreenDevice device;
    PadMode mode;

    // Inventory-mode labels.
    GUIContent invExitContent, invLeftContent, invRightContent;

    // Touch bookkeeping; -1 means no finger holds that role. The finger map
    // IS the button state: no second copy to keep coherent.
    int stickFinger = -1;
    Vector2 stickOrigin;               // GUI pixels
    Vector2 stickKnob;                 // -1..1, InControl convention (Y up)

    /** Bits for the slots bound to Skill / Tool; pressing one aims up. */
    int toolMask;

    /** Below this stick magnitude the Tool press supplies the direction. */
    const float AimAssist = 0.35f;
    readonly int[] buttonFinger = new int[ButtonCount];

    readonly string[] labels = new string[ButtonCount];
    readonly GUIContent[] labelContent = new GUIContent[ButtonCount];

    float lastPlayingTime = -99f;
    float labelTimer;

    GUIStyle pillStyle;
    GUIStyle faceStyle;
    int styleScreenWidth, styleScreenHeight;
    Texture2D circleRest, circleHeld, pillRest, pillHeld;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        Verbose = Debug.isDebugBuild || SilksongPatches.Settings.GetBool("touch_debug", false);
        if (!SilksongPatches.Settings.GetBool("touch_overlay", true)) return;
        var go = new GameObject("__VirtualPad__");
        DontDestroyOnLoad(go);
        go.AddComponent<VirtualPad>();
    }

    void Start()
    {
        layout = PadLayoutFile.Load();
        var names = new string[ButtonCount];
        for (int i = 0; i < ButtonCount; i++)
        {
            var spec = layout.keys[i];
            targets[i] = ParseTarget(spec != null ? spec.target : null, FallbackTargets[i]);
            var label = spec != null ? spec.label : null;
            labels[i] = string.IsNullOrEmpty(label) ? FallbackLabels[i] : label;
            names[i] = labels[i];
            labelContent[i] = new GUIContent(labels[i]);
        }
        device = new OnScreenDevice(targets, names);
        // Which slots are the game's "Skill / Tool" control: pressing one of
        // them aims up for you (see RoutePlayTouches).
        toolMask = 0;
        for (int i = 0; i < ButtonCount; i++)
            if (layout.keys[i] != null && layout.keys[i].aimUp) toolMask |= 1 << i;
        // A layout saved before the field existed has it false everywhere: fall
        // back to the default's choice, which is the Tool key.
        if (toolMask == 0)
        {
            var fresh = PadLayoutFile.Defaults();
            for (int i = 0; i < ButtonCount; i++)
                if (fresh.keys[i].aimUp) toolMask |= 1 << i;
        }
        invExitContent = new GUIContent("Exit");
        invLeftContent = new GUIContent("<");
        invRightContent = new GUIContent(">");
        if (Verbose)
            Debug.Log($"[VPad] layout {PadLayoutFile.Source}, stick={layout.stick.mode}, " +
                      "targets=" + string.Join(",", Array.ConvertAll(targets, t => t.ToString())));
    }

    static InputControlType ParseTarget(string name, InputControlType fallback)
    {
        if (string.IsNullOrEmpty(name)) return fallback;
        try
        {
            return (InputControlType)Enum.Parse(typeof(InputControlType), name, true);
        }
        catch (Exception)
        {
            return fallback;
        }
    }

    void Update()
    {
        EnsureAttached();
        UpdateVisibility();

        if (mode == PadMode.Play) RoutePlayTouches();
        else if (mode == PadMode.Inventory) RouteInventoryTouches();
        else device.SetInput(Vector2.zero, 0);

        labelTimer -= Time.unscaledDeltaTime;
        if (labelTimer <= 0f)
        {
            labelTimer = 0.5f;
            RefreshLabels();
        }
    }

    void EnsureAttached()
    {
        if (device == null || !InputManager.IsSetup || device.IsAttached) return;
        InputManager.AttachDevice(device);
        // Makes the attach visible to InputHandler.Setup even when the event
        // fired before it subscribed, and flips ActiveDevice immediately.
        device.RequestActivation();
        if (Verbose)
            Debug.Log($"[VPad] attached \"{device.Name}\" devices={InputManager.Devices.Count} " +
                      $"active=\"{InputManager.ActiveDevice.Name}\"");
    }

    void OnDestroy()
    {
        if (device != null && InputManager.IsSetup && device.IsAttached)
            InputManager.DetachDevice(device);
    }

    // ── visibility ─────────────────────────────────────────────────────────

    void UpdateVisibility()
    {
        var gm = GameManager._instance;
        var state = gm != null ? gm.GameState : global::GlobalEnums.GameState.INACTIVE;
        bool inventory = PlayerData.HasInstance && PlayerData.instance.isInventoryOpen;
        bool paused = gm != null && gm.isPaused;
        bool playingNow = gm != null && state == global::GlobalEnums.GameState.PLAYING && !paused;
        if (playingNow) lastPlayingTime = Time.unscaledTime;

        // Short hold across ENTERING_LEVEL so a scene transition does not
        // blink the pad; pause and menus hide it immediately.
        bool transitioning = gm != null &&
            state == global::GlobalEnums.GameState.ENTERING_LEVEL &&
            (Time.unscaledTime - lastPlayingTime) < 0.5f;

        PadMode next;
        if (inventory && gm != null) next = PadMode.Inventory;
        else if (playingNow || transitioning) next = PadMode.Play;
        else next = PadMode.Hidden;

        if (next == mode) return;
        ReleaseAll();
        if (Verbose)
            Debug.Log($"[VPad] mode={next} state={state} paused={paused} inventory={inventory}");
        mode = next;
    }

    void ReleaseAll()
    {
        stickFinger = -1;
        stickOrigin = Vector2.zero;
        stickKnob = Vector2.zero;
        for (int i = 0; i < ButtonCount; i++) buttonFinger[i] = -1;
        device.SetInput(Vector2.zero, 0);
    }

    // ── touch routing ──────────────────────────────────────────────────────

    static Vector2 ToGui(Vector2 position) => new Vector2(position.x, Screen.height - position.y);

    void RoutePlayTouches()
    {
        var touches = Input.touches;

        // Held roles: follow their fingers, drop them on release.
        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            var gui = ToGui(t.position);

            if (t.fingerId == stickFinger)
            {
                if (t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled)
                {
                    stickFinger = -1;
                    stickOrigin = Vector2.zero;
                    stickKnob = Vector2.zero;
                    continue;
                }
                UpdateKnob(gui);
            }

            for (int b = 0; b < ButtonCount; b++)
            {
                if (t.fingerId != buttonFinger[b]) continue;
                if (t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled || !Hit(b, gui))
                    buttonFinger[b] = -1;
            }
        }

        // New touches pick their role by zone; held fingers were matched above.
        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            if (t.phase != TouchPhase.Began) continue;
            var gui = ToGui(t.position);

            // A key placed inside the stick's zone is the one the user asked
            // for; the zone is only a convenience. Keys win, and the editor is
            // what keeps the overlap from being an accident.
            //
            // Among keys, the nearest centre wins rather than the lowest slot
            // number: the hit areas are bounding squares (deliberately
            // generous for a thumb), the face circles overlap by design, and
            // "first slot that contains this point" made Jump win every tie by
            // being slot 0 -- a mis-press at exactly the edge where aim is
            // worst.
            var claim = -1;
            var claimDistance = float.MaxValue;
            for (int b = 0; b < ButtonCount; b++)
            {
                if (buttonFinger[b] != -1) continue;
                if (!Hit(b, gui)) continue;
                var distance = (ButtonCentre(b) - gui).sqrMagnitude;
                if (distance >= claimDistance) continue;
                claim = b;
                claimDistance = distance;
            }
            if (claim >= 0)
            {
                buttonFinger[claim] = t.fingerId;
                continue;
            }
            if (stickFinger == -1 && InStickZone(gui))
            {
                stickFinger = t.fingerId;
                // Pinned: the ring stays where it is drawn and the knob leans,
                // so a finger lifted and put back resumes from the same centre
                // instead of dragging the stick across the screen.
                stickOrigin = layout.stick.mode == "pinned" ? StickRestCentre() : gui;
                UpdateKnob(gui);
            }
        }

        int mask = 0;
        bool anyInput = false;
        for (int b = 0; b < ButtonCount; b++)
        {
            if (buttonFinger[b] == -1) continue;
            mask |= 1 << b;
            anyInput = true;
        }
        var stick = Vector2.zero;
        if (stickFinger != -1)
        {
            stick = Vector2.ClampMagnitude(stickKnob, 1f);
            anyInput = true;
        }
        // The game throws a tool only while the stick is up -- on a controller
        // that is a flick of the thumb, on a touchscreen it costs a second
        // finger and a steady hold. So pressing Tool alone aims up for you.
        // A direction the player is already giving is left alone: aiming a tool
        // down (or throwing while running) has to keep working.
        if (toolMask != 0 && (mask & toolMask) != 0 && stick.sqrMagnitude < AimAssist * AimAssist)
        {
            stick = Vector2.up;
            anyInput = true;
        }
        device.SetInput(stick, mask);
        if (anyInput) device.RequestActivation();
    }

    static readonly int[] InventorySlots =
    {
        (int)PadButton.Inventory,   // Exit: Select closes the inventory
        (int)PadButton.Map,         // Pane left: LeftBumper
        (int)PadButton.Throw,       // Pane right: RightBumper
    };

    /** Inventory owns the screen; only exit and pane arrows route here. */
    void RouteInventoryTouches()
    {
        var touches = Input.touches;

        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            var gui = ToGui(t.position);
            for (int k = 0; k < InventorySlots.Length; k++)
            {
                int b = InventorySlots[k];
                if (t.fingerId != buttonFinger[b]) continue;
                if (t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled || !Hit(b, gui))
                    buttonFinger[b] = -1;
            }
        }

        for (int i = 0; i < touches.Length; i++)
        {
            var t = touches[i];
            if (t.phase != TouchPhase.Began) continue;
            var gui = ToGui(t.position);
            for (int k = 0; k < InventorySlots.Length; k++)
            {
                int b = InventorySlots[k];
                if (buttonFinger[b] != -1) continue;
                if (!Hit(b, gui)) continue;
                buttonFinger[b] = t.fingerId;
                break;
            }
        }

        int mask = 0;
        bool anyInput = false;
        for (int k = 0; k < InventorySlots.Length; k++)
        {
            int b = InventorySlots[k];
            if (buttonFinger[b] == -1) continue;
            mask |= 1 << b;
            anyInput = true;
        }
        device.SetInput(Vector2.zero, mask);
        if (anyInput) device.RequestActivation();
    }

    void UpdateKnob(Vector2 guiPos)
    {
        var max = KNOB_RANGE * layout.stick.r * vmin();
        var delta = guiPos - stickOrigin;
        var clamped = Vector2.ClampMagnitude(delta, max);
        // GUI Y grows downward; the device expects stick-up as positive.
        stickKnob = new Vector2(clamped.x / max, -clamped.y / max);
    }

    Vector2 StickRestCentre()
    {
        var s = layout.stick;
        return new Vector2(s.cx * Screen.width, s.cy * Screen.height);
    }

    bool InStickZone(Vector2 guiPos)
    {
        var s = layout.stick;
        return guiPos.x >= s.zoneX0 * Screen.width &&
               guiPos.x <= s.zoneX1 * Screen.width &&
               guiPos.y >= s.zoneY0 * Screen.height &&
               guiPos.y <= s.zoneY1 * Screen.height;
    }

    // ── geometry: one source for draw and hit-test ─────────────────────────

    static float vmin() => Mathf.Min(Screen.width, Screen.height);

    bool IsPill(int slot) => Pad(slot).shape == "pill";

    PadKeySpec Pad(int slot) => layout.keys[slot];

    Vector2 ButtonCentre(int slot)
    {
        var k = Pad(slot);
        return new Vector2(k.x * Screen.width, k.y * Screen.height);
    }

    static Rect Centered(float cx, float cy, float w, float h) =>
        new Rect(cx - w * 0.5f, cy - h * 0.5f, w, h);

    Rect InventoryRect(int slot)
    {
        var w = INV_W * Screen.width;
        var h = INV_H * vmin();
        if (slot == (int)PadButton.Inventory)
            return Centered(INV_EXIT_CX * Screen.width, INV_EXIT_CY * vmin(), w, h);
        if (slot == (int)PadButton.Map)
            return Centered(INV_LEFT_CX * Screen.width, INV_ARROW_CY * vmin(), w, h);
        return Centered(INV_RIGHT_CX * Screen.width, INV_ARROW_CY * vmin(), w, h);
    }

    Rect ButtonRect(int slot)
    {
        if (mode == PadMode.Inventory) return InventoryRect(slot);
        var k = Pad(slot);
        var c = ButtonCentre(slot);
        // Draw and hit use this one rect; a scale that changed only one of
        // them would make a button that looks big and works small.
        var scale = k.scale > 0f ? k.scale : 1f;
        return Centered(c.x, c.y, k.w * vmin() * scale, k.h * vmin() * scale);
    }

    bool Hit(int slot, Vector2 guiPos) => ButtonRect(slot).Contains(guiPos);

    // ── labels, read-only sweep ────────────────────────────────────────────

    void RefreshLabels()
    {
        var gm = GameManager._instance;
        var handler = gm != null ? gm.inputHandler : null;
        var actions = handler != null ? handler.inputActions : null;
        if (actions == null) return;

        LabelFor(actions.Jump, "Jump");
        LabelFor(actions.Cast, "Cast");
        LabelFor(actions.Attack, "Attack");
        LabelFor(actions.QuickCast, "Tool");
        LabelFor(actions.QuickMap, "Map");
        LabelFor(actions.OpenInventory, "Inventory");
    }

    void LabelFor(PlayerAction action, string label)
    {
        if (action == null) return;
        var bindings = action.UnfilteredBindings;
        if (bindings == null) return;
        for (int i = 0; i < bindings.Count; i++)
        {
            if (!(bindings[i] is DeviceBindingSource source)) continue;
            int slot = SlotForControl(source.Control);
            if (slot >= 0) SetLabel(slot, label);
        }
    }

    int SlotForControl(InputControlType control)
    {
        for (int i = 0; i < ButtonCount; i++)
            if (targets[i] == control) return i;
        return -1;
    }

    void SetLabel(int slot, string text)
    {
        if (labels[slot] == text) return;
        labels[slot] = text;
        labelContent[slot].text = text;
    }

    // ── drawing ────────────────────────────────────────────────────────────

    /** Dark disc with a white border ring; brighter fill while held. */
    Texture2D CircleTexture(bool held)
    {
        var tex = held ? circleHeld : circleRest;
        if (tex != null) return tex;
        const int size = 96;
        tex = new Texture2D(size, size, TextureFormat.ARGB32, false);
        tex.wrapMode = TextureWrapMode.Clamp;
        var px = new Color32[size * size];
        var c = (size - 1) / 2f;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                var dist = Mathf.Sqrt((x - c) * (x - c) + (y - c) * (y - c)) / c;
                Color32 col;
                if (dist > 0.95f) col = new Color32(0, 0, 0, 0);
                else if (dist > 0.80f) col = new Color32(255, 255, 255, held ? (byte)255 : (byte)220);
                else col = held ? new Color32(255, 255, 255, 70) : new Color32(0, 0, 0, 110);
                px[y * size + x] = col;
            }
        }
        tex.SetPixels32(px);
        tex.Apply(false, true);
        if (held) circleHeld = tex; else circleRest = tex;
        return tex;
    }

    /** Rounded rectangle with the same treatment, drawn at a 3:1 aspect. */
    Texture2D PillTexture(bool held)
    {
        var tex = held ? pillHeld : pillRest;
        if (tex != null) return tex;
        const int w = 192, h = 64;
        const float radius = 30f, border = 3f;
        tex = new Texture2D(w, h, TextureFormat.ARGB32, false);
        tex.wrapMode = TextureWrapMode.Clamp;
        var px = new Color32[w * h];
        var cx = (w - 1) / 2f;
        var cy = (h - 1) / 2f;
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                var dx = Mathf.Abs(x - cx) - (w * 0.5f - radius);
                var dy = Mathf.Abs(y - cy) - (h * 0.5f - radius);
                var outside = Mathf.Sqrt(Mathf.Max(dx, 0f) * Mathf.Max(dx, 0f) +
                                         Mathf.Max(dy, 0f) * Mathf.Max(dy, 0f));
                var dist = outside + Mathf.Min(Mathf.Max(dx, dy), 0f) - radius;
                Color32 col;
                if (dist > 0f) col = new Color32(0, 0, 0, 0);
                else if (dist > -border) col = new Color32(255, 255, 255, held ? (byte)255 : (byte)220);
                else col = held ? new Color32(255, 255, 255, 70) : new Color32(0, 0, 0, 110);
                px[y * w + x] = col;
            }
        }
        tex.SetPixels32(px);
        tex.Apply(false, true);
        if (held) pillHeld = tex; else pillRest = tex;
        return tex;
    }

    void RefreshStylesIfNeeded()
    {
        if (pillStyle != null && styleScreenWidth == Screen.width &&
            styleScreenHeight == Screen.height) return;
        styleScreenWidth = Screen.width;
        styleScreenHeight = Screen.height;

        var baseStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = (int)(17f + 14f * vmin() / 1080f),
            alignment = TextAnchor.MiddleCenter,
            clipping = TextClipping.Overflow,
        };
        pillStyle = new GUIStyle(baseStyle);
        faceStyle = new GUIStyle(baseStyle)
        {
            fontSize = Mathf.Max(10, (int)(Pad(OnScreenDevice.Jump).w * vmin() * 0.22f)),
        };
    }

    void DrawButton(int slot, GUIContent content, Color restTint, Color heldTint)
    {
        var rect = ButtonRect(slot);
        bool pressed = buttonFinger[slot] != -1;
        GUI.color = pressed ? heldTint : restTint;
        GUI.DrawTexture(rect, IsPill(slot) ? PillTexture(pressed) : CircleTexture(pressed));

        if (content.text.Length > 0)
        {
            var style = IsPill(slot) ? pillStyle : faceStyle;
            GUI.color = new Color(0f, 0f, 0f, 0.55f);
            GUI.Label(new Rect(rect.x, rect.y + 1.5f, rect.width, rect.height), content, style);
            GUI.color = Color.white;
            GUI.Label(rect, content, style);
        }
    }

    void OnGUI()
    {
        // The game's own IMGUI can leave a matrix behind; draw and hit-test
        // both use raw screen coordinates, so start from identity.
        GUI.matrix = Matrix4x4.identity;
        if (mode == PadMode.Hidden) return;
        RefreshStylesIfNeeded();

        var restTint = new Color(1f, 1f, 1f, 0.92f);
        var heldTint = Color.white;

        if (mode == PadMode.Inventory)
        {
            DrawButton((int)PadButton.Inventory, invExitContent, restTint, heldTint);
            DrawButton((int)PadButton.Map, invLeftContent, restTint, heldTint);
            DrawButton((int)PadButton.Throw, invRightContent, restTint, heldTint);
            GUI.color = Color.white;
            return;
        }

        var vminNow = vmin();
        var s = layout.stick;
        var ringR = s.r * vminNow;
        var restCentre = StickRestCentre();
        // Floating: the ring follows the finger. Pinned: it stays where it is
        // drawn and only the knob leans, so lifting a finger and putting it
        // back resumes from the same centre.
        var floated = stickFinger != -1 && s.mode != "pinned";
        var origin = floated ? stickOrigin : restCentre;

        GUI.color = stickFinger == -1 ? restTint : heldTint;
        GUI.DrawTexture(new Rect(origin.x - ringR, origin.y - ringR,
            ringR * 2f, ringR * 2f), CircleTexture(false));

        if (stickFinger != -1)
        {
            var knob = new Vector2(origin.x + stickKnob.x * KNOB_RANGE * ringR,
                                   origin.y - stickKnob.y * KNOB_RANGE * ringR);
            GUI.DrawTexture(new Rect(knob.x - ringR * 0.55f, knob.y - ringR * 0.55f,
                ringR * 1.1f, ringR * 1.1f), CircleTexture(true));
        }

        for (int b = 0; b < ButtonCount; b++)
            DrawButton(b, labelContent[b], restTint, heldTint);

        GUI.color = Color.white;
    }
}

// ── the layout file ─────────────────────────────────────────────────────────

// What pad-layout.json holds, and the rules for trusting it.
//
// Written by the launcher's pad editor, read here once per process. The file
// is the whole reason a key can be moved without rebuilding the game: these
// values are compiled INTO the player image otherwise, and an image is a
// ten-minute conversion away.
//
// JsonUtility rather than a hand-rolled parser: the schema is three flat lists
// of primitives, and a dependency-free reader that ignores unknown fields is
// exactly what it does.

[Serializable]
public class PadKeySpec
{
    public string id;                 // stable name, for a future editor merge
    public string target;             // InputControlType name, e.g. "Action2"
    public float x, y;                // centre, fractions of width / height from top
    public float w = 0.16f, h = 0.16f;// size, fractions of vmin
    public string shape = "circle";   // circle | pill
    public float scale = 1f;          // 0.5 .. 2
    public string label;

    /**
     * Supplies "up" while this key is held, for controls the game only accepts
     * with an aim direction (Skill / Tool throws a tool only that way). False
     * everywhere else: the same control without it casts the Silk Skill.
     */
    public bool aimUp;
}

[Serializable]
public class PadStickSpec
{
    public string mode = "floating";  // floating | pinned
    public float cx = 0.165f, cy = 0.70f;   // ring centre, from the left / top
    public float r = 0.125f;                // ring radius, of vmin
    // The touch zone, as corners rather than width/height from a corner: the
    // editor moves it as a rectangle, and a rectangle that can only grow from
    // one corner is half an editor.
    public float zoneX0 = 0f, zoneY0 = 0.30f, zoneX1 = 0.40f, zoneY1 = 1f;
}

[Serializable]
public class PadLayoutFile
{
    public int schema = 1;
    public PadStickSpec stick = new PadStickSpec();
    public PadKeySpec[] keys;

    public const string FileName = "pad-layout.json";

    /** Which of the two above answered, for the startup log. */
    public static string Source = "defaults";

    public static PadLayoutFile Load()
    {
        var path = Path.Combine(Application.persistentDataPath, FileName);
        try
        {
            if (!File.Exists(path))
            {
                Source = "defaults";
                return Defaults();
            }
            var text = File.ReadAllText(path);
            var parsed = JsonUtility.FromJson<PadLayoutFile>(text);
            if (parsed == null || !parsed.Valid())
            {
                Debug.LogWarning("[VPad] " + FileName + " does not describe a pad; using defaults");
                Source = "defaults (file rejected)";
                return Defaults();
            }
            parsed.Clamp();
            Source = "file";
            return parsed;
        }
        catch (Exception e)
        {
            Debug.LogWarning("[VPad] " + FileName + " ignored: " + e.Message);
            Source = "defaults (file unreadable)";
            return Defaults();
        }
    }

    /** Structure only: a file that fails this is not a layout at all. */
    bool Valid()
    {
        if (schema != 1) return false;
        if (keys == null || keys.Length != OnScreenDevice.ButtonCount) return false;
        for (int i = 0; i < keys.Length; i++)
            if (keys[i] == null) return false;
        return true;
    }

    /**
     * Values into a range the pad can survive.
     *
     * Clamped rather than rejected: a key dragged a little off screen by a
     * newer editor should still be tappable, and a scale past the limit should
     * be the limit rather than a button nobody can miss.
     */
    void Clamp()
    {
        if (stick == null) stick = new PadStickSpec();
        stick.mode = stick.mode == "pinned" ? "pinned" : "floating";
        stick.cx = Mathf.Clamp(stick.cx, 0f, 1f);
        stick.cy = Mathf.Clamp(stick.cy, 0f, 1f);
        stick.r = Mathf.Clamp(stick.r, 0.05f, 0.30f);
        stick.zoneX0 = Mathf.Clamp(stick.zoneX0, 0f, 0.95f);
        stick.zoneY0 = Mathf.Clamp(stick.zoneY0, 0f, 0.95f);
        stick.zoneX1 = Mathf.Clamp(stick.zoneX1, stick.zoneX0 + 0.05f, 1f);
        stick.zoneY1 = Mathf.Clamp(stick.zoneY1, stick.zoneY0 + 0.05f, 1f);
        for (int i = 0; i < keys.Length; i++)
        {
            var k = keys[i];
            k.x = Mathf.Clamp(k.x, -0.05f, 1.05f);
            k.y = Mathf.Clamp(k.y, -0.05f, 1.05f);
            k.w = Mathf.Clamp(k.w, 0.02f, 0.80f);
            k.h = Mathf.Clamp(k.h, 0.02f, 0.80f);
            k.scale = Mathf.Clamp(k.scale <= 0f ? 1f : k.scale, 0.5f, 2f);
            if (k.shape != "pill") k.shape = "circle";
        }
    }

    /**
     * The layout a device that has never opened the editor gets.
     *
     * The old constants, to the pixel, with two corrections that were asked
     * for: Map and Inventory sit above the stick's zone instead of inside it
     * (they were at 0.40 and 0.30 of the height, and the zone is the bottom
     * 70 % of the left 40 %), and Pause keeps a margin from the corner
     * instead of sitting 14 px from it.
     *
     * Computed from Screen because the old units mixed width, height and vmin;
     * on the landscape screens this game runs in, vmin is the height and the
     * conversion is exact.
     */
    public static PadLayoutFile Defaults()
    {
        var vmin = Mathf.Min(Screen.width, Screen.height);
        var wf = Screen.width / vmin;   // one vmin in width fractions
        var hf = Screen.height / vmin;  // one vmin in height fractions
        var off = 0.155f;               // diamond offset, in vmin
        var face = 0.16f;               // face diameter, in vmin
        const float clusterX = 0.845f;
        const float clusterY = 0.60f;

        PadKeySpec Key(int slot, string target, float x, float y, float w, float h,
            string shape, string label) => new PadKeySpec
        {
            id = label.ToLowerInvariant(),
            target = target,
            x = x, y = y, w = w, h = h,
            shape = shape,
            scale = 1f,
            label = label,
        };

        /** A key that supplies "up" while held: a tool throw needs an aim. */
        PadKeySpec ToolKey(int slot, string target, float x, float y, float w, float h,
            string shape, string label)
        {
            var spec = Key(slot, target, x, y, w, h, shape, label);
            spec.aimUp = true;
            return spec;
        }

        var keys = new PadKeySpec[OnScreenDevice.ButtonCount];        keys[OnScreenDevice.Jump] = Key(OnScreenDevice.Jump, "Action1",
            clusterX, clusterY + off / hf, face, face, "circle", "Jump");
        keys[OnScreenDevice.Cast] = Key(OnScreenDevice.Cast, "RightBumper",
            0.906f, 0.27f, 0.110f * wf, 0.090f * hf, "pill", "Skill");
        keys[OnScreenDevice.Attack] = Key(OnScreenDevice.Attack, "Action3",
            clusterX - off / wf, clusterY, face, face, "circle", "Attack");
        keys[OnScreenDevice.Heal] = Key(OnScreenDevice.Heal, "Action2",
            clusterX + off / wf, clusterY, face, face, "circle", "Bind");
        keys[OnScreenDevice.Dash] = Key(OnScreenDevice.Dash, "RightTrigger",
            clusterX, clusterY - off / hf, face, face, "circle", "Dash");
        keys[OnScreenDevice.Map] = Key(OnScreenDevice.Map, "LeftBumper",
            0.100f, 0.26f, 0.130f * wf, 0.075f * hf, "pill", "Map");
        keys[OnScreenDevice.Inventory] = Key(OnScreenDevice.Inventory, "Select",
            0.100f, 0.18f, 0.130f * wf, 0.075f * hf, "pill", "Inventory");
        keys[OnScreenDevice.Throw] = ToolKey(OnScreenDevice.Throw, "RightBumper",
            0.784f, 0.27f, 0.110f * wf, 0.090f * hf, "pill", "Tool");
        keys[OnScreenDevice.Pause] = Key(OnScreenDevice.Pause, "Start",
            0.940f, 0.10f, 0.055f * wf, 0.075f * hf, "pill", "II");

        return new PadLayoutFile
        {
            schema = 1,
            stick = new PadStickSpec
            {
                mode = "floating",
                cx = 0.165f,
                cy = 0.70f,
                r = 0.125f,
                zoneX0 = 0f,
                zoneY0 = 0.30f,
                zoneX1 = 0.40f,
                zoneY1 = 1f,
            },
            keys = keys,
        };
    }
}

#endif
