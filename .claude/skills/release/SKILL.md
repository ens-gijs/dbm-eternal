---
name: release
description: |
  Cut a Maven Central release of dbm-eternal. Use when the user says "cut a release",
  "release vX.Y.Z", "publish to Maven Central", or anything that means promoting a
  -SNAPSHOT to a tagged version on master. Walks the full sequence with safety checks
  before each irreversible step. Do NOT run autonomously — pause for explicit user
  confirmation before tagging or pushing.
---

# Release skill — dbm-eternal

This codifies the manual release sequence so it runs the same way every time. The
publish itself is performed by the `publish.yml` GitHub Actions workflow when a
`vX.Y.Z` tag is pushed; this skill prepares and pushes that tag.

## Inputs

If the user did not specify a version, ask before doing anything else:
- **Target version** (e.g. `0.2.0`). Must be non-SNAPSHOT and follow SemVer.
- **Next dev version** (default: bump patch and append `-SNAPSHOT`, e.g.
  `0.2.1-SNAPSHOT`). Confirm with user.

## Pre-flight (run all; abort on any failure)

Report each check's result before proceeding.

1. **Working tree clean.** `git status --porcelain` is empty.
2. **On `master`.** `git rev-parse --abbrev-ref HEAD` == `master`.
3. **Up to date with origin.** `git fetch origin && git status -sb` shows no
   "behind" indicator.
4. **CI green for HEAD.** `gh run list --branch master --limit 1 --json conclusion`
   reports `success`. If `gh` isn't available, ask the user to confirm CI is green.
5. **No `-SNAPSHOT` deps in published POM.** Run
   `./gradlew publishToMavenLocal` and grep `~/.m2/repository/io/github/ens-gijs/dbm/`
   POMs for `-SNAPSHOT`. Should only match the project's own version.
6. **Tag doesn't already exist.** `git rev-parse v<VERSION>` should fail.
7. **Tests pass.** `./gradlew clean build`.
8. **japicmp baseline check** (only if a previous release exists): run
   `./gradlew japicmp -PpreviousVersion=<previousReleaseVersion>` and surface
   the report. At 0.x this is advisory; at 1.x+ binary breaks should block.

After all checks pass, **show the user a summary and ask for explicit
confirmation** before doing any of the steps below.

## Release sequence

Each step is a separate commit so the release can be reverted cleanly if needed.

### Commit 1 — Release commit
1. Edit root `build.gradle`: set `version = '<TARGET_VERSION>'` (drop `-SNAPSHOT`).
2. Edit `CHANGELOG.md`:
   - Change `## [<TARGET_VERSION>] - TBD` → `## [<TARGET_VERSION>] - <YYYY-MM-DD>`.
   - If the release section is `[Unreleased]`, rename it to `[<TARGET_VERSION>] - <date>` and add a fresh empty `[Unreleased]` section above.
   - Update the comparison/tag links at the bottom.
3. Edit `README.md` dependency snippet to use `<TARGET_VERSION>` (no `-SNAPSHOT`).
4. `./gradlew build` — final confidence check.
5. `git add -A && git commit -m "Release <TARGET_VERSION>"`.

### Commit 2 — Tag and push
1. `git tag -a v<TARGET_VERSION> -m "Release <TARGET_VERSION>"`.
2. **Stop and confirm with user before pushing.** Pushing the tag fires the
   publish workflow and uploads to Maven Central — this is irreversible
   (you can drop a *deployment* in the Central UI, but only before promotion;
   our workflow auto-promotes via `publishAndReleaseToMavenCentral`).
3. `git push origin master && git push origin v<TARGET_VERSION>`.

### Commit 3 — Resume development
1. Edit root `build.gradle`: set `version = '<NEXT_DEV_VERSION>'` (with `-SNAPSHOT`).
2. Edit `README.md` snippet back to `<NEXT_DEV_VERSION>`.
3. `git add -A && git commit -m "Bump version to <NEXT_DEV_VERSION>"`.
4. `git push origin master`.

## Post-flight

1. **Wait for publish workflow to complete.** Poll `gh run list --workflow publish.yml --limit 1 --json status,conclusion` until the run shows `status: "completed"` with `conclusion: "success"`. Report the result to the user before proceeding.

2. **Create a GitHub release** from the tag. Once the publish workflow succeeds, run:
   ```sh
   gh release create v<TARGET_VERSION> --notes-from-tag
   ```
   This creates a release from the annotated tag, using the CHANGELOG entry as the body.

3. **Poll Maven Central** for artifact appearance (uses the `loop` skill at a
   reasonable cadence — check every 5–10 minutes, not faster). URL:
   `https://repo.maven.apache.org/maven2/io/github/ens-gijs/dbm/dbm-sql/<TARGET_VERSION>/`.
   Initial appearance is typically within ~30 minutes; full search-index
   propagation can take several hours.

4. Tell the user the release is live and link to:
   - GitHub release: `https://github.com/ens-gijs/dbm-eternal/releases/tag/v<TARGET_VERSION>`
   - Maven Central: `https://repo.maven.apache.org/maven2/io/github/ens-gijs/dbm/dbm-sql/<TARGET_VERSION>/`

## Things to refuse

- Do not push a tag the user didn't explicitly confirm.
- Do not amend or force-push to `master`.
- Do not skip the pre-flight checks even if the user says "just do it" — surface
  the failure and ask if they want to override one specific check, then proceed
  with everything else.
- Do not generate or modify GPG keys, Sonatype tokens, or
  `~/.gradle/gradle.properties`. Those are user-managed.
