# Worker review batch — 2026-11-15

Read-only public-source reviews via the existing worker module imported with importlib. No build, phone operation, configuration change or key change. Every route received a different question. Requested model names are recorded; actual backend identity is not exposed by the worker API.

## Execution

- 22 assigned routes: 19 attempted, 4 returned nonempty content, 15 errored, 3 skipped.
- Concurrency 6; max_tokens 1200; timeout 60 seconds per request; one attempt, no retry or fallback.
- `ask_openai_compat` was used directly for consistent limits. Therefore this is not an exact reproduction of provider-specific worker wrappers (some choose other token parameters, headers or model selection).
- Missing-key routes: Cerebras and Mistral. Local runner not ready.
- Config loaded only in memory; known secret values and common key/Bearer/query patterns redacted before writing. Error bodies and exception URL/messages discarded; only class and HTTP status retained.
- Background job collected: completed, exit code 0. This means orchestration completed, not that all remote reviews succeeded.

## Responded

| Route | Requested model | Review usefulness |
|---|---|---|
| groq | qwen/qwen3.8-27b | Nonempty but truncated and self-contradictory. No validated new finding. |
| gemini | gemini-3.5-flash | Code fragment rather than a review. No actionable finding. |
| xkiro | mistralai/codestral-2508 | Three speculative concurrency/resource claims; not validated. |
| codexrelay-gpt61 | gpt-6.1-sol | Two useful fixture coverage observations, below. |

## Errors

- HTTP: vyceai 503; oxionai 401; anyrouter 403; carbon and intern 404; vireonix 502.
- Timeout: orcarouter and itera (approximately 60 seconds).
- URLError: clawlabs.
- RuntimeError: inception, openrouter, codexrelay, dahl, kilo, moonmath. The verified worker helper raises RuntimeError for empty response content; raw detail intentionally not retained.

These statuses describe this bounded review attempt, not permanent provider availability. In particular reasoning-token models may consume a 1200-token limit without returning final content.

## Findings to consider

1. **Medium, test coverage gap:** lifecycle fixture's single-flight scenario invokes transcription only once and does not assert the request counter. It can show that STT pauses, but cannot establish that overlapping inputs cannot dispatch a second request. Add a deterministic blocked-first-request scenario through the production enforcing entry point and assert exactly one dispatch. See codexrelay-gpt61.json and its exact source snapshot in codexrelay-gpt61.prompt.txt; source may have changed after dispatch.
2. **Medium, fixture limitation:** Handler.postDelayed returns success but does not retain/execute delayed work. These tests cannot establish lifecycle safety of delayed callbacks. Add a virtual-time queue before making timer-related claims.

No remote review finding is a device verification or independently reproduced runtime bug. Groq's negative-inflight claim conflicts with the supplied done generation/positive-counter guards and is not accepted. Xkiro's deadlock claim provides no lock cycle and is not accepted without additional evidence.

## Files

`assignments.json` records every task, readiness and source scope. Individual route JSON files contain sanitized answers or minimal error status. `summary.json` is compact status; `results.json` aggregates the same sanitized results. Prompt snapshots contain public application source only, not worker source, environment files, credentials, keystores or private device evidence.
