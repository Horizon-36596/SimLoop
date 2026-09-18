# Publishing SimLoop

How a release is cut, what it is verified against, and what would have to change to put it on Maven
Central. The build wiring this describes lives at the bottom of `SimLoop/build.gradle`.

## Coordinates

| | Value |
|---|---|
| Group (in the POM) | `org.horizon36596` |
| Group (as JitPack serves it) | `com.github.Horizon-36596.SimLoop` |
| Artifact | `SimLoop` (matches the Gradle subproject name, which is what JitPack uses for a module) |
| Current version | `0.1.0-beta1` |
| Packaging | `aar`, plus a sources jar |

Two groups is not a mistake. JitPack rewrites the group of everything it builds to
`com.github.<owner>.<repo>`, so the value in `build.gradle` is what a Maven Central release and
`publishToMavenLocal` use, and the JitPack one is what a consumer types today. Both are correct at the
same time; only one of them is ever in a consumer's `build.gradle`.

## Version scheme

`MAJOR.MINOR.PATCH`, with a `-betaN` suffix while the API is still moving.

- **`-betaN`** says the API may change without a major bump. That is the honest state today and should
  stay until someone outside the team has used it for a season.
- **MINOR** for new capability and for any source-compatible change.
- **MAJOR** for anything a consumer has to edit code for — a renamed method, a changed signature, a
  removed class, or a bumped FTC SDK version, because the SDK is `api` and a consumer compiles against
  it through us.

### Behaviour changes since `0.1.0-beta1` was tagged

Signature-compatible, so no MAJOR bump under the rule above, but each one changes what existing consumer
code *does* and belongs in the next release's notes. All from the 2026-09-17 adversarial review.

| What changed | What a consumer sees |
|---|---|
| `FakeMotor.setMode(RUN_TO_POSITION)` | Now throws `UnsupportedOperationException` instead of being accepted and silently ignored. Code that set the mode and waited on `isBusy()` used to hang forever against the fake; it now fails at the call. |
| `FakeMotor.setVelocity`, `setMotorDisable`, `STOP_AND_RESET_ENCODER` | Now actually affect the shaft, the encoder count and the reported current. A test that passed because these were no-ops can legitimately go red. |
| `FakeServo.setPosition` with `REVERSE` + `scaleRange` | Direction is applied before scaling, matching `ServoImpl`. `getScaledPosition()` returns a different number than before for that combination — the old one was a mirror image. `scaleRange` now clips out-of-range endpoints rather than throwing. |
| `EnvelopeGuardrails` guardrail names | `withinBound`, `loopTimeUnderBudget` and `safeStopAtEnd` now embed their thresholds in `Guardrail.name()`. Any persisted last-best score from an earlier version will read as a different envelope in `Gate` and must be re-baselined. Non-finite and negative thresholds are now rejected at construction. |
| `RunResult.fromRlog` | `RealOutputs/Console` and `RealOutputs/Logger/QueuedCycles` are no longer scoreable, so `keys()` and `frames()` no longer contain them. |

`SimLoop/build.gradle` is the only place the version is *authoritative*, but it is not the only place
it is written: `README.md`'s install snippet, the coordinates table above and the verification note
below all quote it, and none of them is generated. Step 2 of the checklist below exists because of
that — a bump that misses the README leaves teams copying a version that was never tagged.

## Cutting a beta release

1. **Green first.** `./gradlew :SimLoop:testDebugUnitTest` — all of it, not the tests you touched.
   Then `./gradlew :SimLoop:apiDocs`, which must report **zero warnings**: the API reference is published
   and read by people and agents who have nothing else, so a missing unit is a release defect there in a
   way it is not in an IDE.
2. **Bump** `version` in `SimLoop/build.gradle`, **and the two copies of it**: `README.md`'s install
   snippet, and the `copyright:` line in `SimLoop/mkdocs.yml`, which is the version stamped in the footer
   of every documentation page. None of the three is linked to the others; see the note above.
   `Select-String -Path SimLoop\README.md,SimLoop\mkdocs.yml -Pattern '0\.1\.0-beta1'` finds them.
3. **Verify the artifact locally before anyone can consume it** — the section below says how, and it is
   the part people skip.
