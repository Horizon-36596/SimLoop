# SimLoop

Run your FTC robot code on a laptop, deterministically, and get a log you can score.

SimLoop is the season-agnostic core of the Horizon (FTC 36596) sim + loop tooling. Your `Robot.init()`,
your subsystems and your OpMode run **unchanged** against fake hardware in a JVM unit test; time comes
from a fake clock rather than the wall, so the same run produces the same log every time; and the log
is a PsiKit RLOG, which AdvantageScope opens and which the `loop` package can score automatically.

Package root: `org.horizon36596.simloop`. Status: **beta** — the API is settled enough to use and not
settled enough to promise.

## Installing

**The coordinate below is provisional until one real JitPack build has confirmed it.** JitPack builds a
tag on demand and serves what that build installs, so nothing resolves for anybody until a `v*` tag is
pushed and JitPack has built it once. `PUBLISHING.md` makes that first confirmed build the gate before
the coordinate is handed to any team — it is written out here so that cutting the tag is the only thing
left to do.

JitPack, which builds a tag of this repository on demand:

In a `build.gradle` — your root one's `allprojects { }` block, or the module's own:

```groovy
repositories {
    mavenCentral()
    google()
    maven { url "https://jitpack.io" }
    maven { url "https://repo.dairy.foundation/releases" }   // PsiKit lives here, not on Maven Central
}
```

Or, if your project declares its repositories in `settings.gradle` instead — which newer Android project
templates do — the same four lines go inside `dependencyResolutionManagement`:

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { url "https://jitpack.io" }
        maven { url "https://repo.dairy.foundation/releases" }
    }
}
```

**A bare `repositories { }` block does not work in `settings.gradle`.** `repositories` is part of the
project DSL; a settings file has no such method, in any Gradle version, so pasting the first block there
fails while Gradle is still evaluating settings — before it ever tries to resolve SimLoop — with
`Could not find method repositories()`. The error names the wrong thing, which is why both forms are
written out here.

**Check your `settings.gradle` for `repositoriesMode` before you pick a form.** If it contains
`repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` — which Android Studio's newer project
templates put there by default — the settings block is the only one allowed, and adding the
`build.gradle` form as well fails with a different message again: "Build was configured to prefer settings
repositories over project repositories". Without that line, both forms work and either will do.

```groovy
// TeamCode/build.gradle
dependencies {
    testImplementation 'com.github.Horizon-36596.SimLoop:SimLoop:0.1.0-beta1'

    // JUnit 5, if you are not already on it. SimLoop's own tests use it; yours need not.
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.3'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.11.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.3'
}

