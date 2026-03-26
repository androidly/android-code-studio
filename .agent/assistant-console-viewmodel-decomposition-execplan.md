# Decompose AI Assistant Console ViewModel

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with [.agent/PLANS.md](D:/android-code-studio/.agent/PLANS.md).

## Purpose / Big Picture

The AI assistant console currently works, but too much behavior is concentrated in one ViewModel file: session coordination, queue handling, tool activity rendering, streaming response state, and timeline persistence all live in [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleViewModel.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleViewModel.kt). That makes every assistant change risky. After this refactor, the assistant should behave the same from a user's perspective, but the code should be split into smaller Kotlin components with clearer ownership so future UI, Codex bridge, and review-flow work can land without further inflating the ViewModel.

The first visible proof is that the existing assistant console still compiles and behaves the same after extracting the streaming-turn state machine out of the ViewModel. That includes working status updates, streamed assistant text, tool attachments, and tool completion summaries still rendering in the console.

## Progress

- [x] (2026-03-26 00:40 +08:00) Audited `core/app` large files and selected `AIAssistantConsoleViewModel.kt` as the first refactor target.
- [x] (2026-03-26 00:48 +08:00) Identified the first extraction slice: the streaming response and tool-attachment state machine inside `AIAssistantConsoleViewModel.kt`.
- [x] (2026-03-26 01:22 +08:00) Extracted the streaming-turn controller into `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantStreamingTurnController.kt` and rewired `AIAssistantConsoleViewModel.kt` to delegate streaming state, tool attachment lifecycle, and working ticker updates to it.
- [x] (2026-03-26 01:42 +08:00) Extracted the prompt execution and queue orchestration path into `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantPromptExecutionController.kt`.
- [x] (2026-03-26 01:58 +08:00) Extracted the timeline persistence slice into `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantTimelinePersistenceController.kt`.
- [ ] (2026-03-26 01:58 +08:00) Choose the next `core/app` large-file target after `AIAssistantConsoleViewModel.kt` reached the desired size threshold. The leading candidates are `AIAgentManager.kt` and `CustomProviderAgent.kt`.
- [x] (2026-03-26 01:58 +08:00) Ran `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` from `D:\android-code-studio` after Milestone 3 and got `BUILD SUCCESSFUL`.

## Surprises & Discoveries

- Observation: The assistant package is already partially decomposed. Session command handling, memory command rendering, status rendering, and timeline models were previously extracted, but the ViewModel still owns the largest mutable state machine.
  Evidence: `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/` already contains `AIAssistantSessionCommandController.kt`, `AIAssistantMemoryCommandSupport.kt`, `AIAssistantStatusCommandSupport.kt`, and `AIAssistantTimelineModels.kt`, while `AIAssistantConsoleViewModel.kt` still contains 100+ functions.

- Observation: The streaming-turn extraction needed only a small host interface and did not require changing timeline persistence storage formats.
  Evidence: `AIAssistantStreamingTurnController.kt` compiles while `AIAssistantTimelineStore.kt` and persisted message schemas remain unchanged; `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeded after the extraction.

- Observation: The execution slice also extracted cleanly once the lazy properties were given explicit types.
  Evidence: The first Milestone 2 compile failed on recursive type inference around `lazy` properties that referenced each other; after making the controller properties explicitly typed, `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeded.

- Observation: After extracting streaming, execution, and persistence into three controllers, the ViewModel dropped below the 30 KB threshold that originally flagged it as a large-file risk.
  Evidence: `AIAssistantConsoleViewModel.kt` measured about 67.6 KB before the refactor, about 45.7 KB after Milestone 1, about 36.0 KB after Milestone 2, and about 29.2 KB after Milestone 3.

## Decision Log

- Decision: Start with the streaming-turn state machine instead of queue handling or timeline persistence.
  Rationale: This slice is both large and self-contained. It owns `Working` status, streamed text buffering, tool attachment rendering, tool output aggregation, and ticker updates. Extracting it reduces the ViewModel substantially without immediately touching SQLite persistence.
  Date/Author: 2026-03-26 / Codex

- Decision: Keep behavior fixed while decomposing; do not redesign assistant UX during this refactor.
  Rationale: The user asked to start the decomposition work, not to change runtime behavior again. The safest first milestone is structural extraction with the same observable assistant behavior.
  Date/Author: 2026-03-26 / Codex

- Decision: Extract the streaming-turn state via a host interface instead of moving timeline arrays directly into the new controller.
  Rationale: The controller needed to own mutable stream state, but timeline mutation should remain centralized in the ViewModel so persistence ordering, dirty marking, and visible timeline ownership stay in one place.
  Date/Author: 2026-03-26 / Codex

- Decision: Extract execution orchestration into a second controller before touching SQLite persistence.
  Rationale: After the streaming controller moved out, the next largest coherent cluster was the prompt execution path: queueing, interruption, cancellation, callback wiring, and run-finalization. This slice reduced ViewModel churn without changing persistence semantics.
  Date/Author: 2026-03-26 / Codex

