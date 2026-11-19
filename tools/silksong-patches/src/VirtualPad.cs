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
using UnityEngine;

// ── the device ─────────────────────────────────────────────────────────────

public class OnScreenDevice : InputDevice
{
    public const int ButtonCount = 9;
    public const int Jump = 0, Cast = 1, Attack = 2, Heal = 3,
                     Dash = 4, Map = 5, Inventory = 6, Throw = 7, Pause = 8;

    readonly bool[] buttons = new bool[ButtonCount];
    Vector2 stick;

    public OnScreenDevice() : base("On-Screen Controls")
    {
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
        AddControl(InputControlType.Action1, "Jump");
        AddControl(InputControlType.Action2, "Cast");
        AddControl(InputControlType.Action3, "Attack");
        AddControl(InputControlType.RightTrigger, "Dash", 0.2f, 0.9f);
        AddControl(InputControlType.LeftBumper, "Map");
        AddControl(InputControlType.RightBumper, "Quick Cast");
        AddControl(InputControlType.RightStickButton, "Taunt");
        AddControl(InputControlType.Select, "Inventory"); // not a "standard" target
        AddControl(InputControlType.Start, "Pause");
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
        UpdateWithState(InputControlType.Action1, buttons[Jump], updateTick, deltaTime);
        // Heal is the same game action as Cast (hold to heal); two buttons
        // reach it, so OR them into the one control.
        UpdateWithState(InputControlType.Action2, buttons[Cast] || buttons[Heal], updateTick, deltaTime);
        UpdateWithState(InputControlType.Action3, buttons[Attack], updateTick, deltaTime);
        UpdateWithState(InputControlType.RightTrigger, buttons[Dash], updateTick, deltaTime);
        UpdateWithState(InputControlType.LeftBumper, buttons[Map], updateTick, deltaTime);
        UpdateWithState(InputControlType.Select, buttons[Inventory], updateTick, deltaTime);
        // Tools (the thrown needles/pins) are thrown with QuickCast, not Cast.
        UpdateWithState(InputControlType.RightBumper, buttons[Throw], updateTick, deltaTime);
        UpdateWithState(InputControlType.Start, buttons[Pause], updateTick, deltaTime);
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

    // ── layout, as fractions of the screen ──────────────────────────────────

    const float CLUSTER_CX = 0.845f;   // face diamond centre, fraction of width
    const float CLUSTER_CY = 0.60f;    // fraction of height, from TOP (Gui Y)
    const float DIAMOND_R = 0.155f;    // face centre offset, of vmin
    const float FACE_R = 0.080f;       // face hit/draw radius, of vmin

    // Throw/Cast pair above the diamond: 257 px each on a 2340 px screen,
    // 28 px gap, centred on the diamond. Fits inside its 496 px face span
    // with a small overhang.
    const float CAST_THROW_W = 0.110f; // of width
    const float CAST_THROW_H = 0.090f; // of vmin
    const float CAST_THROW_CY = 0.27f; // of vmin, from top
    const float THROW_CX = 0.784f;
    const float CAST_CX = 0.906f;

    // Map/Inventory: small pills stacked under the health HUD, left side.
    const float MAP_INV_W = 0.130f;
    const float MAP_INV_H = 0.075f;
    const float MAP_INV_CX = 0.100f;
    const float INV_CY = 0.30f;
    const float MAP_CY = 0.40f;

    const float PILL_PAUSE_W = 0.055f; // pause, actually in the top-right corner
    const float PILL_PAUSE_H = 0.075f;
    const float PILL_PAUSE_CX = 0.965f;
    const float PILL_PAUSE_CY = 0.050f;

    // Inventory overlay: exit in the top-right, pane arrows in the bottom corners.
    const float INV_W = 0.10f;
    const float INV_H = 0.075f;
    const float INV_EXIT_CX = 0.93f;
    const float INV_EXIT_CY = 0.085f;
    const float INV_LEFT_CX = 0.09f;
    const float INV_RIGHT_CX = 0.91f;
    const float INV_ARROW_CY = 0.92f;
    const float STICK_ZONE_W = 0.40f;  // left thumb zone
    const float STICK_ZONE_H = 0.70f;  // of height, from top
    const float STICK_RING_R = 0.125f; // resting ring radius, of vmin
    const float STICK_REST_X = 0.165f; // resting ring centre, of width
    const float STICK_REST_Y = 0.30f;  // of height, from bottom
    const float KNOB_RANGE = 0.65f;    // knob travel, of ring radius

    static readonly InputControlType[] Targets =
    {
        InputControlType.Action1,       // Jump
        InputControlType.Action2,       // Cast
        InputControlType.Action3,       // Attack
        InputControlType.Action2,       // Heal (same action as Cast)
        InputControlType.RightTrigger,  // Dash
        InputControlType.LeftBumper,    // Map
        InputControlType.Select,        // Inventory
        InputControlType.RightBumper,   // Throw (tools / pins)
        InputControlType.Start,         // Pause / back
    };

    static readonly string[] FallbackLabels =
    {
        "Jump", "Cast", "Attack", "Heal", "Dash",
        "Map", "Inventory", "Throw", "II",
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
        device = new OnScreenDevice();
        invExitContent = new GUIContent("Exit");
        invLeftContent = new GUIContent("<");
        invRightContent = new GUIContent(">");
        for (int i = 0; i < ButtonCount; i++)
        {
            labels[i] = FallbackLabels[i];
            labelContent[i] = new GUIContent(labels[i]);
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

            if (stickFinger == -1 && InStickZone(gui))
            {
                stickFinger = t.fingerId;
                stickOrigin = gui;
                UpdateKnob(gui);
                continue;
            }
            for (int b = 0; b < ButtonCount; b++)
            {
                if (buttonFinger[b] != -1) continue;
                if (Hit(b, gui))
                {
                    buttonFinger[b] = t.fingerId;
                    break;
                }
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
        var max = KNOB_RANGE * STICK_RING_R * vmin();
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

    // ── geometry: one source for draw and hit-test ─────────────────────────

    static float vmin() => Mathf.Min(Screen.width, Screen.height);

    static bool IsPill(int slot) => slot == (int)PadButton.Cast || slot >= (int)PadButton.Map;

    Vector2 ButtonCentre(int slot)
    {
        var cx = CLUSTER_CX * Screen.width;
        var cy = CLUSTER_CY * Screen.height;
        var off = DIAMOND_R * vmin();
        switch ((PadButton)slot)
        {
            case PadButton.Dash: return new Vector2(cx, cy - off);          // top
            case PadButton.Attack: return new Vector2(cx - off, cy);        // left
            case PadButton.Jump: return new Vector2(cx, cy + off);          // bottom
            case PadButton.Heal: return new Vector2(cx + off, cy);          // right
            case PadButton.Throw: return new Vector2(THROW_CX * Screen.width, CAST_THROW_CY * vmin());
            case PadButton.Cast: return new Vector2(CAST_CX * Screen.width, CAST_THROW_CY * vmin());
            case PadButton.Inventory: return new Vector2(MAP_INV_CX * Screen.width, INV_CY * vmin());
            case PadButton.Map: return new Vector2(MAP_INV_CX * Screen.width, MAP_CY * vmin());
            case PadButton.Pause: return new Vector2(PILL_PAUSE_CX * Screen.width, PILL_PAUSE_CY * vmin());
            default: return Vector2.zero;
        }
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
        var c = ButtonCentre(slot);
        if (slot == (int)PadButton.Pause)
            return Centered(c.x, c.y, PILL_PAUSE_W * Screen.width, PILL_PAUSE_H * vmin());
        if (slot == (int)PadButton.Cast || slot == (int)PadButton.Throw)
            return Centered(c.x, c.y, CAST_THROW_W * Screen.width, CAST_THROW_H * vmin());
        if (slot == (int)PadButton.Map || slot == (int)PadButton.Inventory)
            return Centered(c.x, c.y, MAP_INV_W * Screen.width, MAP_INV_H * vmin());
        var r = FACE_R * vmin();
        return new Rect(c.x - r, c.y - r, r * 2f, r * 2f);
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
        LabelFor(actions.QuickCast, "Throw");
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

    static int SlotForControl(InputControlType control)
    {
        for (int i = 0; i < Targets.Length; i++)
            if (Targets[i] == control) return i;
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
            fontSize = Mathf.Max(10, (int)(FACE_R * vmin() * 2f * 0.22f)),
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

        // Resting ring marks the stick zone even untouched.
        if (stickFinger == -1)
        {
            var restCentre = new Vector2(STICK_REST_X * Screen.width,
                Screen.height - STICK_REST_Y * Screen.height);
            GUI.color = restTint;
            GUI.DrawTexture(new Rect(restCentre.x - STICK_RING_R * vminNow, restCentre.y - STICK_RING_R * vminNow,
                STICK_RING_R * 2f * vminNow, STICK_RING_R * 2f * vminNow), CircleTexture(false));
        }
        else
        {
            var origin = stickOrigin;
            GUI.color = heldTint;
            GUI.DrawTexture(new Rect(origin.x - STICK_RING_R * vminNow, origin.y - STICK_RING_R * vminNow,
                STICK_RING_R * 2f * vminNow, STICK_RING_R * 2f * vminNow), CircleTexture(false));
            var knob = new Vector2(origin.x + stickKnob.x * KNOB_RANGE * STICK_RING_R * vminNow,
                                   origin.y - stickKnob.y * KNOB_RANGE * STICK_RING_R * vminNow);
            GUI.DrawTexture(new Rect(knob.x - STICK_RING_R * 0.55f * vminNow, knob.y - STICK_RING_R * 0.55f * vminNow,
                STICK_RING_R * 1.1f * vminNow, STICK_RING_R * 1.1f * vminNow), CircleTexture(true));
        }

        for (int b = 0; b < ButtonCount; b++)
            DrawButton(b, labelContent[b], restTint, heldTint);

        GUI.color = Color.white;
    }
}

#endif