android {
    testOptions { unitTests.all { useJUnitPlatform() } }
}
```

**That Dairy repository line is not optional.** PsiKit is not on Maven Central, and a POM can tell your
build what a dependency is but never where to find it. Leaving it out fails at resolution with
`Could not find org.psilynx.psikit:core`, which reads like SimLoop is broken and is not.

`testImplementation` rather than `implementation` is the normal case: SimLoop is for the tests that run
on your laptop, and nothing here belongs on the robot. Put it on `implementation` only if you are
writing an OpMode that uses the fakes on the Control Hub, which is a strange thing to want.

SimLoop pulls in the FTC SDK (`RobotCore` and `Hardware`, 11.2.1) and PsiKit at **compile** scope,
because its own public types are SDK types — `FakeMotor` is a `DcMotorEx`, `FakeHardwareMap` is a
`HardwareMap`. If your project pins a different SDK version, yours wins; the note in `build.gradle`
says why the two have to agree anyway.

## The documentation

Full documentation lives in `docs/` in this directory and builds into a site: installing it, a first test
that actually runs, a page per package with units and frame on every number, what SimLoop deliberately
does **not** do, and a flat index written for AI agents. Start at `docs/index.md`.

## What is in it

| Package | What it gives you |
|---|---|
| `fakehardware` | Fake devices that implement the **real** SDK interfaces, so your robot code cannot tell: `FakeMotor`, `FakeExternalEncoder`, `FakeServo`, `FakeCRServo`, `FakeAnalogInput`, `FakeDigitalChannel`, `FakeI2cDeviceSynchSimple`, `FakeVoltageSensor`, and a `FakeHardwareMap` to hand to `init()`. |
| `plant` | The physics behind those fakes: `MecanumDrivePlant` and `MecanumPoseIntegrator` for the drivetrain, `Mechanism1DofPlant` and `MultiDofMechanismPlant` for arms, slides and turrets, `PositionalServoPlant` for the two ways a real servo refuses to go where it is told. First-order parameterized dynamics, calibration-ready — deliberately **not** rigid-body or contact physics. |
| `config` | The interfaces your season code implements to state its own numbers: `SimRobotConfig`, `DrivetrainSimConfig`, `MechanismSimConfig`, `PositionalServoSimConfig`, `ContinuousRotationSimConfig`. This is the one-way seam — SimLoop never imports your package. |
| `sim` | `FakeTimer` (the deterministic clock), `ScenarioRunner` (owns the whole PsiKit lifecycle and the fixed-timestep loop, so your test does not hand-write it), `RlogDecodedCompare` (decode an RLOG back to per-frame field maps). |
| `field` | Game pieces and possession without contact physics: `GamePiece`, `GamePieceTracker`, `TriggerVolume`, `PossessionState`. |
| `loop` | Scoring a run: `RunResult` reads an RLOG, `Objective` says what better means, `Guardrail` and `EnvelopeGuardrails` say what may not get worse, `Scorer` combines them, and `Gate`, `LoopDriver`, `Milestone` and `StopReport` drive an automated edit-and-replay iteration. |
| `viz` | `MechanismSketch` and the joint definitions, for drawing a mechanism's pose into the log so AdvantageScope can show it. |

## The shortest useful example

```java
@Test
void theArmReachesItsTarget() throws IOException {
    FakeTimer timer = new FakeTimer();
    // ... build your robot against a FakeHardwareMap, wired to `timer` for its clock ...

    Path rlog = Paths.get("build", "sim", "arm-run.rlog");
    ScenarioRunner.run("ArmTest", myConfig, timer, 500, 0.02, rlog, deltaTime -> {
        robot.arm.setTarget(12.0);
        robot.update();
        plants.update(deltaTime);
    });

    RunResult run = RunResult.fromRlog("ArmTest", rlog, 500);
    assertTrue(run.isComplete());
    assertEquals(12.0, run.finalValue("Arm/positionInches"), 0.25);
}
```

`ScenarioRunner.run` throws rather than returning a log you cannot trust: if PsiKit's writer queue
overflowed, if the file is empty, if it decodes to a different number of frames than ticks you ran, or
if the writer thread stopped making progress while the run waited for it.
That last one is why the class paces itself against the writer — a simulator has no 50 Hz clock holding
it back, and will otherwise outrun the writer and lose the tail of the log with no error at all.

However the run ends, PsiKit is shut down. A scenario body that throws propagates its own exception, and
the writer is still flushed and closed on the way out, so the partial log is readable and the next run in
the same JVM starts clean.

## The rules it holds itself to

- **No season imports.** No file here imports `org.firstinspires.ftc.teamcode.*`. Season facts arrive
  through the `config` interfaces, and a test in this module enforces it.
- **Depends only on** the FTC SDK hardware interfaces and PsiKit. No SolversLib, no follower library,
  no season code.
- **Deterministic.** No wall-clock reads on any sim path. `update(deltaTime)` mutates; getters are pure
  reads.
- **The fakes replicate real device semantics exactly** — every method of the interface, including the
  ones nobody calls, behaving the way the hardware does.

## Building and testing it

```bash
./gradlew :SimLoop:testDebugUnitTest
```

359 JVM tests, no device and no emulator. They live in `src/test/` and travel with the module.

Every command below is run from the repository root, not from this directory. The `SimLoop/` path prefix
and the `:SimLoop` task prefix are correct in every checkout of it: SimLoop is always a Gradle subproject
and never the root project, which is what the Android Gradle Plugin's library plugin expects and what
keeps the published coordinate stable.

Building the documentation site (one dependency, `pip install mkdocs-material`):

```bash
python -m mkdocs build -f SimLoop/mkdocs.yml --strict
python -m mkdocs serve -f SimLoop/mkdocs.yml
```

Generating the API reference, which the site links to and every release ships:

```bash
./gradlew :SimLoop:apiDocs
```

Releasing: `PUBLISHING.md`. Conventions for changing this subtree: `CLAUDE.md`.

## Licence

**AGPL-3.0-or-later** — `LICENSE` in this directory is the licence text, `NOTICE.md` beside it is the
copyright and what it covers.

In practice, for an FTC team: **use it, change it, run it, with no obligation at all** — the copyleft
term is triggered by *distributing*, not by using. If you publish your robot code, or hand a build of it
to anyone outside your team, then what you hand over has to be AGPL-3.0 too and its source has to be
available. If your season repository is private and stays that way, nothing is asked of you.

The dependencies keep their own licences. The FIRST Tech Challenge SDK and SolversLib are BSD;
PsiKit and Pedro Pathing carry theirs. This licence covers SimLoop's own code and nothing else.
