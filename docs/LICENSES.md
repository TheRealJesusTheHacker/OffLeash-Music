# OffLeash Music — Dependency License Ledger

**Project license: GNU GPL-3.0** (DECIDED BY OWNER, 2026-10-23; root `LICENSE`).

**Compatibility note (recorded at the license landing):** GPL-3.0 is compatible with the Apache-2.0 stack this app is built on. Apache-2.0-licensed components (Kotlin, Jetpack Compose, AndroidX Media3, OkHttp, kotlinx-serialization, Coil) may be combined into a GPL-3.0 work — the compatibility runs Apache-2.0 → GPL-3.0, and the combined work is distributed under GPL-3.0. No component under a GPL-incompatible license may enter the dependency tree; anything whose license is not Apache-2.0 / MIT / BSD-class gets an explicit entry and a second look before it ships.

**Originality rule (unchanged by the license decision):** this ledger tracks *dependencies* — libraries we link. It is not a channel for code from other projects. No code from GPL projects in the category (SimpMusic, InnerTune, NewPipeExtractor, …) is copied or adapted into OffLeash, in whole, in part, or reworded.

| Dependency | Role | License | Status |
|---|---|---|---|
| Kotlin stdlib (via Kotlin 2.0.20) | Language runtime | Apache-2.0 | VERIFIED 2026-10-23 — resolved by the Kotlin Gradle plugin 2.0.20 |
| Jetpack Compose BOM 2024.12.01 (ui 1.7.6, material3 1.3.1, material-icons-extended 1.7.6, navigation-compose 2.7.7, lifecycle 2.8.7, activity-compose 1.9.3) | UI + navigation | Apache-2.0 | VERIFIED 2026-10-23 — resolved versions read from Maven metadata by the coordinator; compose compiler plugin 2.0.20 |
| AndroidX Media3 1.5.1 (exoplayer, session, datasource, common + transitives) | Playback + media notification | Apache-2.0 | VERIFIED 2026-10-23 — required compileSdk 35; see the toolchain note |
| OkHttp 4.12.0 (okio transitives) | HTTP transport | Apache-2.0 | VERIFIED 2026-10-23 |
| kotlinx-serialization-json 1.7.3 | JSON parsing (API layer) | Apache-2.0 | VERIFIED 2026-10-23 |
| kotlinx-coroutines-android 1.8.1 | Concurrency (app layer) | Apache-2.0 | VERIFIED 2026-10-23 |
| Coil 2.7.0 (coil-compose) | Thumbnail loading | Apache-2.0 | VERIFIED 2026-10-23 |
| kotlin-test 2.0.20 / JUnit 4.13.2 (+ hamcrest-core 1.3) | JVM tests — test scope only, never shipped in the APK | Apache-2.0 / EPL-2.0 | VERIFIED 2026-10-23 |

*Build toolchain (not shipped in the APK, no license entries): Gradle 8.9, Android Gradle Plugin 8.7.2, compileSdk/targetSdk 35, minSdk 26. The Media3 1.5.1 pairing forced the jump from the initial AGP 8.5.2 / SDK 34 skeleton — Media3 1.5.x requires libraries to compile against SDK 35; CI installs `platforms;android-35` and `build-tools;35.0.0` accordingly.*

**Ledger audit (2026-10-23):** no GPL-incompatible dependency entered the tree. The two GPL-class libraries that were *considered* during the VPN-program research era (ics-openvpn GPLv3, openvpn3 AGPLv3) belong to Sphinx's program, not this app — nothing of them is present or referenced here.
