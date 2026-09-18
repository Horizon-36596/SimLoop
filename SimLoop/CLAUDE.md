# SimLoop module — CLAUDE.md

> This file scopes guidance to the `SimLoop` module subtree only. It travels with the module on extraction. It must NOT give repo-wide robot-development instructions — if this module is ever pulled into a live season repo, that repo's own root CLAUDE.md governs robot development.

## What this module is
The season-agnostic, reusable core of the Horizon (FTC 36596) sim/loop tooling: fake-hardware device models, `FakeHardwareMap`, `FakeTimer`, plant primitives (first-order motor, 1-DOF mechanism with end-limits, mecanum-FK pose integrator), the JVM run-loop scaffolding, deterministic-time + PsiKit `Logger` wiring helpers, and (Phase 3) the Claude-loop runner + scorer interfaces. Package root: `org.horizon36596.simloop`.

## Hard rules for this module
1. **No season imports.** Nothing here may import `org.firstinspires.ftc.teamcode.*` or any season-specific class. If you need a season fact (subsystem set, drivetrain geometry, field, mechanism limits), take it through an interface/config that the season glue implements — the core never reaches out.
2. **Depends only on** the FTC SDK hardware interfaces + PsiKit. No SolversLib dependency in core — a season's own `TeamCode` wires SolversLib, and this library never sees it.
3. **Deterministic & pure.** Plant `update(deltaTime)` mutates state; getters are pure reads. No wall-clock; the clock is injected (`FakeTimer`).
4. **Self-contained.** This module's tests, README, `LICENSE`, `NOTICE.md`, `PUBLISHING.md` and the `maven-publish` wiring at the bottom of `build.gradle` all live inside this directory, so the module carries everything it needs wherever it is checked out. Adding something the library needs to the repository root instead is how that stops being true.
5. **This is published, so the public API is a promise.** Other teams compile against it. Renaming a public method, changing a signature, or bumping the FTC SDK version is a MAJOR bump and a line in `PUBLISHING.md`'s version scheme, not a tidy-up. The SDK is an `api` dependency, so consumers compile against it *through* us; changing its version changes theirs.

## References in the comments that do not resolve here

Comments and javadoc throughout this module cite things like `BACKLOG B18`, `architecture §3`,
`conventions §7`, `domain R5` and `docs/briefs/...`. Those are entries in the development notes of the
repository this library is written in, and they are deliberately not copied here — they are a
maintainer's audit trail, not documentation, and they would rot the moment they were duplicated.

**Nothing a reader needs is only in them.** Every one of those citations sits beside the explanation it
supports: the comment says what is true and why, and the citation records where the decision was argued.
Read the comment and ignore the pointer. The documentation a user needs is `docs/`, and the reasoning a
maintainer needs is in the comment itself.

## Where the rest of the specification lives
`docs/` in this directory — a page per package, with units and frame on every number, plus what this
library deliberately does not do. `PUBLISHING.md` for releases. `README.md` for installing it.
