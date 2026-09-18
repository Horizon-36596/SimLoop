# Installing it

There are two ways in, and the first one is three steps.

!!! danger "Read this before you copy anything"
    **No release has been cut yet.** JitPack builds a git tag on demand and serves what that build
    installs, and no `v*` tag exists, so the coordinate below resolves for nobody today. It is written
    out now so that cutting the tag is the only thing left to do, not so that you can paste it and
    wonder why it fails.

    What is **unverified** until one real JitPack build has served it: the coordinate itself and the
    JitPack repository line that goes with it. Everything under
    [Building it yourself](#building-it-yourself-works-today) is verified — it is how SimLoop's own
    consumers build against it today.

## What you need first

- **Java 17 or newer** to run Gradle. Android Studio ships one.
- **An FTC SDK project**, any recent one. SimLoop compiles against SDK 11.2.1 and passes those types
  through to you, so if your project pins a different version, yours wins.

That is the whole list. You do **not** need to clone this repository, and you do not need SolversLib,
Pedro Pathing, Road Runner, FTC Dashboard or Robolectric — SimLoop depends on none of them.

## The three-step way: copy the starter folder

[`simloop-starter/`](https://github.com/Horizon-36596/SimLoop/tree/main/simloop-starter) is one folder
that carries every build setting SimLoop needs, plus a small example robot that drives itself.

1. **Copy the folder** into the root of your FTC project, beside `TeamCode/` and `FtcRobotController/`.
2. **Add one line to the bottom of `TeamCode/build.gradle`**, after the `android { }` block:

    ```groovy
    apply from: "$rootDir/simloop-starter/simloop.gradle"
    ```

3. **Run it.**

    ```powershell
    ./gradlew :TeamCode:testDebugUnitTest
    ```

Two tests run and pass in a few seconds, with no robot and no Driver Station. That is the example robot
driving itself in simulation.

!!! tip "Why a folder and not a quickstart repository"
    Plenty of FTC libraries ship a quickstart you clone and start your season in. That is a fine pattern
    and this is not one, on purpose: a team can only start their season in one repository, and if you are
    already in Pedro Pathing's or SolversLib's, a second quickstart is a thing you have to merge rather
    than a thing you can adopt. A folder you drop in competes with nothing, and removing it is deleting
    the folder and one line.

The starter's own [README](https://github.com/Horizon-36596/SimLoop/tree/main/simloop-starter#readme)
says what is in it, and [Your first simulated test](first-test.md) walks through the same code line by
line.

## The manual way: what the starter folder is actually doing

Read this if you would rather wire it yourself, or if you want to know what you just applied.

### The repository lines

Two of them, and **both are required**. Add them where your project declares repositories — in an FTC
project that is the `allprojects { repositories { } }` block of the root `build.gradle`, or a
`repositories { }` block in `TeamCode/build.gradle`, which is where the starter puts them.

```groovy
repositories {
    mavenCentral()
    google()

    // 1. JitPack, which builds a tag of the SimLoop repository on demand.
    maven { url = 'https://jitpack.io' }

    // 2. Dairy, where PsiKit lives. NOT optional - see below.
    maven { url = 'https://repo.dairy.foundation/releases' }
}
```

!!! note "Why the Dairy line is not optional"
    SimLoop depends on PsiKit, and PsiKit is not on Maven Central. A POM can tell your build **what** a
    dependency is, but never **where** to find it — that is always the consumer's repository list. Leave
    the Dairy line out and resolution fails with `Could not find org.psilynx.psikit:core`, which reads
    like SimLoop is broken and is not.

### The dependency

```groovy
// TeamCode/build.gradle
dependencies {
    testImplementation 'com.github.Horizon-36596.SimLoop:SimLoop:v0.1.0-beta1'

    // JUnit 5, if you are not already on it.
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.3'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.11.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.3'
}

android {
    testOptions {
        unitTests.all {
            useJUnitPlatform()
        }
    }
}
```

`testImplementation`, not `implementation`. SimLoop is for the tests that run on your laptop; nothing in
it belongs on the robot, and this scope is what keeps it out of the APK.

!!! warning "The version string includes the `v`"
    JitPack's version **is** the git tag, letter for letter. The tag is `v0.1.0-beta1`, so the version is
    `v0.1.0-beta1`. Ask for `0.1.0-beta1` and resolution fails with `Could not find`, which reads like a
    missing library rather than a missing letter.

!!! tip "That `junit-platform-launcher` line is not padding"
    Gradle 8 put the JUnit Platform launcher on the test runtime classpath for you. Gradle 9 stopped. On
    Gradle 9 without it, every test in the module fails before a single one runs, with
    `Failed to load JUnit Platform`, which reads like a broken module rather than a build-tool change.

### The unit-test setting that is not a preference

The starter also turns off the **release** unit-test variant, and this one is worth understanding before
you decide to leave it out:

```groovy
androidComponents {
    beforeVariants(selector().withBuildType('release')) { variantBuilder ->
        variantBuilder.enableUnitTest = false
    }
}
```

By default Android gives every build type its own unit-test task, so `./gradlew test` runs
`testDebugUnitTest` **and** `testReleaseUnitTest` in two separate JVMs sharing one working directory.
Every SimLoop scenario writes a log file into `build/sim/`, so two processes end up opening the same
file: on Windows the second delete fails while the first JVM holds the handle and both append, and on
Linux the delete succeeds and one JVM asserts on a truncated file.

The symptom is a determinism test failing with garbage that looks exactly like a determinism bug and is
not one — intermittently at first, then every time. `:TeamCode:testDebugUnitTest` stays green throughout,
so the two commands disagree and the one CI runs is the one that fails.

Turning the release unit tests off is safe because unit tests are variant-independent: both variants
compile the same sources against the same dependencies, and no minification is applied to unit tests.

### Which scope SimLoop's own dependencies arrive at

SimLoop declares the FTC SDK (`RobotCore`, `Hardware`) and PsiKit as `api`, not `implementation`, so they
land on **your compile classpath** too. That is deliberate and not negotiable: SimLoop's public types are
SDK types — `FakeMotor` **is** a `DcMotorEx`, `FakeHardwareMap` **is** a `HardwareMap` — and you cannot
so much as name the type you got back without the SDK in front of you.

Those three are the whole dependency list. SimLoop pulls in nothing else.

## Building it yourself (works today)

This is the route that works before a tag exists, and it is also how you would test a change to SimLoop
itself before tagging it. It is the only route that needs a clone.

```powershell
./gradlew :SimLoop:publishToMavenLocal
```

Then point your project at that local repository, *before* the others so it wins:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven { url = 'https://repo.dairy.foundation/releases' }
}
```

```groovy
dependencies {
    testImplementation 'org.horizon36596:SimLoop:0.1.0-beta1'
}
```

!!! note "Two groups, and both are correct"
    `org.horizon36596` is the group this project declares. It is what `publishToMavenLocal` writes and
    what a future Maven Central release would use. JitPack ignores it and derives its own group from the
    repository owner and name, so a JitPack dependency is spelled
    `com.github.Horizon-36596.SimLoop:SimLoop` instead. Use whichever matches the repository you are
    pulling from — and note that the local one carries the plain version `0.1.0-beta1`, because that is
    what `build.gradle` declares, while JitPack's carries the tag.

## Checking it worked

If you copied the starter folder, the example tests are the check. If you wired it by hand, this tells
you your test sourceset runs at all:

```powershell
./gradlew :TeamCode:testDebugUnitTest
```

A green run with zero tests means the wiring is right and you have not written one yet. That is exactly
where [Your first simulated test](first-test.md) picks up.
