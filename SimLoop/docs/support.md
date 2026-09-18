# Reporting a bug

## Where

**GitHub Issues on [`Horizon-36596/SimLoop`](https://github.com/Horizon-36596/SimLoop/issues).**

That is the only channel. There is no Discord, no mailing list and no support address, and pretending
there is would waste your time. An issue is also the only route that leaves a record the next person
with the same problem can find.

## What to put in it

A simulator bug report is unusually easy to make complete, because the run is reproducible by
construction. Four things, and the first two matter most:

1. **The scenario**, or enough of it to run — the config values, the tick count, the time step. Numbers,
   not descriptions.
2. **What you expected and what you got**, as the actual assertion and the actual failure message.
3. **The SimLoop version**, from your build file, and your FTC SDK version.
4. **The RLOG**, if the failure is about what was logged rather than about a compile error.

If two runs of the same scenario disagree, say so explicitly and include both logs — that is a
determinism bug, which is a different and more serious thing than a wrong number.

## What is a bug and what is a boundary

Before filing, check [What it does not do](limits.md). Several reasonable-sounding requests — contact
physics, vision, a field model, tuning — are deliberate boundaries rather than gaps, and the page says why
for each. A request to cross one is still worth making; it is just a different conversation from "this is
broken".

## Contributing a fix

The module is built to be read: every public class, method and field carries javadoc with units and frame
on every number, and that is enforced — `./gradlew :SimLoop:apiDocs` runs with doclint on and warnings
fatal, so a new public method without a comment fails the build.

Run the tests before you propose anything:

```powershell
./gradlew :SimLoop:testDebugUnitTest
```

(Run it from the repository root. The `:SimLoop` prefix is always correct: the library is a Gradle
subproject and never the root project.)
