# QALabAI — State Audit / Senior Engineering Review

**Date:** 2026-09-26
**Scope:** backend (Spring Boot 3 / Java 21), frontend (Next 16 / React 19), CLI (`cli/qalab`), deployment (Docker), AI subsystem, QA subsystem
**Baseline:** `main` @ `9d5cbef`
**Verification:** `mvn test` → **127 tests, 0 failures** (BUILD SUCCESS)

---

## 0. Executive summary

The product is **functionally rich and architecturally ambitious** — a 5-agent QA pipeline (explorer → locator → planner → testgen → executor), a multi-provider AI gateway with budget enforcement and cascading fallbacks, a locator-intelligence subsystem (fingerprinting, stability scoring, quality scoring), a self-healing subsystem with human approval, and a Next.js dashboard. That is a lot of surface area, and most of it works.

The problem is **not what is built — it is what is not finished and not hardened.** The code reads like a well-structured sprint-11-of-12 prototype: contracts and extension points are in place, but several implementations behind them are still stubs, and the seams between subsystems leak in ways that only show up outside localhost.

Three things dominate:

1. **Correctness bugs in the execution path** — a dead 60s timeout on the Playwright runner, no HTTP timeouts on any AI client, tests generated twice per run, page objects never delivered to the user's workspace. These make the primary feature (`qalab test`) unreliable and slow.
2. **Zero security posture** — no authentication on any endpoint, CORS pinned to `localhost:3000`, no rate limiting, plaintext credential caching. Fine for a laptop, unacceptable the moment it is deployed (which the user has already done on Oracle Cloud).
3. **The developer-facing output is not usable** — the CLI dumps the raw workflow JSON, the test plan is generated but never written anywhere, and there is no human-readable report artifact. For a tool whose entire value is "tell me what is broken in my app", this is the single biggest product gap.

**Overall:** strong foundation, ~60% production-ready. The remaining 40% is concentrated in reliability, security, and reporting — not in missing features.

---

## 1. What is genuinely finished and working

| Area | Evidence | State |
|---|---|---|
| Agent pipeline & orchestration | `QaWorkflowService`, 5 agents, progress store + WebSocket | **Done**, works end-to-end |
| AI gateway | `AiGateway`, 5 provider clients, credential modes (MANAGED/BYOK/LOCAL), token budget hard/soft stop, usage recording, retry + backoff, cost estimation | **Done**, well-designed |
| Locator intelligence | 9 services + 9 test classes, all passing | **Done**, genuinely strong |
| Self-healing | classify → generate candidates → validate live → propose → human approve → apply | **Done**, incl. source rewrite |
| Budget enforcement | `TokenBudgetService`, HARD/SOFT/NONE policies, pre-flight stop | **Done** |
| Page-object overwrite fix | `WorkspaceManager` per-test page files + union merge | **Done** (this session) |
| LLM step parallelisation | virtual threads, locators ∥ test-plan, bug-report ∥ failure-analysis | **Done** (this session) |
| Unregistered-project resilience | `FailureAnalystAgent` no longer requires `projectId` | **Done** (this session) |
| CI | `.github/workflows/ci.yml` — backend verify (H2) + frontend build/typecheck | **Done** |
| Test suite | 127 tests green | **Adequate for current scope** |

The locator-intelligence subsystem deserves specific credit: fingerprinting, semantic analysis, stability scoring, quality scoring, comparator and observation history — each with dedicated unit tests. This is the most mature part of the codebase and it is the part that makes the "self-healing" claim credible rather than marketing.

---

## 2. Critical defects (P0 — fix before any non-local deployment)

### P0-1. The Playwright 60-second timeout is dead code; the runner can hang forever
`backend/.../tool/playwright/PlaywrightTool.java:76-87`

```java
try (BufferedReader reader = ...) { while ((line = reader.readLine()) != null) { ... } }  // blocks until EOF
boolean completed = process.waitFor(60, TimeUnit.SECONDS);   // only reached AFTER the process exited
```

The output-draining loop blocks until the process closes stdout, so `waitFor(60, …)` is evaluated only once the process has already terminated. `completed` is effectively always `true`, the timeout never fires, and `destroyForcibly()` is unreachable. A hung Playwright run holds the HTTP request thread **indefinitely**.

