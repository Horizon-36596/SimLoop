# Troubleshooting

Every failure on this page has been seen for real. They are here because each one *reads* like a bug in
your robot code and is not.

## `Could not find org.psilynx.psikit:core`

You are missing the Dairy repository line. PsiKit is not on Maven Central, and a POM can say what a
dependency is but never where to find it.

```groovy
maven { url "https://repo.dairy.foundation/releases" }
```

See [Installing it](getting-started.md#the-repository-lines).

## `Failed to load JUnit Platform`, before any test runs

You are on Gradle 9 without the JUnit Platform launcher. Gradle 8 put it on the test runtime classpath for
you; Gradle 9 does not.

```groovy
testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.3'
```

The version tracks your Jupiter version — 1.11.3 pairs with 5.11.3, and they move together.

## `RunResult.MetricNotFoundException` on a key you definitely logged

You logged `"Slide/positionInches"`; read it back as `"RealOutputs/Slide/positionInches"`. PsiKit files
everything `Logger.recordOutput` writes beneath `RealOutputs/`.

It throws rather than returning zero on purpose: a silent zero is a passing test that measured nothing.

## `cannot find symbol: class DcMotorEx` pointing at SimLoop's jar

Your project is not pulling the FTC SDK through SimLoop. SimLoop declares it at `api` scope precisely so
this cannot happen, so if you see it, something in your build is stripping transitive dependencies —
check for an `exclude` or a platform constraint on `org.firstinspires.ftc`.

## `SDK location not found`

Nothing to do with SimLoop. A Gradle build in a fresh checkout or a new git worktree needs
`local.properties`, which is not in version control. Copy it from a working checkout.

## The mechanism moves a tick late, and no gain fixes it

Check the order inside your scenario body. It must be: read state, decide, command the fake, **then**
`plant.update(deltaTime)`. Updating before commanding delays every response by exactly one tick.

## Two runs of the same scenario produce different logs

Something on a sim path is reading a wall clock. `System.nanoTime()`, `System.currentTimeMillis()`, and
anything built on them — `ElapsedTime` is the usual one, because it is `System.nanoTime()` underneath and
looks innocent.

Everything sim-side must take its time from the `FakeTimer` you constructed. `RlogDecodedCompare` is what
tells you the two runs differ and in which field.

## The run threw, and the message is about the RLOG rather than my robot

`ScenarioRunner` refuses to hand back a log it cannot vouch for. Four things cause it, and they are all
the same statement — *the log is not a record of the run*:

- PsiKit's async writer queue **overflowed**;
- the file is **missing or empty**;
- it decodes to a **different number of frames** than ticks you ran;
- the writer thread **stopped making progress** while the run waited.

None of them is affected by what your own assertions found. The commonest cause in a new project is a
second test JVM writing the same RLOG path — give each scenario its own file name.

## The RLOG is not there any more

`build/` is a build output directory and `./gradlew clean` deletes it. Re-run the test; that is the
command that regenerates the log.

## AdvantageScope opens the file but the graph is empty

Drag the key in from the tree on the left — `RealOutputs/...`, not the bare name. An empty graph with the
file open usually means the key you dragged had no numeric values, not that the run logged nothing.

## `./gradlew :SimLoop:check` fails in lint

Known, and not yours: lint reports `NewApi` errors on `java.nio.file` calls in classes that only ever run
in a JVM test. Run `:SimLoop:testDebugUnitTest` instead, which is what CI runs and what the library is
verified with.
