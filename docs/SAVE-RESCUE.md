# Rescuing saves around a reinstall

The game writes to `<persistentDataPath>/default/`, which on Android is the app's
**external files dir**: `/sdcard/Android/data/com.jakobkhansen.silksong/files/default/`.
An uninstall deletes it, and `pm uninstall` does not even raise the
`hasFragileUserData` prompt (`hasFragileUserData` only matters for an uninstall
from the phone's own UI). So a build signed with a different key — which forces
an uninstall — takes the saves with it unless they are copied out first.

Order matters: **copy out → uninstall/install → copy back.**

The launcher already snapshots saves before and after every session into
`/sdcard/Games/silksong-saves-backup/<yyyyMMdd-HHmmss>/`, and Settings →
*Restore saves from backup* puts one back. This document is for the case where
that is not available (an older build, a different app, or nobody able to open
the launcher).

---

## Prompt for a local agent

Copy everything between the markers into the agent, then say **save** or
**restore** and give it the device address.

```text
You are helping me move the save files of an Android game out of a phone and
back, so they survive an uninstall. Work only through adb. Never uninstall,
never delete a file, and never touch another app.

FACTS
- Package: com.jakobkhansen.silksong
- Saves live in the app's external files dir:
  /sdcard/Android/data/com.jakobkhansen.silksong/files/default/
  The files are: user1.dat, user1.dat.bak1, user1.dat.bak2, shared.dat,
  shared.dat.bak, user1_<version>.dat, and the folders Restore_Points1/ and
  Restore_Points2/. There may be more; copy everything in that directory.
- On Android 11+ adb cannot read /sdcard/Android/data/<pkg> directly. Use
  `run-as`, which works only while the installed app is debuggable
  (dev builds are; a release build is not).
- A second copy may exist at /sdcard/Games/silksong-saves-backup/<timestamp>/
  — that is the launcher's own snapshot, readable without run-as.
- The app may be running. Force-stopping it is allowed only when I say so:
  ask first, telling me what it will interrupt.

TASKS

1. SAVE (copy out)
   a. Confirm the device: `adb devices`. If it is not listed, ask me for the
      wireless-debugging address (`adb connect host:port`).
   b. List what is there:
      `adb shell run-as com.jakobkhansen.silksong ls -l files/default`
   c. If that fails with "not debuggable" or "package not found":
      - print the exact error,
      - try `adb shell ls /sdcard/Games/silksong-saves-backup`,
      - and stop, telling me which fallback exists. Do not invent another path.
   d. Otherwise copy the whole directory out to
      `~/silksong-saves/<yyyy-mm-dd-HHMM>/` on this computer:
      `adb exec-out run-as com.jakobkhansen.silksong tar -cf - files/default | tar -xf -`
      If tar is missing on the device, copy file by file with
      `adb exec-out run-as com.jakobkhansen.silksong cat files/default/<name> > <name>`.
   e. Verify: list both sides with sizes, and print a sha256 of every .dat file
      from both. If any differs, say so loudly — a partial backup is worse than
      none because it looks like one.
   f. Tell me where the copy is and how big it is.

2. RESTORE (copy back)
   a. Ask me to confirm which copy to restore, and confirm the game is not
      running. If it is, ask whether to force-stop it; wait for my answer.
   b. `adb shell am force-stop com.jakobkhansen.silksong` (only with my yes).
   c. Make sure the directory exists:
      `adb shell run-as com.jakobkhansen.silksong mkdir -p files/default`
   d. Push each file through /data/local/tmp, which run-as can read:
      `adb push <file> /data/local/tmp/<file>`
      `adb shell run-as com.jakobkhansen.silksong cp /data/local/tmp/<file> files/default/<file>`
   e. Remove stale write-through temps that the game leaves behind and that
      would be promoted over the real save on the next write:
      `adb shell run-as com.jakobkhansen.silksong rm -f files/default/*.dat.new`
   f. Verify with `ls -l files/default` and sha256 on both sides, as in 1e.
   g. Tell me to launch the game and check the slot's play time.

3. BEFORE ANY UNINSTALL
   Do not do it. Print this checklist and wait:
   - step 1 finished and verified,
   - the copy is outside the phone as well as on it,
   - the new APK is on this computer and installs,
   - I confirm the version I am replacing.

REPORTING
Every step: print the command, then its output. If a command fails, show the
error verbatim and stop — do not try five alternatives. At the end, one short
summary: what was copied, where it is, sizes, and what is still to do.
```

---

## Doing it by hand

```sh
# out
adb shell run-as com.jakobkhansen.silksong ls -l files/default
adb exec-out run-as com.jakobkhansen.silksong tar -cf - files/default | tar -xf - -C ~/silksong-saves

# in (game closed)
adb shell am force-stop com.jakobkhansen.silksong
adb push ~/silksong-saves/files/default/user1.dat /data/local/tmp/
adb shell run-as com.jakobkhansen.silksong cp /data/local/tmp/user1.dat files/default/user1.dat
adb shell run-as com.jakobkhansen.silksong rm -f files/default/*.dat.new
```

`run-as` is the whole trick: it gives you the app's own uid, so the file
permissions and SELinux labels stay correct. Copying *into* the directory as
the shell user leaves a file the game cannot read, which looks exactly like a
corrupt save.
