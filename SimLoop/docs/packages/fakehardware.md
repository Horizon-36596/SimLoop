# `fakehardware`

`org.horizon36596.simloop.fakehardware`

Fake devices that implement the **real** FTC SDK interfaces. Your robot code resolves them from a
`FakeHardwareMap` by the same config name it uses on the robot, and cannot tell the difference — which is
why your code does not have to be modified to be tested.

## What you use

| Type | Stands in for | Notes |
|---|---|---|
| `FakeHardwareMap` | `HardwareMap` | Hand this to your `init()`. Register each fake under its config name. |
| `FakeMotor` | `DcMotorEx` | The big one: modes, encoders, power, velocity, current, direction. |
| `FakeExternalEncoder` | a separate encoder on a motor port | For a mechanism whose encoder is not on its own motor. |
| `FakeServo` | `Servo` | Positional. Honours `scaleRange` the way the SDK does. |
| `FakeCRServo` | `CRServo` | Continuous rotation. Starts stopped. |
| `FakeAnalogInput` | `AnalogInput` | A constant voltage source. |
| `FakeDigitalChannel` | `DigitalChannel` | Input or output, with the SDK's mode rules. |
| `FakeVoltageSensor` | `VoltageSensor` | Reports a constant 12.0 V. |
| `FakeI2cDeviceSynchSimple` | the I2C bus client inside a device driver | Bus config only — see below. |
| `FakeDevice` | — | The interface every fake implements: `update(deltaTime)`. |
| `AbstractFakeDevice` | — | Base class supplying the `HardwareDevice` boilerplate. |

## How it is used

```java
FakeMotor slideMotor = new FakeMotor(2000.0, 8.0);   // max ticks/s, accel constant (1/s)
FakeHardwareMap hardwareMap = new FakeHardwareMap();
hardwareMap.register("slide", slideMotor);           // the name in YOUR robot configuration

// ... your robot code does hardwareMap.get(DcMotorEx.class, "slide") and never knows ...

hardwareMap.updateAll(deltaTime);                    // once per tick, from the scenario body
```

## Units and frames

- **Motor position:** encoder **ticks**. Velocity: **ticks per second**.
- **Motor power:** unitless, `[-1, 1]`.
- **Servo position:** unitless, `[0, 1]`, before `scaleRange` is applied.
- **Voltage:** **volts**.
- **`update(deltaTime)`:** `deltaTime` in **seconds**, always the scenario's fixed tick.

## Two things worth knowing

**State changes only in `update`.** Every getter is a pure read. That is what makes a tick reproducible:
nothing moves because you looked at it.

**Sim-only accessors are marked as such.** Methods like `FakeMotor.getPhysicalPositionTicks()` and
`FakeCRServo.getPhysicalPower()` report the simulation's truth rather than what the device would report —
useful in a test, and they do not exist on real hardware, so never wire season code to one.

## What this package will not do

- **It will not move anything.** A fake on its own only remembers what it was told. The motion is in
  [`plant`](plant.md); a `FakeMotor` with no plant behind it reports a position that never changes.
- **No I2C peripherals.** `FakeI2cDeviceSynchSimple` models the bus *configuration* a driver's constructor
  performs, so a driver can be built headless. Every read and write **throws**, on purpose: there is no
  peripheral behind the bus, and a fake returning plausible bytes would be worse than a loud failure. A
  device fake is expected to override the driver methods it uses so the bus is never reached.
- **No sensor fusion, no IMU, no distance sensors.** Not modelled at all.
- **No USB, no Lynx module, no firmware behaviour.** `isArmed()` is always false; the fakes are never
  attached to anything.
