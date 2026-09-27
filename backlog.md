# QALabAI — Engineering Backlog

**Created:** 2026-09-26
**Source:** [`STATE-AUDIT.md`](./STATE-AUDIT.md)
**Baseline commit:** `9d5cbef`
**Branch convention:** one branch per task ID (`task/B-041-playwright-timeout`), squash-merge.

---

## How to read this

| Field | Meaning |
|---|---|
| **P0** | Correctness / security / data-loss. Blocks any non-local deployment. |
| **P1** | Production-readiness. Blocks a real production release. |
| **P2** | Quality, maintainability, product moat. |
| **S/M/L** | Rough size. S ≈ ≤half day, M ≈ 1–2 days, L ≈ 3–5 days. Not a commitment. |

**Definition of Done (applies to every task):**
- [ ] `cd backend && mvn verify` green (127 existing tests must not regress)
- [ ] New behaviour has unit tests; new task-specific tests added
- [ ] No secrets, API keys or `.env` values committed
- [ ] README updated if CLI flags, env vars or API contracts changed
- [ ] Log lines are actionable (include URL / executionId / operationId where relevant)
- [ ] PR description states the *before* behaviour, not just the *after*

**Sprint sizing rule:** a sprint ≈ 1 week of one engineer. Sprint 0 is deliberately front-loaded with small, independent, high-certainty fixes; anything genuinely uncertain (auth model, async job design) sits in Sprint 1 and starts with a spike.

---

## Sprint 0 — Stop the bleeding

*Goal: the `qalab test` command becomes reliable and its output becomes readable. Nothing here changes product behaviour beyond fixing broken behaviour.*

**Exit criteria:** a full `qalab test` run on a clean workspace produces a readable summary, a written test plan, compilable specs, and cannot hang past a bounded time.

---

### B-001 · Enforce a real timeout on the Playwright runner
**P0 · S · area: backend/tooling**

`PlaywrightTool.execute` drains the process stdout in the calling thread *before* calling `process.waitFor(60, SECONDS)`, so the timeout is unreachable and `destroyForcibly()` is dead code. A hung Playwright run holds the request thread forever (observed: 158 s run under a "60 s" timeout).

**Change**
- Redirect the child process to a temp file (`ProcessBuilder.redirectOutput(File)`), then `waitFor(timeout)`.
- On timeout: `destroyForcibly()`, `waitFor(5s)`, and return `status: "TIMEOUT"` plus the captured tail.
- Make the budget configurable: `@Value("${qalab.playwright.timeout-seconds:600}")`.
- Read the file after the process exits; keep only the last N KB in memory.

**Acceptance criteria**
- [ ] A test that hangs is killed at the configured budget and returns `status: "TIMEOUT"`, not a hung request
- [ ] `completed == false` is reachable in a unit test
- [ ] Output is still returned for successful runs, tail preserved for long runs
- [ ] Unit test in `PlaywrightToolTest` using a fake long-running process

**Files:** `backend/src/main/java/com/qalab/qalabai/tool/playwright/PlaywrightTool.java`
**Depends on:** —

---

### B-002 · Put real HTTP timeouts on every AI provider client
**P0 · S · area: backend/ai**

`new RestTemplate()` in `OpenAiCompatProviderClient:26`, `OpenCodeAiProvider:66` and `AnthropicCompatProviderClient` uses connect/read timeout `0` = **infinite**. A stalled provider never becomes an exception, so `AiGateway.executeWithRetry` never retries it.

**Change**
- One `@Bean RestTemplate aiRestTemplate(...)` with `SimpleClientHttpRequestFactory`, `connectTimeout = 10s`, `readTimeout = 180s`.
- Inject into all three clients; delete the per-class `new RestTemplate()`.
- Note in the commit body why the read timeout is generous (long generations) but finite.

**Acceptance criteria**
- [ ] No `new RestTemplate()` remains in `backend/src/main/java`
- [ ] A socket that never responds surfaces as an exception within the read budget
- [ ] Existing gateway tests still pass

**Files:** `config/AiGatewayConfig.java`, the three client classes
**Depends on:** —

---

### B-003 · Generate the test suite once per workflow run
**P0 · M · area: backend/service**

`QaWorkflowService` calls the expensive test-generation LLM twice: `generateTestsContent` at step 4 (`:131`) and `generateTestsEntities` inside `runInWorkspace` (`:161`). Both delegate to `runGenerator`. Consequences: double cost, double latency, and the specs returned to the client are **not** the specs that were executed.

**Change**
- Call `generateTestsEntities` once, keep the `List<GeneratedTest>`.
- Derive the `GeneratedFile` list from it for the response.
- Pass the same list into `writeTests`.

**Acceptance criteria**
- [ ] `runGenerator` is invoked exactly once per `runFullTest` (assert with a spy in `QaWorkflowServiceTest`)
- [ ] Files in `steps.generatedTests` are byte-identical to the files on disk
- [ ] `qalab test` LLM call count drops by one full generation

**Files:** `service/QaWorkflowService.java`
**Depends on:** —

---

### B-004 · Ship page objects to the client's workspace
**P0 · M · area: backend/service + cli**

`CodeGenerationService:128-130` returns **only** `testCode`, but that code contains `import { LoginPage } from '../pages/LoginPage'`. The CLI writes those specs and never writes a page object, so the delivered tests cannot compile.

**Change**
- Add `pageObjects: List<GeneratedFile>` to the workflow response (path + content, one entry per test-specific page file, produced by the same code path that writes to disk).
- CLI: write page objects into `pages/` next to the specs, and create the directory.
- Single source of truth: build the response payload **from** the files that `writeTests` produced, so response and disk can never diverge.

**Acceptance criteria**
- [ ] `qalab test` leaves a workspace where `npx playwright test --list` resolves every import
- [ ] `pages/` contains the page object each spec imports
- [ ] The merged shared `<Class>.ts` still exists as a fallback

**Files:** `service/CodeGenerationService.java`, `service/QaWorkflowService.java`, `cli/qalab`
**Depends on:** B-003 (avoid regenerating just to get page objects)

---

### B-005 · Unify the workspace path used by CLI and backend
**P0 · M · area: backend/service**

For an unregistered project, `runInWorkspace` writes to `project.getWorkspacePath()` (which resolves to e.g. `backend/workspaces/project-N`) while the CLI writes to `$WORKSPACE`. Two different directories → the user is told files landed in `quiz/qa/` while execution happened elsewhere, and that directory was empty afterwards.

