# Phase 1 — Weighted-Fair, Governed Scheduler

Upgrades `AIScheduler` from simple priority admission to a fair, device-aware
scheduler (arch-doc **Part 8**). One warm context ⇒ one generation at a time; the
scheduler decides *who runs next* when the engine frees.

## Effective score

When the engine frees, the next job is the eligible waiter with the highest score:

```
score = priority.base + aging - fairnessPenalty
```

- **priority.base** — FOREGROUND 1000 · INTERACTIVE 700 · BATCH 300 · BACKGROUND 100.
- **aging** — `waitedSec * 150`. Rises with wait time so nothing starves.
- **fairnessPenalty** — `recentUsageShare * 400`. An app's recent engine time is
  tracked with a 20 s half-life decay; a recent hog is penalised so two apps at the
  same priority get a fair share instead of one stampeding. Anonymous/internal work
  (appId `""`) has no penalty.

Per-app identity is the caller passed from `EdgeLMService` (already resolved from the
Binder UID), so fairness is per real app.

## Governor (battery + thermal clamp)

`DeviceGovernor.snapshot()` reads thermal status (PowerManager 0..6 → NORMAL/WARN/
SEVERE), charging state, battery level, and power-save mode. The scheduler consults it:

- **deferBackground** (WARN, low battery, or power-save): BATCH/BACKGROUND waiters are
  held behind *any* interactive/foreground waiter.
- **blockBackground** (SEVERE thermal): background admission is refused outright —
  `withEngine` throws `DeferredException`, surfaced to the app as a soft `onError`
  ("deferred: device is too hot…"). Foreground work is never blocked.

## Preemption — status

Still **non-preemptive**: a running generation completes before the next is admitted.
Honest and correct for a single context; generations are short so head-of-line
blocking is bounded.

The seam for real preemption is in place: the scheduler tracks the running job's
priority and hands each block a cooperative `Preemption` signal (`shouldYield()`).
An engine that can pause/resume a decode — i.e. the continuous-batching upgrade that
rides on paged-KV (`PHASE1-KV-POOLING.md`) — can poll it to yield a background decode
to an arriving foreground request. Until then the arg is unused (documented at the
call site).

## Files

- `AIScheduler.kt` — WFQ scoring, per-app decayed usage, governor hook, preemption seam.
- `DeviceGovernor.kt` — thermal/battery/power-save snapshot.
- `EdgeLMService.kt` — constructs the scheduler with the governor; passes the caller as
  the fairness appId.

## Needs on-device verification

- Fairness: two apps hammering at INTERACTIVE should interleave, not one-then-the-other.
- Governor: force thermal (or use `cmd thermalservice`) and confirm background/HTTP
  requests defer while a foreground request still runs.
- Back-compat: internal load/unload paths (FOREGROUND) are unaffected.
