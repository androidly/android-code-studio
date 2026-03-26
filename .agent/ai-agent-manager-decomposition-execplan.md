# Decompose AI Agent Manager

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with [.agent/PLANS.md](D:/android-code-studio/.agent/PLANS.md).

## Purpose / Big Picture

The assistant screen is no longer blocked on its own ViewModel, but the AI runtime still has a large central manager at [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentManager.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentManager.kt). That file currently mixes provider lifecycle, session persistence, tool resolution, native tool orchestration, forced-finalization fallback, and public UI-facing APIs. This makes every AI change risky because too many unrelated code paths meet in one class.

After this refactor, the assistant should behave the same, but the manager should delegate one coherent responsibility to a dedicated Kotlin component. The first milestone focuses on the tool and response resolution engine so a novice can point to one file that owns "ask the agent, handle tool loops, execute tools, and force a final answer when needed" without reading the entire manager.

## Progress

- [x] (2026-03-26 02:08 +08:00) Audited `AIAgentManager.kt` and identified the first extraction slice: response resolution and tool/native-tool orchestration.
- [x] (2026-03-26 02:20 +08:00) Extracted the response resolution engine into `core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentResponseResolver.kt` and rewired `AIAgentManager.kt` to delegate tool/native-tool response loops to it.
- [x] (2026-03-26 02:23 +08:00) Re-measured `AIAgentManager.kt` after the first slice. It dropped from about 35.2 KB / 51 functions to about 22.0 KB / 45 functions, so it is no longer above the 30 KB threshold.
- [x] (2026-03-26 02:23 +08:00) Ran `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` from `D:\android-code-studio` and got `BUILD SUCCESSFUL`.
- [ ] Select the next large `core/app` target now that `AIAgentManager.kt` is below the threshold.

## Surprises & Discoveries

- Observation: `AIAgentManager.kt` already has several helper classes around it, but the largest runtime loop still lives inline.
  Evidence: The package already contains `AIAgentRequestRetrySupport.kt`, `AIAgentSessionSupport.kt`, `AIAgentFileModificationProcessor.kt`, and `AIAgentAssistantPreviewSupport.kt`, while `AIAgentManager.kt` still contains `resolveAgentResponse`, `resolveNativeToolAgentResponse`, `finalizeToolRunWithCurrentContext`, and `attemptForcedFinalAnswer`.

- Observation: The response-resolution extraction removed the biggest single cluster from `AIAgentManager.kt` without needing any UI-layer changes.
  Evidence: The new `AIAgentResponseResolver.kt` owns the tool/native-tool loops and forced-finalization logic, and `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeded immediately after the manager delegated to it.

## Decision Log

- Decision: Start with the response resolution engine instead of provider lifecycle or UI helper methods.
  Rationale: The longest, most self-contained logic in `AIAgentManager.kt` is the loop that asks the agent for a response, detects tool calls, executes tools, manages native tool sessions, and forces a final answer when the loop guard trips. That is both large and behaviorally central.
  Date/Author: 2026-03-26 / Codex

- Decision: Stop on `AIAgentManager.kt` after the first milestone rather than forcing another extraction immediately.
  Rationale: Once the response resolver moved out, `AIAgentManager.kt` dropped below the over-30-KB threshold and became much less risky. Continuing to split it immediately would have lower return than moving on to the next oversized file.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

Milestone 1 landed successfully. `AIAgentManager.kt` is now below the size threshold that triggered this refactor pass, and the response/tool resolution engine has a dedicated home in `AIAgentResponseResolver.kt`. The next high-value target should now shift to another oversized assistant/provider file instead of continuing to chase smaller leftovers in the manager.

## Context and Orientation

The relevant files live in `core/app/src/main/java/com/tom/rv2ide/artificial/agents/`.

The important current pieces are:

- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentManager.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentManager.kt): the central manager that owns current provider, current agent, session support, tool execution, and request execution.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentRequestRetrySupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentRequestRetrySupport.kt): retry and provider auto-switch behavior.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentSessionSupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentSessionSupport.kt): short-turn memory and persistent session state.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentAssistantPreviewSupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentAssistantPreviewSupport.kt): streamed preview display decisions.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentToolPresentationSupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AIAgentToolPresentationSupport.kt): tool protocol text and fallback finalization prompts.

In this plan, "response resolution engine" means the part of the manager that:

- asks the current agent for a response
- detects whether the response contains structured tool calls
- executes those tools and feeds results back into the loop
- handles the native-tool variant of that loop for providers that support structured tool turns directly
- stops looping safely and asks for a final natural-language answer when repeated tool calls would waste more rounds

Today those behaviors are implemented inline in `AIAgentManager.kt` mainly through `resolveAgentResponse`, `resolveNativeToolAgentResponse`, `finalizeToolRunWithCurrentContext`, and `attemptForcedFinalAnswer`.

## Plan of Work

Milestone 1 creates a new Kotlin component in the same package, likely named `AIAgentResponseResolver.kt`. That component will receive the minimum host surface it needs: current agent access, native tool agent access, tool execution permission state, session-context access, tool-result session summarization, and session-turn recording. The component will own the response-resolution loop and return the same `ResolvedAgentResponse` model the manager already uses.

The manager should remain the public API surface. It will still own `executeRequest`, provider lifecycle, session identity, and modification post-processing. But it should stop directly implementing the low-level response/tool loop itself.

## Concrete Steps

Work from `D:\android-code-studio`.

1. Create a new Kotlin file under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/` for the response resolver.
2. Move `resolveAgentResponse`, `resolveNativeToolAgentResponse`, `finalizeToolRunWithCurrentContext`, and `attemptForcedFinalAnswer` into the new resolver, plus the smallest required host interface.
3. Replace the moved logic in `AIAgentManager.kt` with a resolver instance and a single delegation call.
4. Re-measure `AIAgentManager.kt`. If it is below the 30 KB threshold after the first milestone, stop and pick the next target instead of over-splitting.
5. Compile:

   `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`

Observed after Milestone 1:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 28s`

## Validation and Acceptance

Acceptance for Milestone 1:

- `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeds from `D:\android-code-studio`.
- `AIAgentManager.kt` no longer directly contains the full response/tool resolution engine.
- A novice can point to one file that owns tool/native-tool response resolution and one file that still owns the higher-level request lifecycle.

## Idempotence and Recovery

This refactor is source-only. If compilation fails midway, revert to the last compiling state, record the failure mode here, and retry with a smaller host interface instead of moving unrelated manager responsibilities in the same patch.
