# API reference

Every public class, method and field in `org.horizon36596.simloop`, with **units and frame stated on
every number**.

[Open the API reference](/javadoc/index.html){ .md-button .md-button--primary }

That link works on the published site, where the javadoc is copied in beside these pages by the site
build. If you are reading this locally after `mkdocs build` alone, generate the javadoc first:

```powershell
./gradlew :SimLoop:apiDocs
```

and open `SimLoop/build/docs/apiDocs/index.html` directly.

(Run it from the repository root, not from inside `SimLoop/`. The library is a Gradle subproject of a
repository that holds only it, so the `:SimLoop` prefix is correct.)

## How it is kept honest

The javadoc is generated with `-Xdoclint:all` and `-Werror`, so a public member with no comment, a missing
`@param` or `@return`, or a broken `{@link}` **fails the build**. It is not a report somebody is supposed
to read; it is a gate.

That matters more here than in most libraries, because this reference is read by AI agents as well as
people, and an agent handed `setTarget(double)` with no unit will pick one.

The same build ships in every release as `SimLoop-<version>-javadoc.jar`, so the pages your IDE shows and
the pages on this site are the same build.
