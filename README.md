# SimLoop

Run your FTC robot code on a laptop, deterministically, and get a log you can score.

SimLoop is a season-agnostic library from Horizon (FTC 36596). Your `Robot.init()`, your subsystems and
your OpMode run **unchanged** against fake hardware in a JVM unit test — no Control Hub, no emulator, no
Robolectric. Time comes from a fake clock rather than the wall, so the same run produces the same log
every time, and that log is a PsiKit RLOG, which AdvantageScope opens and which the `loop` package can
score automatically.

Package root: `org.horizon36596.simloop`. Status: **beta** — the API is settled enough to use and not
settled enough to promise.

## Start here

| If you want to | Read |
|---|---|
| Install it and write a first test | [`SimLoop/README.md`](SimLoop/README.md) |
| Understand a package, with units and frame on every number | [`SimLoop/docs/`](SimLoop/docs/index.md) |
| Know what it deliberately does **not** do | [`SimLoop/docs/`](SimLoop/docs/index.md), "What SimLoop is not" |
| Cut a release, or check the coordinate | [`SimLoop/PUBLISHING.md`](SimLoop/PUBLISHING.md) |
| Change code in this repository | [`SimLoop/CLAUDE.md`](SimLoop/CLAUDE.md) |

`SimLoop/docs/` is written to be read as Markdown in the repository and also builds into a site. Once
GitHub Pages is switched on for this repository — the steps are at the top of
[`.github/workflows/docs-publish.yml`](.github/workflows/docs-publish.yml) — the rendered site is served
at `simloop.horizon36596.org`, with the generated API reference under `/javadoc/`.

## Why the library sits in a subdirectory

This repository contains one library, and that library is a Gradle **subproject** rather than the root
project. That reads as redundant and is not, for two reasons that are both cheaper to keep than to
change:

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
./gradlew :SimLoop:testDebugUnitTest
```

358 JVM tests, no device and no emulator. The documentation site and the API reference:

```bash
pip install -r SimLoop/docs-requirements.txt
./gradlew :SimLoop:docsSite
```

Both are built on every push by [`.github/workflows/test.yml`](.github/workflows/test.yml), so neither
can quietly become something that only builds on one laptop.

## Licence

**AGPL-3.0-or-later** — [`LICENSE`](LICENSE) is the licence text, [`NOTICE.md`](NOTICE.md) is the
copyright and what it covers. Copyright (C) 2026 Horizon (FTC 36596).

For an FTC team, in practice: **use it, change it, run it, with no obligation at all.** The copyleft term
is triggered by *distributing*, not by using, so a private season repository owes nothing. If you publish
your robot code, or hand a build of it to anyone outside your team, then what you hand over has to be
AGPL-3.0 as well and its source has to be available.

The dependencies keep their own licences — the FIRST Tech Challenge SDK and SolversLib are BSD, PsiKit
and Pedro Pathing carry theirs. This licence covers SimLoop's own code and nothing else, which is what
[`NOTICE.md`](NOTICE.md) states exactly.
