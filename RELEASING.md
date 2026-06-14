# Releasing

InferenceStore publishes to Maven Central via the
[com.vanniktech.maven.publish](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin (host: Central Portal). Coordinates: `dev.mattramotar.inferencestore:<module>`.

## Versioning

`VERSION_NAME` in `gradle.properties` is the single source of truth (SemVer). A
`-SNAPSHOT` suffix publishes to the snapshot repository (unsigned); a release version
(no suffix) is signed and published to Maven Central.

## One-time setup (maintainers)

Provide these as Gradle properties / environment variables (never commit them):

- `ORG_GRADLE_PROJECT_mavenCentralUsername` / `ORG_GRADLE_PROJECT_mavenCentralPassword`
  — Central Portal user token.
- `ORG_GRADLE_PROJECT_signingInMemoryKey` / `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`
  — ASCII-armored GPG secret key + passphrase.

In CI these come from repository secrets (see `.github/workflows/publish.yml`).

## Publish a snapshot

Run the **Publish** workflow manually (`workflow_dispatch`) with `VERSION_NAME` ending
in `-SNAPSHOT`, or locally:

```bash
./gradlew publishToMavenCentral   # with VERSION_NAME ending in -SNAPSHOT
```

Verify locally without credentials (unsigned, to `~/.m2`):

```bash
./gradlew publishToMavenLocal
```

## Cut a release

1. Ensure `main` is green (`./gradlew build` — full target matrix on CI).
2. Move the `CHANGELOG.md` `[Unreleased]` items under a new `## [x.y.z]` heading (dated).
3. Set `VERSION_NAME=x.y.z` (drop `-SNAPSHOT`) in `gradle.properties`; commit.
4. Tag and push: `git tag vx.y.z && git push origin vx.y.z`. The publish workflow signs
   and publishes to Maven Central (`SONATYPE_AUTOMATIC_RELEASE=true`).
5. Create the GitHub release with the changelog section as notes.
6. Set `VERSION_NAME=x.y.(z+1)-SNAPSHOT`; commit to resume development.

## Release checklist

- [ ] `./gradlew build` green (common / JVM / Android / iOS).
- [ ] `CHANGELOG.md` updated and dated.
- [ ] `VERSION_NAME` set to the release version.
- [ ] Tag `vx.y.z` pushed; publish workflow succeeded.
- [ ] Artifacts visible on Maven Central; coordinates in the Quickstart resolve.
- [ ] `VERSION_NAME` bumped back to the next `-SNAPSHOT`.
