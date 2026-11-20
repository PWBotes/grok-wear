# micfix

This branch holds OPPO Watch X fixes on top of upstream Grok Wear.

- Mic source fallback: `VOICE_RECOGNITION` > `MIC` > `VOICE_COMMUNICATION`, without call mode or AEC on non-call sources.
- Adaptive software mic gain, plus a Mic boost setting.
- LoudnessEnhancer voice boost, plus a Voice volume setting.
- Keep-screen-on-during-calls setting.
- Level-only diagnostic logging under the tag `GrokWearMic`.
- White Grok Bot launcher icon.
- `versionName` `0.7.6-micfix7` / `versionCode` `50`.

Signing keys are not in this repo.

## Updating from upstream

On GitHub, use **Sync fork** on `main`. Then locally:

```
git checkout micfix && git rebase main
```

Merging `main` into `micfix` is fine too. Resolve conflicts, then bump `versionName`.
