# Owner directives and acting decisions — recorded 2026-09-30

This file is the written baseline for the mesh / multi-platform program. Every agent working on the
program treats the items below as DECIDED unless the owner overrules them. Items marked AD-* were taken
by the session under directive 5 and are explicitly submitted for owner ratification.

## Owner directives (the owner's own words, condensed; 2026-09-29 and 2026-09-30)

D-A. **Apple platforms are in scope.** macOS, iOS and iPadOS. "My ambitions have grown somewhat, and I feel
     it is time to get started with Apple development too." DECIDED. Only sequencing and shape remain open.

D-B. **The mesh is symmetric and holonic.** "The phone is not the literal hotspot." A phone borrows compute
     from a laptop, a desktop, another phone, an iPad; a car infotainment system borrows from whatever is in
     the cabin (a laptop, an iPad) to run increasingly complex visualisations and simulations; a Wi-Fi-capable
     appliance may borrow compute. Nodes act as holons: individuals and parts of a whole simultaneously. The
     long horizon is ubiquitous computing where any device can borrow the intelligence of others and rich-IO
     devices become the interface for intelligence that lives elsewhere. DECIDED as the framing.
     Boundary (unchanged): the roadmap stop-line stays. Owner-paired, owner-controlled devices are inside it;
     anything that pairs itself or serves unpaired parties is outside; an appliance with no radio is not a
     target.

D-C. **Verified positioning (facts established this session, with sources).**
     - MLCommons MLPerf Mobile v6.0 (2026-06-15): consumer-installable, Apache-2.0, Play Store + App Store +
       GitHub; Llama 3.2 1B/3B and 3.1 8B on-device. https://mlcommons.org/2026/06/mlperf-mobile-v6/
     - MLCommons MLPerf Client v1.6 (2026-04-06): PC/laptop/workstation LLM benchmark; GUI apps on the iOS and
       Mac App Stores and Steam; llama.cpp+Metal and MLX backends; standardized responsiveness/throughput
       metrics; open source. https://mlcommons.org/2026/04/mlperf-client-v1-6/
     - Google Android Bench (2026-07-08) is a leaderboard of LLMs on Android CODING tasks (developer tool),
       not a device benchmark.
     - Enterprises build their own routers: AT&T (~45B tokens/day, LiteLLM-based gateway, difficulty- and
       cache-aware, up to 56% cost cut at ~2% quality loss, ~40% of traffic on open models),
       https://about.att.com/blogs/2026/the-tokenomics-equation.html ; OpenRouter Fusion, Cognition Devin
       Fusion, Harvey, Vercel, Splunk, vLLM Semantic Router, Bedrock and Foundry native routers,
       https://pakodas.substack.com/p/llm-routers ; the server-side gateway niche is crowded and
       consolidating (Portkey acquired by Palo Alto Networks; Helicone in maintenance).
     Consequences (DECIDED): the ROUTER is ASOM's differentiator (device-resident, shared by all of a user's
     apps, BYOK keys held by the user, auditable egress ledger, no operator backend, and now a mesh of the
     user's own devices — no found project does this). The benchmark HARNESS is not a differentiator:
     adopt MLPerf Mobile's model set and metric definitions as the comparability baseline where licences
     permit; differentiate ONLY on (i) the signed, verifiable manifest consumed by other devices and apps,
     (ii) plain-text reports generated from the same data, (iii) results feeding the router. "Desktop and
     Apple coverage" is NOT a differentiator (MLPerf Client covers it). Never self-stamp "MLPerf-comparable"
     (trademark and results-messaging rules); say "measured with MLPerf Mobile's model set and metric
     definitions".

D-D. **Roadmap order stands except where AD-1 below revises it under directive 5.** v1 device validation is
     not complete; nothing ships before it.

D-E. **Platforms in scope (2026-09-30):** Android (exists), Linux, Steam Deck (SteamOS), **Windows**, macOS,
     iOS/iPadOS, and **Ubuntu Touch** (UBports). DECIDED.

D-F. **Delegation (2026-09-30):** "Go ahead and finish everything you can without my intervention. Take the
     most sensible approach and set different agents each to work on different platforms." The session
     therefore takes the pending decisions below, records them here, and proceeds.

## Acting decisions taken under D-F (submitted for ratification; owner may overrule any of them)

AD-1. **Sequencing (revises the letter of D-D).** After v1 device validation and v1.1: **v2 (engine) → mesh-1
      (the v4 core: node identity, pairing, transport, signed manifest, mesh router, asom-desktop) → v2.5 →
      v3 → remaining v4 items (overlay polish, Android-as-provider, iPad lending).** Rationale: the owner's
      stated ambition is multi-device; the router is the differentiator and the mesh router is part of it;
      v3's loops and firewall are not prerequisites for the mesh. v2.5's per-app cloud-ban column is pulled
      forward into mesh-1 because the mesh needs it. The roadmap §2 ladder is amended by this directive; the
      design brief's phased plan is the operative plan.

AD-2. **Invariant accounting: declare a THIRD amendment explicitly.** Amendment 2 stays exactly as the
      roadmap wrote it (Invariant 2, the off-by-default private-overlay/mTLS listener). **Amendment 3 (mesh)**
      is new and touches: Invariant 1 — narrowly: banded live state and OWNER-APPROVED manifests may be
      transmitted automatically, but ONLY to explicitly paired nodes, and prompt/response CONTENT is never
      automatic (this IS a relaxation of "no automatic egress" and is to be stated as one, in the roadmap and
      in user-facing copy); Invariant 3 — a new egress class `peer` (stated truthfully; overlay traffic may be
      relayed, so it is not called `lan`); Invariant 5 — node pairing identity is a pinned node key verified
      at the transport layer, not Binder (app identity on-device stays Binder-verified). Roadmap §13's count
      becomes three. Rationale: every reviewer; the audit lesson that a stated property must be true; stretching
      the two existing amendments over five invariants would blunt the escalation rule the roadmap relies on.

AD-3. **Repository placement.** All program work lands on the designated branch in separable directories:
      `docs/design/mesh/` (the brief and its reviews), `lab/` (pure-JVM lab build), `desktop/` (asom-desktop
      JVM node and per-OS packaging), `apple/` (Swift package), `ubuntu-touch/` (Click app scaffold). The
      root Gradle build and `./gradlew jvmTest` are unchanged; the lab and desktop builds are separate and
      run as separate CI jobs. The owner may split these into separate PRs.

AD-4. **The lab is authorised** (design decision D1a/D1b) as the sanctioned exception to "do not start later
      versions": it pins today's v1 behaviour as conformance vectors, and builds the manifest signer/verifier,
      the mesh-router simulator and the benchmark report generator. It ships nothing to a device.

AD-5. **Verification honesty.** Anything that cannot be compiled or run in the build environment is labelled
      as such in PROGRESS.md; hosted CI runners (ubuntu, windows, macOS incl. Apple-silicon) are used wherever
      they can compile or test a platform target; device-only items stay `NEEDS-DEVICE-VALIDATION`.

AD-6. **Models.** Opus for design and review; Sonnet for implementation (owner standing instruction).
