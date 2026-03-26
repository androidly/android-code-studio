# Decompose Codex Termux Bridge

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with [.agent/PLANS.md](D:/android-code-studio/.agent/PLANS.md).

## Purpose / Big Picture

The Termux bridge for Codex is one of the most volatile pieces of the AI runtime. [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt) currently mixes installer shell-script generation, config export, status checks, and launcher repair logic. That makes every installer fix difficult to review because most of the file is one huge `buildString`.

After this refactor, the installer shell-script builder should live in its own Kotlin file. A novice should be able to open one file to understand how the Termux installer command is assembled, without reading the bridge’s config-export logic at the same time.

## Progress

- [x] (2026-03-26 03:20 +08:00) Audited `CodexTermuxBridge.kt` and selected the installer shell-script builder as the first extraction slice.
- [x] (2026-03-26 03:30 +08:00) Extracted the installer shell-script builder into `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxInstallerScriptSupport.kt`.
- [x] (2026-03-26 03:31 +08:00) Re-measured `CodexTermuxBridge.kt` after the extraction. It dropped from about 38.2 KB / 26 functions to about 21.7 KB / 26 functions, so it is no longer above the 30 KB threshold.
- [x] (2026-03-26 03:31 +08:00) Ran `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` from `D:\android-code-studio` and got `BUILD SUCCESSFUL`.
- [ ] Select the next large `core/app` target now that `CodexTermuxBridge.kt` is below the threshold.

## Surprises & Discoveries

- Observation: The single largest contiguous block in `CodexTermuxBridge.kt` is the installer command builder.
  Evidence: `buildInstallCommand` begins around line 214 and runs for more than 250 lines of shell-script generation before the launch-configuration code resumes.

- Observation: The installer-shell extraction alone was enough to push `CodexTermuxBridge.kt` well below the size threshold.
  Evidence: After moving the shell builder into `CodexTermuxInstallerScriptSupport.kt`, `CodexTermuxBridge.kt` measured about 21.7 KB and still compiled successfully.

## Decision Log

- Decision: Start with the installer shell-script builder rather than the config-export path.
  Rationale: The installer builder is huge but self-contained. Extracting it reduces file size and review complexity without changing the bridge’s runtime state model.
  Date/Author: 2026-03-26 / Codex

- Decision: Stop on `CodexTermuxBridge.kt` after the first milestone rather than continuing into the export path immediately.
  Rationale: The file dropped below the threshold after the installer builder moved out, so the next best return is now on other oversized `core/app` files.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

Milestone 1 landed successfully. `CodexTermuxBridge.kt` is now below the size threshold and the installer shell builder has a dedicated home. The next high-value target should now move to one of the remaining oversized files such as `AIToolExecutor.kt` or `aiAgentPrefExts.kt`.

## Context and Orientation

The relevant files live under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/`.

Key files:

- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexTermuxBridge.kt): the bridge entry point for installer launch, Codex export, preset application, and status inspection.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexCliConfig.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/CodexCliConfig.kt): Codex provider configuration for model, base URL, auth mode, and token settings.

In this plan, "installer shell-script builder" means the logic that constructs the scripted Termux command launched by `Install Codex CLI`. In the current bridge that is the `buildInstallCommand(context)` function, which writes a full shell script containing source repair, Node/npm installation, npm registry selection, shebang repair, and wrapper validation.

## Plan of Work

Milestone 1 creates a new Kotlin file in the same package, likely named `CodexTermuxInstallerScriptSupport.kt`. That file should own the shell-script string construction. The bridge should keep installer launch behavior unchanged and simply delegate to the extracted builder.

## Concrete Steps

Work from `D:\android-code-studio`.

1. Create a new Kotlin file under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/external/`.
2. Move the shell-script builder from `CodexTermuxBridge.kt` into that file.
3. Replace `buildInstallCommand(context)` with a delegation call.
4. Re-measure `CodexTermuxBridge.kt`. If it falls below the 30 KB threshold, stop and move to the next target.
5. Compile:

   `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`

Observed after Milestone 1:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 33s`

## Validation and Acceptance

Acceptance for Milestone 1:

- `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeds from `D:\android-code-studio`.
- `CodexTermuxBridge.kt` no longer directly contains the giant installer shell `buildString`.
- A novice can point to one file that owns the installer shell-script assembly.

## Idempotence and Recovery

This refactor is source-only. If compilation fails, revert to the last compiling state, update this ExecPlan with the failure, and retry with a smaller helper surface instead of mixing installer and export-path refactors together.
