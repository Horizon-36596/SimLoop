# The SimLoop starter folder

Copy **this folder** into the root of your FTC project, add **one line** to `TeamCode/build.gradle`, and
run the tests. That is the whole installation. You do not clone the SimLoop repository, you do not build
it, and you do not copy any of its source into your project.

> ## Read this first: no release has been cut yet
>
> SimLoop is served by JitPack, which builds a git **tag** on demand. **No `v*` tag exists yet**, so the
> version this folder asks for resolves for nobody today, and step 3 below fails with
> `Could not find com.github.Horizon-36596.SimLoop:SimLoop:v0.1.0-beta1`. That message means the release
> is not out, not that you did anything wrong.
>
> Until the first tag is cut, the route that works is to build the library yourself and depend on your
> own local copy — [Building it yourself](https://simloop.horizon36596.org/getting-started/#building-it-yourself-works-today)
> is the whole procedure, and it is two commands.

## The three steps

**1. Copy the folder.** The whole of `simloop-starter/`, into the directory that holds `TeamCode/` and
`FtcRobotController/`. Your project should end up looking like this:

```
your-ftc-project/
  FtcRobotController/
  TeamCode/
  simloop-starter/      <- this folder
  build.gradle
  settings.gradle
```

**2. Add one line to the bottom of `TeamCode/build.gradle`.** The bottom, after the `android { }` block
that is already there:

```groovy
apply from: "$rootDir/simloop-starter/simloop.gradle"
```

**3. Run the example.**

```
./gradlew :TeamCode:testDebugUnitTest
```

Two tests run and pass, in a few seconds, with no robot and no Driver Station. They are the example robot
in this folder driving itself in simulation.

## Why one line and not ten

Everything a build needs in order to resolve and run SimLoop — the two repositories, the dependency, the
JUnit 5 runner plus the vintage engine that keeps any JUnit 4 tests you already have from silently
vanishing, one unit-test setting that is not optional and is explained where it is set, and the two source
folders below — is in [`simloop.gradle`](simloop.gradle). That file is heavily commented and is
meant to be read, but it is not meant to be edited or split up and pasted into your own build. Keeping it
whole is what makes upgrading SimLoop a matter of replacing this folder.

The one thing you will edit is the first setting in it:

```groovy
ext.simloopVersion = 'v0.1.0-beta1'
```

## What is in here

```
simloop-starter/
  simloop.gradle       every build setting SimLoop needs; the one line above applies it
  robot/               the example robot - ORDINARY robot code, main sourceset
  tests/               the example's simulated tests
```

**`robot/` is compiled into your app**, in the main sourceset, exactly like your own subsystems. That is
deliberate and it is the entire claim SimLoop makes: your robot code runs in simulation *unchanged*,
because it never learns it is being simulated. `ExampleSlide` resolves a motor and an encoder from a
`HardwareMap`, runs a proportional controller with a gravity feedforward, and commands a motor. There is
nothing in it that knows a test exists. None of these classes carries `@TeleOp` or `@Autonomous`, so none
of them appears on the Driver Station.

**`tests/` is where simulation lives.** `ExampleRobotSim` is the file worth copying: it builds the fake
devices under the names the subsystems look up, puts a plant behind each one, and constructs the same
`ExampleRobot` an OpMode would. Every test then drives the robot through it, so adding a mechanism is one
edit there rather than one edit per test.

| File | What it is for |
| --- | --- |
| `robot/.../ExampleRobot.java` | One object owns the hardware map and builds every subsystem from it. The pattern worth copying. |
| `robot/.../ExampleDrive.java` | Mecanum chassis. Three commands in, four motor powers out. No follower. |
| `robot/.../ExampleSlide.java` | A vertical slide on an external encoder, with the gravity feedforward a vertical mechanism needs. |
| `robot/.../ExampleClaw.java` | A servo, with no sensor — because a real servo has none either. |
| `tests/.../ExampleRobotSim.java` | The fake hardware, the plants, and the physical numbers. Written once. |
| `tests/.../ExampleSlideSimTest.java` | The slide reaches scoring height and holds it. Read this one first. |
| `tests/.../ExampleAutoSimTest.java` | A short autonomous routine, and the two things simulation catches that a bench test does not. |

## The robot in here is invented

A mecanum chassis, one vertical slide, one claw. It is the simplest machine that still has all three kinds
of actuator a season robot has — a drivetrain, a position-controlled mechanism, and a servo. **Every
number in it is a plausible round figure, not a measurement of anything**, so do not use them as a
starting point for tuning. Yours are measured on your robot, and the value of measuring them is that a
simulation built on measured numbers is one that can be wrong in a way you can see.

## Deleting the example once you have read it

Delete `robot/` and `tests/`, and delete the `sourceSets` block at the bottom of `simloop.gradle`'s
`android { }`. Keep everything else. Nothing in the rest of that file depends on them.

## Two things in here that are worth reading even if you write your own

Both are mistakes this example made on its first run, kept because they are mistakes a team makes too.

**The drivetrain that went nowhere.** `ExampleDrive` reverses the right-hand motors in software, the way
a mecanum has to, and `wheelMountingSigns()` in `ExampleRobotSim` says which way each motor is *bolted
in*. Left at its all-`+1` default it describes a robot whose four motors are mounted identically, which no
mecanum chassis is — the software reversal is then uncancelled and the two sides fight each other.
Measured: one second of "drive forward" moves the chassis 0.0 inches and turns it 2.5 radians. It does not
crawl or veer; it spins in place, which is exactly what the same mistake does on the field. The product of
the two signs is what moves the robot, and a simulation that models only one of them hides the mistake
instead of reproducing it.

**The servo wait that was too short.** `ExampleClaw.TRAVEL_TIME_SECONDS` is the number a team guesses once
and never revisits. The autonomous test reads that constant rather than repeating it and then checks,
against the modelled jaws, that they really had finished moving. Shorten the constant and the test fails.
On the field the same mistake is a sample released on the way up, and nothing about a bench test finds it.

## Where the rest of it is

The library's documentation is at **<https://simloop.horizon36596.org>** — what each package does, what
SimLoop deliberately does not simulate, and the full API reference.
