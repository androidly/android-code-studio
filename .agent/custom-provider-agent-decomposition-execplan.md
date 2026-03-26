# Decompose Custom Provider Agent

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with [.agent/PLANS.md](D:/android-code-studio/.agent/PLANS.md).

## Purpose / Big Picture

The custom provider path is one of the most active parts of the assistant runtime. Even after several helper files were added around it, [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderAgent.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderAgent.kt) still mixes prompt construction, native tool-turn context building, provider request orchestration, retry context, compatibility learning, and modification history state in one class. That makes future work on custom providers, responses continuation, and tool behavior harder than it should be.

After this refactor, the agent should behave the same, but the main class should delegate one coherent concern to a dedicated Kotlin support file. The first milestone focuses on prompt and context construction so the class stops directly owning large prompt-building blocks for both plain text and native tool sessions.

## Progress

- [x] (2026-03-26 02:28 +08:00) Audited `CustomProviderAgent.kt` and identified the first extraction slice: prompt/context/native-conversation construction.
- [x] (2026-03-26 02:40 +08:00) Extracted prompt/context/native-conversation construction into `core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderPromptContextSupport.kt`.
- [x] (2026-03-26 02:49 +08:00) Extracted provider request and response orchestration into `core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderRequestCoordinator.kt`.
- [x] (2026-03-26 02:50 +08:00) Re-measured `CustomProviderAgent.kt` after the second slice. It dropped from about 37.8 KB / 58 functions to about 20.6 KB / 39 functions, so it is no longer above the 30 KB threshold.
- [x] (2026-03-26 02:50 +08:00) Ran `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` from `D:\android-code-studio` after both milestones and got `BUILD SUCCESSFUL`.
- [ ] Select the next large `core/app` target now that `CustomProviderAgent.kt` is below the threshold.

## Surprises & Discoveries

- Observation: The custom provider package is already partially modularized.
  Evidence: The package already contains `CustomProviderRequestExecutor.kt`, `CustomProviderNativeResponseParser.kt`, `CustomProviderRequestPayloadSupport.kt`, `CustomProviderConversationCompactionSupport.kt`, and other support files, but the main agent still owns a large amount of prompt/context logic.

- Observation: `CustomProviderAgent.kt` dropped below the 30 KB threshold after two coherent extractions, without changing the surrounding helper package structure.
  Evidence: After moving prompt/context code and then request orchestration out, the main agent measured about 20.6 KB and still compiled with `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`.

## Decision Log

- Decision: Start with prompt/context/native-conversation construction instead of request execution.
  Rationale: This slice is coherent, pure Kotlin, and easy to move without changing provider behavior. It is also a visible contributor to the file size and reduces cognitive load in the main agent before touching request execution.
  Date/Author: 2026-03-26 / Codex

- Decision: Follow the prompt/context extraction with request orchestration instead of stopping after the first milestone.
  Rationale: After Milestone 1 the file was still slightly above the 30 KB threshold, and the request-orchestration block was the next most self-contained concern. Extracting it reduced the file sharply while preserving the existing helper architecture.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

Milestone 1 and Milestone 2 both landed successfully. `CustomProviderAgent.kt` is now below the size threshold that triggered this refactor pass, and the file reads much more like a stateful facade over support components than a monolithic implementation. The next step should move to the next oversized AI-runtime file rather than continuing to over-split the custom provider agent.

## Context and Orientation

The relevant files are under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/`.

Key files:

- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderAgent.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderAgent.kt): the main agent class for custom OpenAI-compatible or Claude-compatible gateways.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderRequestExecutor.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderRequestExecutor.kt): executes HTTP requests and compatibility fallback retries.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderNativeResponseParser.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderNativeResponseParser.kt): parses native tool-turn responses and streams.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderRequestPayloadSupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderRequestPayloadSupport.kt): builds JSON payloads for chat, responses, and Claude messages.
- [core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderConversationCompactionSupport.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/CustomProviderConversationCompactionSupport.kt): conversation token estimation and compaction helpers.

In this plan, "prompt/context/native-conversation construction" means:

- the plain text prompt wrapper used by `generateResponse`
- the native user turn and native system prompt used by structured tool sessions
- the condensed conversation turn and current native conversation list
- correction detection and native conversation token estimation used before compaction
- JSON normalization used for streamed tool arguments

These behaviors are currently implemented inline in `CustomProviderAgent.kt` through methods such as `buildPrompt`, `buildNativeUserTurn`, `buildNativeSystemPrompt`, `currentNativeConversation`, `buildCondensedConversationTurn`, `isUserRequestingCorrection`, `autoCompactNativeConversationIfNeeded`, `estimateNativeConversationTokens`, and `normalizeJsonObjectString`.

## Plan of Work

Milestone 1 creates a new support file in the same package, likely named `CustomProviderPromptContextSupport.kt`. That file should contain pure or nearly-pure Kotlin helpers for building prompts and native conversation context. The main agent will pass its mutable state into those helpers rather than reimplementing the prompt logic inline.

This milestone should not redesign provider behavior. It must keep the same system prompt wording, retry wording, correction wording, and compaction criteria. The change is structural: move the logic, keep the output stable.

## Concrete Steps

Work from `D:\android-code-studio`.

1. Create a new support file under `core/app/src/main/java/com/tom/rv2ide/artificial/agents/custom/`.
2. Move prompt and context construction methods from `CustomProviderAgent.kt` into the new file, keeping signatures explicit and Kotlin-first.
3. Replace the old inline methods with delegation calls from `CustomProviderAgent.kt`.
4. If the file is still above the threshold, extract the provider request/response orchestration into a second dedicated coordinator.
5. Compile:

   `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`

Observed after Milestone 2:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 26s`

## Validation and Acceptance

Acceptance for Milestone 1:

- `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeds from `D:\android-code-studio`.
- `CustomProviderAgent.kt` no longer directly contains the large prompt/native-context construction block.
- The custom provider package has a dedicated file a novice can open to understand how prompts and native conversation context are constructed.
- If the second milestone is applied, `CustomProviderAgent.kt` no longer directly contains the provider request/response orchestration block either.

## Idempotence and Recovery

This refactor is source-only. If the extraction fails to compile, restore the last compiling state, update this ExecPlan with the failure mode, and retry with a smaller helper surface instead of mixing request-execution changes into the same patch.