4. **Commit, tag, push the tag.** The tag is the version, e.g. `v0.1.0-beta1`.
5. **Build that tag on JitPack yourself, and read the log**, at
   `https://jitpack.io/#Horizon-36596/SimLoop`. This is a gate, not a warm-up, and until it has passed
   once nobody should be given the coordinate. Two specific things can only be learned here:
   - **Whether the coordinate is right at all.** JitPack derives the artifact id from the module, and
     the only way to be certain which spelling it serves is to see it serve one.
   - **Whether JitPack's environment builds it at all.** `jitpack.yml` pins `openjdk17` because the
     Android Gradle Plugin requires it and JitPack's default is older. That pin has never been exercised
     by a real JitPack build; the first tag is what exercises it.

   The repository is public, which is what JitPack's free tier requires - it does not build private
   repositories.
6. **Tell the teams the coordinate and the two repository lines**, not just the coordinate. The
   PsiKit one is the thing that bites — see below.

Tagging and pushing leave the machine, so they are a human's call, not an agent's.

## Verifying a release locally

```bash
./gradlew :SimLoop:publishToMavenLocal
```

writes the AAR, the sources jar, the POM and the Gradle module metadata into `~/.m2/repository/`.

Then check the POM lists **RobotCore, Hardware, psikit-core and psikit-ftc at `compile` scope**. If any
of them says `runtime`, someone changed an `api` back to `implementation` and consumers will not be
able to name the types they are handed.

The real check is to compile something against it from outside this repo: a throwaway Android library
module whose only declared dependency is `org.horizon36596:SimLoop:<version>`, with `mavenLocal()`
first in its repositories, containing one class that does this much —

```java
FakeHardwareMap map = new FakeHardwareMap();
FakeMotor motor = new FakeMotor(1000.0, 2.0);
map.register("fl", motor);
DcMotorEx asSdkType = motor;            // names an SDK type: this is the line that fails on runtime scope
Logger.recordOutput("Demo/time", 0.0);  // names a PsiKit type, same reason
```

That was run for `0.1.0-beta1` and passed, and the same project with the dependencies switched back to
`implementation` failed with `cannot access HardwareMap` — the scope claim above is measured, not
assumed.

## Why the library is a subproject and not the root project

This repository holds one library, and that library is a Gradle **subproject**. Both the `SimLoop/` path
prefix and the `:SimLoop` task prefix that appear throughout this file and the workflows are therefore
correct, and they are meant to stay:

- The Android Gradle Plugin's library plugin is applied to a subproject, which is the layout it expects
  and the layout every FTC project already uses. A root project that is itself an Android library is a
  shape nothing else in this ecosystem has.
- **The coordinate depends on it.** JitPack derives the group from the repository owner and the artifact
  id from the module, so a flat layout would serve `com.github.Horizon-36596:SimLoop` where this one
  serves `com.github.Horizon-36596.SimLoop:SimLoop`. Flattening is not a tidy-up; it is a silent rename
  of the artifact under anyone already depending on it.

The root of the repository holds what belongs to the repository rather than to the library:
`settings.gradle`, `build.gradle`, `gradlew` and the wrapper, `jitpack.yml`, `LICENSE`, `NOTICE.md`,
`README.md` and
`.github/workflows/`. Everything else is under `SimLoop/` and travels with the module.

## What consumers have to add that a POM cannot tell them

**PsiKit is not on Maven Central.** It is served from `https://repo.dairy.foundation/releases`. A POM
records what a dependency is and never where to find it, so a consumer who adds JitPack and nothing
else fails at resolution with `Could not find org.psilynx.psikit:core`, which looks like our bug. The
README's install section leads with this for that reason.

## Hosting the documentation site

The site is at `SimLoop/docs/`, it builds with one command, and CI builds it on every push so it can
never become something that only builds on one laptop:

```powershell
./gradlew :SimLoop:docsSite
```

That renders the markdown, checks that every internal link resolves, generates the API reference with
doclint fatal, checks that the version stamped in the site footer matches the version this build
publishes, and drops the API reference inside the site at `/javadoc/`. It writes to
`SimLoop/build/docs/site`. Its one dependency is pinned in `SimLoop/docs-requirements.txt`:

