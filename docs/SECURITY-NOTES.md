# Security / publication notes

Scan performed 2026-11-14 before making this fork public.

## What was checked

- Tracked files (`git ls-files`, 200+ entries) for passwords, API keys, OAuth
  secrets, tokens, private keys, keystores and local machine paths.
- Full git history (`git log --all -p`) for common token/key patterns
  (`ghp_`, `github_pat_`, `AKIA…`, `BEGIN … PRIVATE KEY`, bearer tokens,
  refresh tokens) and for the user's GOG account id.
- Untracked/ignored trees: `ref/` (decompiled game code), `build/`, Gradle
  intermediates, `.env` files and keystores.

## Findings

1. **GOG public Galaxy client credential.** `GogCloud.kt` contains the GOG
   Galaxy OAuth client id and client secret, and they are present in the base
   commit's history. These are the **public Galaxy client credentials** used by
   open-source GOG tools (the same values circulate in `gogdl`); they are not
   this user's personal credential and cannot be removed without breaking GOG
   sign-in. They will be visible in the source and in the APK. Decide whether
   that is acceptable before publishing; if GitHub secret scanning flags them,
   that is expected.
2. **No personal credentials tracked.** No GOG account id, refresh token,
   access token, GitHub token, private key, `.env` file or keystore is tracked.
   Tokens are created at runtime and stored on the device
   (`files/gog_tokens.json`), never in the repo.
3. **Game data is not tracked.** `ref/` (decompiled `Assembly-CSharp.dll`,
   depot copies, screenshots) and `build/` are ignored by `.gitignore`; `git
   ls-files ref` and `git ls-files build` are empty. Do not force-add them:
   publishing the game's decompiled/managed files would be a copyright problem
   independent of secret scanning.
4. **Build outputs are untracked.** `tools/*/bin` and `tools/*/obj` exist only
   in the working tree; their `sourcelink.json` points at the repo URL, not at
   local paths.
5. **Debug signing.** The release APK is signed with the Docker build's debug
   keystore (`KS_PASS=android`), which is standard for sideload builds and not a
   secret. It does mean updates must be installed over the same signature; a
   build signed differently needs an uninstall first.

## Recommendation

The only real decision is the GOG Galaxy client credential in (1). It is
appropriate to publish if the project is comfortable using GOG's public client
credentials (as other open-source GOG tools do). Otherwise remove the GOG
feature from the public build rather than trying to hide the value — it is in
the APK either way.