**Change**
- One rule: an explicitly supplied `project.workspacePath` always wins, for both writing and execution.
- If absent, resolve deterministically and **echo the resolved absolute path in the response** so the CLI can report it truthfully.
- Never silently fall back to a server-side workspace when the client supplied one.

**Acceptance criteria**
- [ ] CLI-reported write path == backend execution path == response-reported path
- [ ] A registered and an unregistered project both write where the client asked
- [ ] Response contains `steps.execution.workspace`

**Files:** `service/QaWorkflowService.java`, `service/ProjectContextResolver.java`, `service/workspace/WorkspaceManager.java`
**Depends on:** —

---

### B-006 · Human-readable CLI summary + `--json` escape hatch
**P0 · M · area: cli**

`cli/qalab:475` dumps the entire workflow response — all 20 generated test sources inline — as raw JSON. This is the "nepregledno" complaint.

**Change**
- Default output: a compact summary — workflow status, per-step status + duration, scenario/test counts, execution status, failed-test list, workspace path, artifact paths, bug-report headline, and the exact paths of everything written.
- `--json` prints the raw response (current behaviour); `--quiet` prints only the artifact paths.
- Colour only when stdout is a TTY; no ANSI when piped to a file.
- Keep the live progress lines on stderr so stdout stays pipeable.

**Acceptance criteria**
- [ ] Default `qalab test` output fits on one screen for a 20-test run
- [ ] `--json` reproduces today's output byte-compatibly
- [ ] Exit code is non-zero when the workflow status is `FAILED` (CI-usable)

**Files:** `cli/qalab`
**Depends on:** B-004 (summary must report page objects)

---

### B-007 · Write the test plan to disk
**P0 · S · area: cli**

The plan is generated and stored in the DB but was never surfaced: the workflow response carried only `scenarioCount`, and the CLI has no plan-writing code at all. The backend half landed in `9d5cbef` (`steps.testPlan.scenarios`); the CLI half is still missing.

**Change**
- CLI writes `test-plan.md` (human) and `test-plan.json` (machine) into `.qalab/reports/<timestamp>/`.
- Markdown: title, scenario count, then a table of `name | type | priority | description` with each scenario's `steps` as a nested list.
- Print the written paths in the summary.

**Acceptance criteria**
- [ ] `test-plan.md` exists after every `qalab test` and lists all scenarios
- [ ] No `jq` dependency for the non-`jq` fallback path (mirror the existing dual jq/python3 pattern)

**Files:** `cli/qalab`
**Depends on:** B-006 (the artifact list it extends)

---

### B-008 · Regression tests for the two fixes already shipped
**P0 · M · area: backend/test**

`WorkspaceManager.mergePageObjects` / `rewritePageObjectImport` and the virtual-thread parallelisation in `QaWorkflowService` shipped in `9d5cbef` with **no tests**. The merger is a hand-rolled brace-matching parser.

**Change**
- `WorkspaceManagerPageObjectTest`: merging two variants of the same class (union of fields / ctor lines / methods, first-wins per name), unbalanced braces, braces inside string literals, arrow-function properties, generic type params, empty/blank `pageObjectCode`.
- `WorkspaceManagerImportRewriteTest`: standard import, combined import `import { A, B } from …`, class name that is a suffix of another (`Page` vs `LoginPage`), no match → unchanged.
- `QaWorkflowServiceTest`: parallel branch — both locators and plan recorded, failure in one does not lose the other, `steps` fully populated after join.

**Acceptance criteria**
- [ ] Merger covered by adversarial cases, not just the happy path
- [ ] Parallel path asserted with mocks completing in reverse order

**Files:** `backend/src/test/java/.../service/workspace/`, `.../service/QaWorkflowServiceTest.java`
**Depends on:** —

---

### B-009 · Make CORS origins configurable
**P0 · S · area: backend/config**

`WebConfig.java:13` pins `allowedOrigins("http://localhost:3000")`. Deployed anywhere else, every frontend API call is blocked. This is a likely live symptom of the Oracle Cloud deployment.

**Change**
- `@Value("${qalab.cors.allowed-origins:http://localhost:3000}")`, comma-separated.
- Keep `allowCredentials(true)`; never combine with `allowedOrigins("*")` — document that.
- Set the env var in `docker-compose.yml` for the frontend origin.

**Acceptance criteria**
- [ ] A non-localhost origin works when configured
- [ ] Default dev behaviour unchanged
- [ ] Documented in README + `.env.example`

**Files:** `config/WebConfig.java`, `application.yml`, `docker-compose.yml`, `.env.example`
**Depends on:** —

---

### B-010 · Derive the agent WebSocket URL from config
**P0 · S · area: frontend**

`use-agent-websocket.ts:16` hardcodes `ws://localhost:8080/ws/agents`. Live progress is silently dead on any remote host, and an https page needs `wss://`.

**Change**
- Build from `API_BASE_URL`: swap `http→ws`, `https→wss`.
- Reconnect with bounded exponential backoff; surface connection state in the UI instead of failing silently.

**Acceptance criteria**
- [ ] Progress updates work against a non-localhost backend
- [ ] No hardcoded host/port remains in `frontend/src`
- [ ] Reconnect after backend restart without a page reload

**Files:** `frontend/src/lib/use-agent-websocket.ts`, `frontend/src/lib/config.ts`
**Depends on:** —

---

### B-011 · Persist the artifacts directory
**P0 · S · area: deployment**

`docker-compose.yml:55-57` mounts volumes for `workspaces` and `screenshots` but not `artifacts`. `qalab.artifacts-dir` defaults to `./artifacts` → `/app/artifacts`, unpersisted. Every collected screenshot, video, trace, `report.json` and `report.md` is lost on restart — i.e. most of the product's output.

**Change**
- Add a `qalab-artifacts` volume, mount at `/app/artifacts`, set `QALAB_ARTIFACTS_DIR=/app/artifacts`.
- Add the same `ENV` to `backend/Dockerfile` for non-compose runs.
- Write a smoke test (or documented manual check) that a report survives `docker compose restart`.

**Acceptance criteria**
- [ ] `artifacts/execution-*/report.md` survives a container restart
- [ ] Documented in README

**Files:** `docker-compose.yml`, `backend/Dockerfile`, `.env.example`
**Depends on:** —

---

