# QALabAI — User Manual

**Version:** reflects `main` @ Sprint 1 (post Sprint 0)
**Audience:** QA engineers, developers evaluating the tool, and operators deploying it.
**Status:** living document. Updated as behaviour changes — see §19.

> This manual documents **actual, verified behaviour**, not intentions. Where something
> is broken or incomplete it says so explicitly and links to a known-limitation note
> rather than glossing over it. Section 18 lists every known gap.

---

## Table of contents

1. [What this tool does](#1-what-this-tool-does)
2. [Concepts](#2-concepts)
3. [Requirements](#3-requirements)
4. [Installation](#4-installation)
5. [Configuration](#5-configuration)
6. [Quick start](#6-quick-start)
7. [CLI reference](#7-cli-reference)
8. [`qalab test` — the full workflow](#8-qalab-test--the-full-workflow)
9. [Output artifacts](#9-output-artifacts)
10. [Reading the results](#10-reading-the-results)
11. [Web UI walkthrough](#11-web-ui-walkthrough)
12. [Self-healing](#12-self-healing)
13. [Bug reports](#13-bug-reports)
14. [API reference](#14-api-reference)
15. [Architecture](#15-architecture)
16. [The AI subsystem](#16-the-ai-subsystem)
17. [Locator intelligence](#17-locator-intelligence)
18. [Known limitations](#18-known-limitations)
19. [Deployment to a cloud host](#19-deployment-to-a-cloud-host)
20. [Troubleshooting](#20-troubleshooting)
21. [Development](#21-development)

---

## 1. What this tool does

QALabAI takes the URL of a web application and produces an executable Playwright test
suite for it, then runs that suite and reports what broke.

The distinguishing feature is that **locators are not just generated, they are
tracked**. Every element the tool decides how to address gets a fingerprint, a health
score and a stability score. When a test later fails because an element moved or was
renamed, the tool can reason about *why* it broke, propose a better locator, and —
after human approval — rewrite the stored test so the next run passes. That subsystem
(locator intelligence + self-healing) is the most mature part of the codebase.

Concretely, one command:

```bash
qalab test https://staging.example.com --instruction "test the login form"
```

will open the page in a headless browser, capture and simplify its DOM, ask an LLM to
analyse it, generate locators, produce a test plan, generate Playwright specs and page
objects, write them into your workspace, execute them, and — if anything failed —
analyse the failure, classify it, propose a healing fix, and write a bug report.

### What it is not

- It is **not** a record/replay tool. Tests are generated from a single page snapshot
  plus an AI reading of it.
- It is **not** a load or performance tool.
- It is **not** a general-purpose crawler. It analyses the page you give it (and, with
  credentials, the page you land on after login).
- It **does not own your repository.** It writes generated files into a workspace path
  you nominate, and never modifies your code without an explicit, recorded approval.

---

## 2. Concepts

| Term | Meaning |
|---|---|
| **Core** | The backend service. Owns the AI gateway, the agents, the database, and artifact storage. |
| **Project** | Your application under test. Identified by a logical `projectId`; optionally registered in the Core (which yields a `databaseId`). |
| **Workspace** | A directory on disk where generated tests, page objects and Playwright's own output live. You choose it. |
| **Agent** | One AI-backed step. There are five: Explorer, Locator, Planner, TestGenerator, Executor, plus FailureAnalyst, SelfHealing and BugReport. |
| **Locator** | How an element is addressed, e.g. `page.getByRole('button', { name: 'Login' })`. Stored with fallbacks and a confidence score. |
| **Fingerprint** | A stable identity for an element (tag, role, accessible name, test id, label, placeholder, name, id, href) used to recognise the same element across runs. |
| **Page object** | A TypeScript class wrapping the locators for one page, so specs read as behavior rather than selectors. |
| **Healing proposal** | A suggested locator replacement, produced after a failure, requiring human approval before it is applied. |
| **Execution** | One recorded run of the test suite, with artifacts and a report. |
| **Operation** | One invocation of a workflow, tracked by `operationId` for live progress. |

---

## 3. Requirements

### For Docker deployment (recommended)
- Docker with Compose v2
- ~2 GB RAM available to the backend, more for large suites
- Ports 8080 (backend) and 3000 (frontend) reachable

### For local development
- JDK 21
- Node.js 20
- PostgreSQL 16 (or use the bundled compose `db` service)
- Chromium via Playwright: `npx playwright install chromium`
- At least one AI provider key

### For the CLI only
- `bash`, `curl`, and **`jq`** (recommended; there is a `python3` fallback)
- Network access to the Core

---

## 4. Installation

### 4.1 Docker Compose

```bash
git clone <repo> qalab-ai
cd qalab-ai
cp .env.example .env      # then fill in at least one AI provider key
docker compose up --build
```

This starts three services: `db` (Postgres 16), `backend` (Spring Boot on 8080) and
`frontend` (Next.js on 3000).

Then open <http://localhost:3000>.

The backend image already contains Node 20 and the Playwright Chromium build, so no
browser download happens at runtime — provided you leave
`QALAB_AUTO_INSTALL_PLAYWRIGHT` off or let the warm-up find everything already in
place (see §19.4).

### 4.2 Running the backend and frontend separately

```bash
# terminal 1 — database
docker compose up db

# terminal 2 — backend
cd backend
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# terminal 3 — frontend
cd frontend
npm install
npm run dev
```

The backend reads a `.env` file from the repository root on startup and maps its
entries onto system properties, so a root `.env` is enough for local work.

---

## 5. Configuration

### 5.1 AI providers

At least one key is required for any generation feature. The gateway tries providers
in a cascade and falls back on failure.

| Variable | Provider | Default model |
|---|---|---|
| `OPENCODE_GO_API_KEY` | OpenCode Zen "go" | `qwen3.7-plus` |
| `OPENCODE_ZEN_API_KEY` | OpenCode Zen | `space-bunny-free`, then `big-pickle` |
| `GEMINI_API_KEY` | Google Gemini | `gemini-1.5-flash` |
| `OPENAI_API_KEY` | OpenAI | `gpt-4o-mini` |
| `OLLAMA_API_KEY` | Ollama | `gpt-oss:20b` |

If none is set, AI-backed operations fail with `AI_PROVIDER_NOT_CONFIGURED`.

Models and base URLs default from `qalab.ai.providers` in `application.yml` and can be
overridden per provider with `OPENAI_MODEL`, `ANTHROPIC_MODEL`, `GEMINI_MODEL`,
`OLLAMA_MODEL` and `AIQALAB_MODEL` (plus matching `*_BASE_URL` variables).

A provider with no resolvable model is now **rejected with a message naming the missing
key**, rather than being sent `{"model": null}` and failing with an opaque provider
error. If you see `AI provider X has no model configured`, that is a genuine
configuration gap on your side.

Only **AIQALAB** (the default) uses the managed cross-provider cascade described in
§16.2. Bring-your-own-key providers are retried individually.

### 5.2 Token budget

Every AI call is metered. When the monthly allowance is exhausted:

- policy `HARD` → the call is refused with `AI_BUDGET_EXCEEDED` before any provider
  request is made
- policy `SOFT` → the call proceeds, and the workflow context is flagged so callers can
  degrade gracefully
- policy `NONE` → no limit

```bash
qalab budget-policy            # show
qalab budget-policy set SOFT   # change
```

| Variable | Default | Notes |
|---|---|---|
| `QALAB_AI_FREEMONTHLYTOKENLIMIT` | `0` (unlimited) | The **code** default was 4000 — below one full workflow run. The shipped configuration overrides it. See §20.3. |
| `QALAB_AI_RATE_LIMIT_ENABLED` | `false` | Token-bucket rate limiting on AI calls, per provider **and** per account. Turn on for any shared deployment. |
| `QALAB_AI_RATE_LIMIT_PROVIDER_RPS` / `_BURST` | `2.0` / `10` | Per-provider sustained rate and burst allowance |
| `QALAB_AI_RATE_LIMIT_ACCOUNT_RPS` / `_BURST` | `1.0` / `5` | Per-account sustained rate and burst allowance |

### 5.3 Paths and behaviour

| Variable | Default | Purpose |
|---|---|---|
| `QALAB_WORKSPACES_DIR` | `./workspaces` | Where server-side project workspaces live |
| `QALAB_SCREENSHOTS_DIR` | `./screenshots` | Page-capture screenshots |
| `QALAB_ARTIFACTS_DIR` | `./artifacts` | **Per-execution** artifacts: screenshots, videos, traces, `report.md`, `report.json`. Docker: backed by the `qalab-artifacts` volume. |
| `QALAB_AUTO_START_FRONTEND` | `true` | Dev convenience: spawns `npm run dev`. Disable in containers. |
| `QALAB_AUTO_OPEN_BROWSER` | `true` | Dev convenience: opens a browser. Disable in containers. |
| `QALAB_AUTO_INSTALL_PLAYWRIGHT` | `true` | Warm up workspaces in the **background** after startup. |
| `QALAB_PLAYWRIGHT_TIMEOUT_SECONDS` | `600` | Wall-clock budget for one Playwright run. Always finite. |
| `QALAB_PLAYWRIGHT_SCREENSHOT` | `only-on-failure` | Artifact profile for **generated** workspaces |
| `QALAB_PLAYWRIGHT_VIDEO` | `off` | " |
| `QALAB_PLAYWRIGHT_TRACE` | `retain-on-failure` | " |
| `QALAB_WORKFLOW_WORKERS` | `2` | Concurrent full-test workflows. Each holds a browser. |
| `QALAB_WORKFLOW_QUEUE_CAPACITY` | `20` | Queued workflows before the API returns `429` |
| `QALAB_WAIT_SECONDS` | `3600` | Client-side cap on how long `qalab test` waits for a result |
| `QALAB_AI_CONNECT_TIMEOUT_MS` | `10000` | Outbound AI connect timeout |
| `QALAB_AI_READ_TIMEOUT_MS` | `180000` | Outbound AI read timeout |
| `QALAB_AI_FREEMONTHLYTOKENLIMIT` | `0` | Managed monthly token allowance; `0` = unlimited |
| `OPENAI_MODEL` / `ANTHROPIC_MODEL` / `GEMINI_MODEL` / `OLLAMA_MODEL` / `AIQALAB_MODEL` | see §5.1 | Per-provider model override |

### 5.4 Deployment / CORS

| Variable | Default | Purpose |
|---|---|---|
| `QALAB_API_KEY` | — | Bearer API key the CLI sends. Also `apiKey` in `.qalab.json`. See §14.0. |
| `QALAB_REQUIRE_API_KEY` | `false` | Force API-key enforcement on `/api/**` even with no key issued. |
| `QALAB_API_BASE_URL` | `http://localhost:8080` | Where the **browser** reaches the backend. Read at runtime by the frontend's `/config.js`, so one image works everywhere. |
| `QALAB_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated browser origins allowed to call `/api/**`. Must include the frontend's public URL. |

These two are a **pair**: set them together or every API call is blocked by the browser.
`"*"` is rejected because credentials are enabled.

### 5.5 CLI configuration

`qalab init <dir>` writes `.qalab.json`:

```json
{
  "projectId": "my-app",
  "baseUrl": "https://staging.example.com",
  "framework": "PLAYWRIGHT_TYPESCRIPT",
  "language": "TypeScript",
  "workspacePath": "/abs/path/to/workspace",
  "apiUrl": "http://localhost:8080",
  "databaseId": ""
}
```

Resolution order: explicit environment variable → `.qalab.json` (searched upward from
the current directory) → built-in default. `--url` on any command overrides `baseUrl`
for that invocation.

| Variable | Meaning |
|---|---|
| `QALAB_API_URL` | Core base URL |
| `QALAB_PROJECT_ID` | Logical project id |
| `QALAB_BASE_URL` | Application under test |
| `QALAB_WORKSPACE` | Workspace for generated tests and execution |
| `QALAB_DB_ID` | Registered project id, if you have one |
| `QALAB_USERNAME` / `QALAB_PASSWORD` | Login credentials |
| `QALAB_REPORT_DIR` | Override the report output directory |
| `QALAB_FRAMEWORK` / `QALAB_LANGUAGE` | Recorded in the project payload |

---

## 6. Quick start

```bash
# 1. from your project root
qalab init .

# 2. point at your app and run the whole thing
qalab test https://staging.example.com/login \
  --instruction "focus on the login form and the username validation" \
  --username "testuser" --password "secret"
```

You should see a short summary, not a wall of JSON:

```
QA RUN COMPLETED
  url        https://staging.example.com/login
  operation  cli-1786464800-24206
  workspace  /Users/you/project/qa

  PIPELINE
    explore                ok
    analyze                ok
    locators (14 locators) ok
    testPlan (11 scenarios) ok
    generatedTests (11 specs, 12 page objects) ok
    execution              ok
    failureAnalysis        ok
    healing                ok
    bugReport              ok

  TESTS  FAILED - 7 passed, 4 failed, 0 skipped (94s)
    ✘ login-with-empty-password.spec.ts: Login with empty password (31s)
    ✘ login.spec.ts: Login shows an error for a wrong password (12s)
    ... and 2 more failing test(s)
      Error: expect(locator).toBeVisible() failed
      Timeout 30000ms exceeded.
  FAILURE ANALYSIS  ASSERTION_FAILURE
    Expected the error banner to be visible after submitting an empty password.
  HEALING  Proposed a new locator for Login Button
  BUG REPORT  [MEDIUM] Test failed: Login with empty password

  ARTIFACTS
    .qalab/reports/20260926-135000/report.json
    .qalab/reports/20260926-135000/test-plan.md
    .qalab/reports/20260926-135000/bug-report.json
    (add --json for the full response)
```

Then read `test-plan.md` first (§10.1), then the failing tests.

---

## 7. CLI reference

Run `qalab help` for the built-in list. All commands accept `--url <url>` to override
the application URL for that invocation.

### 7.1 `qalab test <url>` — the main command

Runs the entire pipeline: explore → analyse → locators → plan → generate → execute →
failure analysis → healing → bug report.

| Flag | Meaning |
|---|---|
| `--instruction "<text>"` | Natural-language guidance. **Now reaches page analysis, the planner and the test generator.** |
| `--test-type <api\|ui\|e2e>` | Deterministic filter applied to generation and execution. |
| `--username` / `--password` | Credentials, used to explore and capture the post-login page. |
| `--json` | Print the raw workflow response instead of the summary. |
| `--quiet` | Print only the report directory. For scripts. |

The summary prints a per-test breakdown — how many passed, failed and skipped, then
each failing test with its file, title, duration and first assertion message — taken
from the structured results rather than scraped from the console text (§10.2).

**Exit code:** `0` only when the workflow completed *and* the tests passed. A completed
workflow whose tests failed exits `1`, so the command is usable directly as a CI gate.

**Requires a workspace.** Without one the command refuses to run, because it could not
execute the suite and writing files to the current directory instead would be
misleading. Use `qalab generate --write` if you only want files.

**How it waits.** The CLI submits the workflow and polls for the result, printing live
stage changes to stderr as before. The server runs the work off its own request
threads, so a long run is no longer cut short by a proxy timeout (§14.1).
`QALAB_WAIT_SECONDS` (default 3600) caps the client-side wait; on expiry the CLI tells
you the operation id so you can check on it later rather than hanging.

### 7.2 `qalab plan --url <url>`

Generates a test plan and prints it. `--instruction` shapes the scenarios.

### 7.3 `qalab generate --url <url> [--write]`

Generates Playwright specs. Without `--write` the sources are printed; with it they are
written into `$QALAB_WORKSPACE`. `--test-type` filters.

### 7.4 `qalab explore --url <url>`

Opens the page headlessly and prints what was found: title, URL, counts of
buttons/inputs/links/forms. `--username`/`--password` follow a login. Screenshots are
stripped from the output.

### 7.5 `qalab analyze [--force] [--url <url>]`

AI analysis of the page. Without `--force`, a cached analysis for the URL is reused.
Credentials and instruction accepted.

### 7.6 `qalab execute --all | --test <id>`

Runs previously generated tests. Requires a registered project or a workspace.
`--healing-analysis` additionally produces a healing proposal for the run.
`--test-type` restricts which specs are selected.

### 7.7 `qalab heal <executionId>` / `qalab heal-status <proposalId>`

Re-run failure analysis for a past execution and print the proposal in a readable
form, or fetch a proposal by id.

### 7.8 `qalab bug-report <executionId>` / `qalab bug-reports [projectId]`

Generate a bug report from a failed execution, or list existing ones.

### 7.9 `qalab report [executionId]`

List executions, or show one.

### 7.10 `qalab locator analyze --url <url> --locator '<locator>'`

Evaluates a locator against the live page: does it resolve, is it unique, and how good
is it? `qalab locator history [projectId] [fingerprint]` shows the observation history
for an element.

### 7.11 `qalab intent "<prompt>"`

Detects the intent behind a natural-language request and dispatches to the matching
operation (`EXPLORE`, `TEST_PLAN`, `GENERATE_TESTS`, `RUN_TESTS`, `FULL_TEST`).
Returns `INVALID_REQUEST` for `UNKNOWN` and for intents needing inputs it cannot get.

> **Known gap:** the prompt is used only for intent *detection* and is **not** forwarded
> to the operation it triggers. See §18.

### 7.12 `qalab budget-policy [set <HARD|SOFT|NONE>]`

Show or change the token budget policy.

---

## 8. `qalab test` — the full workflow

Nine steps. Each is reported in the summary with a status; `SKIPPED` always carries a
reason.

| # | Step | What happens | Skipped when |
|---|---|---|---|
| 1 | `explore` | Headless browser opens the URL. Title, URL, simplified DOM, accessibility tree, element counts and a screenshot are captured. With credentials, the post-login page is captured too. | never |
| 2 | `analyze` | An LLM reads the page content and returns a page type, summary, confidence, key elements, possible flows and risk areas. | never |
| 3 | `locators` | Locators are generated for the page's elements, each with a preferred strategy, fallbacks, confidence and a reason. | never |
| 4 | `testPlan` | Scenarios are derived from the analysis and the locator repository: name, type, priority, description, steps, required elements. | never |
| 5 | `generatedTests` | Playwright specs and page objects are generated. **Generated exactly once** and used for both the response and the files on disk. | never |
| 6 | `execution` | Specs are written into the workspace and run with `npx playwright test`. | no workspace configured |
| 7 | `failureAnalysis` | A failed execution is classified: failure type, summary, confidence, affected element, and whether it is a healing candidate. | tests passed, or no execution |
| 8 | `healing` | For a healing candidate, a new locator is proposed and validated against the live page. | not a healing candidate |
| 9 | `bugReport` | A structured bug report is written. | tests passed, or no execution |

### 8.1 How the steps are scheduled

Steps 3 and 4 are independent and run **concurrently**. Step 9 (bug report) runs
concurrently with steps 7–8. So the critical path is:

```
explore+analyze → [locators ‖ testPlan] → generate → execute → [failureAnalysis → healing ‖ bugReport]
```

### 8.2 What "generate" actually produces

For each scenario you get two things:

- **A spec** in `tests/<scenario-slug>.spec.ts`
- **A page object** in `pages/<PageClass>_<scenario-slug>.ts`

Plus a merged `pages/<PageClass>.ts` containing the union of every variant's locators
and methods, as a fallback for imports that were not rewritten.

**Why one page object per test.** Every generated variant of `LoginPage` is a partial
class — it contains only the locators that particular test uses. If they all wrote to
a single shared `pages/LoginPage.ts`, the last one would overwrite the rest and every
sibling test would reference members that no longer existed. Each spec therefore gets
its own page object file and its import is rewritten to point at it. The merged file
keeps a complete class available for anything that still imports the shared path.

### 8.3 How the run is executed

- Command: `npx playwright test` in your workspace, with a hard wall-clock budget
  (`QALAB_PLAYWRIGHT_TIMEOUT_SECONDS`, default 600 s). On timeout the process is killed
  and the execution is recorded as `TIMEOUT` with the output tail.
- Playwright's own config in your workspace controls screenshots, video and traces. If
  you did not supply one, a default is generated on first use.
- Concurrency and timeouts can be constrained; see §21 for the knobs.

---

## 9. Output artifacts

### 9.1 In your workspace

```
<workspace>/
├── tests/                                  generated specs
│   └── <scenario-slug>.spec.ts
├── pages/                                  page objects
│   ├── <PageClass>_<scenario-slug>.ts      per test (what specs import)
│   └── <PageClass>.ts                      merged union (fallback)
├── test-results/                           Playwright's own output
└── playwright.config.ts
```

### 9.2 In the report directory

`qalab test` writes to `.qalab/reports/<YYYYMMDD-HHMMSS>/` (override with
`QALAB_REPORT_DIR`):

| File | Contents |
|---|---|
| `report.json` | The complete workflow response: every step, every status, the full generated sources, the structured per-test results (§10.2), the tail of the execution output, failure analysis, healing and bug-report references. |
| `test-plan.md` | The test plan as a readable table plus per-scenario steps and required elements. |
| `test-plan.json` | The same plan, machine-readable, for diffing between runs. |
| `bug-report.json` | The full bug report, when one was generated. |

### 9.3 On the server

Under `QALAB_ARTIFACTS_DIR`:

```
artifacts/execution-<id>/
├── report.json          execution-level report
├── report.md            the same, human-readable
├── console.log          full Playwright output
├── screenshot.png       first failure screenshot
├── screenshot-2.png     …
├── video.webm
└── trace.zip
```

In Docker these live on the `qalab-artifacts` volume and survive `docker compose restart`.

---

## 10. Reading the results

### 10.1 Start with the test plan

`test-plan.md` tells you what the tool *believed* should be tested, before any test
ran. It is the fastest way to answer "did it understand my app?" — if the plan is
generic, your instruction did not land (check `--instruction` and §18) or the page
snapshot was too thin.

### 10.2 Then the failures

The summary lists failing tests, capped at 8 with a count of the remainder, and
prints the first assertion message of each. Everything it shows comes from the
structured results in `report.json` under `steps.execution.results`, not from
scraping Playwright's console text, so the list stays correct no matter how noisy
the run is.

That object looks like this:

```json
{
  "totalCount": 11, "passedCount": 7, "failedCount": 4, "skippedCount": 0,
  "flakyCount": 0, "durationMs": 94120,
  "globalErrors": [],
  "tests": [
    {
      "file": "login-with-empty-password.spec.ts",
      "title": "Login with empty password",
      "fullTitle": "login.spec.ts › Login with empty password",
      "status": "failed",
      "durationMs": 31200,
      "retries": 0,
      "error": "Error: expect(locator).toBeVisible() failed\n...",
      "snippet": "at Object.login (login.spec.ts:31:24)",
      "screenshots": ["/…/test-results/login-empty/test-failed-1.png"],
      "videos": ["/…/test-results/login-empty/video.webm"],
      "traces": ["/…/test-results/login-empty/trace.zip"]
    }
  ]
}
```

- `status` is one of `passed`, `failed`, `skipped` or `unknown`.
- `retries` is how many attempts came before the final one. A test that failed and
  then passed is reported as `passed` with `retries: 1` and counted in `flakyCount`.
- Screenshot, video and trace paths belong to that specific test, so you can open
  the evidence for a failure without guessing which run it came from.
- `globalErrors` holds problems that stopped the whole run (for example a config that
  would not load) and so produced no per-test results at all.

The same per-test results are stored in the database, one row per test, linked to the
execution. `report.json` under `steps.execution.output` keeps only the **last 50
lines** of Playwright's console output for diagnostics.

> If `steps.execution.results` is missing entirely, the run predates this feature or
> the workspace's Playwright config could not be extended. The summary then falls
> back to the run status alone.

For each failure, look at:

1. **The assertion that failed** — usually the first line of the Playwright error.
2. **`steps.failureAnalysis`** — the tool's classification and reasoning.
3. **`steps.healing`** — whether a locator replacement was proposed.
4. **The screenshot** in `artifacts/execution-<id>/`.

### 10.3 Distinguishing a real bug from a broken test

This distinction matters, and the tool is deliberately conservative about it.

| Symptom | Likely cause |
|---|---|
| Assertion fails on a *value* (text, URL, state) | Probably a **real application bug** |
| Locator resolves to nothing / times out | **Test problem** — the element moved, or the tool picked a bad locator. Check the healing proposal. |
| `page.goto` or navigation fails | Environment or the app is down |
| Test times out with no error | Selector matched multiple elements, or an unhandled dialog |
| Every test fails identically | The workspace, config, or app under test is broken — not 20 separate bugs |

> **Current limitation.** The tool produces **one bug report per execution**, not one per
> distinct failure. A run with twenty failures of the same kind yields a single report
> about whichever failure was analysed. Distinguishing and de-duplicating failures into
> separate reports is tracked as **B-032** (Sprint 3).

### 10.4 Exit codes

`qalab test` exits non-zero when the workflow did not complete, **or** when it completed
and the tests failed. Use it directly as a CI step.

---

## 11. Web UI walkthrough

The Next.js dashboard at `:3000` exposes the same capabilities as the CLI.

| View | What it does |
|---|---|
| **Dashboard** | Explore form, live agent status, chat panel |
| **Analyze** | Page analysis with the captured content and screenshot |
| **Projects** | List, create and inspect registered projects; per-project history |
| **Project detail** | Generated tests, execution history, test plan, healing proposals, locator repository, locator intelligence panel, token usage |

Live agent status is streamed over a WebSocket. The socket URL is derived from
`QALAB_API_BASE_URL` (`http→ws`, `https→wss`) and reconnects with backoff, so live
progress works against a remote backend and survives a backend restart. The panel shows
"connecting…"/"reconnecting…" rather than silently appearing frozen.

The frontend reads its API base URL at **runtime** from `/config.js`, so a single image
can be deployed to many environments — see §19.2.

---

## 12. Self-healing

When a test fails, the tool tries to distinguish *the application broke* from *our
locator went stale*.

```
failure
  └─ classify            LOCATOR_FAILURE | ASSERTION_FAILURE | TIMEOUT | …
       └─ if LOCATOR_FAILURE → generate candidates
            └─ validate each candidate against the LIVE page (must resolve uniquely)
                 └─ score: health, stability, semantic, quality
                      └─ produce a proposal   →  human review  →  apply
```

- A proposal is **never** applied automatically.
- `accept` promotes the new locator to the active history entry and demotes the old
  one; `reject` discards it.
- `apply` rewrites the stored test source **and** the page object, then re-syncs the
  workspace file — so the next run genuinely uses the healed locator.
- If a proposal is marked `safeToApply: false`, review it especially carefully.

```bash
qalab heal 26                     # analyse a past execution
qalab heal-status <proposalId>    # inspect a proposal
```

Or in the UI: Projects → project → Healing.

> **Note.** Healing only helps when the element still exists. If the feature was
> genuinely removed, the correct outcome is a failing test and a bug report, not a
> healed locator.

---

## 13. Bug reports

A bug report is a structured record, not free text:

| Field | Meaning |
|---|---|
| `reportId` | Stable identifier, e.g. `bug-7b12b60a` |
| `status` | `COMPLETED` or `FAILED` (failed = AI generation failed, deterministic fallback used) |
| `title`, `severity` | One-line summary and a severity rating |
| `summary` | What went wrong |
| `stepsToReproduce` | How to reproduce |
| `expectedBehavior` / `actualBehavior` | The gap |
| `affectedElement` | Element involved, when identifiable |
| `failureType` | Classification from the failure analysis |
| `suggestedFix` | Suggested remedy |
| `errorMessage`, `consoleLogsExcerpt` | Raw evidence |
| `instruction` | The user instruction in force, when supplied |

If AI generation of the report fails, a **deterministic fallback** is produced from the
execution record so you still get a usable report rather than nothing.

---

## 14. API reference

Base URL defaults to `http://localhost:8080`. All v1 endpoints are under `/api/v1`.

### 14.0 Authentication

Every `/api/**` endpoint requires a bearer API key:

```
Authorization: Bearer qalab_<64 hex chars>
```

Keys are per account and stored as salted hashes — the raw value is shown **once**, at
creation, and cannot be recovered.

```bash
# Issue a key
curl -X POST http://localhost:8080/api/v1/account/api-keys \
  -H "Authorization: Bearer $EXISTING_KEY" \
  -H 'Content-Type: application/json' -d '{"label":"my laptop"}'

# List (never returns the secret)
curl http://localhost:8080/api/v1/account/api-keys -H "Authorization: Bearer $KEY"

# Revoke
curl -X DELETE http://localhost:8080/api/v1/account/api-keys/1 -H "Authorization: Bearer $KEY"
```

**When is it enforced?** A key is required as soon as *one has been issued*, so a fresh
local install works with zero setup and a deployed instance protects itself
automatically. Set `QALAB_REQUIRE_API_KEY=true` to force enforcement even with no key
issued — the first key is then printed to the backend log at startup.

```bash
# Does this instance need a key?
curl http://localhost:8080/api/v1/account/bootstrap
# {"apiKeyRequired":true,"apiKeysExist":true,...}
```

The CLI reads `QALAB_API_KEY` (or `apiKey` in `.qalab.json`) and sends it on every call;
it fails fast with instructions if the Core requires a key and none is configured.

See §18 for what this deliberately does not yet cover (no signup, no roles, no audit
log) and §19.3 for deployment guidance.

### 14.1 Workflow

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/workflows/full-test` | Submit the full pipeline. Returns **`202 Accepted`** immediately with an `operationId` and `statusUrl`. |
| `GET` | `/api/v1/workflows/{operationId}` | The result: `200` when terminal, `202` with the current stage while running, `404` unknown, `500` if the workflow threw. |
| `GET` | `/api/v1/workflows/{operationId}/progress` | Current stage and message. Unchanged, for clients already watching progress. |

**Why it is asynchronous.** A full run takes minutes — a headless browser, npm and
six to seven LLM calls. When it ran on the request thread, every concurrent run held
a server thread for its whole duration and any reverse proxy dropped the connection at
its own read timeout: the backend kept working while the user was told the run had
failed. Submitting and polling removes that failure mode.

Calling it directly from a script:

```bash
# 1. submit
ACK=$(curl -sS -X POST http://localhost:8080/api/v1/workflows/full-test \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"project":{...},"url":"https://staging.example.com","operationId":"my-run-1"}')
echo "$ACK"
# {"operationId":"my-run-1","status":"QUEUED","statusUrl":"/api/v1/workflows/my-run-1",...}

# 2. poll until it is no longer 202
while :; do
  CODE=$(curl -sS -o /tmp/wf.json -w '%{http_code}' \
    -H "Authorization: Bearer $KEY" http://localhost:8080/api/v1/workflows/my-run-1)
  [ "$CODE" = "202" ] && { sleep 5; continue; }
  break
done
cat /tmp/wf.json
```

**Capacity.** The pool is bounded (`QALAB_WORKFLOW_WORKERS`, default 2 — each worker
holds a browser). Beyond `QALAB_WORKFLOW_QUEUE_CAPACITY` (default 20) queued jobs the
API returns **`429`** with a retry message rather than accepting work the node cannot
finish. `POST` returns in well under a second regardless of how long the run takes.

Results are retained for two hours, so a slow or retried poll still succeeds.

Request:

```jsonc
{
  "project": {
    "projectId": "my-app",
    "baseUrl": "https://staging.example.com",
    "framework": "PLAYWRIGHT_TYPESCRIPT",
    "language": "TypeScript",
    "workspacePath": "/abs/path/to/workspace",  // required for execution
    "databaseId": 2                              // optional
  },
  "url": "https://staging.example.com/login",
  "username": "testuser",                        // optional
  "password": "secret",                          // optional
  "instruction": "focus on login validation",    // optional
  "testType": "ui",                              // optional: ui | e2e | api
  "operationId": "cli-1234-5678"                 // optional, enables progress polling
}
```

### 14.2 Individual steps

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/explore` | Capture and describe a page |
| `POST` | `/api/v1/analyze` | AI page analysis |
| `POST` | `/api/v1/test-plan` | Generate a test plan |
| `POST` | `/api/v1/tests` | Generate test sources |
| `POST` | `/api/v1/locators` | Generate locators for a URL |
| `POST` | `/api/v1/run` | Execute tests |
| `POST` | `/api/v1/failures/analyze` | Analyse a failed execution |
| `POST` | `/api/v1/healing/analyze` | Produce a healing proposal |
| `POST` | `/api/v1/bug-reports` | Generate a bug report for an execution |
| `POST` | `/api/v1/intent/run` | Detect intent from a prompt and dispatch |

### 14.3 Queries

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/bug-reports` | All bug reports |
| `GET` | `/api/v1/bug-reports/{reportId}` | One bug report |
| `GET` | `/api/v1/projects/{projectId}/bug-reports` | Bug reports for a project |
| `GET` | `/api/v1/reports/{executionId}` | Execution report |
| `GET` | `/api/v1/healing` | Proposals |
| `GET` | `/api/v1/healing/{proposalId}` | One proposal |
| `POST` | `/api/v1/healing/{proposalId}/accept` | Approve |
| `POST` | `/api/v1/healing/{proposalId}/reject` | Reject |
| `GET` | `/api/v1/projects/{projectId}/healing` | Project proposals |
| `POST` | `/api/v1/locators/analyze` | Evaluate one locator live |
| `GET` | `/api/v1/projects/{projectId}/locators/history` | Locator observation history |
| `GET` | `/api/v1/account/usage` | Token usage breakdown |
| `GET` / `PATCH` | `/api/v1/account/budget-policy` | Read / set budget policy |

### 14.4 Errors

Non-2xx responses use one shape:

```json
{ "error": { "code": "AI_BUDGET_EXCEEDED", "message": "…", "operationId": "op-…" } }
```

| Code | Meaning |
|---|---|
| `INVALID_REQUEST` | Malformed or missing input |
| `INVALID_PROJECT_CONTEXT` | `project` or `projectId` missing |
| `PROJECT_NOT_FOUND` | `databaseId` does not resolve |
| `AI_BUDGET_EXCEEDED` | Monthly allowance exhausted (HARD policy) |
| `AI_PROVIDER_UNAVAILABLE` | Every provider in the cascade failed |
| `AI_PROVIDER_NOT_CONFIGURED` | No usable credentials |
| `AI_CREDENTIAL_INVALID` | Provider rejected the key (401/403) |
| `AI_RATE_LIMITED` | Rate limited (429) |
| `AI_OPERATION_NOT_ALLOWED` | Not permitted for this account |
| `INVALID_PROVIDER` | Unknown provider requested |
| `UNAUTHENTICATED` | Missing or invalid API key |
| `FORBIDDEN` | Authenticated but not permitted |
| `INTERNAL_ERROR` | Unexpected server fault |

### 14.5 Legacy endpoints

A second, unversioned surface exists under `/api` (`/api/explore`, `/api/analyze`,
`/api/projects`, `/api/run`, `/api/healing/*`, …). **Some UI code still calls it.**
Prefer `/api/v1`. The legacy surface is scheduled for removal in **B-033**.

Both surfaces sit behind the same API-key requirement.

---

## 15. Architecture

```
              ┌──────────────────────────────┐
  Browser ───▶│  Next.js UI  (:3000)         │  runtime config from /config.js
  CLI    ───▶└──────────────┬───────────────┘
                          │  /api/v1/**
              ┌───────────▼────────────────────────────┐
              │  Spring Boot Core  (:8080)             │
              │                                        │
              │  Controllers ── Services ── Agents      │
              │                     │          │        │
              │              WorkspaceManager   AiGateway│
              │                     │          │        │
              │        ┌────────────┴───┐   ProviderClients
              │        │                │   (OpenAI-compat,
              │  PlaywrightTool  BrowserTool   Anthropic-compat,
              │        │                │   OpenCode managed)
              │   npx playwright   Playwright/Chromium
              │   test
              └────────┬───────────────────────────────┘
                       │
                  PostgreSQL          artifacts/  screenshots/  workspaces/
```

### 15.1 The five agents

| Agent | Input | Output |
|---|---|---|
| **Explorer** | URL, optional credentials | Title, URL, simplified DOM, accessibility tree, element counts, screenshot |
| **LocatorAgent** | Analysis + locator repository | Locators with strategy, fallbacks, confidence, reason |
| **PlannerAgent** | Analysis + locators | Scenarios: name, type, priority, description, steps, required elements |
| **TestGeneratorAgent** | Analysis + plan + locators + credentials | Specs and page objects as source text |
| **ExecutorAgent** | Workspace + test selection | Status, duration, output |

Plus **FailureAnalystAgent** (classification), the healing subsystem (classification,
candidate generation, live validation, proposal), and **BugReportService**.

Every agent goes through `AiGateway`; none talk to a provider directly.

### 15.2 Agent contract

```java
public interface QaAgent {
    String getName();
    AgentResult execute(Task task);
}
```

`Task` carries an id, a type, a target and a context map. `AgentResult` carries
success, a message and typed data. This uniformity is what makes the workflow
composable and the progress stream generic.

### 15.3 Workspace ownership

The Core **never assumes it owns your filesystem**. All workspace access goes through
`WorkspaceProvider`:

```java
String getWorkspace(ProjectContext project);
void prepareWorkspace(ProjectContext project);
WriteResult writeTestsAndReport(ProjectContext project, List<GeneratedTest> tests);
Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll, String testType);
```

A client-supplied `workspacePath` **always wins** and is normalised to an absolute
path, so the location the response reports is the location that was used. `WriteResult`
returns exactly what was written — including page objects — so the response and the
disk cannot disagree.

### 15.4 Parallelism

Independent LLM steps run on a virtual-thread executor: locators ∥ test plan, and bug
report ∥ failure analysis → healing. Result maps are merged on the calling thread after
joining, so the shared step map is never mutated concurrently.

---

## 16. The AI subsystem

### 16.1 One gateway, several providers

`AiGateway.complete()` is the single entry point for every AI call. It:

1. resolves provider config (request > workflow context > defaults)
2. resolves credentials — `MANAGED` (server keys), `BYOK` (per-user) or `LOCAL`
3. pre-flight budget check — `HARD` policy refuses before any provider request
4. rate-limit check
5. executes with retry and backoff, classifying HTTP failures
6. records usage (tokens, cost, provider, model) for every mode
7. returns content, or a typed `ApiException`

Provider clients: `OpenAiCompatProviderClient` (OpenAI, Google, Ollama),
`AnthropicCompatProviderClient`, `OpenCodeManagedProviderClient`.

### 16.2 The cascade

The managed path tries, in order: **OpenCode Go → Zen `space-bunny-free` → Zen
`big-pickle` → Gemini → Ollama**, skipping a provider whose key is absent, and stopping
early on a usage-limit signal. This is why a single provider being down does not fail
your run.

Override the two Zen models without touching the config file:

| Variable | Default |
|---|---|
| `OPENCODE_ZEN_MODEL` | `space-bunny-free` |
| `OPENCODE_ZEN_FALLBACK_MODEL` | `big-pickle` |

**One logical operation is capped at `max-provider-calls` upstream calls** (default 6,
which covers all five candidates plus one retry). A provider that fails is abandoned
after `max-attempts-per-provider` tries (default 2) so a single broken one cannot
consume the whole budget.

| Variable | Default | Meaning |
|---|---|---|
| `OPENCODE_MAX_PROVIDER_CALLS` | `6` | Hard ceiling on upstream calls per operation, across the whole cascade. |
| `OPENCODE_MAX_ATTEMPTS_PER_PROVIDER` | `2` | Tries before one provider is abandoned. |

Keep the cap at or above your number of configured candidates. If it is lower, the
providers at the end of the cascade become unreachable in the worst case — which is
exactly when you would want them.

> Previously this was a retry loop of 3 wrapped around the 5-provider cascade: up to
> **15 upstream calls** for one operation, each requesting 12 000 tokens, with a
> backoff sleep between the outer attempts — and the gateway then re-ran the whole
> cascade on failure, multiplying it again. The bound is the point, not the number: a
> cascade with no ceiling is a serial retry storm, not a fallback.

**Rejected responses are billed and now counted.** When an earlier provider's answer is
rejected by a validator, those tokens were spent too. The usage record therefore
reports the **sum across every attempt**, not just the response that finally succeeded,
so a failing run no longer looks cheap right up until the allowance is gone.

**An exhausted cascade is not retried.** Once every provider has been tried within its
budget, the gateway fails immediately rather than re-running the identical chain — which
would multiply the cost of a failing operation by the retry count. Ordinary transport
errors are still retried as before.

> The cascade still exists only on the managed path; BYOK providers are retried
> individually. Consolidating the two is tracked as **B-023**.

### 16.3 Per-operation token budgets

Each operation declares the output-token ceiling it actually needs, so a one-line
locator verdict no longer requests as much as a generated test suite:

| Operation | Ceiling | | Operation | Ceiling |
|---|---|---|---|---|
| `EXPLORE` | 2 000 | | `FAILURE_ANALYSIS` | 2 000 |
| `ANALYZE` | 4 000 | | `SELF_HEALING` | 2 000 |
| `LOCATOR_GENERATION` | 4 000 | | `HEALING_EVALUATION` | 1 500 |
| `TEST_PLAN` | 4 000 | | `BUG_REPORT` | 3 000 |
| `TEST_GENERATION` | 8 000 | | `FULL_WORKFLOW` | 4 000 |

A request may still override its own ceiling. Previously every operation asked for
12 000 on the managed path, while the BYOK clients defaulted to about 4 000.

### 16.4 Reliability controls

| Control | Default | Note |
|---|---|---|
| Connect timeout | 10 s | Finite on purpose |
| Read timeout | 180 s | Generous — generations legitimately take minutes — but never infinite |
| Retries | 2 | With linear backoff. **Not** applied to an exhausted cascade (§16.2). |
| Cascade cap | 4 calls | Upstream calls per operation on the managed path (§16.2). |
| Rate limit | off | Token bucket per provider **and** per account. Bursts allowed, sustained rate bounded. In-memory, per process. |
| Circuit breaker | — | **Not implemented.** Tracked as **B-037** |

### 16.5 Rate limiting

Each provider and each account gets a token bucket holding `burst` tokens that refills
at `rps`. A call spends one token; an empty bucket yields `AI_RATE_LIMITED` (HTTP 429)
*before* any provider request is made.

Bursts are deliberate: a single workflow fires several LLM calls in quick succession, so
a strict requests-per-second cap would reject legitimate work, while a leaky bucket alone
would not bound cost. Set `QALAB_AI_RATE_LIMIT_ENABLED=true` to switch it on.

State is in memory and per process, which is correct for the single-node deployment this
is. A multi-node deployment would need a shared store.

### 16.4 Token accounting

When a provider does not report usage, tokens are estimated at roughly four characters
per token and the record is flagged `estimated`. Cost is estimated from a pricing
registry when the provider/model is known, otherwise left null.

---

## 17. Locator intelligence

This is what makes the healing claim credible. For each element the tool stores a
**fingerprint** built from tag, role, accessible name, test id, label, placeholder,
name, id and href, and scores locators on four axes:

| Score | Question it answers |
|---|---|
| **Health** | Does it resolve right now, and uniquely? |
| **Stability** | How much has it changed across observed runs? |
| **Semantic** | Does it describe what a *user* would call the element, rather than how the DOM happens to be built? |
| **Quality** | Is it well-formed, and free of the patterns that make locators brittle? |

Strategy detection classifies a locator (`getByRole`, `getByTestId`, `getByPlaceholder`,
text, CSS, XPath, …) so two candidates can be compared rather than just diffed as
strings. Observation history is retained per element and per fingerprint, which is what
lets the tool distinguish "this element was renamed" from "this element is gone".

A candidate is only proposed if it resolves uniquely against the **live** page — a
plausible-looking locator that matches nothing is rejected before it reaches you.

---

## 18. Known limitations

Ordered by how likely you are to hit them.

| # | Limitation | Impact | Tracking |
|---|---|---|---|
| 1 | ~~No authentication~~ **Partly fixed in Sprint 1** — bearer API keys now guard `/api/**` (B-013). **Still open:** no signup/login, no roles, no audit log, `defaultAccount()` is still global so usage is not per-tenant, and `CredentialStore` is still global so BYOK keys have no owner. `/ws/**` is unauthenticated (browser WebSockets cannot set headers). | Keys close the anonymous-spend hole; the tenancy model is still single-tenant. | B-016, B-033, ADR 0001 follow-ups |
| 2 | **No database migrations.** `ddl-auto: update` in dev/base, `validate` in prod. A production schema can be neither created nor evolved reliably. | Blocks trustworthy releases. | B-014 |
| 3 | ~~`qalab.ai` config block absent~~ **Fixed in Sprint 1** — every provider has a default base URL and model, and a missing model is a loud configuration error. | — | — |
| 4 | ~~Rate limiting is a no-op~~ **Fixed in Sprint 1** — token bucket per provider and per account, off by default. **Still open:** state is in-memory and per-process, so a multi-node deployment would not share limits. | Single node is now protected; a cluster is not. | — |
| 5 | ~~Workflow runs synchronously on the request thread~~ **Fixed in Sprint 1** — `POST` returns `202` immediately and the work runs on a bounded background pool; the CLI polls. **Still open:** job state is in-memory and per process, so a restart loses in-flight and uncollected results, and a multi-node deployment would not share the queue. | Single node is now safe against proxy timeouts. | — |
| 6 | **`intent` discards the instruction.** The prompt is used only to detect intent, then dropped. | Intent-driven runs produce generic suites. | `docs/known-limitations/intent-drops-instruction.md` |
| 7 | **One bug report per execution**, not per distinct failure. | Twenty identical failures yield one vague report. | B-032 |
| 8 | **No per-test structured results.** Failures are parsed from Playwright's text output; the summary caps the list at 8. | No reliable "which tests failed" for large suites. | B-022 |
| 9 | **No HTML/Allure report.** `report.md` is generated server-side but never surfaced by the CLI. | The most useful artifact is missing. | B-029, B-030 |
| 10 | ~~Default token budget below one workflow run~~ **Fixed in Sprint 1** — shipped configuration sets `0` (unlimited). A metered deployment must choose its own limit deliberately. | — | — |
| 11 | **No caching of page analysis between runs** (deliberate). | Every run re-explores and re-analyses. The largest available latency win, but it trades correctness for speed and that trade is unchosen. | deferred |
| 12 | ~~Playwright records artifacts for every test~~ **Fixed in Sprint 1** — generated workspaces now default to `only-on-failure` / `off` / `retain-on-failure`. Existing workspaces keep their own config until regenerated. | — | — |
| 13 | ~~Playwright concurrency unset~~ **Fixed in Sprint 1** — generated workspaces now pin workers to `cores - 1` (override with `QALAB_PLAYWRIGHT_WORKERS`) and report the effective value. Existing workspaces keep their own config until regenerated. | — | — |
| 14 | **Legacy `/api/*` surface still live and used by the UI.** | Two contracts to maintain; doubles the security review surface. | B-033 |
| 15 | **Generated tests inherit the generator's limits.** Assertions are grounded in the captured page content, so a defect that is invisible in a static snapshot cannot be found. | Fundamental to the approach. | by design |
| 16 | **No prompt versioning or eval harness.** | No signal on whether a prompt or model change improved or degraded output. | B-034, B-035 |

---

## 19. Deployment to a cloud host

### 19.1 Minimum viable deployment

```bash
cp .env.example .env
```

Set at least:

```bash
OPENCODE_GO_API_KEY=…      # or another provider
QALAB_AI_FREEMONTHLYTOKENLIMIT=0     # or raise it deliberately
QALAB_ARTIFACTS_DIR=/app/artifacts
```

### 19.2 Public exposure checklist

If the UI or API will be reachable from a browser **on a different host**, all three of
these must agree, and this is the most common cause of "the backend works but the UI is
broken":

```bash
# Where the BROWSER reaches the backend
QALAB_API_BASE_URL=https://api.example.com

# Where the BROWSER reaches the frontend — must be allowed by the backend
QALAB_CORS_ALLOWED_ORIGINS=https://qa.example.com
```

Also terminate TLS in front. The UI derives its WebSocket scheme from the API base URL
(`https→wss`), so an https page will not attempt a plaintext `ws://` connection.

### 19.3 Do not expose it publicly yet

There is no authentication (§18 #1). Until B-013 lands, treat any deployment as
single-user and trusted-network only. Put it behind an authenticating reverse proxy if
you need remote access.

### 19.4 Container notes

- Playwright browsers are baked into the backend image, so no download is needed at
  runtime. You may set `QALAB_AUTO_INSTALL_PLAYWRIGHT=false` to skip the warm-up
  entirely; workspaces are then prepared lazily on first use.
- Volumes in use: `qalab-db-data`, `qalab-workspaces`, `qalab-screenshots`,
  `qalab-artifacts`. **Anything not on a volume is lost on container replacement** —
  this includes artifacts, which hold your screenshots and reports.
- The application is now serving **before** any npm or browser install begins; the
  warm-up runs in the background and cannot block startup.

### 19.5 Resource guidance

| Suite size | vCPU | RAM | Notes |
|---|---|---|---|
| < 20 tests | 2 | 4 GB | Comfortable |
| 20–100 tests | 4 | 8 GB | Constrain Playwright workers explicitly |
| > 100 tests | 8+ | 16 GB+ | Consider splitting by test type |

---

## 20. Troubleshooting

### 20.1 "The UI loads but every API call fails"

Almost always CORS. Check that `QALAB_CORS_ALLOWED_ORIGINS` contains the **frontend's**
public URL, and that `QALAB_API_BASE_URL` is the **backend's** public URL. See §19.2.

The browser console will show the exact rejected origin.

### 20.2 "401 UNAUTHENTICATED"

The Core requires a key and the CLI is not sending one.

```bash
curl http://localhost:8080/api/v1/account/bootstrap   # is a key required?
```

If `apiKeyRequired` is `true`, either set `QALAB_API_KEY=<key>` or add `"apiKey"` to
`.qalab.json`, or pass it for one command. If you have no key, issue one — see §14.0.
On a first run with `QALAB_REQUIRE_API_KEY=true` the key is printed to the backend log.

### 20.3 "Live progress never updates"

The agent WebSocket is not connecting. It is derived from `QALAB_API_BASE_URL`; if the
page is served over https the socket must be `wss://`. Behind a proxy, ensure
`Upgrade`/`Connection` headers are forwarded. The panel shows
"connecting…"/"reconnecting…" when the socket is down.

### 20.4 `AI_BUDGET_EXCEEDED` on a fresh start

A single workflow run exceeds 4000 tokens, which was the old *code* default. The shipped
configuration now sets the allowance to unlimited, so hitting this normally means
something has set a low limit deliberately. Either raise
`QALAB_AI_FREEMONTHLYTOKENLIMIT` (use `0` for unlimited in trusted environments) or set
the policy to `NONE`:

```bash
qalab budget-policy set NONE
```

### 20.5 `AI_PROVIDER_NOT_CONFIGURED`

No provider key reached the backend. Confirm the variable is set **in the backend's**
environment — `docker compose` passes only the keys listed in its `environment:` block.
For a bare `mvn spring-boot:run`, put it in the repository-root `.env`.

### 20.6 Tests all fail with the same error

Look at the first failing test's error before reading the rest. Common causes:

- the application under test is not reachable from the backend
- the workspace has no `playwright.config.ts` and the default `testDir` does not match
- the login credentials are wrong, so every test lands on the login page
- Chromium is missing in the container

### 20.7 `TIMEOUT` execution

The run exceeded `QALAB_PLAYWRIGHT_TIMEOUT_SECONDS`. Either raise it, or narrow the run
with `--test-type` or a single test. If it times out on a modest suite, suspect resource
contention — constrain workers (§21).

### 20.8 "Workflow queue is full" (429)

The bounded pool is saturated: `QALAB_WORKFLOW_WORKERS` running, `QALAB_WORKFLOW_QUEUE_CAPACITY`
waiting. This is deliberate — the alternative is accepting work the node can never
finish. Retry shortly, or raise the limits if the host has capacity to spare (each
worker holds a browser, so raise `workers` only with the RAM to back it).

### 20.9 "Connection reset by peer" during a long run

**Fixed in Sprint 1.** This was the workflow being held on a request thread while a
proxy or load balancer dropped the connection at its own read timeout. `qalab test` now
submits and polls, and the API returns immediately, so the connection is short either
way.

If you still see it, the cause is something else:

- A proxy in front of the Core with a very short timeout on the **other** endpoints —
  check its `proxy_read_timeout`.
- A dropped connection to an **AI provider**: those calls have their own budget
  (`QALAB_AI_READ_TIMEOUT_MS`, §16.3) and retry automatically.
- The browser being killed for exceeding its budget — that surfaces as a `TIMEOUT`
  execution (§20.7), not a connection reset.

### 20.10 Page objects missing / tests do not compile

Each spec imports `../pages/<Class>_<scenario>`. Both the spec and the page object must
be written together. If you copied only `tests/`, the import will not resolve. Re-run
`qalab test`, or `qalab generate --write`, and copy both directories.

### 20.11 Getting help

Backend logs carry `operationId`, `executionId` and `projectId`. With an `operationId`
you can retrieve the full workflow report:

```bash
cat .qalab/reports/<timestamp>/report.json | jq '.steps'
```

---

## 21. Development

### 21.1 Build and test

```bash
cd backend && mvn verify        # 178 tests
cd frontend && npm run build    # includes typecheck
```

### 21.2 The generated workspace config

When a workspace has no `playwright.config.ts`, the Core writes one. **If the file
already exists it is never modified** — the config belongs to you.

The generated defaults capture evidence for **failures only**:

```ts
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  use: {
    headless: true,
    screenshot: 'only-on-failure',
    video: 'off',
    trace: 'retain-on-failure',
  },
});
```

Earlier versions used `screenshot: 'on', video: 'on', trace: 'on'`, which produced 20
videos and 20 traces for a 20-test run regardless of outcome — expensive in disk and
wall-clock time, and it buried the failures among noise.

Override the profile per deployment:

| Variable | Default |
|---|---|
| `QALAB_PLAYWRIGHT_SCREENSHOT` | `only-on-failure` |
| `QALAB_PLAYWRIGHT_VIDEO` | `off` |
| `QALAB_PLAYWRIGHT_TRACE` | `retain-on-failure` |

Concurrency and timeouts are baked in too, and are also overridable:

| Variable | Default | Meaning |
|---|---|---|
| `QALAB_PLAYWRIGHT_WORKERS` | `0` = auto | Worker count. Auto is `cores - 1`, minimum 1. |
| `QALAB_PLAYWRIGHT_TEST_TIMEOUT_MS` | `30000` | Per-test timeout |
| `QALAB_PLAYWRIGHT_EXPECT_TIMEOUT_MS` | `5000` | Per-expectation timeout |

`retries: 0` is deliberate and not configurable: a retried failure is a flake, and
silently re-running it hides the signal the run exists to produce. If you see flakes,
fix them rather than enabling retries.

The worker count is reported back in the execution result as `effectiveWorkers`, so
"how many workers was this actually running with?" is always answerable from
`report.json` — the first question when a suite times out or flakes.

### 21.3 The temporary reporting config

To capture per-test results the Core runs Playwright with an extra config file,
`playwright.qalab-report.config.ts`, written into the workspace root for the duration
of the run and deleted afterwards. If you run tests while one is in flight, seeing
that file is expected.

It never edits your `playwright.config.ts`. It imports your config, spreads it and
appends one reporter, so your `workers`, `retries`, `testDir` and evidence profile all
still apply:

```ts
import base from './playwright.config.ts';
import { defineConfig } from '@playwright/test';

const existing = Array.isArray(base.reporter) ? base.reporter : [];
export default defineConfig({
  ...base,
  reporter: [...existing, ['json', { outputFile: '…' }]],
});
```

Your own reporters are preserved and keep printing, so the console output is
unchanged.

### 21.4 Adding a provider

Implement `ProviderClient` (`type()` and `call(ProviderCallRequest)`), register it as a
bean in `AiGatewayConfig`, and add it to the `providerClients` list. It will then be
resolved by `AiProviderType` and participate in retries and usage accounting
automatically.

### 21.5 Project layout

```
backend/src/main/java/com/qalab/qalabai/
├── agent/          the five agents + failure/healing agents
├── ai/             gateway, provider clients, budget, usage
├── api/v1/         versioned controllers
├── controller/     legacy unversioned controllers (being removed)
├── cache/          in-memory analysis cache
├── dto/            request/response records
├── healing/        classification, candidates, validation, proposals
├── intent/         natural-language intent detection
├── locator/        fingerprints, scoring, history
├── model/          JPA entities
├── repository/     Spring Data repositories
├── service/        orchestration and business logic
├── service/report/ per-execution report rendering
├── service/workspace/  workspace + artifact ownership
└── tool/           Playwright and browser tools

cli/qalab           the CLI (single bash script)
frontend/src/       Next.js app router UI
docs/               architecture notes and known limitations
```

### 21.6 Planning documents

| File | Purpose |
|---|---|
| `STATE-AUDIT.md` | Senior-engineering audit: what is finished, what is not, what to add |
| `backlog.md` | Prioritised multi-sprint task list with acceptance criteria |
| `backlog_progress.md` | Per-task delivery record: date, duration, commit, what changed, how tested |
| `docs/known-limitations/` | One file per confirmed defect with impact and fix sketch |

---

## 22. Version history of this manual

| Date | Change |
|---|---|
| 2026-09-26 | Created. Documents behaviour after Sprint 0 (reliability + CLI reporting) and the start of Sprint 1 (deployability). All limitations recorded with tracking IDs. |
| 2026-09-26 | Updated for B-017: §14.1 rewritten for the asynchronous contract (202 + polling, with a worked example), `429` on a saturated queue, new workflow configuration variables, new §20.8 troubleshooting, limitation 5 closed with the remaining in-memory-state caveat. |
| 2026-09-26 | Updated for B-016: §16.5 rate limiting, new configuration variables, limitation 4 closed. |
| 2026-09-26 | Updated for B-013: §14.0 authentication, `UNAUTHENTICATED` error code, CLI key configuration, new §20.2 troubleshooting, limitation 1 downgraded to "partly fixed" with the remaining tenancy gaps named. |
| 2026-09-26 | Updated for B-018 (background Playwright warm-up), B-019 (runtime API base URL), B-020 (artifact profile now failures-only; limitation 12 closed), B-021 (bounded concurrency; limitation 13 closed), B-015 (`qalab.ai` config block; limitations 3 and 10 closed). Added `intent` instruction-dropping to limitations. |

**Maintenance:** update this file in the same commit as any change to CLI flags,
environment variables, API contracts, output artifacts or known limitations.
