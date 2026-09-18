# SimLoop

**Run your FTC robot code on a laptop, deterministically, and get a log you can score.**

Your `Robot.init()`, your subsystems and your OpMode run unchanged against fake hardware in a JVM unit
test. Time comes from a fake clock rather than the wall, so the same run produces the same log every
time. The log is a PsiKit RLOG, which AdvantageScope opens and which SimLoop's own `loop` package can
score automatically.

No robot, no Driver Station, no emulator. A test run is a few seconds.

!!! warning "Status: beta, and not yet released"
    The API is settled enough to use and not settled enough to promise. No version has been tagged, so
    **the install coordinate on the next page does not resolve for anybody yet**. That page says plainly
    which parts are verified and which are written ahead of the release that makes them true.

## The fastest way in

Copy one folder into your FTC project and add one line to `TeamCode/build.gradle`:

```groovy
apply from: "$rootDir/simloop-starter/simloop.gradle"
```

That folder carries every build setting SimLoop needs and a small example robot that drives itself, so
`./gradlew :TeamCode:testDebugUnitTest` passes two simulated tests immediately. You never clone this
repository. [Installing it](getting-started.md) has the three steps, and then says exactly what the
folder is doing if you would rather wire it by hand.

## Who this is for

Two readers, and the site is shaped for both.

**A team that has written robot code but never a simulator.** You know what a `DcMotorEx` is and what a
subsystem does. You do not need convincing that testing is good; you need the twenty lines of Gradle that
make it work and a first test that passes. Start at [Installing it](getting-started.md), then
[Your first simulated test](first-test.md).

**An AI agent writing code against this library.** Every page states units and frame for every number,
because those cannot be inferred from a signature. Every package page ends with what that package will
not do, because the expensive failure is not a wrong call — it is confidently writing against a feature
that does not exist. [What it does not do](limits.md) is the page that matters most for that, and
[Index for AI agents](llms.md) is a flat list of every page and what is on it.

## The idea in one paragraph

A real robot loop reads hardware, decides, and writes hardware, fifty times a second. SimLoop replaces
only the hardware: `FakeMotor` really is a `DcMotorEx`, `FakeHardwareMap` really is a `HardwareMap`, so
your code cannot tell the difference and does not have to be modified to be tested. Behind each fake sits
a **plant** — a small, parameterized model of how that piece of the robot actually moves. Your season's
numbers reach those plants through the `config` interfaces, which is the one-way seam: SimLoop never
imports your package. A run is driven by `ScenarioRunner`, which owns the clock and writes the log, and
the log is what you assert on afterwards.

## What is in it

| Package | What it gives you |
|---|---|
| [`fakehardware`](packages/fakehardware.md) | Fake devices that implement the real SDK interfaces, and the `FakeHardwareMap` you hand to `init()`. |
| [`plant`](packages/plant.md) | The physics behind those fakes: drivetrain, 1-DOF and multi-DOF mechanisms, positional servos. |
| [`config`](packages/config.md) | The interfaces your season code implements to state its own numbers. The one-way seam. |
| [`sim`](packages/sim.md) | `FakeTimer`, `ScenarioRunner`, and decoding an RLOG back to fields. |
| [`field`](packages/field.md) | Game pieces and possession, without contact physics. |
| [`loop`](packages/loop.md) | Scoring a run, and the automated edit-and-replay iteration built on top of it. |
| [`viz`](packages/viz.md) | Drawing a mechanism's pose into the log so AdvantageScope can show it. |

The generated API reference — every class, method and field, with units and frame on every number — is
built by `./gradlew :SimLoop:apiDocs` and ships in the release as `SimLoop-<version>-javadoc.jar`.

## The rules it holds itself to

These are enforced by tests in the module, not by good intentions.

- **No season imports.** No file in `org.horizon36596.simloop` imports `org.firstinspires.ftc.teamcode`.
  Season facts arrive through the `config` interfaces.
- **Depends only on** the FTC SDK hardware interfaces and PsiKit. No SolversLib, no follower library.
- **Deterministic.** No wall-clock reads on any sim path. `update(deltaTime)` mutates state; getters are
  pure reads.
- **The fakes replicate real device semantics exactly** — every method of the interface, including the
  ones nobody calls, behaving the way the hardware does.