### Sprint 0 definition of exit
- [ ] `qalab test` on a clean workspace: readable summary, written `test-plan.md`, compilable specs, written page objects, artifacts persisted
- [ ] No code path can hang indefinitely (Playwright bounded, AI HTTP bounded)
- [ ] One test generation per run
- [ ] `mvn verify` green including the new B-008 tests
- [ ] Frontend talks to a non-localhost backend (CORS + WS)

---

## Sprint 1 — Deployable

*Goal: safe to expose beyond a single trusted user, and able to start from an empty database.*

**Spike first (½ day each, write a short ADR):** auth model (API key vs JWT), tenant model, async-job contract. Do not start implementation before the spikes land.

---

### B-012 · Spike: authentication & tenancy ADR
**P0 · S · area: architecture**

Before any auth code: decide API key vs JWT, how a key maps to an `Account`/tenant, how per-tenant budgets and provider credentials work, and what the frontend login story is. `AccountService.defaultAccount()` currently returns one implicit global account, which makes usage attribution and budget enforcement meaningless with >1 user.

**Acceptance criteria**
- [ ] `docs/adr/0001-auth-and-tenancy.md` with the decision, alternatives, and migration path
- [ ] Names the exact entities/tables required

**Files:** new `docs/adr/`
**Depends on:** —

---

### B-013 · Auth filter on the API
**P0 · L (after B-012) · area: backend/security**

Verified: no `SecurityFilterChain`, no `@PreAuthorize`, no JWT anywhere. Every `/api/**` and `/api/v1/**` endpoint is anonymous, including the endpoints that spend money (`POST /api/v1/workflows/full-test`) and mutate sources (`…/healing/{id}/apply`).

**Change**
- Add `spring-boot-starter-security`; deny-by-default, then explicitly permit the endpoints that must be open.
- API-key filter (or JWT per B-012) on everything under `/api/**`.
- Return the existing structured `ErrorCode` on 401/403 — do not leak a default Spring error body.

**Acceptance criteria**
- [ ] Unauthenticated request to any spending endpoint → 401
- [ ] Authenticated request → unchanged behaviour
- [ ] Error body matches the existing error model contract
- [ ] Tests for both paths

**Files:** `config/SecurityConfig.java` (new), `api/v1/ApiExceptionHandler.java`
**Depends on:** B-012

---

### B-014 · Database migrations with Flyway
**P0 · L · area: backend/persistence**

No Flyway, no Liquibase, no `*.sql` anywhere. `ddl-auto: update` in base + dev, `validate` in prod. `validate` means a production database can never be created or evolved — the schema is whatever Hibernate last produced on someone's laptop.

**Change**
- Add Flyway; baseline the current schema from a dev DB (`flyway baselineOnMigrate`).
- Set `ddl-auto: validate` everywhere (dev included).
- All future schema changes via versioned migrations only.
- CI: run migrations against H2/Postgres service container.

**Acceptance criteria**
- [ ] Empty Postgres + `mvn verify` → schema created purely by migrations
- [ ] CI fails if the entity model and migrations diverge
- [ ] Documented: no `ddl-auto: update` in any profile

**Files:** `pom.xml`, new `resources/db/migration/**`, all three yml files, CI
**Depends on:** —

---

### B-015 · Ship a real `qalab.ai` configuration block
**P0 · M · area: backend/ai-config**

There is **no `qalab.ai` block in any yml**. Consequences:
- `providers` is empty → `AiGatewayProperties.endpoint()` returns null → `resolveModel()` returns `null` → `{"model": null}` in the request body → provider 400. **BYOK OpenAI / Gemini / Ollama are effectively broken.** The managed path masks this because `OpenCodeAiProvider` reads its own `@Value` models.
- `freeMonthlyTokenLimit` defaults to **4000 tokens/month**. One `qalab test` run sends 6–7 prompts containing full simplified page HTML. `docker-compose.yml:44` papers over it with `QALAB_AI_FREEMONTHLYTOKENLIMIT: 0`; a bare `mvn spring-boot:run` will hard-stop with `AI_BUDGET_EXCEEDED`.

**Change**
- Add `qalab.ai.default-provider`, `default-credential-mode`, `providers.<NAME>.{base-url,model}` for every provider type.
- Set a coherent FREE default (or make it explicit that FREE is dev-only).
- Single source of model truth: delete the duplicated `@Value` models in `OpenCodeAiProvider` and read from gateway properties.

**Acceptance criteria**
- [ ] BYOK OpenAI and Ollama paths send a non-null model (integration test against a stub server)
- [ ] A default `qalab test` run does not hit the budget ceiling
- [ ] No model string defined in two places

**Files:** `application.yml`, `config/AiGatewayProperties.java`, `ai/opencode/OpenCodeAiProvider.java`
**Depends on:** B-023 (consolidate provider clients first, or do both together)

---

### B-016 · Real rate limiter
**P1 · M · area: backend/ai**

`NoopRateLimiter.allow()` always returns `true`; its own javadoc says "placeholder". `AiGatewayProperties.rateLimitEnabled` is never read. With B-013 missing, anyone who can reach the port can drive unbounded paid LLM traffic.

**Change**
- Token bucket per provider **and** per account, `ConcurrentHashMap`-based, in-memory.
- Wire `rateLimitEnabled` to enable/disable.
- Emit remaining-quota metrics.

**Acceptance criteria**
- [ ] Sustained requests above the configured rate → `AI_RATE_LIMITED` with the existing error code
- [ ] Flag actually controls behaviour
- [ ] No cross-account leakage in the limiter state

**Files:** `ai/gateway/RateLimiter.java` (new impl), `AiGateway.java`, `AiGatewayProperties.java`
**Depends on:** B-013 (needs a tenant key)

---

### B-017 · Async job model for the full-test workflow
**P1 · L · area: backend/api**

`POST /api/v1/workflows/full-test` holds a Tomcat request thread for the whole run (158 s observed) while the client polls a separate progress endpoint. A handful of concurrent runs exhausts the 200-thread pool, and any proxy/LB drops the connection at its own read timeout — which matches the `AsyncRequestNotUsableException: Connection reset by peer` in the Oracle Cloud logs.

**Change**
- Endpoint returns `202 Accepted` + `operationId` immediately; work moves to a bounded background executor.
- `GET /api/v1/workflows/{operationId}` returns the final result (persist the response in `OperationProgressStore` or a table).
- Reuse the existing progress polling contract — the CLI already speaks it.
- Bounded queue; reject with a clear error when full.

