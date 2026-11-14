# Agent request lifecycle and budgets

## Ownership and transport

`AgentRequestControl` belongs to **one agent execution**, shared by its main model and summary
requests. `newRequest(policy)` creates one `AgentRequestScope` per attempt. Cancelling a scope
cancels only that attempt; cancelling the control stops all of that execution's scopes and rejects
new attempts. It does not cancel another execution using the same model, dispatcher or connection
pool. Always close each scope in `finally`, and close the control when the execution finishes.

`AndroidCompatibleSseHttpClientBuilder` keeps its existing constructor. It obtains the SDK's
OkHttp builder using its **public** accessor, copies it, and composes the existing event listener
with a capture listener using OkHttp 5's `EventListener.plus`. Request conversion, headers, cache,
affinity, timeout configuration, HTTP errors and the Android-safe SSE parser remain at the existing
boundary. No SDK-private reflection or dispatcher-wide `cancelAll()` is involved.

`scope.bind { model.chat(...) }` establishes a thread-local only while entering the SDK. OkHttp's
event-listener factory captures it synchronously in `newCall`; `callStart` registers the fully
initialized `Call` before DNS, connect, TLS or response headers. Thus cancellation works before the
first SSE event as well as while consuming SSE or a non-streaming response body. The SDK's async
HTTP path is supported too, including cancellation of its returned future. `callEnd`/`callFailed`
unregister completed calls. Socket errors produced by scope cancellation propagate as the original
scope cancellation/timeout reason rather than becoming retryable transport errors.

The binding is **not** a coroutine context element or an inheritable thread-local. If the caller
dispatches `model.chat()` onto another executor/coroutine thread, bind **inside** that worker. Do
not close a streaming scope when `model.chat()` merely returns: keep it through the terminal
callback/wait. Raw reactive `HttpClient.stream()` subscription is outside the application's
current API and is not given an additional subscription-time scope adapter here.

## Deadline meanings

`AgentRequestTimeoutPolicy(totalTimeoutMillis, firstEventTimeoutMillis, idleTimeoutMillis)`:

- **Total** starts when the attempt scope is created and never extends. Keep
  `requestTimeoutSeconds * 1000` as the configured total single-request limit.
- **First event** applies until meaningful model output arrives. Headers, heartbeat comments and
  empty SSE events do not count.
- **Idle** starts/reset only on `scope.onMeaningfulEvent()`: nonempty text, thinking or partial
  tool-argument deltas. It is independent of total elapsed time.
- `policy.evaluate(elapsedMillis, lastEventElapsedMillis)` is pure, with total taking precedence at
  a simultaneous expiry. Defaults for first-event and idle equal the total, preserving the old
   configured total semantics for compatibility callers. Production integration selects first-event
   `min(total, 120s)` and idle `min(total, 60s)` for streams. Text, thinking and partial tool arguments
   all reset idle. Non-streaming has no observable intermediate progress, so both phase limits equal
   total there; a long synchronous reasoning response retains the user's full timeout.

The production scheduler closes the real calls at the nearest deadline. The scheduler and
monotonic `AgentRequestClock` are injectable for deterministic tests. `checkTimeout()` and
`throwIfStopped()` also enforce deadlines; `stopReason` retains the winning terminal reason.
The timer cannot interrupt third-party blocking DNS implementations or arbitrary model code,
but calls are marked cancelled immediately and cannot continue normal network work afterward.

## Integrated lifecycle (completed)

1. **Factory:** no new argument is required. All models already using
   `AndroidCompatibleSseHttpClientBuilder` automatically capture bound scopes, including streaming
   Chat Completions and non-streaming Chat Completions/Responses summary models. Keep the existing shared
   pool, headers and per-call upper bound. The scope may impose a shorter remaining-round limit.