This is verifiable against the user's own data: the reported run took **158 753 ms** under a "60 s" timeout.

**Fix:** read the stream on a separate thread (or use `ProcessBuilder.redirectOutput(File)` and tail the file), keep `waitFor` on the main path, and enforce a real wall-clock budget. Also stream-incrementally rather than accumulating the whole run output in one `StringBuilder` (see P1-7).

### P0-2. No HTTP timeouts on any AI provider client
`OpenAiCompatProviderClient.java:26`, `OpenCodeAiProvider.java:66`, `AnthropicCompatProviderClient`

```java
private final RestTemplate restTemplate = new RestTemplate();   // connect + read timeout = 0 = INFINITE
```

Every AI call can block forever. Combined with P0-1 this means a single provider stall can wedge both the request thread and the workflow. `AiGateway.executeWithRetry` retries generic exceptions but a *hang* never becomes an exception.

**Fix:** a shared `RestTemplate` bean with explicit `connectTimeout` / `readTimeout` (e.g. 10 s / 180 s — LLM calls legitimately run long, so the read timeout must be generous but finite). Apply it to all three clients.

### P0-3. No authentication anywhere
No `SecurityFilterChain`, no `@PreAuthorize`, no JWT, no API key — verified by grep across the whole backend. Every `/api/**` and `/api/v1/**` endpoint is open, including:

- `POST /api/v1/workflows/full-test` — triggers 6–7 **paid** LLM calls
- `POST /api/v1/projects/{id}/healing/…/apply` — mutates stored test sources
- all project / locator / execution / usage data — readable and listable by anyone who can reach the port

`AccountService.defaultAccount()` returns a single implicit global account, so usage attribution and budget enforcement are meaningless with more than one user.

**Fix (minimum viable):** an API-key or JWT filter on `/api/**`, at least gating the mutating and AI-spending endpoints. Realistically this needs a tenant/account entity — the domain model already has `Account`, so the seam exists.

### P0-4. CORS pinned to localhost — the cloud deployment is broken by construction
`config/WebConfig.java:13`

```java
.allowedOrigins("http://localhost:3000")
```

Deployed anywhere other than a browser on the same machine, **every** frontend API call is blocked by CORS. The user has already deployed to Oracle Cloud; this is very likely a live symptom they have not yet isolated.

**Fix:** externalise to `${qalab.cors.allowed-origins}` (comma-separated), default to localhost for dev.

### P0-5. Frontend WebSocket URL hardcoded to localhost
`frontend/src/lib/use-agent-websocket.ts:16`

```ts
new WebSocket("ws://localhost:8080/ws/agents")
```

Live progress silently fails on any remote deployment (and on any https origin, which needs `wss://`). Build it from the same `API_BASE_URL` config, deriving ws/wss from the http/https scheme.

### P0-6. `NEXT_PUBLIC_API_BASE_URL` is frozen at image build time
`frontend/Dockerfile:12-14` uses `ARG` + `ENV` **before** `npm run build`. Next.js inlines `NEXT_PUBLIC_*` at build time, so the `environment:` entry in `docker-compose.yml:63` is a **no-op** — it sets the variable on a container that is already built. The deployed UI always talks to the build-time URL.

**Fix:** make the base URL a runtime value (server-side proxy route, or inject via a small `config.js`/env-substitution step in the entrypoint), or bake it correctly at build with the real public URL.

### P0-7. The CLI writes spec files whose page objects do not exist
`service/CodeGenerationService.java:128-130`

```java
List<GeneratedFile> files = tests.stream()
        .map(t -> new GeneratedFile(TestWorkspaceService.resolveFileName(t), t.getTestCode()))   // testCode only
        .toList();
```

`generateTestsContent` returns **only test code**. But that code contains `import { LoginPage } from '../pages/LoginPage'`. The CLI (`cli/qalab:465-468`) writes exactly these files into the user's workspace and **never writes any page object**. So the specs the user receives cannot compile — `pages/LoginPage.ts` is absent.

The page objects only ever reach `project.getWorkspacePath()` via the separate `writeTests` call inside `runInWorkspace`, which for an unregistered project may resolve to a *different* directory (e.g. `backend/workspaces/project-N`). This is precisely the confusion observed: the CLI announced 20 files written to `quiz/qa/`, yet that directory was empty afterwards, because execution had happened somewhere else.

