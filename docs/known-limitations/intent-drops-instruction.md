# Known Limitation — `intent` path drops the user's instruction

**Found:** 2026-09-26, during Sprint 1 while writing the user manual.
**Status:** open. Not fixed in Sprint 0 or Sprint 1 so far.
**Related:** the same class of defect as B-003, which fixed the `full-test` workflow.

## What happens

`POST /api/v1/intent/run` accepts a natural-language `prompt` and routes it to one of
six operations. The `prompt` is used **only for intent detection** — it is never
forwarded to the operation it triggers.

In `V1IntentController.dispatch`, every branch calls the underlying service with
`null` for instruction and credentials:

```java
case FULL_TEST -> {
    new V1FullWorkflowRequest(
            new ProjectInfo(...),
            url, null, null, null, null, null);   // username, password, instruction, operationId, testType
    workflowService.runFullTest(full);
}
```

`V1IntentRequest` itself has no instruction field — only `project`, `prompt` and `url`.

## Consequence

```bash
curl -X POST /api/v1/intent/run \
  -d '{"project":{...},"url":"http://localhost:8080",
       "prompt":"test the login form and check the username hint"}'
```

The words "check the username hint" are used to decide *that* this is a test-related
request, then discarded. The planner and the test generator see nothing, and the
generated suite is generic.

This is exactly the symptom reported against `qalab test --instruction ...` before
B-003, still present on the intent entry point.

## Also inconsistent on this path

- `GENERATE_TESTS` uses `generateTestsContent`, which returns files but does **not**
  persist `GeneratedTest` rows. The `full-test` path persists them. Intent-driven
  generation therefore produces output with no database record and no healing history.
- No credentials can be supplied, so any intent that needs a login will fail at the
  explore step even if the same call would succeed via `qalab test --username/--password`.

## Fix

1. Add `instruction` (and optionally `username`/`password`) to `V1IntentRequest`.
2. Thread it through `dispatch` into each branch, normalising with
   `UserInstructions.normalize` as the other entry points do.
3. Make `GENERATE_TESTS` use the same persisting path as `FULL_TEST`, or document the
   difference explicitly.
4. Add a test asserting a supplied instruction reaches the planner and the generator —
   the guard that would have caught this.

Estimate: S. Suggested ID: **B-022a** or fold into the existing B-003 follow-up.
