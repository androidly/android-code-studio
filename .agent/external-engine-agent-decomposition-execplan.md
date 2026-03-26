# Decompose External Engine Agent

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with [.agent/PLANS.md](D:/android-code-studio/.agent/PLANS.md).

## Purpose / Big Picture

The Codex bridge in [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineAgent.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineAgent.kt) is still too large for one class. It mixes configuration validation, process launching, session state persistence, and JSON-event translation for Codex CLI output. That makes bridge fixes risky because CLI event parsing and process control live together.

After this refactor, the bridge should behave the same from a user's perspective, but the Codex JSON event translation layer should live in its own Kotlin file. A novice should be able to open one file to understand how Codex JSON lines become assistant text and tool events, without reading the whole agent.

## Progress

- [x] (2026-03-26 03:02 +08:00) Audited `ExternalEngineAgent.kt` and selected the Codex JSON collector plus synthetic tool-result builders as the first extraction slice.
- [x] (2026-03-26 03:12 +08:00) Extracted the Codex JSON collector into `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineCodexJsonCollector.kt`.
- [x] (2026-03-26 03:13 +08:00) Re-measured `ExternalEngineAgent.kt` after the extraction. It dropped from about 35.3 KB / 50 functions to about 27.2 KB / 42 functions, so it is no longer above the 30 KB threshold.
- [x] (2026-03-26 03:13 +08:00) Ran `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` from `D:\android-code-studio` and got `BUILD SUCCESSFUL`.
- [ ] Select the next large `core/app` target now that `ExternalEngineAgent.kt` is below the threshold.

## Surprises & Discoveries

- Observation: The external-engine package is much thinner than the custom-provider package, so one well-chosen extraction can likely get the agent below the 30 KB threshold quickly.
  Evidence: The package currently contains only `CodexCliConfig.kt`, `CodexTermuxBridge.kt`, `ExternalEngineConfig.kt`, and `ExternalEngineAgent.kt`.

- Observation: The collector extraction alone was enough to push `ExternalEngineAgent.kt` below the threshold.
  Evidence: After moving the collector and synthetic tool-result helpers into `ExternalEngineCodexJsonCollector.kt`, the remaining agent measured about 27.2 KB and still compiled successfully.

## Decision Log

- Decision: Start with the Codex JSON collector instead of the process-launching code.
  Rationale: The collector and its synthetic tool-result builders are self-contained and do not need to own process environment setup. This lowers the risk of changing runtime process behavior while still removing a large parsing-focused block from the agent.
  Date/Author: 2026-03-26 / Codex

- Decision: Stop on `ExternalEngineAgent.kt` after the first milestone rather than splitting process-launch logic immediately.
  Rationale: Once the collector moved out, the file dropped below the size threshold. Further slicing can wait until there is a concrete behavior change that touches process launch or session-file handling.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

Milestone 1 landed successfully. `ExternalEngineAgent.kt` is now below the size threshold and the Codex JSON event translation layer has a dedicated home. The next high-value target should shift to another oversized `core/app` file instead of continuing to subdivide the external-engine agent immediately.

## Context and Orientation

The relevant files live under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/`.

Key files:

- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineAgent.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineAgent.kt): the main external-engine bridge for Codex CLI.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt): installs and configures the Termux-side Codex runtime.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineConfig.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/ExternalEngineConfig.kt): stores display-name, command-template, and workdir settings.

In this plan, "Codex JSON collector" means the layer that reads Codex `--json` output lines and turns them into AndroidCodeStudio assistant events. In the current code that includes:

- collecting `thread.started`, `item.started`, `item.completed`, `turn.failed`, and `error`
- storing the thread ID in the external session file
- translating `command_execution` items into tool-call start/output/completion events
- translating `file_change` items into synthetic `apply_patch`-style tool results
- accumulating assistant text from `agent_message` items

Today that logic lives in `ExternalEngineAgent.kt` as the `CodexJsonStreamCollector` inner class plus helper methods `buildSyntheticCommandToolCall`, `buildSyntheticCommandResult`, and `buildSyntheticFileChangeResult`.

## Plan of Work

Milestone 1 creates a new Kotlin file in the same package, likely named `ExternalEngineCodexJsonCollector.kt`. That file should own the JSON-line collector and the synthetic tool-result helpers. `ExternalEngineAgent.kt` should instantiate the collector and pass only the callbacks and session-file read/write helpers it needs.

This milestone must not change process startup, command arguments, environment variables, or session-file naming. It is a structural parsing extraction only.

## Concrete Steps

Work from `D:\android-code-studio`.

1. Create a new Kotlin file under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/`.
2. Move the Codex JSON collector and synthetic tool-result helpers from `ExternalEngineAgent.kt` into that file.
3. Replace the old inner-class usage with the extracted collector.
4. Re-measure `ExternalEngineAgent.kt`. If it falls below the 30 KB threshold, stop and move to the next target.
5. Compile:

   `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`

Observed after Milestone 1:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 31s`

## Validation and Acceptance

Acceptance for Milestone 1:

- `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeds from `D:\android-code-studio`.
- `ExternalEngineAgent.kt` no longer directly contains the Codex JSON collector inner class.
- A novice can point to one file that owns Codex JSON event translation.

## Idempotence and Recovery

This refactor is source-only. If it fails to compile, revert to the last compiling state, note the failure in this ExecPlan, and retry with a smaller helper surface rather than mixing process-start changes into the same patch.
