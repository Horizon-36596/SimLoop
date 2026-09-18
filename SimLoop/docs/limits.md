# What SimLoop does not do

Read this page before you plan anything around SimLoop. Everything here is a deliberate boundary, not a
gap waiting to be filled — each one is a decision with a reason, and several of them are the reason the
rest works.

!!! warning "If you are an AI agent"
    This page is the authoritative list of what is out of scope. If a task needs something on it, the
    answer is not to look harder for the API — there isn't one. Say so and propose the alternative named
    here.

## It is not a physics engine

**No rigid-body dynamics. No collisions. No contact.** Nothing in SimLoop can tell you what happens when
your robot hits a wall, when two robots touch, when a game piece bounces, or whether an intake will
actually pick something up.

What it models instead is **parameterized first-order dynamics per mechanism**: one time constant, one
speed ceiling, one pair of end stops, one gravity term. That is enough to answer "does my controller
settle where I told it, in about the time I expect, without running past its own limit", which is the
question most control bugs are.

If a model you want seems to need contact physics, that is the signal to stop and check the design rather
than to go looking for a method.

## It does not simulate vision

No cameras, no AprilTags, no colour blobs, no Limelight, no TensorFlow. A vision-dependent auto cannot be
tested end to end here. What you can do is test everything *downstream* of vision by feeding your
subsystem the pose or the detection as an input — which is usually the part that has the bug anyway.

## It does not model the field

There is no field geometry, no walls, no scoring elements with shape. The drivetrain plant clamps the
robot's pose to a rectangle so a runaway integration does not produce coordinates in the next county, and
that rectangle is the whole of the field model.

Game pieces exist (see [`field`](packages/field.md)) as positions and possession states with trigger
volumes — "was a piece inside this box relative to the robot" — which is a model of *intake logic*, not a
model of *intaking*.

## It does not schedule anything

SimLoop has no command scheduler, no subsystem registry, no requirements or exclusivity. If you use
SolversLib, SolversLib keeps owning your control flow exactly as it does on the robot; SimLoop replaces
hardware, underneath all of it. A scenario body is a plain lambda that runs once per tick.

## It does not know what your robot is

No season code, no subsystem classes, no drivetrain of yours. SimLoop never imports your
package — the dependency runs one way, through the [`config`](packages/config.md) interfaces that your
code implements. This is what makes it survive a season change, and it is enforced by a test.

## It is not a robot-side library

Nothing here is meant to run on a Control Hub. It is a `testImplementation` dependency. The fakes would
technically construct on a robot; there is no reason to want that.

## It does not tune your controller

There is no auto-tuner, no system identification, no optimizer over gains. The `loop` package runs an
edit-and-replay iteration — it scores runs, holds guardrails and decides whether a candidate is better —
but *what to change* comes from you or from an AI agent driving it, not from SimLoop.

## Its numbers are as good as your numbers

A plant is a model with constants in it. If `timeConstant` and `maxSpeed` were guessed, the simulation is
a plausible story, not a prediction. SimLoop will not warn you about this, because it cannot tell a
measured constant from an invented one — that is why the configs ask for real units and why the
documentation states them everywhere.

Calibrating against a real robot, and reporting the sim-to-real gap, is future work and is not here today.

## Known rough edges

These are defects or unfinished corners rather than boundaries, and they are worth knowing before you
trip over them.

- **The published coordinate is unverified.** No version has been tagged, so no JitPack build has ever
  served it. See [Installing it](getting-started.md).
- **Nothing calibrated has shipped.** Every constant in every example on this site is plausible, not
  measured.
- **There are two field frames in the library and they disagree.** The drivetrain integrates into +x
  forward / +y left; game pieces and trigger volumes live in +X right / +Y forward. Converting at the
  boundary is the caller's job today, and nothing warns you. See [`plant`](packages/plant.md).
- **Loop-time guardrails have nothing to check.** `EnvelopeGuardrails.loopTimeUnderBudget` exists and
  returns `UNKNOWN` until some scenario logs a loop time — which no scenario does yet. An envelope with
  unknowns in it is guarding less than it looks like it is; `Scorer.Score.unknowns()` is how you find out.
