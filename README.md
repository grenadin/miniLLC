# miniLLC

A small Android mini-project built to test a hypothesis about how [microG](https://microg.org/) GmsCore's `LastLocationCapsule` handles location granularity.

This is **not** a general-purpose location app. It exists to reproduce one specific behavior side by side, so the question can be shown rather than just described.

## Background

While reading through microG's `GmsCore` source (`LastLocationCapsule.kt`), one line stood out:

```kotlin
fun getLocation(effectiveGranularity: @Granularity Int, maxUpdateAgeMillis: Long = Long.MAX_VALUE): Location? {
    val location = when (effectiveGranularity) {
        GRANULARITY_COARSE -> lastCoarseLocationTimeCoarsed
        GRANULARITY_FINE -> lastCoarseLocation   // <- this
        else -> return null
    } ?: return null
    ...
}
```

`lastCoarseLocation` is updated by **both** GPS and network fixes (whichever arrives most recently wins). `lastFineLocation` — which is written on every GPS fix and never overwritten by network — is never read anywhere in `getLocation()`.

The hypothesis: when an app requests `GRANULARITY_FINE`, it can receive a network-derived location instead of the last GPS fix, because the fine/coarse split that already exists in the data model isn't being used on the read path.

## What this app does

`miniLLC` reimplements the same fine/coarse split as a minimal standalone class (`MiniLastLocationCapsule.kt`), fed by the same GPS/network location callbacks a real Android app would receive. It renders two maps side by side, each labeled by the variable it reads from:

- **`lastCoarseLocation` panel** — mirrors the current GmsCore behavior: `GRANULARITY_FINE -> lastCoarseLocation`
- **`lastFineLocation` panel** — the proposed alternative: `GRANULARITY_FINE -> lastFineLocation`

Both panels update from the same location stream in real time, so any divergence between them is visible immediately — for example, a brief jump on the `lastCoarseLocation` panel when a network fix arrives, with no corresponding jump on the `lastFineLocation` panel.

The app also includes a field-test recording mode: start a recording, walk/drive around, stop, and export a paired log (`lastCoarseLocation [...]` / `lastFineLocation [...]`) for later analysis.

## What this app does *not* prove

This is a reproduction of the *mechanism*, not a patched build of GmsCore itself. It doesn't account for the additional logic in the real `LastLocationCapsule` (time-coarsing, cliffs, persistence to disk), and it hasn't been tested for side effects a real fix might have elsewhere in GmsCore. It's meant to make the question concrete, not to claim the fix is safe or that the current behavior is a bug rather than a deliberate design choice.

## Project structure

```
app/src/main/java/org/microg/locationtest/
├── MainActivity.kt              — location handling, maps, recording, UI
├── MiniLastLocationCapsule.kt   — mirrors GmsCore's fine/coarse split
├── CodeSnippets.kt              — the two code paths shown side by side in-app
├── MapSync.kt                   — keeps the two maps in sync (pan/zoom)
└── PulseOverlay.kt              — ripple animation for network-derived fixes
```

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. Built with Gradle 8.11.1, AGP 8.2.2, Kotlin 1.9.22.

## Related

- microG GmsCore: https://github.com/microg/GmsCore
- Relevant code: [`LastLocationCapsule.kt`](https://github.com/microg/GmsCore/blob/master/play-services-location/core/src/main/kotlin/org/microg/gms/location/manager/LastLocationCapsule.kt)
