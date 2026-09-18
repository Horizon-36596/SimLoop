# SimLoop

Run your FTC robot code on a laptop, deterministically, and get a log you can score.

SimLoop is a season-agnostic library from Horizon (FTC 36596). Your `Robot.init()`, your subsystems and
your OpMode run **unchanged** against fake hardware in a JVM unit test — no Control Hub, no emulator, no
Robolectric. Time comes from a fake clock rather than the wall, so the same run produces the same log
every time, and that log is a PsiKit RLOG, which AdvantageScope opens and which the `loop` package can
score automatically.

Package root: `org.horizon36596.simloop`. Status: **beta** — the API is settled enough to use and not
settled enough to promise.

## Adopting it is one folder and one line

Copy [`simloop-starter/`](simloop-starter) into the root of your FTC project, add one line to the bottom
of `TeamCode/build.gradle`, and run the tests:

```groovy
apply from: "$rootDir/simloop-starter/simloop.gradle"
```

```bash
./gradlew :TeamCode:testDebugUnitTest
```

Two tests pass in a few seconds, with no robot attached — that is the example robot in that folder
driving itself. The folder carries every build setting SimLoop needs, so nothing else in your build
changes, and removing it is deleting the folder and the line.

You do not clone this repository to use SimLoop. It resolves from JitPack like any other dependency, and
the starter folder is what declares it.

## Start here

| If you want to | Read |
|---|---|
| Adopt it in an existing FTC project | [`simloop-starter/README.md`](simloop-starter/README.md) |
| Install it by hand, or know what the starter is doing | [`SimLoop/docs/getting-started.md`](SimLoop/docs/getting-started.md) |
| Write a first test, line by line | [`SimLoop/docs/first-test.md`](SimLoop/docs/first-test.md) |
| Understand a package, with units and frame on every number | [`SimLoop/docs/`](SimLoop/docs/index.md) |
| Know what it deliberately does **not** do | [`SimLoop/docs/limits.md`](SimLoop/docs/limits.md) |
| Cut a release, or check the coordinate | [`SimLoop/PUBLISHING.md`](SimLoop/PUBLISHING.md) |
| Change code in this repository | [`SimLoop/CLAUDE.md`](SimLoop/CLAUDE.md) |

`SimLoop/docs/` is written to be read as Markdown in the repository and also builds into a site. Once
GitHub Pages is switched on for this repository — the steps are at the top of
[`.github/workflows/docs-publish.yml`](.github/workflows/docs-publish.yml) — the rendered site is served
at `simloop.horizon36596.org`, with the generated API reference under `/javadoc/`.

## What is in this repository

| Path | What it is |
|---|---|
| [`SimLoop/`](SimLoop) | The library. The only thing that is published. |
| [`simloop-starter/`](simloop-starter) | The folder teams copy: the build settings, and the example robot. |
| [`examples/`](examples/build.gradle) | A Gradle module with no sources of its own — it compiles the starter folder and runs its tests, so the thing teams copy cannot rot. Never published. |

## Why the library sits in a subdirectory

The library is a Gradle **subproject** rather than the root project. That reads as redundant and is not,
for two reasons that are both cheaper to keep than to change:

- The Android Gradle Plugin's library plugin is applied to something it expects. A root project that is
  itself an Android library is a layout nothing else in the FTC ecosystem uses.
- The published coordinate stays `com.github.Horizon-36596.SimLoop:SimLoop`. JitPack derives the group
  from the repository owner and the artifact from the module, so flattening the layout would silently
  rename the artifact out from under anyone already depending on it.

So `SimLoop/` in a path and `:SimLoop` in a Gradle task are correct everywhere they appear, including in
the CI workflows.

## Building and testing it

From the repository root:

```bash
./gradlew test
```

361 JVM tests — 359 in the library, 2 in the example robot — with no device and no emulator. The
documentation site and the API reference:

```bash
pip install -r SimLoop/docs-requirements.txt
./gradlew :SimLoop:docsSite
```

All of it is built on every push by [`.github/workflows/test.yml`](.github/workflows/test.yml), so none
of it can quietly become something that only builds on one laptop.

## Licence

**AGPL-3.0-or-later** — [`LICENSE`](LICENSE) is the licence text, [`NOTICE.md`](NOTICE.md) is the
copyright and what it covers. Copyright (C) 2026 Horizon (FTC 36596).

For an FTC team, in practice: **use it, change it, run it, with no obligation at all.** The copyleft term
is triggered by *distributing*, not by using, so a private season repository owes nothing. If you publish
your robot code, or hand a build of it to anyone outside your team, then what you hand over has to be
AGPL-3.0 as well and its source has to be available.

SimLoop's own dependencies are three and they keep their own licences: the FIRST Tech Challenge SDK's
`RobotCore` and `Hardware` (BSD), and PsiKit. This licence covers SimLoop's own code and nothing else,
which is what [`NOTICE.md`](NOTICE.md) states exactly.
