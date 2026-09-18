# `config`

`org.horizon36596.simloop.config`

The one-way seam. These are interfaces **you** implement, in your season repository, to tell SimLoop your
robot's numbers. SimLoop reads them; SimLoop never imports anything of yours.

## What you implement

| Interface | Describes |
|---|---|
| `SimRobotConfig` | The robot: its drive motor names, its odometry device name, its drivetrain config. |
| `DrivetrainSimConfig` | Chassis geometry and the drive motors' limits. |
| `MechanismSimConfig` | One powered axis: time constant, speed ceiling, end stops, gravity. |
| `PositionalServoSimConfig` | A servo joint: what command maps to what position, plus deadband and quantum. |
| `ContinuousRotationSimConfig` | A CR-servo axis. |

## Units, exactly

`DrivetrainSimConfig`

| Method | Unit |
|---|---|
| `trackWidth()`, `wheelBase()`, `wheelRadius()` | inches |
| `ticksPerInch()` | encoder ticks per inch of wheel travel |
| `maxVelocityTicksPerSecond()` | ticks per second |
| `maxAccel()` | reciprocal seconds (how fast speed chases command) |
| `maxLateralVelocityTicksPerSecond()` | ticks per second — a **strafe** figure, and it is not the forward one |
| `wheelMountingSigns()` | unitless `+1`/`-1` per wheel, `[frontLeft, frontRight, backLeft, backRight]` |
| `fieldHalfWidth()`, `fieldHalfHeight()` | inches |

`MechanismSimConfig`

| Method | Unit |
|---|---|
| `timeConstant()` | seconds, strictly positive |
| `maxSpeed()` | your position units per second, strictly positive |
| `minPosition()`, `maxPosition()` | your position units |
| `gravityHoldPowerFraction()` | unitless, `[0, 1)`, always **positive** |

`PositionalServoSimConfig`

| Method | Unit |
|---|---|
| `positionAtCommandZero()`, `positionAtCommandOne()` | your position units |
| `commandDeadband()`, `commandQuantum()` | servo command units, the same `[0, 1]` scale as the command |

!!! danger "Two of these have defaults that quietly lie"
    `maxLateralVelocityTicksPerSecond()` and `wheelMountingSigns()` have defaults, so a config that
    ignores them compiles. A wrong mounting sign makes a robot spin instead of drive; a wrong strafe speed
    makes it out-run its own path follower sideways — both in simulation only, so you debug a control
    problem that is really a config problem. Answer both deliberately. "We measured it and there is no
    penalty" is a fine answer; "we never looked" is not.

## Positional servos: why two separate numbers

`commandDeadband` and `commandQuantum` are different failures and a control law that fixes one does not
fix the other.

- A **quantum** makes the reachable positions a grid, so the best possible aim is half a quantum off, and
  no smoothing helps.
- A **deadband** makes the joint stick wherever it happened to stop, so the error depends on which
  direction it approached from.

`positionAtCommandZero()` is allowed to be **greater** than `positionAtCommandOne()`. That is just a servo
mounted so increasing command drives the joint the other way, and it needs no "reversed" flag. Neither
endpoint has to lie inside the mechanism's end stops either — a hard stop reached before the servo's full
sweep is a real build, and the plant simply stops there.

## What this package will not do

- **It will not validate your physics.** Values are checked for being finite and ordered, not for being
  true.
- **It will not read your robot configuration file.** The device *names* are strings you supply; nothing
  parses an FTC config XML.
- **It will not convert units.** Pick inches or millimetres, degrees or radians, and stay in them
  everywhere.
- **It does not describe your subsystems.** There is no place here to say "I have an intake". These are
  numbers, not a robot model.
