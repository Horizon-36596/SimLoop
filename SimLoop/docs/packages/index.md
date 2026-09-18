# The packages

Seven packages, and you will touch four of them on your first day. Each page below says what the package
is for, which types you actually use, the units and frame every number is in, and — at the bottom — what
that package will not do.

| Package | You touch it | What it is |
|---|---|---|
| [`fakehardware`](fakehardware.md) | immediately | The fake devices your robot code resolves instead of real ones. |
| [`plant`](plant.md) | immediately | The physics behind those fakes. |
| [`config`](config.md) | immediately | The interfaces you implement to give the plants your robot's numbers. |
| [`sim`](sim.md) | immediately | The clock, the scenario runner, and reading a log back. |
| [`loop`](loop.md) | once you score runs | Turning a log into a number, with guardrails on what may not get worse. |
| [`field`](field.md) | if you model game pieces | Possession without contact physics. |
| [`viz`](viz.md) | if you want to see it | Drawing a mechanism's pose into the log for AdvantageScope. |

## How they fit together

```
your robot code
      |  resolves devices by name
      v
FakeHardwareMap  ->  fakehardware  (FakeMotor is a DcMotorEx)
                          ^
                          |  reads the commanded power
                       plant       (Mechanism1DofPlant moves)
                          ^
                          |  takes its numbers from
                       config      (MechanismSimConfig - YOUR implementation)

  all of it ticked by:  sim  (FakeTimer + ScenarioRunner)  ->  an RLOG
                                                                  |
                                                                  v
                                                    loop  (RunResult, Scorer)
```

The arrow that never reverses is the one into `config`: SimLoop reads your numbers through an interface
you implement, and never imports anything of yours.

## The generated reference

Every class, method and field — with units and frame on every number — is in the javadoc:

```powershell
./gradlew :SimLoop:apiDocs
```

It is written to `SimLoop/build/docs/apiDocs/index.html`, and it ships in every release as
`SimLoop-<version>-javadoc.jar`. It is generated with doclint on and warnings fatal, so a public method
without a comment fails the build rather than reaching you undocumented.