**Acceptance criteria**
- [ ] Endpoint returns in <1 s regardless of workload
- [ ] CLI needs only a small change to poll the result endpoint
- [ ] Load test: 20 concurrent runs, no thread-pool exhaustion, no dropped connections

**Files:** `api/v1/V1WorkflowController.java`, `service/QaWorkflowService.java`, `service/OperationProgressStore.java`, `cli/qalab`
**Depends on:** —

---

### B-018 · Move Playwright install off the startup path
**P1 · M · area: backend/config**

`PlaywrightSetupConfig` is a `CommandLineRunner` that calls `prepareWorkspace()` for every `project-*` dir, shelling out to `npm install` and `npx playwright install chromium` with a **600 s** timeout each. With N workspaces the app can be unavailable for up to 10×N minutes on boot. Plausible contributor to the reported "500 on install playwright".

**Change**
- Run the scan asynchronously after startup, or behind a readiness gate, or lazily on first workspace use.
- Add structured progress + a timeout that fails the workspace, not the boot.
- Skip entirely when `qalab.auto-install-playwright=false` (already supported — make it the documented default in containers).

**Acceptance criteria**
- [ ] Application is serving traffic before any npm/browser install runs
- [ ] Install failures are visible per workspace and do not abort boot
- [ ] Startup time with N workspaces is roughly constant

**Files:** `config/PlaywrightSetupConfig.java`, `service/workspace/WorkspaceManager.java`
**Depends on:** —

---

### B-019 · Serve the frontend API base URL at runtime
**P1 · S · area: frontend/deployment**

`frontend/Dockerfile:12-14` sets `NEXT_PUBLIC_API_BASE_URL` via `ARG`/`ENV` **before** `npm run build`. Next.js inlines `NEXT_PUBLIC_*` at build time, so `docker-compose.yml:63` sets the variable on an already-built container — a **no-op**. The deployed UI always talks to the build-time URL.

**Change** — pick one and document it:
1. Server-side proxy route in Next (`/api/proxy/*` → backend), client calls a relative URL; **or**
2. Entrypoint script that writes a `config.js` with `window.__QALAB_CONFIG__` consumed by `lib/config.ts`.

**Acceptance criteria**
- [ ] One image, different environments, correct backend URL
- [ ] No `NEXT_PUBLIC_*` reliance for runtime config

**Files:** `frontend/Dockerfile`, `frontend/src/lib/config.ts`, `docker-compose.yml`
**Depends on:** —

---

### B-020 · Sensible default artifact profile
**P1 · S · area: backend/workspace**

`WorkspaceManager.initializeProjectStructure:341-344` sets `screenshot: 'on', video: 'on', trace: 'on'`. Note `screenshot: 'on'` is **not** the Playwright default (`only-on-failure`) — it captures passing tests too. 20 tests → 20 videos + 20 traces per run: heavy disk, real wall-clock cost, and a large `ArtifactStore` copy per execution.

**Change**
- Default `screenshot: 'only-on-failure'`, `video: 'off'`, `trace: 'retain-on-failure'`.
- Make the profile configurable per workspace; never clobber a user-owned `playwright.config.ts` (respect existing settings).
- Store a generated-tests profile under a distinct name if the user's config must stay untouched.

**Acceptance criteria**
- [ ] A passing run produces no video/trace
- [ ] A failing run keeps its screenshot + trace
- [ ] User-authored config is never overwritten

**Files:** `service/workspace/WorkspaceManager.java`
**Depends on:** —

---

### B-021 · Bounded Playwright concurrency
**P1 · S · area: backend/tooling**

The workspace config sets no `workers`, so Playwright picks a default (observed "6 workers" on a small VM). On a 1–2 vCPU Oracle instance this causes timeouts and flaky failures.

**Change**
- Set `workers` from config, default `max(1, availableProcessors - 1)`.
- Bound `timeout` and `expect.timeout` explicitly; set `retries: 0` (retries hide flakiness — surface it instead).
- Report the effective config in the execution result.

**Acceptance criteria**
- [ ] Worker count honours a config override
- [ ] Execution result records the effective Playwright settings

**Files:** `service/workspace/WorkspaceManager.java`
**Depends on:** —

---

### Sprint 1 definition of exit
- [ ] Unauthenticated request to a spending endpoint is rejected
- [ ] Empty database → schema created by migrations alone, in CI and in prod
- [ ] BYOK providers work end-to-end against a stub
- [ ] `POST /workflows/full-test` returns immediately; 20 concurrent runs survive
- [ ] Container boots fast and serves traffic before any install work
- [ ] One frontend image, correct backend URL per environment

---

## Sprint 2 — Reliable and measurable

*Goal: when the user says "it was slow" or "it was wrong", we can answer from data instead of log-scraping.*

---

### B-022 · Playwright JSON reporter → structured per-test results
**P0→P1 · M · area: backend/tooling · BLOCKER FOR ALL REPORTING**

The workflow only ever sees a 2000-char truncated stdout blob (`QaWorkflowService:176`) and cannot say *which* tests failed in a structured way. This is the prerequisite for the HTML report, Allure, and a useful CLI summary.

**Change**
- Run with `--reporter=json:<file>` alongside the human reporter.
- Parse it: per test — file, title, status, duration, retry count, error message + stack, attachment paths.
- Return `results` structurally in the execution step; keep a stdout **tail** (last ~50 lines) for diagnostics, not a 2000-char head.
- Persist per-test results against the `TestExecution` (new table via B-014 migrations).

**Acceptance criteria**
- [x] Execution step carries `passedCount`, `failedCount`, `skippedCount` and a per-test array
- [x] Screenshot/trace paths are surfaced per failing test
- [x] One `mvn verify` unit test parses a fixture JSON report

**Shipped in** `c225c5c` — with two corrections to the plan above:
- `--reporter=json:<file>` is **not supported** on Playwright 1.48 (it is parsed as a
  module name). The JSON reporter is configured through a generated companion config
  that imports the user's `playwright.config.ts`, spreads it and appends one reporter,
  selected with `--config` and deleted after the run.
- The companion must sit at the **workspace root**, not a subdirectory: Node resolves
  `node_modules` by walking up from the importing file, and Playwright defaults
  `testDir` to the config file's own directory.

