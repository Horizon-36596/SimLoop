# `viz`

`org.horizon36596.simloop.viz`

Drawing a mechanism's pose into the log, so AdvantageScope can show you the arm instead of four line
graphs you have to hold in your head.

## What you use

| Type | What it is |
|---|---|
| `MechanismSketchDefinition` | The mechanism's fixed geometry, built once with a builder. |
| `MechanismSketch` | The live drawing: give it joint values each tick, it logs the pose. |
| `JointDefinition` | The base for one drawn segment. |
| `RevoluteJointDefinition` | A segment that pivots — an arm. Constant length, varying angle. |
| `LinearJointDefinition` | A segment that extends — a slide stage. Varying length, fixed direction. |
| `RobotVector3` | A direction or point in the robot frame. |

## Units and frames

- Lengths: **inches**.
- Angles: **radians**, right-hand rule about the joint's rotation axis, measured from the zero pose.
- Directions: unit vectors in the **robot-local** frame, unitless.
- Line weight: **pixels**, and colour is `"#RRGGBB"` upper-case.

The **zero pose** is the mechanism as drawn in CAD: every joint at its own zero. A revolute joint's
angles are relative to that pose, and its min angle is always `<= 0` while its max is always `>= 0`.

## What this package will not do

- **It will not render anything.** It writes numbers into the log; AdvantageScope draws them. There is no
  image output here.
- **It will not do inverse kinematics.** You give it joint values; it does not solve for them.
- **It is not a collision model.** A sketch that draws two segments through each other draws them through
  each other.
- **It does not know where the robot is.** The sketch is a mechanism, drawn in the robot frame; putting
  the robot on the field is the drivetrain's business.