**Fix:** include page objects in the workflow response (e.g. `steps.generatedTests.pageObjects: [{path, content}]`) and have the CLI write them alongside the specs. Better still, return the exact files that `writeTests` produced so the response and the disk state can never diverge.

### P0-8. Tests are generated twice per workflow run
`QaWorkflowService.java:131` → `generateTestsContent` → `runGenerator`
`QaWorkflowService.java:161` (inside `runInWorkspace`) → `generateTestsEntities` → `runGenerator`

Both call `runGenerator`, which is the expensive test-generation LLM call. So every `qalab test` pays for it **twice**, and the two invocations are independent — the specs returned to the CLI are **not** the specs that were executed. Doubles cost, adds latency, and makes the reported output untrustworthy.

**Fix:** generate once, keep the `List<GeneratedTest>`, and use it for both the response payload and `writeTests`.

### P0-9. Test plan is generated but never persisted
The plan is built and stored in the DB (`PlanningService:107`), but the workflow response contained only `{"status":"COMPLETED","scenarioCount":14}` — the scenarios were discarded. The CLI has no code path to write a plan either (`grep test-plan cli/qalab` → nothing).

Partially addressed this session: `QaWorkflowService` now returns `plan.scenarios()` in `steps.testPlan`. **The CLI half is still missing** — nothing consumes it yet, so the user-visible behaviour is unchanged.

### P0-10. CLI output is unusable
`cli/qalab:475`

```bash
printf '%s' "$response" | jq . 2>/dev/null || true
```

The entire workflow response — including all 20 generated test sources inline — is dumped as raw JSON. This is the "JAKO nepregledno" complaint, and it is objectively justified.

**Fix:** a human summary by default (status table, counts, duration, failed-test list, artifact paths, bug-report headline), with the full JSON behind `--json` / `--verbose`.

### P0-11. Artifacts volume is missing from compose
`docker-compose.yml:55-57` mounts volumes for `workspaces` and `screenshots` but **not** `artifacts`. `qalab.artifacts-dir` defaults to `./artifacts` → `/app/artifacts`, which is not persisted. Every collected screenshot, video, trace and generated `report.md`/`report.json` is lost on container restart — which is most of the value the product adds.

**Fix:** add a `qalab-artifacts` volume + `QALAB_ARTIFACTS_DIR` env.

---

## 3. Production-readiness gaps (P1)

### P1-1. Startup blocks on `npm install` + `playwright install`
`config/PlaywrightSetupConfig.java:22-40` is a `CommandLineRunner` that calls `WorkspaceManager.prepareWorkspace()` for every `project-*` dir. `prepareWorkspace` shells out to `npm install` and `npx playwright install chromium` with a **600-second** timeout each (`WorkspaceManager.java:420`). With N workspaces, the application can be unavailable for up to 10×N minutes on boot. This is a plausible contributor to the "500 on install playwright" symptom the user reported on Oracle Cloud.

**Fix:** do this lazily/asynchronously (readiness gate instead of startup gate), or run it only when the workspace is first used, or as a separate init job.

### P1-2. No database migrations
`ddl-auto: update` in base + dev; `ddl-auto: validate` in prod (`application-prod.yml:8`). No Flyway, no Liquibase, no `*.sql` anywhere. `validate` means a prod database can never be created or evolved — the schema is whatever Hibernate last happened to produce on a dev machine. This is the single biggest blocker to a trustworthy production release.

**Fix:** introduce Flyway, baseline the current schema, and move all environments to `validate` + versioned migrations.

### P1-3. `qalab.ai` configuration is entirely absent
`config/AiGatewayProperties.java` defaults: `defaultProvider = AIQALAB`, `defaultCredentialMode = MANAGED`, `freeMonthlyTokenLimit = 4000`, `providers = {}`. There is **no `qalab.ai` block in any yml file**.

Consequences:
- `providers` is empty → `resolveModel()` returns `null` for every BYOK provider → `{"model": null}` in the request body → provider 400. **BYOK OpenAI / Gemini / Ollama are effectively broken.** (The managed path masks this because `OpenCodeAiProvider` reads its own `@Value` models.)
- `freeMonthlyTokenLimit = 4000` tokens/month is unusable for the product's main flow: one full-test run sends 6–7 prompts containing full simplified page HTML. `docker-compose.yml:44` works around it with `QALAB_AI_FREEMONTHLYTOKENLIMIT: 0`, but a plain `mvn spring-boot:run` will hard-stop on `AI_BUDGET_EXCEEDED`.

