package org.horizon36596.simloop.config;

/**
 * Season-glue interface: drivetrain geometry + motor-model + field-bound constants the core
 * plant needs, supplied by the season layer in {@code TeamCode} (architecture §3, domain R7).
 *
 * <p>The core never hard-codes a season's numbers; it asks for them through this interface.
 * The DECODE glue implements it from {@code globals/Constants}. Units are inches, seconds,
 * radians, and encoder ticks unless noted.
 *
 * <p>Scope (Batch 1.2): this is the interface surface only. The plant primitives that consume
 * it (FakeMotor first-order model, mecanum-FK pose integrator) are built in Batch 1.4.
 *
 * <h2>Read this before writing a new season's config</h2>
 * Two methods here have <b>defaults</b> — {@link #wheelMountingSigns()} and
 * {@link #maxLateralVelocityTicksPerSecond()} — and both defaults describe an idealised robot: every motor
 * bolted in the same way round, and a chassis that strafes exactly as fast as it drives. Neither is true of
 * a typical mecanum.
 *
 * <p>They are defaulted rather than abstract so that adding them did not break every existing season config,
 * which is a real benefit and also a real trap: <b>a season that simply forgets them compiles clean and gets
 * a quietly wrong simulation</b> rather than a compiler error. On this repo's own robot, forgetting the
 * mounting signs made it spin instead of drive, and forgetting the strafe speed made it out-run its own path
 * follower sideways. If you are writing a config for a new robot, answer both deliberately — "we measured it
 * and there is no penalty" is a fine answer, "we never looked" is not.
 */
public interface DrivetrainSimConfig {

    /** {@return the left-right wheel center-to-center distance, in inches} */
    double trackWidth();

    /** {@return the front-back wheel center-to-center distance, in inches} */
    double wheelBase();

    /** {@return the drive wheel radius, in inches} */
    double wheelRadius();

    /** {@return the encoder ticks per inch of wheel travel} (drives FakeMotor position integration) */
    double ticksPerInch();

    /** {@return the maximum wheel velocity, in encoder ticks per second} (the FakeMotor first-order model's ceiling) */
    double maxVelocityTicksPerSecond();

    /**
     * How fast the chassis actually travels <b>sideways</b> at full power, in the same encoder ticks/second
     * units as {@link #maxVelocityTicksPerSecond()} — which is a <i>forward</i> figure.
     *
     * <p><b>Why a mecanum needs two numbers here.</b> Strafing turns the wheels just as fast as driving
     * forward, but the chassis does not travel as far: sideways motion is produced by the rollers, and
     * rollers slip. The loss is typically in the region of ten to fifteen percent, and no amount of gain
     * tuning removes it, because it is the wheels and not the controller.
     *
     * <p><b>Why it matters more than it sounds.</b> A path follower computes its braking distances from what
     * it believes the robot's top speeds are. If the simulated chassis strafes faster than the follower
     * believes it can, the follower under-brakes every lateral move, and the sim shows overshoot the real
     * robot does not have — or, worse, hides overshoot the real robot does have. Feed this and the follower's
     * own strafe limit from the <b>same measurement</b> and that whole class of artefact goes away.
     *
     * <p><b>This is a chassis speed expressed in the wheels' tick units, not a wheel's own tick ceiling.</b>
     * The units match {@link #maxVelocityTicksPerSecond()} so the two can be compared directly, but do not
     * read "ticks per second" here as "how fast the encoder ticks while strafing" — the wheels spin at their
     * full rate sideways, and it is the <i>chassis</i> that falls short. Measure it the way the robot's
     * strafe-velocity tuner does: drive sideways at full power and see how fast the robot actually goes.
     *
     * <p>Defaulted to {@link #maxVelocityTicksPerSecond()} — no lateral penalty — so a season that has not
     * measured its strafe speed behaves exactly as this core did before the number existed. It may not
     * exceed the forward figure: a mecanum is never faster sideways than forwards, so a larger value is a
     * paste error, and {@code MecanumPoseIntegrator} rejects it rather than modelling a robot that cannot
     * exist.
     *
     * @return max sideways chassis speed in encoder ticks/second, in {@code (0, maxVelocityTicksPerSecond()]}
     */
    default double maxLateralVelocityTicksPerSecond() {
        return maxVelocityTicksPerSecond();
    }

