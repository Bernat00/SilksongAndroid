// ControllerAliases — binding the menu buttons the game's mappings never do.
//
// Silksong's InputHandler.MapControllerButtons binds its screen actions by
// hand, per gamepad type, from exactly two InControl controls:
//
//     inputActions.Pause         <- InputControlType.Start
//     inputActions.OpenInventory <- InputControlType.Select
//
// What the physical buttons arrive as is decided by the device profile of
// whichever controller is connected, and on Android the Xbox-style profiles
// name those same two physical buttons Menu and View -- different controls
// entirely (106 and 109, against the game's 101 and 102). An Xbox Wireless
// Controller is the reported case: the game's own
// XboxOneBluetoothAndroidUnityProfile matches its name, maps Menu to button
// 10 and View to button 11, and InControl therefore reports every press --
// which nothing listens to. Pause and inventory are dead while every
// gameplay control works, and no remapping screen can help, because a
// mapper can only offer the controls a controller is seen to have.
//
// PlayStation pads have the same hole by another door (#43): on Android a
// DualSense's Options and Create reach InControl as the Command controls,
// LeftCommand and RightCommand, which the game also binds nothing to.
//
// So both styles get ADDITIONAL bindings on the actions that need them:
//
//     Pause          <- Menu, RightCommand   (Options on a DualSense)
//     OpenInventory  <- View, LeftCommand    (Share/Create/Back on a DualSense)
//
// Inert by construction where they do not apply: a DeviceBindingSource for a
// control the active device does not expose matches nothing, so a controller
// that reports Start and Select the usual way is left exactly as the game
// mapped it.
//
// Checked and healed every second instead of woven into MapControllerButtons'
// return. The game clears everything with HeroActions.Reset at the start of
// that method and re-adds its own at once, so the worst this repair can be
// is up to a second behind it -- the same cadence InControl's own device
// refresh keeps -- and it is idempotent, so a reconnect or a remap cannot
// accumulate duplicates. A binding that comes back late is a binding that
// comes back.

using InControl;
using UnityEngine;

public class ControllerAliases : MonoBehaviour
{
    /** How often the aliases are checked against the game's mapping. */
    const float PERIOD = 1f;

    float _untilNextCheck;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        var go = new GameObject("ControllerAliases");
        DontDestroyOnLoad(go);
        go.AddComponent<ControllerAliases>();
    }

    void Update()
    {
        _untilNextCheck -= Time.deltaTime;
        if (_untilNextCheck > 0f) return;
        _untilNextCheck = PERIOD;
        Tick();
    }

    void Tick()
    {
        // The static field, not the instance property: the getter logs an
        // error to logcat once a second while it cannot find a manager --
        // the first fifteen seconds of every game session -- and reads fine
        // once one exists. The field is exactly what the property caches.
        var gm = GameManager._instance;
        if (gm == null) return;
        var handler = gm.inputHandler;
        if (handler == null) return;
        var actions = handler.inputActions;
        if (actions == null) return;

        Ensure(actions.Pause, "Pause", InputControlType.Menu, InputControlType.RightCommand);
        Ensure(actions.OpenInventory, "OpenInventory", InputControlType.View, InputControlType.LeftCommand);
    }

    /**
     * Adds one alias binding per control, once, and says so when it did.
     *
     * The presence check is a scan of UnfilteredBindings rather than a
     * HasBinding call, because what is wanted here is "is THIS control
     * already among the bindings", which a scan of the source's control
     * answers directly.
     */
    static void Ensure(PlayerAction action, string name, params InputControlType[] targets)
    {
        foreach (var target in targets)
        {
            if (AliasPresent(action, target)) continue;
            if (action.AddBinding(new DeviceBindingSource(target)))
            {
                Debug.Log($"[ControllerAliases] {name} <- {target}");
            }
        }
    }

    static bool AliasPresent(PlayerAction action, InputControlType target)
    {
        var bindings = action.UnfilteredBindings;
        for (int i = 0; i < bindings.Count; i++)
        {
            if (bindings[i] is DeviceBindingSource source && source.Control == target) return true;
        }
        return false;
    }
}