2. **Executor/stop:** the Service creates one `AgentRequestControl` before starting its worker and
   passes it through Executor → Gateway and ContextManager → Compaction. Stop and `onDestroy` call
   `control.cancel()` before interrupting the worker, including during synchronous chat. Executor's
   `use` and the service worker's `finally` close it. Direct executor/gateway callers get compatible
   per-execution defaults. Cancellation never uses a global dispatcher.
3. **Gateway:** `AgentRoundBudget` is created once outside retries, network recovery and
   streaming-to-nonstreaming fallback for each model/tool round. Its budget equals the configured
   single-request timeout, not four times that timeout.
   Before each attempt, compute `round.requestTimeoutMillis(configuredTimeoutMillis)`; zero throws
   `AgentRequestTimeoutException(ROUND_BUDGET)`. Create a scope using that total, bind the actual
   SDK invocation, and close it in the existing terminal/wait `finally`. Forward nonempty response,
   thinking and tool deltas to `scope.onMeaningfulEvent()`; SDK streaming handles are optional extra
   cancellation, not the pre-first-event solution. On a waiting-thread cancellation, call
   `scope.cancel()` before exiting. Fake/custom models also need `scope.throwIfStopped()` in waits.
4. **Backoff/network:** test `round.canWait(delayMillis)` before waiting. Recheck remaining time
   during/after recovery; clamp each recovery wait to the remaining budget and recheck before the
   next attempt. Time is measured from the original round start, so backoff/network delays cannot
   reset the budget. A `ROUND_BUDGET` timeout ends that round, even though socket timeouts remain
   generally retryable under the ordinary short policy when budget remains.
5. **Compaction:** uses the same execution control and fresh attempt scopes binding actual
   sync/streaming calls. It has a separate cumulative budget equal to `summaryTimeoutMillis` (the
   executor uses the user's request timeout). Shrinking, protocol flattening, one template correction
   and transport retries all spend that budget. Main-round elapsed time also keeps advancing while
   compaction runs. Failed/cancelled summaries leave the original checkpoint and archive intact.

6. **Execution budget:** the shared control tracks elapsed execution time and available provider
   token usage across main and summary requests, including completed empty attempts. The default
   `AgentExecutionPolicy` is two hours, no token ceiling, and three identical no-progress batches.
   Duration/token ceilings are nullable constructor settings. Checks happen before a request or at
   complete tool boundaries; a limit never discards already completed tool results. Resuming starts
   a fresh execution budget. Unknown provider usage cannot provide an exact token ceiling.

`SocketTimeoutException` no longer classifies as a connection abort. Socket reset, broken pipe,
EOF and closed-channel failures retain the existing reconnection policy. Existing pool eviction
for those failures is separate from cancellation and is not used to stop an active request.

## Verification boundary

Focused tests cover pure first/idle/total policy, finite cumulative retry budgets, deterministic
clock/scheduler expiry, scope isolation and cancellation races, plus localhost HTTP/SSE calls and
socket release. A silent localhost TLS peer observes the client socket closing before the first
event; MockWebServer cases observe real OkHttp connection-release and call-terminal events before
unblocking the server's response body. Production factory tests cover scope capture for both model
endpoints. These establish client-side cancellation, not upstream provider billing behavior.
No live model service or Android device is needed. `AgentGatewayLifecycleTest` adds real Gateway /
ContextManager localhost cancellation for sync, streaming and both summary transports; independent
owners remain active. Injected time covers meaningful progress, all three request deadlines, fallback,
network waits and summary correction budgets. Service destruction and executor model switching have
separate integration regressions. Full commands/results are recorded in `ai-agent-efficiency.md`.

Transport fixtures also register owners/servers for runner-thread `@After` cleanup: JUnit 4's timed
test interrupt does not join a blocked worker. The silent TLS peer uses a dedicated executor and
an exception-propagating readiness future with accepted/secure-connect/ClientHello diagnostics.
The existing 5-second test and 2-second EOF/terminal assertions are unchanged. Earlier intermittent
failures are not attributed to a confirmed single root cause solely because subsequent runs passed.