```powershell
pip install -r SimLoop/docs-requirements.txt
```

`.github/workflows/docs-publish.yml` publishes it on a `v*` tag. No tag has been cut, so it has never
run, and three repository settings still have to exist before a deployment can succeed. The CI job in
`test.yml` builds the site on every push and keeps it as a downloadable artifact regardless, so a break
in the site is caught by the push that causes it rather than by a release.

### Turning it on — three steps, all of them a human's

A fourth step, deleting the workflow's off switch, was done on 2026-09-17 when the repository was made
public. Nothing in this repository needs editing again; what is left is three things in a browser.

**1. Enable GitHub Pages.** Settings → Pages → Build and deployment → Source: **GitHub Actions**. Not
"Deploy from a branch" — the workflow deploys the built artifact directly, and there is no `gh-pages`
branch to point at.

**2. Add the DNS record.** One record, at whoever hosts `horizon36596.org`:

| Field | Value |
|---|---|
| **Type** | `CNAME` |
| **Name** / Host | `simloop` |
| **Value** / Target / Points to | `horizon-36596.github.io` |
| **TTL** | leave it at the default, or 3600 |
| **Proxy status** (Cloudflare only) | **DNS only** — grey cloud, not orange |

Three things about that record, each of which has cost somebody an afternoon:

- The **Name** is `simloop`, not `simloop.horizon36596.org`. Most registrars append the domain for you and
  a full name here becomes `simloop.horizon36596.org.horizon36596.org`. A few registrars do want the full
  name — if yours shows existing records with their full names, match what you see.
- The **Value** is the organisation's Pages host, `horizon-36596.github.io`, and it is **not** the
  repository: there is no `/SimLoop` on the end. GitHub's own instruction is that the record "should
  always point to `<user>.github.io` or `<organization>.github.io`, excluding the repository name".
  Some registrars require a trailing dot (`horizon-36596.github.io.`); GitHub does not mention one, so if
  the field rejects the value without it, that is your registrar's convention, not GitHub's.
- **On Cloudflare, turn the proxy off** until GitHub has issued the certificate. *Unverified:* GitHub's
  own documentation says nothing about proxies, but this is a common report — a proxied record hides the
  real origin from the certificate check. If the certificate does issue with the proxy on, leave it on.

**3. Enter the custom domain in the repository's settings.** Settings → Pages → Custom domain → type
`simloop.horizon36596.org` → **Save**.

This step is not optional and is easy to skip, because the repository *does* contain a `CNAME` file and
that looks like it should be enough. It is not, on this publish path. GitHub's documentation is explicit:
**"If you are publishing from a custom GitHub Actions workflow, no `CNAME` file is created, and any
existing `CNAME` file is ignored."** `docs-publish.yml` is a custom Actions workflow, so the Pages setting
is the only thing that sets the domain.

`SimLoop/docs/CNAME` is kept anyway, holding `simloop.horizon36596.org`. It costs 25 bytes, it records in
the repository which domain the site is meant to answer on, and it is what would carry the domain if
anyone ever switched this to a branch-based publish. It is not what makes step 3 unnecessary.

With those three done, pushing a `v*` tag publishes the site, and so does running the workflow by hand
from the Actions tab. All three are a human's, in a browser, and a build may not do any of them.

### Checking it worked

GitHub's documentation gives one number worth knowing: **"It can take up to an hour for your site to
become available over HTTPS after you configure your custom domain."** DNS propagation is on top of that
and depends on your registrar. Both are waiting, not failure.

- `https://simloop.horizon36596.org/` loads, over HTTPS, with no certificate warning.
- The footer of every page reads the version you just tagged.
- `https://simloop.horizon36596.org/javadoc/` loads the API reference. If the site loads and this 404s,
  the `docsSite` task did not run — the deploy published a bare `mkdocs build` from somewhere.

## What a Maven Central release would additionally need

Not done, and not needed for a beta with a handful of teams. For when it is:

- A verified namespace on Sonatype Central for `org.horizon36596`, which means proving control of
  `horizon36596.org` with a DNS TXT record. That is the reason the declared group is a domain rather
  than a `com.github.*` one: the namespace is claimable at the moment it is wanted. An
  `io.github.horizon-36596` group is the fallback that needs no domain.
