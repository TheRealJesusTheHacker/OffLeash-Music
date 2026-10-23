# OffLeash Music — Dependency License Ledger

**Project license: GNU GPL-3.0** (DECIDED BY OWNER, 2026-10-23; root `LICENSE`).

**Compatibility note (recorded at the license landing):** GPL-3.0 is compatible with the Apache-2.0 stack this app is built on. Apache-2.0-licensed components (Kotlin, Jetpack Compose, AndroidX Media3, OkHttp, kotlinx-serialization, Coil) may be combined into a GPL-3.0 work — the compatibility runs Apache-2.0 → GPL-3.0, and the combined work is distributed under GPL-3.0. No component under a GPL-incompatible license may enter the dependency tree; anything whose license is not Apache-2.0 / MIT / BSD-class gets an explicit entry and a second look before it ships.

**Originality rule (unchanged by the license decision):** this ledger tracks *dependencies* — libraries we link. It is not a channel for code from other projects. No code from GPL projects in the category (SimpMusic, InnerTune, NewPipeExtractor, …) is copied or adapted into OffLeash, in whole, in part, or reworded.

| Dependency | Role | License | Status |
|---|---|---|---|
| Kotlin stdlib | Language runtime | Apache-2.0 | PLANNED — version + license verified at integration |
| Jetpack Compose (BOM) | UI | Apache-2.0 | PLANNED — verify at integration |
| AndroidX Media3 (ExoPlayer, session) | Playback + media notification | Apache-2.0 | PLANNED — verify at integration |
| OkHttp | HTTP transport | Apache-2.0 | PLANNED — verify at integration |
| kotlinx-serialization | JSON parsing (API layer) | Apache-2.0 | PLANNED — verify at integration |
| Coil | Thumbnail loading | Apache-2.0 | PLANNED — verify at integration |
| kotlin-test / JUnit | JVM tests — test scope only, never shipped in the APK | Apache-2.0 / EPL-2.0 | PLANNED — verify at integration |

*Entries move from PLANNED to VERIFIED (with the exact resolved version) when the Phase 1 build integrates them; the audit for that build re-checks this table against the resolved dependency tree.*
