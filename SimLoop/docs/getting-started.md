# Installing it

This page is the whole Gradle change, written out rather than described, because a half-quoted build edit
is where an evening goes.

!!! danger "Read this before you copy anything"
    **No release has been cut yet.** JitPack builds a git tag on demand and serves what that build
    installs, and no `v*` tag exists, so the coordinate below resolves for nobody today. It is written
    out now so that cutting the tag is the only thing left to do, not so that you can paste it and
    wonder why it fails.

    Two things on this page are **unverified**: the coordinate itself, which is only settled once one real
    JitPack build has served it, and the JitPack repository line that goes with it. Everything under
    [Until it is published](#until-it-is-published) is verified — it is how SimLoop's own consumers build
    against it today.

## What you need first

- **Java 17 or newer** to run Gradle. Android Studio ships one.
- **JUnit 5.** SimLoop's own tests use it, and the examples here are written in it. If your `TeamCode`
  is still on JUnit 4, the snippet below switches it; nothing else in your project has to change.
- **An FTC SDK project**, any recent one. SimLoop compiles against SDK 11.2.1 and passes those types
  through to you, so if your project pins a different version, yours wins.

## The repository lines

Two of them, and **both are required**. Add them where your project declares repositories — in modern
FTC projects that is `settings.gradle`'s `dependencyResolutionManagement` block, in older ones the
`allprojects { repositories { } }` block of the root `build.gradle`.

```groovy
repositories {
    mavenCentral()
    google()

    // 1. JitPack, which builds a tag of the SimLoop repository on demand.
    maven { url "https://jitpack.io" }

    // 2. Dairy, where PsiKit lives. NOT optional - see below.
    maven { url "https://repo.dairy.foundation/releases" }
}
```

!!! note "Why the Dairy line is not optional"
    SimLoop depends on PsiKit, and PsiKit is not on Maven Central. A POM can tell your build **what** a
    dependency is, but never **where** to find it — that is always the consumer's repository list. Leave
    the Dairy line out and resolution fails with `Could not find org.psilynx.psikit:core`, which reads
    like SimLoop is broken and is not.

## The dependency

```groovy
// TeamCode/build.gradle
dependencies {
    testImplementation 'com.github.Horizon-36596.SimLoop:SimLoop:0.1.0-beta1'

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
it belongs on the robot. The only reason to put it on `implementation` is if you intend to run the fakes
on the Control Hub itself, which is a strange thing to want.

!!! tip "That `junit-platform-launcher` line is not padding"
    Gradle 8 put the JUnit Platform launcher on the test runtime classpath for you. Gradle 9 stopped. On
    Gradle 9 without it, every test in the module fails before a single one runs, with
    `Failed to load JUnit Platform`, which reads like a broken module rather than a build-tool change.

## Which scope SimLoop's own dependencies arrive at

SimLoop declares the FTC SDK (`RobotCore`, `Hardware`) and PsiKit as `api`, not `implementation`, so they
land on **your compile classpath** too. That is deliberate and not negotiable: SimLoop's public types are
SDK types — `FakeMotor` **is** a `DcMotorEx`, `FakeHardwareMap` **is** a `HardwareMap` — and you cannot
so much as name the type you got back without the SDK in front of you.

## Until it is published

This is the route that works today, and it is also how you would test a change to SimLoop itself before
tagging it.

Build SimLoop and install it into your machine's local Maven repository:

```powershell
./gradlew :SimLoop:publishToMavenLocal
```

Then point your project at that local repository, *before* the others so it wins:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven { url "https://repo.dairy.foundation/releases" }
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
    repository owner, so a JitPack dependency is spelled `com.github.Horizon-36596:SimLoop` instead —
    the line above the tab you are reading. Use whichever matches the repository you are pulling from.

## Checking it worked

You do not need SimLoop for this — you need to know your test sourceset runs at all:

```powershell
./gradlew :TeamCode:testDebugUnitTest
```

A green run with zero tests means the wiring is right and you have not written one yet. That is exactly
where [Your first simulated test](first-test.md) picks up.