**Fix:** ship a real `qalab.ai` block (default provider, per-provider base URLs and models) and raise the FREE default to something coherent.

### P1-4. Rate limiting is a no-op and its config flag is dead
`RateLimiter` javadoc: *"Sprint 11 ships a placeholder implementation"*. `NoopRateLimiter.allow()` always returns `true`. Meanwhile `AiGatewayProperties.rateLimitEnabled` is never read by `AiGateway`. Combined with P0-3, any anonymous caller can drive unbounded paid LLM traffic.

**Fix:** implement a token-bucket per provider/account; wire `rateLimitEnabled` to it.

### P1-5. Provider cascade can issue up to 15 LLM calls per operation
`OpenCodeAiProvider.attemptProviders` tries Go → Zen → Zen-fallback → Gemini → Ollama, inside a 3-attempt loop (`MAX_ATTEMPTS = 3`). Worst case 15 upstream calls for one logical operation. Each also sends `max_tokens: 12000`. On a free/rate-limited tier this is both the latency and the "sporo" problem the user reported, and it is invisible in the token-budget estimate (which only counts the final response).

**Fix:** cap total attempts across the cascade (not per provider), stop escalating once a provider has failed twice, and surface per-operation cost/attempt count in the usage record.

### P1-6. LLM JSON extraction is duplicated 9× and too naive
Nine separate `extractJson` implementations (`LocatorAgent`, `PlannerAgent`, `TestGeneratorAgent`, `FailureAnalystAgent`, `SelfHealingAgent`, `HealingAiEvaluator`, `BugReportService`, `ExplorerService`, `JsonValidators`). All only strip ```` ``` ```` fences — none handle prose before/after the JSON. The test log contains proof this fails in production:

```
WARN BugReportService : Failed to parse bug report AI response:
      Unrecognized token 'I': was expecting (JSON String, Number, ...)
