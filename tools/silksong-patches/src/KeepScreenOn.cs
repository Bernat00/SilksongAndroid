// KeepScreenOn — the screen stops dimming out from under controller-held
// sessions.
//
// SetupActivity holds FLAG_KEEP_SCREEN_ON for as long as the BUILD runs,
// because the likeliest way for an unattended build to die is a screen
// that sleeps. The same argument applies with more force to the game
// itself: the player holding an Xbox pad is not touching the phone's
// touch surface, Android's idle timer runs regardless, and losing the
// session to a blanked screen is a worse trade than any battery the
// always-on panel could cost. Games that take controller input are
// expected to hold this flag; it costs no permission.
//
// The flag belongs to the WINDOW, and the game's window belongs to its
// own Activity in the game process, which is where SilksongPatches runs.
// Unity hands that Activity over as UnityPlayer.currentActivity; adding
// the flag through it on the UI thread is one JNI hop and done once.
// There is no per-frame or per-second component in this file: after the
// flag is set it stays set for the life of the window, the way the
// engine's own immersive mode does.
//
// Toggle: keep_screen_on in the launcher's exported settings file,
// default TRUE -- the flag is the right default for a controller game
// and the setting exists to turn it off for people who prefer their
// screen to sleep while docked to a TV. Takes effect on the next game
// launch, like every setting in that file.

using System;
using UnityEngine;

public class KeepScreenOn : MonoBehaviour
{
    /** WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON. */
    const int FLAG_KEEP_SCREEN_ON = 0x00000080;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        if (!SilksongPatches.Settings.GetBool("keep_screen_on", true)) return;
        var go = new GameObject("__KeepScreenOn__");
        DontDestroyOnLoad(go);
        go.AddComponent<KeepScreenOn>();
    }

    void Awake()
    {
        try
        {
            var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
            if (activity == null)
            {
                Debug.Log("[KeepScreenOn] no current activity; the flag was not set");
                return;
            }
            activity.Call("runOnUiThread", new AndroidJavaRunnable(() =>
            {
                try
                {
                    using var window = activity.Call<AndroidJavaObject>("getWindow");
                    window?.Call("addFlags", FLAG_KEEP_SCREEN_ON);
                    Debug.Log("[KeepScreenOn] FLAG_KEEP_SCREEN_ON set on the game window");
                }
                catch (Exception e)
                {
                    Debug.Log($"[KeepScreenOn] could not set the flag: {e.Message}");
                }
            }));
        }
        catch (Exception e)
        {
            Debug.Log($"[KeepScreenOn] unavailable: {e.Message}");
        }
    }
}
