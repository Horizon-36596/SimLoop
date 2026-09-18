# `loop`

`org.horizon36596.simloop.loop`

Turning a log into a number, and the automated edit-and-replay iteration built on top of that.

## What you use

Most teams need the first two rows. The rest is the iteration engine.

| Type | What it is |
|---|---|
| `RunResult` | A finished run, read back out of its RLOG. `finalValue`, `maxValue`, `minValue`, `meanValue`, `hasKey`, `isComplete`. |
| `Objective` | What "better" means for a scenario — one number, oriented so bigger is better. |
| `Guardrail`, `EnvelopeGuardrails` | What may not get worse. Each returns holds, violated, over-budget or **unknown**. |
| `Scorer` | Objective minus penalties, with the full per-guardrail breakdown attached. |
| `Gate` | The acceptance clauses a candidate has to satisfy before it counts. |
| `LoopDriver` | Offers candidates, keeps the best, issues milestones. |
| `Milestone` | What changed and why this candidate was better, as readable text. |
| `StopReport` | The end of a run of the loop — and it refuses to report a score that did not reproduce. |

## Reading a run

```java
RunResult run = RunResult.fromRlog("SlideExample", rlogPath, 150);

run.isComplete();                                   // a frame per tick?
run.finalValue("RealOutputs/Slide/positionInches"); // last frame's value
run.maxValue("RealOutputs/Slide/positionInches");   // worst case over the run
```

**The key needs its `RealOutputs/` prefix.** Ask for a key nothing logged and you get a
`MetricNotFoundException`, never a zero — a silent zero is a passing test that measured nothing.

## `UNKNOWN` is a real answer, and it is the important one

A guardrail that cannot check itself from a run returns `UNKNOWN` with a reason, rather than passing. An
envelope with four unknowns in it is guarding much less than it appears to, so read
`Scorer.Score.unknowns()` before you trust a score. Today `loopTimeUnderBudget` is always unknown,
because no scenario logs a loop time yet.

## It will not report a score that did not reproduce

`StopReport.reportedScore()` throws if the best candidate failed to reproduce, rather than handing back a
best-effort number. The one mistake the class exists to prevent is printing a score the second run did not
agree with. The number that failed is still available, named `unreproducedScoreForDiagnosis()`, so a
determinism failure can say *which* number did not come back.

## Units and frames

- Scores: the objective's own units, always oriented **bigger is better**.
- A violated guardrail contributes an **infinite** penalty, which is what makes a violating run ineligible
  no matter how good its objective was.
- `Milestone.improvement()`: the rise in `Scorer.Score.total()` over the previous best, same units.

## What this package will not do

- **It will not decide what to change.** The loop scores, gates and records; the edit comes from you or
  from an agent driving it.
- **It will not tune gains, search a parameter space, or optimize anything.**
- **It will not read anything but a log.** Facts no log carries — tests are green, a review passed — are
  handed in explicitly as `Gate.SuppliedFacts`, so they are visible rather than assumed.
- **It will not compare across scenarios.** Two scores are only comparable if they measure the same
  objective over the same scenario; `Score.isComparableTo` checks, because "the number went up" after
  quietly switching scenarios is an improvement that is not one.
