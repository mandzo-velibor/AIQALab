# Known Limitation — `intent` path drops the user's instruction

**Found:** 2026-09-26, during Sprint 1 while writing the user manual.
**Status:** **FIXED** — see "Resolution" at the foot of this file.
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


---

## Resolution

**Fixed** after Sprint 4 began, alongside B-037. All four items in the fix list above are
done.

1. `V1IntentRequest` gained `username`, `password` and `testType`. Additive and nullable,
   with a three-field constructor retained, so an existing client that sends only
   `project`, `prompt` and `url` keeps working — there is a test for exactly that.
2. `dispatch` now threads the prompt through as the instruction, normalised with
   `UserInstructions.normalize`, and passes credentials and the structured test type.
   The prompt is used **twice** on purpose: once to detect the intent, once as the
   instruction for whatever it triggered.
3. `GENERATE_TESTS` now uses the **persisting** path (`generateTestsEntities`) rather
   than `generateTestsContent`. Generating through the intent route left no
   `GeneratedTest` rows, so the tests existed only in the response: nothing to run
   afterwards, no execution history and no healing.
4. `V1IntentControllerTest` asserts the instruction reaches exploration, planning,
   generation, execution and the full workflow, and that credentials reach both
   exploration and the workflow. That last test is the guard that would have caught
   this in the first place.

One detail worth recording: `CodeGenerationService.normalizeTestType` had to become
public. Every entry point must resolve the structured filter the same way, because a
textual instruction and a structured filter that disagree silently is the bug B-003 was
about. Sharing one resolver is the only way to keep them in agreement.

**Why it survived so long.** This is the third instance of the same defect class — the
user's own words failing to reach the thing they were aimed at (B-003 on `qalab test`,
B-022's `summary` dropped between the runner and the agent, and this). Each time the
symptom looked like "the output is generic" rather than "an argument is missing", and
each time no test covered the *seam* between the entry point and the operation. The
tests written this time assert the argument arrives, not that the call returns.