**Files:** `tool/playwright/PlaywrightTool.java`, `tool/playwright/PlaywrightResultParser.java`, `service/QaWorkflowService.java`, `model/TestCaseResult.java`, `repository/TestCaseResultRepository.java`, `migration/V2__test_case_results.sql`, `cli/qalab`
**Depends on:** B-014 (needs a table), B-001

---

### B-023 · Consolidate the provider clients
**P1 · M · area: backend/ai**
**Status: DONE** — `30bb215`

Two provider stacks coexist: the legacy `AiProvider` (`OpenCodeAiProvider`) and the new `ProviderClient` (`OpenAiCompatProviderClient`, `AnthropicCompatProviderClient`). `OpenCodeAiProvider` contains four copy-pasted ~60-line HTTP methods (`callGoApi`/`callZenApi`/`callOllamaApi`/`callGeminiApi`) differing only in URL, auth header and response extraction.

**Change**
- One `ChatCompletionClient` with pluggable `AuthStrategy` (bearer / `x-api-key` + `anthropic-version`) and `ResponseExtractor` (OpenAI `choices[].message.content` / Anthropic `content[].text`).
- Port the cascade onto `ProviderClient` so `AiGateway` gets cross-provider fallback (today it only exists on the legacy path).
- Delete the legacy `AiProvider` stack once BYOK is proven (folds into B-015).

**Acceptance criteria**
- [x] One HTTP method, not five — `restTemplate.exchange` in this class went 4 → 1
- [x] Cascade works for BYOK providers, not just managed
- [x] One request builder: the body is constructed in exactly one place

**The second stack needed no migration work.** BYOK was already covered — `AiGateway`
resolves a `ProviderClient` per provider type, so BYOK and managed share the same
cascade, breaker, budget and metrics. What remained was dead weight: `OpenAiProvider`
was a `@Component` Spring built on every boot that nothing called, and the `AiProvider`
interface it implemented had no injectors. Both deleted.

**Files:** `ai/gateway/*`, `ai/opencode/OpenCodeAiProvider.java`
**Depends on:** —

---

### B-024 · Cap the provider cascade
**P1 · S · area: backend/ai**

`attemptProviders` tries Go → Zen → Zen-fallback → Gemini → Ollama inside a 3-attempt loop: up to **15 upstream LLM calls for one logical operation**, each requesting `max_tokens: 12000`. On a free/rate-limited tier this is both the latency and the "sporo" problem — and it is invisible to the token budget, which only counts the final response.

**Change**
- Cap *total* attempts across the whole cascade (e.g. 4), not per provider.
- Stop escalating a provider that already failed twice.
- Record attempt count and per-attempt tokens in usage so cost is attributable.
- Reduce `MAX_TOKENS` per operation to what the task actually needs.

**Acceptance criteria**
- [x] Worst-case upstream calls per operation is bounded and asserted in a test
- [x] `usage` records attempts and total tokens including rejected responses
- [x] Measurable latency improvement on the provider-failure path

**Shipped in** `e55a1b1` — with three corrections to the plan above:
- The real worst case was **45** upstream calls, not 15. `AiGateway.executeWithRetry`
  sat outside the cascade and re-ran all of it up to 3 times. Capping only the provider
  would have left 12 and looked like a fix.
- The backoff sleeps (1 s, then 2 s) are gone: sleeping *between different providers*
  bought nothing, since the next candidate is not a retry of the last.
- The cap must be **at least the number of configured candidates**, or the tail of the
  cascade is unreachable in the worst case. Default raised to 6 for 5 candidates.

**Files:** `ai/opencode/OpenCodeAiProvider.java`, `ai/gateway/AiGateway.java`, `ai/gateway/ProviderCascadeExhaustedException.java`, `ai/gateway/ProviderCallResult.java`, `ai/gateway/AiOperation.java`, `ai/gateway/OpenCodeManagedProviderClient.java`

**Done without B-023**, which this task listed as a prerequisite: the cap is
independent of how many HTTP methods the provider has. B-023 is still owed.

**Files:** `ai/opencode/OpenCodeAiProvider.java` (or its B-023 successor), `ai/gateway/UsageService.java`
**Depends on:** B-023

---

### B-025 · One shared, tolerant LLM JSON extractor
**P1 · M · area: backend/ai**

Nine duplicated `extractJson` implementations. All only strip ```` ``` ```` fences; none tolerate prose around the JSON. The test log shows this failing in practice:

```
WARN BugReportService : Failed to parse bug report AI response:
      Unrecognized token 'I': was expecting (JSON String, Number, ...)