- Decision: Extract persistence scheduling and normalization into a third controller even though some loaded-window helpers remain in the ViewModel.
  Rationale: This was the largest remaining cluster and it could move without redesigning session loading. Keeping restored-window entry insertion in the ViewModel preserved local reasoning around loaded lists while still moving the storage engine logic out.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

Milestone 1, Milestone 2, and Milestone 3 all landed successfully. The assistant still compiles, and `AIAssistantConsoleViewModel.kt` is now down from roughly 67.6 KB / 125 functions to roughly 29.2 KB / 81 functions. The file is no longer on the over-30-KB watchlist. The next highest-value refactor target should now move to another large assistant core file such as `AIAgentManager.kt` or `CustomProviderAgent.kt`.

## Context and Orientation

The relevant assistant console code lives under `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/`.

The current top-level pieces are:

- [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleFragment.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleFragment.kt): the Fragment that binds UI widgets, delegates to the ViewModel, and handles editor-opening actions.
- [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleViewModel.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantConsoleViewModel.kt): the large ViewModel that currently owns execution state, streaming state, queue state, timeline state, and persistence orchestration.
- [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantTimelineModels.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantTimelineModels.kt): data models for timeline items, tool attachments, and diff previews.
- [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantTimelineStore.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantTimelineStore.kt): SQLite-backed persistence for timeline windows and message parts.
- [core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantSessionCommandController.kt](D:/android-code-studio/core/app/src/main/java/com/tom/rv2ide/fragments/assistant/AIAssistantSessionCommandController.kt): an existing example of extracting a coherent responsibility out of the ViewModel while keeping the ViewModel as the orchestration point.

In this plan, a "streaming turn state machine" means the mutable logic that manages the active assistant response while a request is running. In this repository that includes:

- the current `Working` item and its elapsed timer
- buffered partial assistant text
- attached tool cards that move through queued, running, streaming, completed, failed, or cancelled states
- the logic that converts tool callbacks into timeline item updates

That logic currently spans methods such as `startAssistantStream`, `appendAssistantDelta`, `completeAssistantStream`, `replaceOrAppendStreamToolAttachment`, `updateStreamToolAttachment`, `markPendingToolsCancelled`, and related state fields inside `AIAssistantConsoleViewModel.kt`.

## Plan of Work

Milestone 1 extracts the streaming turn state machine from `AIAssistantConsoleViewModel.kt` into a dedicated Kotlin component in the same package, likely named `AIAssistantStreamingTurnController.kt`. The new component will own the mutable fields for the active streamed response, the active tool attachments, and the working ticker job. The ViewModel will provide only the minimal host callbacks the controller needs to mutate timeline items and request state publishing. No user-visible logic changes are allowed in this milestone.

The extraction must move the following responsibilities together so the new component is coherent rather than another grab bag:

- active stream item lifecycle
- assistant text buffering and truncation decisions for live output
- tool attachment lifecycle from `onToolCallStarted` through `onToolCallCompleted`
- working elapsed time formatting and ticker scheduling
- stream reset and cancellation cleanup

Milestone 2 extracted execution queue handling into `AIAssistantPromptExecutionController.kt`. Milestone 3 extracted persistence scheduling and normalization into `AIAssistantTimelinePersistenceController.kt`. The ViewModel is now primarily a screen orchestrator again. The next phase should move to the next large `core/app` file instead of continuing to over-optimize this ViewModel.

## Concrete Steps

Work from `D:\android-code-studio`.

1. Create a new file under `core/app/src/main/java/com/tom/rv2ide/fragments/assistant/` for the streaming turn controller and move the streaming-specific mutable state and helper methods into it.
2. Replace the moved fields and methods in `AIAssistantConsoleViewModel.kt` with a controller instance and small host adapter methods.
3. After the extraction compiles, choose the next slice inside `AIAssistantConsoleViewModel.kt`. The preferred order is request execution and queue orchestration first, timeline persistence second.
4. Extract the timeline persistence slice into a dedicated assistant persistence coordinator after Milestone 2.
5. Re-measure `AIAssistantConsoleViewModel.kt` after Milestone 3 and stop work on it once it drops below the 30 KB threshold, unless a still-obvious god-object cluster remains.
6. Compile the assistant app module:

   `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon`

4. Record the compile result in this ExecPlan.

Expected successful transcript shape:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL`

Observed after Milestone 1:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 3m 23s`

Observed after Milestone 2:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 25s`

Observed after Milestone 3:

   `> Task :core:app:compileDebugKotlin`

   `BUILD SUCCESSFUL in 1m 34s`

## Validation and Acceptance

Acceptance for Milestone 1 is behavioral parity plus compilation:

- `./gradlew.bat :core:app:compileDebugKotlin --console=plain --no-daemon` succeeds from `D:\android-code-studio`.
- The assistant code still has one orchestration ViewModel, but the streaming-turn logic is no longer implemented inline there.
- A novice reading the assistant package can point to one file that owns streaming-turn state and one file that owns overall screen state.

## Idempotence and Recovery

This refactor is source-only and can be repeated safely as long as the extracted responsibilities remain behaviorally identical. If a partial extraction fails to compile, restore the ViewModel to the last compiling state, update this ExecPlan with the failure mode, and restart the milestone with a smaller slice rather than mixing unrelated extractions into the same patch.