```

**Fix:** one shared, brace-matching extractor (tolerate preamble/epilogue, fenced blocks, trailing commas) + one parser used by every agent. Then unit-test it against a corpus of real malformed LLM outputs.

### P1-7. Execution output is buffered whole and truncated arbitrarily
The full Playwright stdout goes into one `StringBuilder`, is persisted to `consoleLogs` (TEXT), then truncated to 2000 chars for the API response (`QaWorkflowService:176`). The user therefore sees the first 2000 characters of a 20-test run — which is exactly the truncation that made the output unreadable. Per-test results are never parsed out, so the workflow cannot tell the user *which* tests failed in a structured way.

**Fix:** run Playwright with a JSON reporter (`--reporter=json:<file>`), parse per-test results (status, duration, error, attachments), return them structurally, and keep the raw text only as a tail for diagnostics.

### P1-8. `AnalysisCache` is in-memory, unbounded, TTL-less, and caches credentials
`cache/AnalysisCache.java` — four `ConcurrentHashMap`s, no persistence, no TTL, no size bound, no invalidation. Restart = total loss; the cache grows without limit. `putLoginCredentials` stores **plaintext username/password in memory keyed by URL hash** for the lifetime of the process.

**Fix:** TTL + max size; keep credentials out of the cache (pass them through the call); if persistence is wanted, use the existing `PageAnalysisHistory` with a short TTL.

### P1-9. The whole workflow is one long synchronous HTTP request
`V1WorkflowController.fullTest` → `runFullTest` runs inline (~158 s observed) while the client polls a separate progress endpoint. Tomcat's default 200 request threads are held for the entire duration. A handful of concurrent runs exhausts the pool, and any proxy/LB in front (nginx, Oracle's load balancer) will drop the connection at its own read timeout — which matches the `AsyncRequestNotUsableException: Connection reset by peer` in the user's logs.

**Fix:** make `POST /workflows/full-test` return `202 Accepted` + `operationId` immediately and move the work to a background executor, with the existing progress endpoint as the polling channel (and a `GET /workflows/{id}` for the final result).

### P1-10. Duplicate API surface: legacy `/api/*` and `/api/v1/*` — **RESOLVED (B-033)**

Nine legacy controllers (`controller/`) alongside fifteen v1 controllers (`api/v1/`). The frontend still called legacy paths. Two contracts to keep in sync, no deprecation plan, and a large blind spot for security review (P0-3).

**Done:** the dashboard is on `/api/v1`, the nine legacy controllers are deleted, and the
CLI was already v1-only. `ApiSurfaceTest` now fails the build if any `/api/**` mapping
appears outside `/api/v1`, so the finding cannot silently reopen.

**Three capabilities were on the legacy surface only** and were migrated rather than lost:
per-test results (B-031), `agentResults` on explore, and healing *apply*. Two v1 request
records also silently dropped fields the dashboard sends — `testType` on run and
`instruction` on locators — which would have disabled the test-type selector and the
locator guidance with no error anywhere. Both are wired now and pinned by tests.

**Not fixed here:** `HealingProposal` and `HealingSuggestion` remain two models. Unifying
them needs a schema migration, which is a separate decision.

### P1-11. Every Playwright run records screenshot + video + trace for every test
`WorkspaceManager.initializeProjectStructure:341-344` sets `screenshot: 'on', video: 'on', trace: 'on'`. Note `screenshot: 'on'` is not the Playwright default (`only-on-failure`) — it captures for passing tests too. With 20 tests that is 20 videos + 20 traces per run: large disk usage, significant wall-clock overhead, and a large `ArtifactStore` copy per execution.

**Fix:** default to `only-on-failure` for screenshots, keep video off by default, always-on trace only for failures; make the profile configurable.

### P1-12. `BrowserTool` starts a fresh browser per call and embeds base64 screenshots
`tool/browser/BrowserTool.java:41-42, 83-84, 106-107` — `Playwright.create()` + `chromium().launch()` on every invocation (≈1–2 s overhead each, and no `--no-sandbox` arg, which commonly breaks Chromium in containers running as root). `screenshotBase64` (full-page PNG) is returned through the API in `AnalysisResponse`; the CLI has to strip it (`del(.screenshotBase64)`), which is a symptom of the payload being carried in-band.

**Fix:** reuse a pooled browser/context per workspace; store screenshots on disk and return a path/URL instead of base64.

---

## 4. Maintainability & quality (P2)

### P2-1. Test coverage has clear holes
127 tests pass, but coverage is uneven and skips exactly the risky code:

| Untested | Risk |
|---|---|
| `ExplorerService.analyze` (forceRefresh, cache write, login capture) | core pipeline entry point |
| `PlanningService.generateTestPlan` | plan generation + persistence |
| `LocatorService.generateLocators` | locator persistence |
| `PlaywrightTool.execute` | P0-1 lives here |
| `WorkspaceManager.mergePageObjects` / `rewritePageObjectImport` | the fix landed this session with **no regression test** |
| `QaWorkflowService` parallel branch | the parallelisation landed this session with **no test** |
| `ArtifactStore.collect` | artifact collection |
| `ReportService.generate` | report rendering |
| `OpenAiCompatProviderClient` / `AnthropicCompatProviderClient` | provider wire formats |

The last two rows are the ones I would fix first: both changes from this session are unverified by CI, and the page-object merger in particular is a hand-rolled brace-matching parser that deserves adversarial unit tests (unbalanced braces, methods with braces in string literals, arrow-function properties, generics).

### P2-2. `OpenCodeAiProvider` is four copy-pasted HTTP methods
`callGoApi` / `callZenApi` / `callOllamaApi` / `callGeminiApi` are ~60 lines each, differing only in URL, auth header, and response-extraction strategy. Plus a parallel `OpenAiCompatProviderClient` doing the same thing again. Two provider stacks (legacy `AiProvider` + new `ProviderClient`) coexist.

**Fix:** one `ChatCompletionClient` with pluggable auth + response-extractor strategies; delete the legacy stack once BYOK paths are proven.

### P2-3. No observability
Structured JSON logging, request correlation, LLM latency/attempt/token metrics, and Playwright duration histograms: none. `AiGateway` logs one INFO line per call but there is no aggregate view. When the user asks "why was it slow?", the only answer today is log scraping. This is also the prerequisite for the caching decision (P3-1) — you cannot tune what you do not measure.

### P2-4. No prompt versioning or eval harness
Seven prompt templates in `resources/prompts/`, changed ad hoc, with no version, no regression fixture, and no offline eval. Prompt quality is the product here and it is the one asset with zero safety net. `AgentInstructionTest` and `TestGeneratorAgentNormalizationTest` cover string handling, not generation quality.

**Fix:** version prompts in git with an eval set of (page fixture → expected test-quality assertions) runnable in CI.

### P2-5. Long synchronous transactions around external calls
`runFullTest` holds a request thread across browser automation, npm subprocesses, and multiple LLM calls; JPA repositories are called between them. No `@Async`, no bulkhead, no circuit breaker on the AI gateway. One slow provider degrades the whole app.

---

## 5. What is missing and should be added

Ordered by user-visible value:

1. **A real report artifact.** `ReportService` already writes `report.json` + `report.md` per execution — but the CLI never surfaces them, there is no HTML, and screenshots are not linked into the markdown. This is the highest-value missing feature: a single self-contained `report.html` per run with the test plan, per-test results, failure classification, healing proposals, and embedded screenshots. Allure is a reasonable option (`allure-playwright` + `allure generate`), but given P1-7 the prerequisite is the same either way: **parse Playwright's JSON reporter output**. Do that first; the report format is then a cheap addition.
2. **CLI human summary + `--json` escape hatch** (finishes P0-10).
3. **Write the test plan to disk** as `test-plan.md` (finishes P0-9).
4. **Ship page objects to the CLI's workspace** (finishes P0-7).
5. **Async job model** (P1-9) — required before the app can serve more than a handful of concurrent users, and it fixes the cloud connection resets.
6. **Auth + tenant model** (P0-3). Until then, treat the deployment as single-user and do not expose it publicly.
7. **Database migrations** (P1-2) — hard prerequisite for any real production data.
8. **Evaluation harness for generated tests** (P2-4). The product's core claim is "it writes good tests"; nothing measures that. A golden set of pages with known defects, scored on defect recall, would be the most valuable non-feature work available.
9. **Retry/timeout observability** (P2-3) so the "sporo" complaint becomes measurable rather than anecdotal.

---

## 6. Recommended sequencing

**Sprint A — make it trustworthy (P0, no behaviour change)**
Playwright timeout · AI HTTP timeouts · single generation pass · page objects in response + CLI · write test plan · CLI summary · artifacts volume · CORS + WS URL config.

**Sprint B — make it deployable (P0/P1)**
API-key auth · DB migrations via Flyway · async job model · `qalab.ai` config block + real BYOK model resolution · real rate limiter.

**Sprint C — make it measurable (P1/P2)**
Playwright JSON reporter + per-test results · structured logs + LLM metrics · analysis cache TTL · shared JSON extractor + regression tests · dedupe provider clients.

**Sprint D — make it good (features)**
HTML/Allure report with screenshots · test-quality eval harness · prompt versioning · coverage for the untested services listed in P2-1.

---

## 7. Correction to prior work in this session

For the record, to avoid acting on a wrong premise: an earlier message in this session stated that the CLI now writes `test-plan.json` and produces organized/colourised output. **That was incorrect** — only the backend half was done (`QaWorkflowService` now returns `plan.scenarios()` in the response). `cli/qalab` is unchanged since `ddc797d`; it still dumps raw JSON and still writes no plan and no page objects. Those remain open items under P0-7, P0-9 and P0-10 above.

---

## Appendix — verification notes

- `mvn test` (backend): **127 tests, 0 failures, 0 errors** — includes the changes from this session.
- `grep` for `SecurityFilterChain|@PreAuthorize|jwt` across `backend/src/main/java`: **no matches**.
- `grep` for `test-plan|scenarios` in `cli/qalab`: **no matches**.
- `find backend/src/main/resources -name '*.sql'`: **no results**; no Flyway/Liquibase in `pom.xml`.
- `qalab.ai` block: **absent from `application.yml`, `application-dev.yml`, `application-prod.yml`**.
- `AnalysisCache` inspect: four `ConcurrentHashMap`s, no TTL, no eviction, `putLoginCredentials` stores plaintext credentials.
- `git log -- cli/qalab`: last touched in `ddc797d`, i.e. not in this session's commit `9d5cbef`.
