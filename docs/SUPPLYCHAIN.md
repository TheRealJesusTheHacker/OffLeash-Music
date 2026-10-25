# OffLeash Music — Supply chain: dependency hash verification

Fleet policy (owner, adopted 2026-10-25): every dependency install —
local, build, CI — must verify hashes against a committed lock generated
from a known-good resolution, and **fail loudly** on mismatch. Exceptions
only with documented evidence, never silent.

## The lock

`gradle/verification-metadata.xml` is this repo's lock. It was generated
by Gradle itself (`--write-verification-metadata sha256`) from the actual
resolution of the versions pinned in the build files — no version was
changed to create it. Scope of the committed file:

- **415 components** across **116 groups**
- **742 artifacts**, each with a SHA-256 checksum
  (`<verify-metadata>true</verify-metadata>`: Gradle module metadata
  (`.module`) and POM files are checksum-verified too, not just jars/AARs)
- Covers the build/plugin classpath (AGP 8.7.2, Kotlin 2.0.20 and its
  Compose/serialization plugins, and their transitive tooling) and every
  project configuration exercised by the generation run: `:core` test,
  `:app` unit tests for **both flavors (standard, hunt) in debug and
  release**, and `assemble` (debug + release, both flavors). That set
  includes the Compose BOM platform (2024.12.01), Media3 1.5.1,
  OkHttp 4.12.1, kotlinx-serialization-json 1.7.3, Coil 2.7.0,
  WorkManager 2.9.1 and JUnit 4.13.2 at exactly their pinned versions.

Enforcement is automatic: any Gradle build that resolves dependencies
with this file present verifies every downloaded artifact against it and
fails the build on a missing or mismatched checksum. The CI gate of
record (`.github/workflows/android.yml`, the "OffLeash — Android"
workflow) resolves the test and assemble configurations on every push,
so enforcement runs on every CI build with no extra step.

## Regeneration (the only way the lock changes)

Run the manual workflow `.github/workflows/generate-verification-metadata.yml`
(workflow_dispatch only — it never runs automatically and gates nothing),
download its `verification-metadata` artifact, and commit the file
together with the version change that made regeneration necessary.
That workflow is the documented regeneration path; it is kept in the repo
for exactly that purpose. Never hand-edit a checksum to make a build
pass: a mismatch means the artifact differs from the known-good
resolution — investigate first, regenerate only from a resolution you
have independently confirmed is the intended one.

## Enforcement proof (2026-10-25)

Recorded in `docs/AUDITS.md` (entry "2026-10-25 — --require-hashes
rollout"): the metadata was generated in CI from HEAD `e0013ac4`
(generation run 40197146871, success), committed, and then proven in CI
on a scratch branch carrying a deliberately corrupted checksum — that
build failed for dependency-verification reasons — after which the
scratch branch was deleted and a normal CI run on main with the correct
metadata was green.

## Boundaries — what this lock does NOT cover, and why

1. **PGP signatures are not verified** (`<verify-signatures>false</verify-signatures>`).
   The generator was run in SHA-256 mode, per fleet policy: the control
   is hash equality against this committed file. No keyring is committed,
   so a compromised repository serving an artifact *and* a matching
   forged checksum cannot happen silently — the checksum lives in git,
   not in the repository being downloaded from.
2. **Configurations never resolved are not in the lock.** The generation
   run resolved the configurations listed above. A future task that
   resolves a brand-new configuration (e.g. a lint classpath or a new
   build type) may fail verification for artifacts not yet locked; that
   failure is the policy working as intended — regenerate via the path
   above and commit the result with the change that introduced the
   configuration. The Android Gradle plugin's own lint tooling
   artifacts that *were* pulled in as plugin-classpath transitives are
   locked; a standalone `lint` task's full lint classpath was not part
   of the generation set.
3. **The Gradle distribution itself** (`gradle/wrapper/gradle-wrapper.properties`)
   is not covered by this file. The wrapper properties carry no
   `distributionSha256Sum`; that is a separate, pre-existing gap,
   recorded here rather than silently expanded into this change (the
   CI gate installs no distribution beyond what the wrapper fetches,
   and local/CI Gradle behavior was not changed by this rollout).
4. **Android SDK components** (platforms;android-35, build-tools;35.0.0)
   are installed by `sdkmanager` in CI, outside Gradle dependency
   resolution, and are not covered by this lock.
5. **GitHub Actions** are outside Gradle's scope; they are already
   pinned by full commit SHA in both workflows (pre-existing control,
   unchanged).
6. **BOM/platform nuance:** the Compose BOM is locked as a component
   (its `.module`/POM checksums are in the file) and every version it
   aligns is locked as its own component at the aligned version, because
   the generation run resolved the real configurations that consume
   the BOM. The BOM does not bypass verification.

## Other install surfaces in this repo

- **Python:** `scripts/live_smoke.py` (the non-gating CI live-smoke)
  imports only the Python standard library (`json`, `re`, `sys`,
  `urllib`). There is no `requirements*.txt`, `pyproject.toml`,
  `Pipfile`, or any `pip install` anywhere in the repo or its
  workflows, so there is no pip surface to apply `--require-hashes` to.
  If a third-party Python dependency is ever added, it must land with
  a hashed lock and `--require-hashes` wiring in the same change.
- No npm/Node, Cargo, or other package-manager surface exists in
  this repo.