    /**
     * How fast a drive motor's speed chases the power it was commanded, in <b>reciprocal seconds</b>.
     *
     * <p>{@code FakeMotor} steps as {@code speed += (power - speed) * maxAccel * dt}, so this is the
     * first-order <b>rate</b> and its reciprocal is the time constant: 4.0 means a 0.25 s time constant, and
     * a drivetrain reaches roughly 95% of its top speed in three of those. <b>The name says acceleration
     * and the number is not one</b> — it is not in/s² and it does not scale with wheel size. The name is
     * kept because renaming a core interface method would churn every season that implements it.
     *
     * <h4>This is a real drivetrain parameter, so measure or calibrate it — do not copy it</h4>
     * A season that inherits the previous season's value here inherits a different robot's responsiveness.
     * The symptom is not an obvious failure: paths are still followed, but the simulated robot answers
     * corrections late, so it trails on curves and drifts at the ends of paths while every gain in the
     * follower is correct. If a simulated robot misbehaves in a way the real one does not, and every other
     * plant constant is measured, suspect this one.
     *
     * <p>Values below about 1 make the drivetrain sluggish enough that the follower cannot settle; values
     * above about 6 start to oscillate. There is no universally right number — a season config should
     * either measure its drivetrain's step response or calibrate against how the real robot behaves, and
     * <b>record which of the two it did.</b>
     *
     * @return the first-order response rate in 1/s; must be positive
     */
    double maxAccel();

    /**
     * Which way each drive motor is physically bolted in, as a sign per wheel in
     * {@code [frontLeft, frontRight, backLeft, backRight]} order: {@code +1} when a positive shaft
     * rotation drives that corner of the chassis <b>forward</b>, {@code -1} when it drives it backward
     * because the motor is mounted facing the other way.
     *
     * <p><b>This is a fact about the metal, not about the code</b>, and it is the counterpart of the
     * software {@code DcMotorSimple.Direction} the season sets on each motor. On a typical mecanum the two
     * sides' motors are mirror images, so the right-hand pair is {@code -1} and the season reverses those
     * two in software to cancel it. The product of the two signs is what actually moves the robot.
     *
     * <p><b>Why the core needs it.</b> {@code FakeMotor} models the software direction faithfully — a
     * {@code REVERSE} motor commanded {@code +1} turns its shaft the other way — but a fake motor is not
     * bolted to anything, so without this the sim would be a robot whose motors are all mounted identically.
     * Every wheel command would then need <i>no</i> software reversal to drive straight, which is the
     * opposite of what the real robot needs, and a direction mistake would either show up only on the real
     * robot or only in the sim. With both signs modelled, "the robot spins when told to drive straight"
     * happens in the sim for the same reason it happens on the field.
     *
     * <p>Defaulted to all-{@code +1} so a season that has not thought about it yet behaves exactly as this
     * core did before the sign existed. Returning anything other than four values is a programming error.
     *
     * @return four signs, each {@code +1} or {@code -1}, in {@code [FL, FR, BL, BR]} order
     */
    default double[] wheelMountingSigns() {
        return new double[] {1.0, 1.0, 1.0, 1.0};
    }

    /** {@return the field half-width, in inches}; the kinematic pose clamp bounds x to [-this, +this] (domain R6) */
    double fieldHalfWidth();

    /** {@return the field half-height, in inches}; the kinematic pose clamp bounds y to [-this, +this] (domain R6) */
    double fieldHalfHeight();
}