```

**Change**
- One `LlmJson.extract(String)`: handles fenced blocks (```json / ```), preamble/epilogue prose, leading/trailing commas, and finds the first balanced top-level `{`/`[`.
- All nine call sites use it.
- Unit tests against a corpus of real malformed LLM outputs (capture ~20 from the logs).

**Acceptance criteria**
- [x] `grep -rc "private String extractJson"` → 0 (one shared static `LlmJson.extract`, not a private method)
- [x] Corpus tests pass, including the captured real failures
- [x] Validator and parser share the same extractor so validation matches parsing

**Shipped in** `56f62d9`. The duplication was also hiding a second bug: `JsonValidators`
had its own extractor, so the validator and the parser had drifted — and since the
validators drive the cascade's fallback decision, a response the validator accepted but
the parser rejected would spend a fallback provider on a response that was never
malformed. That is the criterion that mattered, and it is now tested.

**Files:** the 9 agent/service classes, `ai/provider/JsonValidators.java`
**Depends on:** —

---

### B-026 · Structured logging + LLM metrics
**P1 · M · area: observability**
**Status: DONE** — `27df82a`

No JSON logging, no correlation IDs, no metrics. One INFO line per AI call; no aggregate view. Answering "why was it slow?" today means log scraping — and the caching decision in Sprint 3 is impossible without this.

**Change**
- JSON logs with `operationId`, `executionId`, `projectId`, `provider`, `model`, `latencyMs`, `tokens`, `attempts`, `cost`.
- Actuator + Micrometer: `ai.calls`, `ai.latency`, `ai.tokens`, `playwright.duration`, `workflow.duration` (tagged by step).
- Propagate `operationId` through `AgentExecutionContext` into every log line (partially exists — finish it).
- Request logging filter for `/api/**`.

**Acceptance criteria**
- [x] One request can be traced end-to-end by `operationId` in the logs
- [x] Dashboards/queryable metrics for the 5 counters above
- [x] Logs are valid JSON in prod profile

**Files:** `application-prod.yml`, `pom.xml`, new `config/`, gateway services
**Depends on:** B-013 (tenant key for metric tags)

---

### B-027 · Fix `AnalysisCache` semantics
**P1 · M · area: backend/cache**

Four `ConcurrentHashMap`s: in-memory only, no TTL, no size bound, no invalidation, lost on restart. `putLoginCredentials` stores **plaintext username/password in memory keyed by URL hash** for the process lifetime.

**Change**
- TTL (e.g. 30 min, configurable) + max entries + LRU eviction.
- **Remove credential caching entirely** — pass credentials through the call instead. A URL-keyed plaintext password in a long-lived heap is a liability with no benefit.
- Make `forceRefresh` meaningful and observable (cache hit/miss metric) so the "always re-analyse" cost becomes a decision rather than a default.
- If persistence is wanted, use `PageAnalysisHistory` with a short TTL.

**Acceptance criteria**
- [x] No credential material retained in any cache
- [x] Entries expire; eviction is tested
- [x] Cache hit rate is observable

**Shipped in** `0051410` — with one correction. The backlog calls credential caching
"a liability with no benefit"; it was worse than that. Because the cache key was the URL
and not the request, and because `ExplorerService` only wrote credentials after a
*successful* login, a later **anonymous** run against the same URL read the previous
user's password back out and attached it to its own generated tests. Two users of the
same login page shared credentials. Removed rather than bounded, and the regression
guard is structural: a test reflects over the cache and fails if any field or method
mentions credentials, so re-adding it breaks the build.

Persistence is deliberately not included — a 30-minute bounded in-memory cache covers
the stated problem, and durable caching belongs with the Sprint 3 caching work rather
than as a side effect of a security fix.

**Files:** `cache/AnalysisCache.java`, `service/ExplorerService.java`
**Depends on:** B-026 (metrics), B-014 (if persisting)

---

### B-028 · Reuse the browser instead of relaunching per call
**P1 · M · area: backend/tooling**
**Status: DONE** — `cd0e67f`

`BrowserTool.java:41,83,106` calls `Playwright.create()` + `chromium().launch()` on **every** invocation (≈1–2 s overhead each), with no `--no-sandbox` arg — which commonly breaks Chromium in containers running as root. `screenshotBase64` (full-page PNG) is carried in-band through the API and `AnalysisResponse`; the CLI has to `del(.screenshotBase64)`, a symptom of the payload problem.

**Change**
- One shared Playwright instance + browser per workspace; new context/page per call.
- `--no-sandbox --disable-dev-shm-usage` when running as root in a container.
- Screenshots: return a **path/URL**, not base64. Keep base64 only where a browser `<img>` genuinely needs it, and cap its size.
- Bound concurrent pages.

**Acceptance criteria**
- [x] N explore calls launch 1 browser, not N
- [x] Chromium launches successfully in the container image
- [x] No unbounded base64 in API responses

**Files:** `tool/browser/BrowserTool.java`, `service/ExplorerService.java`
**Depends on:** —

---

### Sprint 2 definition of exit
- [ ] Per-test results available structurally (B-022)
- [ ] Worst-case LLM calls per operation is bounded and measured
- [ ] "Why was it slow?" answerable from metrics, not log-scraping
- [ ] No plaintext credentials retained in memory

---

## Sprint 3 — Reporting product

*Goal: the artifact a user actually reads. Do not start before B-022 lands — every item here consumes structured per-test results.*

---

### B-029 · Self-contained HTML report per run
**P1 · M · area: backend/report · HIGHEST USER VALUE**

`ReportService` already writes `report.json` + `report.md` next to the artifacts, but the CLI never surfaces them, there is no HTML, and screenshots are not linked into the markdown. This is the product's core deliverable and it currently does not exist in usable form.

**Change**
- One `report.html` per execution: run summary (counts, duration, pass rate), the test plan, per-test results grouped by status, failure classification, healing proposals, and **embedded screenshots** (base64 or copied alongside).
- Self-contained (no CDN, no external assets) so it can be emailed or archived.
- Also render the workspace test-plan section.
- Keep `report.json` as the machine contract; `report.md` stays as the diffable version.

**Acceptance criteria**
- [x] `artifacts/execution-<id>/report.html` opens offline and shows every failing test with its screenshot
- [x] Report links to trace/video when present
- [x] Degrades gracefully when there are no artifacts
- [x] Golden-file test for the renderer

**Shipped in** `a8d84fe` — with two prerequisites the task did not account for:

- **The acceptance criterion was unmeetable as written.** Artifact collection flattened
  every trace to the same `trace.zip` with `REPLACE_EXISTING`, so a run with two failing
  tests kept one trace and silently destroyed the other, and nothing recorded which
  screenshot belonged to which test. Evidence is now filed per test.
- **B-022's structured results never reached the product.** `ExecutorAgent` copies a
  hand-picked list of keys and `summary` was not on it, so `steps.execution.results` was
  never present and nothing was written to `test_case_result`. Fixed here, with a test
  that runs a real Playwright suite through the real tool and the real agent.

Videos and traces are **linked rather than embedded**: they are routinely tens of
megabytes, and base64 would inflate them by a third and produce a file no mail client
will open. Screenshots are embedded, with a 4 MB per-image cap.

**Files:** `service/report/ReportService.java` (new `HtmlReportRenderer`), new template resource
**Depends on:** B-022, B-011

---

### B-030 · Allure integration (optional — decide after B-029)
**P2 · M · area: backend/report · DECISION REQUIRED**

The user explicitly asked about Allure. Honest assessment: Allure gives categories/history/retries/envIRONMENT for free, but it needs `allure-playwright` in **every generated test workspace** — i.e. an npm dependency the tool imposes on the user's project. That is a real cost, and it is also more than a week of work to make it degrade gracefully when the dependency is absent.

**Decision needed:** (a) adopt Allure, (b) ship only the bespoke HTML report, or (c) ship bespoke + optional Allure when the workspace already has it.

**Acceptance criteria (if adopted)**
- [ ] `allure generate` runs as part of execution when the workspace has `allure-playwright`
- [ ] Absent dependency → bespoke report, no failure
- [ ] Result survives into the user's repo (or is copied to the report dir)

**Files:** `service/workspace/*`, new `service/report/AllureReportService.java`
**Depends on:** B-029, decision

---

### B-031 · Surface reports in CLI and UI
**P1 · S · area: cli + frontend**

Reports are generated but invisible.

**Change**
- CLI summary prints the `report.html` / `report.md` / bug-report paths and opens the HTML if requested.
- Frontend: execution view links the report and renders per-test results + failure classification (data from B-022).

**Acceptance criteria**
- [x] One command shows the user where the report is
- [x] Frontend shows per-test status without opening a file

**Shipped across `a8d84fe` and `dfa5b0f`.** One scope note: the report is shown in the
dashboard as a **path, not a link**, because nothing serves the artifact directory over
HTTP. An endpoint streaming those files would also expose every run's screenshots, videos
and traces to anyone who can reach it, so it needs an access rule of its own. **Open
question for the user:** per-account ownership of runs, a shared read-only token, or an
admin-only route.

**Files:** `cli/qalab`, `frontend/src/components/execution-dashboard.tsx`
**Depends on:** B-006, B-022, B-029

---

### B-032 · Bug reports grounded in real failures
**P1 · M · area: backend/service**

The user's complaint: the generated bug report was not one of their known bugs — generic title, `LOCATOR_FAILURE`, `Unknown error`. Root cause: failure analysis hard-failed (P0 previously fixed) so the bug report had no analysis to ground on, and it is fed a whole-run blob rather than per-test failures.

**Change**
- Generate one bug report **per distinct failure signature**, not one per execution (20 failing tests should not yield 1 vague or 20 duplicate reports).
- Ground the report in B-022 per-test errors + the failure analysis + the healing classification.
- Include: repro steps, expected vs actual, the exact assertion that failed, screenshot, and a deduplication key so a known bug is not re-reported every run.
- Respect user instruction context when present.

**Acceptance criteria**
- [x] Distinct failures → distinct reports; identical failures collapse
- [x] Every report cites the specific assertion/error that failed
- [x] Screenshot attached and linked from the HTML report

**Shipped in** `88e7295`. One design point worth stating: the deduplication fingerprint
**excludes the test name** and normalises everything volatile in the message (line
numbers, durations, Playwright timestamps, absolute paths). Including the name would
re-file a tracked bug on every rename; leaving the message un-normalised would re-file it
on every run. The assertion text itself is preserved, so two failures in one spec stay
two bugs.

**Files:** `service/BugReportService.java`, `model/BugReport.java`, migration
**Depends on:** B-022, B-013 (dedup key needs a tenant), B-014

---

### B-033 · Retire the legacy `/api/*` surface
**P2 · M · area: backend/api**
**Status: DONE** — `06f76c5`

Nine legacy controllers (`controller/`) alongside fifteen v1 controllers (`api/v1/`); the frontend still calls `/api/explore` (`frontend/src/lib/api.ts:19`). Two contracts to keep in sync, no deprecation plan, and a large blind spot for the security review in B-013.

**Change**
- Migrate the frontend to `/api/v1/**`.
- Delete the nine legacy controllers.
- Grep the CLI + docs for old paths; update.

**Acceptance criteria**
- [x] No `/api/*` (non-v1) mappings remain — enforced by `ApiSurfaceTest`
- [x] Frontend and CLI fully on v1 (the CLI already was)
- [x] `STATE-AUDIT.md` finding marked resolved

**The migration was not a path rewrite.** Three capabilities lived only on the legacy
surface and would have been deleted by one: per-test results (B-031), `agentResults` on
explore, and healing *apply*. Two v1 request records also dropped fields the dashboard
sends — `testType` on run, `instruction` on locators — which would have disabled the
test-type selector and the locator guidance with no error anywhere. All carried across.

**Still two healing models**, and that is not an oversight: a `HealingProposal` has no
`elementName`, which the applier needs. Unifying them is a schema migration.

**Files:** `controller/**` (delete), `frontend/src/lib/*.ts`, `cli/qalab`
**Depends on:** B-013 (do the security pass on one surface, not two)

---

### Sprint 3 definition of exit
- [ ] Every run leaves an offline-openable HTML report with screenshots
- [ ] Bug reports map to specific, deduplicated failures
- [ ] One API surface

---

## Sprint 4 — Quality moat

*Goal: protect the product's core claim — "it writes good tests" — which is currently unmeasured.*

---

### B-034 · Evaluation harness for generated tests
**P1 · L · area: qa/new · HIGHEST STRATEGIC VALUE**

Nothing measures whether the generated tests are any good. The product's value proposition is defect detection; there is no golden set, no scoring, no regression signal on prompt or model changes.

**Change**
- Golden corpus: N fixture pages (static HTML is fine) each with a **known, documented set of defects** (missing label, wrong role, no error state, XSS reflection, broken validation…).
- Runner: explore → plan → generate → execute the generated suite against the fixture → score **defect recall** (did the generated tests catch the planted bugs?) + false-positive rate + flake rate.
- Run in CI on prompt/model changes; publish as a report.

**Acceptance criteria**
- [x] A single command reproduces the score for the current prompts
- [x] CI fails on regression vs. the committed baseline
- [x] Score includes defect recall, not just "tests ran"

**Shipped in** `d9a7453`, with a scope boundary worth stating. The measurement and the gate
are complete: one defect per fixture with a correct twin, the suite run twice, and
defect recall / false-positive rate / flake rate scored against a committed baseline that
CI enforces. Current score **5/5 defects, 0 false positives, 0 flakes**.

What the baseline measures is the **golden suite** — the instrument's own health check.
It does not yet measure the **generator**, because that needs provider credentials and
therefore a secret in CI. The scorer is agnostic to where the statuses come from, so the
remaining work is a driver that generates a suite per fixture and runs it through the same
two-pass scoring. I stopped short of shipping a CI job that would need a key and skip
itself.

**Files:** new `backend/src/test/.../eval/`, `evals/fixtures/**`, CI job
**Depends on:** B-022 (needs per-test results to score), B-025

---

### B-035 · Prompt versioning
**P2 · S · area: backend/ai**
**Status: DONE** — `0e2ff1a`

Seven prompt templates in `resources/prompts/`, changed ad hoc, with no version, no fixture, no offline check. The prompt is the product; it is the one asset with zero safety net.

**Change**
- Stamp each prompt with a version constant logged on every call (feeds B-026).
- Golden-output checks in the eval harness (B-034) for prompt edits.
- Changelog discipline: prompt changes must cite the eval delta.

**Acceptance criteria**
- [x] Every AI call logs the prompt version — `prompt=<name>@<hash>` on the gateway line
- [x] Prompt diffs require an eval run — `manifest.properties` pins each template to the
      evaluated text and the build fails on drift

**The version is a content hash, deliberately.** A hand-maintained counter is the obvious
design and the wrong one: it gets forgotten, and an un-bumped edit produces unattributable
output. The citation field in the manifest is required to be non-empty, so "the score did
not change" is recorded rather than left implicit.

**Files:** `resources/prompts/*`, `agent/*`, B-026/B-034 outputs
**Depends on:** B-026, B-034

---

### B-036 · Close the test-coverage holes
**P1 · M · area: backend/test**
**Status: DONE** — `2981e1f`

127 tests pass but coverage skips the risky code:

| Untested | Risk |
|---|---|
| `ExplorerService.analyze` | core pipeline entry: forceRefresh, cache write, login capture |
| `PlanningService.generateTestPlan` | plan generation + persistence |
| `LocatorService.generateLocators` | locator persistence |
| `PlaywrightTool.execute` | B-001 lives here |
| `ArtifactStore.collect` | artifact collection |
| `ReportService.generate` | report rendering |
| `OpenAiCompatProviderClient` / `AnthropicCompatProviderClient` | provider wire formats |
| `BrowserTool` | navigation, screenshot, error paths |

**Acceptance criteria**
- [x] Each service above has happy path + at least one failure path
- [x] Provider clients tested against a stub HTTP server (also de-risks B-015)
- [x] A coverage floor enforced by `mvn verify` — 75% `ai/gateway/`, 58% `service/`

**The list was checked, not trusted.** Two entries were already covered incidentally
(`PlaywrightTool` 70.7%, `ArtifactStore` 79.2%, from B-028 and B-029), and one that was not
on the list was not covered at all: `ProjectService`, extracted in B-033, which deletes
nine tables and a workspace directory.

Measured: `service/` 49.6% → **60.0%**, `ai/gateway/` 67.0% → **79.9%**.

`service/` did not reach the suggested 70%. The remainder is concentrated in
`service.git` (2.9%), `ExecutionService` (18%) and `CodeGenerationService` (16%) — none of
them on the original list. **That is the finding worth keeping:** the listed gaps were the
ones somebody already suspected.

**Files:** `backend/src/test/java/**`
**Depends on:** B-008 (do that first — it is already Sprint 0)

---

### B-037 · Resilience: circuit breaker + bulkhead on the AI gateway
**P2 · M · area: backend/ai**

One slow or rate-limited provider degrades the whole application: there is no circuit breaker and no bulkhead, and the workflow holds request threads across every LLM call (B-017 helps but does not solve it).

**Change**
- Circuit breaker per provider (open after N consecutive failures, half-open probe, configurable cooldown).
- Bulkhead: cap concurrent in-flight AI calls per provider.
- Emit breaker state as a metric; surface `AI_PROVIDER_UNAVAILABLE` with retry guidance.

**Acceptance criteria**
- [x] A dead provider stops consuming calls after the threshold
- [x] Recovery is automatic and observable
- [x] Cascade (B-024) skips an open breaker

**Shipped in** `6a13220`, with one addition the task did not ask for: the managed cascade
needs a breaker **per model**, not just per client, because the gateway only sees the
aggregate outcome of the cascade and a single dead model in the middle of the chain would
otherwise keep costing a timeout on every call.

**Files:** `ai/gateway/AiGateway.java`, new breaker component
**Depends on:** B-023, B-026

---

### B-038 · Frontend QA sweep
**P2 · M · area: frontend**
**Status: DONE** — `0e6b597`

51 source files, no test runner beyond `next build` + eslint. The dashboard surfaces the same data the CLI does and is the primary UI.

**Change**
- Add Vitest + React Testing Library; test `lib/*` API clients and the critical components (`qa-workflow`, `execution-dashboard`, `healing-dashboard`).
- Playwright E2E against a stubbed backend for: run a workflow, view results, approve a healing proposal.
- Wire into the existing CI job.

**Acceptance criteria**
- [x] `npm test` runs in CI — 45 unit tests, plus 3 Playwright journeys
- [x] E2E covering the main journey: analyse → run → read per-test results, and approve a
      healing suggestion

**Two UI defects found, in four places.** Every card involved was collapsed exactly when
empty, hiding the controls needed to fill it: the execution history's first-run guidance,
and the instruction box and generate button for locators, plan and tests.

**The E2E needed a real stub backend, not route interception.** `app/projects/[id]` is a
server component that fetches during the server render; a browser-only stub leaves it
empty and the suite passes without exercising it. That is what happened until
`e2e/stub-api.mjs` became an actual server.

**Files:** `frontend/**`, `.github/workflows/ci.yml`
**Depends on:** B-022 (needs realistic fixtures)

---

### Sprint 4 definition of exit
- [ ] Defect recall on the golden corpus is measured and enforced in CI
- [ ] Prompt changes cannot merge without an eval delta
- [ ] A dead provider no longer degrades the app

---

## Backlog summary

| Sprint | Theme | Tasks | Theme P0 count |
|---|---|---|---|
| **0** | Stop the bleeding — reliable + readable output | B-001…B-011 (11) | 8 |
| **1** | Deployable — auth, migrations, async, config | B-012…B-021 (10) | 5 |
| **2** | Reliable and measurable | B-022…B-028 (7) | 1 |
| **3** | Reporting product | B-029…B-033 (5) | 0 |
| **4** | Quality moat | B-034…B-038 (5) | 0 |

**Critical path to a deployable product:** B-008 → B-003 → B-004 → B-006 → B-007 (Sprint 0) → B-012 → B-013 + B-014 + B-017 (Sprint 1) → B-029 (Sprint 3).

**Highest value per unit of effort:**
1. **B-001 + B-002** (S+S) — nothing else can be trusted until these stop hanging.
2. **B-006 + B-007** (M+S) — turns an unreadable dump into a usable deliverable.
3. **B-022** (M) — unblocks the entire reporting track.
4. **B-034** (L) — the only task that protects the product's core claim.

**Explicitly deferred / rejected for now:**
- Allure as a hard dependency (see B-030 — decide after the bespoke report exists).
- Multi-tenancy beyond the minimum needed for auth and budgets (B-012 spike decides).
- Caching the page analysis between runs — deliberately **not** scheduled. It is the biggest single latency win but it trades correctness for speed and the user has not chosen that trade. Revisit once B-026 gives real cache-hit and analysis-duration numbers.