- **GPG signing** of every artifact, with the key published to a keyserver. This is the real work: a
  `signing` block, and a key that is not in the repository and not in an agent's reach.
- ~~A **javadoc jar**.~~ **Done, 2026-09-17.** Every release carries `SimLoop-<version>-javadoc.jar`,
  built by `./gradlew :SimLoop:apiDocs` - the real javadoc tool with `-Xdoclint:all` and `-Werror`, so a
  missing comment, a missing `@param`/`@return` or a broken `{@link}` fails the build rather than printing
  a line. It reports zero warnings today, and that is the state a release is expected to be in.

  It is **not** AGP's `withJavadocJar()`, which was tried first and rejected the same day: AGP's javadoc
  task runs Dokka, which predates the inline `{@return ...}` tag this module's accessors use and emitted
  it as literal text, braces and all, on every getter page. `SimLoop/build.gradle` says this at the
  publishing block. The jar shipped in the POM and the HTML on the documentation site are now the same
  build, which is also why there is nothing to keep in sync.
- A `repositories { maven { url = ...; credentials { ... } } }` block, with credentials read from an
  environment variable or `~/.gradle/gradle.properties` and never from a file in the repository.
- Releases become **immutable**. A beta tag can be re-cut after a mistake; a Central version cannot.

The POM already carries everything else Central validates — name, description, url, licence, developer
and SCM — so this is a known and bounded list, not an unknown.

## Decisions a human has made

Settled 2026-09-17. The answers and the reasoning behind each are in
`docs/progress/decisions/L.md`; this is the short form, because these are the facts the rest of this
file assumes.

- **The licence is AGPL-3.0-or-later, `Copyright (C) 2026 Horizon (FTC 36596)`.** Changed from BSD
  3-Clause on 2026-09-17, before any release, so no consumer was relicensed under them. It is the most
  restrictive licence that still lets another team actually use the library: they may run and modify it
  freely, and the copyleft only bites when they *distribute* — at which point what they distribute must
  be AGPL-3.0 with source available. A team whose season repository is private owes nothing.
  Vendored and depended-on third-party code keeps its own licence; the SDK and SolversLib stay BSD.
- **A person cuts a release, and that person is the repository owner.** The release *is* the tag: pushing
  `v<version>` is what declares a commit usable, and everything downstream reacts to it. No workflow
  creates a tag. This is the same rule root `CLAUDE.md` applies to push, PR and merge — the act that
  leaves the machine is a human's.
- **The repository is public, and the documentation site is bound to that.** Made public on 2026-09-17;
  the site goes public with the code or not at all, so there is deliberately no separate repository
  holding the built site and no paid JitPack plan. What is still not true is that anything has been
  *released*: until a `v*` tag exists, the JitPack coordinate resolves for nobody and
  `publishToMavenLocal` is the only route that works. That is stated wherever it matters rather than
  papered over.
- **The library is a Gradle subproject of a repository that holds only it.** The reasoning is above,
  under "Why the library is a subproject and not the root project"; the short version is that
  flattening the layout would rename the published artifact.
- **Documentation is not versioned** until the library is past beta. One site, no version selector, with
  the version stamped on every page.
- **Bugs and questions go to GitHub Issues** on `Horizon-36596/SimLoop`. One channel, public, and it
  leaves a record the next person with the same problem can find.

### Settled on 2026-09-17: the package and the Maven group

- **The Java package is `org.horizon36596.simloop` and the Maven group is `org.horizon36596`.** Settled
  at the only moment such a thing is free: nothing published, no tag cut, and no team outside Horizon
  holding a copy. Changing either one after the first team has it costs that team an edit to every import
  line, which is why this is a decision rather than a preference. `org.horizon36596` is the reverse-DNS of
  a domain Horizon controls, which is also what a Maven Central namespace verification asks for.
- **The published coordinate did not change with it.** JitPack derives the group from the repository
  owner, not from this file, so `com.github.Horizon-36596.SimLoop` is what a consumer writes either way.
  The declared group matters for `publishToMavenLocal` and for any future Maven Central release.
