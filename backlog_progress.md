# Sprint 0 — Progress Log

**Sprint goal:** the `qalab test` command becomes reliable and its output becomes readable. Nothing changes product behaviour beyond fixing broken behaviour.

**Backlog:** [`backlog.md`](./backlog.md) · **Audit:** [`STATE-AUDIT.md`](./STATE-AUDIT.md)

**Started:** 2026-09-26 12:56 CEST
**Baseline commit:** `9d5cbef`
**Branch:** `main`

---

## Task log

<!-- Entries are appended below, newest last. Format:
### B-0XX · Title
| | |
|---|---|
| **Status** | DONE / IN PROGRESS / BLOCKED |
| **Date** | YYYY-MM-DD |
| **Duration** | Xh Ym |
| **Commit** | `<sha>` (<n> of N for this sprint) |

**What changed**

**How it was tested**
-->

---

### B-001 · Enforce a real timeout on the Playwright runner
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 4m |
| **Commit** | `667f634` (1 of 11) |

**What changed**
- `PlaywrightTool`: subprocess stdout/stderr now redirected to a temp file instead of being drained on the calling thread. This is the actual bug — the inline drain blocked until process exit, so `waitFor(60s)` was unreachable and `destroyForcibly()` was dead code.
- Extracted `runProcess(List<String>, Path)` returning a `ProcessOutcome(boolean completed, int exitCode, long durationMs, String output)` record, which makes the timeout path deterministically testable without invoking npx.
- Budget configurable: `@Value("${qalab.playwright.timeout-seconds:600}")`, added to `application.yml` as `qalab.playwright.timeout-seconds`.
- Timeout path: kill tree → `waitFor(5s)` → return `status=TIMEOUT`, `timeoutSeconds`, and an actionable error naming the exhausted budget and how to resolve it (narrow the run, or raise the property).
- Replaced the unbounded `StringBuilder` with `readTail()`: retains at most the last 200 000 chars and announces truncation. Rationale: Playwright prints its per-test summary *last*, so the tail is the informative part — this also replaces the arbitrary 2000-char head-truncation that made output unreadable.
- Temp output file deleted in a `finally` block.

**How it was tested**
- New `PlaywrightToolTest` — 8 cases:
  - `sleep 60` under a 2 s budget is killed (asserts `completed == false` and elapsed < 30 s) — the regression guard for the original defect.
  - exit codes propagate for `exit 0` / `exit 3`.
  - stdout **and** stderr both captured (stderr merged via `redirectErrorStream`).
  - `execute()` with no target returns the "No test file specified" error.
  - `execute()` always returns a structured status and never hangs.
  - `readTail`: keeps the tail marker, announces truncation, is smaller than the original; returns whole small files; tolerates a missing file.
- Full suite: **135 tests, 0 failures** (127 pre-existing + 8 new). No regressions.
- Log inspection confirmed the 2 s budget firing (process started, no matching "execution completed" line).

---

### B-002 · Put real HTTP timeouts on every AI provider client
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 6m |
| **Commit** | `dc282a7` (2 of 11) |

**What changed**
- Added an `aiRestTemplate` bean in `AiGatewayConfig` using `SimpleClientHttpRequestFactory` with `connectTimeout=10s`, `readTimeout=180s`, both configurable via `qalab.ai.connect-timeout-ms` / `qalab.ai.read-timeout-ms` (added under the existing `qalab:` block in `application.yml`).
- Injected that bean into all **four** clients that were building their own: `OpenAiCompatProviderClient` (used by OpenAI/Google/Ollama), `AnthropicCompatProviderClient`, `OpenCodeAiProvider`, `OpenAiProvider`.
- `OpenCodeAiProvider` and `OpenAiProvider` now take the client via constructor instead of creating one in `@PostConstruct`.
- `grep "new RestTemplate()"` over `src/main/java` → zero occurrences.

Note: the read timeout is intentionally generous (180 s). Tightening it below real generation latency would convert slow-but-successful calls into failures; the point is that it is *finite*, so a stall becomes a retryable exception instead of a permanent hang.

Also caught: `ai/openai/OpenAiProvider.java` was a fourth bare `RestTemplate` not listed in the backlog's "three clients" — fixed in the same pass.

**How it was tested**
- New `AiHttpTimeoutTest` — 3 cases:
  - The bean's `connectTimeout`/`readTimeout` really are 10 000 / 180 000 (read reflectively off the request factory, so a silent revert to `0` fails the test).
  - **Functional proof:** a `ServerSocket` that accepts the connection and never writes a response. With `read-timeout-ms=400` the call must throw, and must do so in < 4 s. This is the direct regression guard for "hung forever".
  - Architectural guard: walks `src/main/java/com/qalab/qalabai/ai` and fails if any file reintroduces `new RestTemplate()`.
- Full suite: **138 tests, 0 failures** (135 + 3 new).
- Log confirms the bean logs `connectTimeout=1000ms readTimeout=400ms` for the test profile and the production values for the default.

---


| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-001 | Playwright runner real timeout | P0 | S | **DONE** | `667f634` |
| B-002 | AI provider HTTP timeouts | P0 | S | **DONE** | `dc282a7` |
| B-003 | Generate test suite once per run | P0 | M | **DONE** | `93b82d0` |
| B-004 | Ship page objects to client workspace | P0 | M | **DONE** | `f0d9bda` |
| B-005 | Unify CLI/backend workspace path | P0 | M | **DONE** | `44f0b3d` |
| B-006 | CLI human summary + `--json` | P0 | M | **DONE** | `47fbf8b` |
| B-007 | Write test plan to disk | P0 | S | **DONE** | `047851f` |
| B-008 | Regression tests for shipped fixes | P0 | M | **DONE** | `7789308` |
| B-009 | Configurable CORS origins | P0 | S | **DONE** | `80f11ce` |
| B-010 | WebSocket URL from config | P0 | S | **DONE** | `83f0fa8` |
| B-011 | Persist artifacts directory | P0 | S | **DONE** | `5667811` |

**Totals:** 11/11 done · 11 commits · elapsed 33m wall (12:56 → 13:29 CEST)

---

## Sprint outcome

**All 11 tasks delivered in 11 commits, 33 minutes wall clock (12:56 → 13:29 CEST).**
Test suite grew from **127 → 172** (+45), all green. Frontend typechecks and builds.

### Defects found and fixed beyond the stated task
| Where | Defect | Task |
|---|---|---|
| `PlaywrightTool` | 60 s timeout unreachable — inline stdout drain blocked before `waitFor`, so a hung run pinned the request thread forever | B-001 |
| 4 AI clients | `new RestTemplate()` → infinite connect/read timeouts; a stalled provider could never be retried or failed over | B-002 |
| `QaWorkflowService` | Test suite generated **twice** per run, and the specs returned to the client were not the specs that ran | B-003 |
| `QaWorkflowService` | **`--instruction` reached only page analysis** — the planner and test generator never saw it, so generated suites came back generic | B-003 |
| `CodeGenerationService` | Page objects never sent to the client → delivered specs could not compile | B-004 |
| `WorkspaceManager` | `extractClassName(null)` NPE — a test without page-object code failed the *entire* write | B-005 |
| `WorkspaceManager` | Page-object splitter counted braces inside string literals → class sheared in half, uncompilable output | B-008 |
| `cli/qalab` | Raw JSON dump to the terminal; **exit 0 on a fully failed QA run** (could never fail CI) | B-006 |
| `WebConfig` | CORS pinned to `localhost:3000` → every API call blocked on any remote deployment | B-009 |
| `use-agent-websocket.ts` | Hardcoded `ws://localhost:8080`, no reconnect, failure swallowed → live progress silently dead remotely | B-010 |
| `docker-compose.yml` | No artifacts volume → every screenshot/trace/report lost on restart | B-011 |

Nine of eleven tasks turned out to be hiding a real defect, not just the stated improvement.

### Honest notes
- **Two of my own mistakes were caught by the tests I wrote**, and are recorded in the task entries rather than quietly fixed: the B-005 NPE and the B-008 malformed fixture. In both cases the diagnosis is written up so the reasoning is auditable.
- **B-008's "bug" was partly mine.** The brace-splitting bug was real and pre-existing, but the first failing assertion was a typo in my own test fixture. Both are documented separately.
- **Scope held.** No test runner was added to the frontend (B-010 verification used a standalone script) because that is B-038 in Sprint 4; adding Vitest inside a P0 task would have been drive-by scope.

### Exit criteria
- [x] `qalab test` on a clean workspace: readable summary, `test-plan.md` written, compilable specs **and** page objects, artifacts persisted
- [x] No code path can hang indefinitely (Playwright bounded, AI HTTP bounded)
- [x] One test generation per run; `--instruction` reaches planner and generator
- [x] Frontend can talk to a non-localhost backend (CORS + WebSocket both configurable)
- [x] `mvn verify` green — 172 tests, 0 failures

### Not addressed here (deliberately)
- **Auth / tenancy (B-013)** and **migrations (B-014)** are Sprint 1. Until they land, the deployment should be treated as single-user and not exposed publicly.
- **Page-analysis caching** remains unscheduled. It is the single largest latency win but trades correctness for speed, and that trade has not been chosen. Revisit once B-026 provides real cache-hit and analysis-duration numbers.
- **Per-test structured results (B-022)** are still absent — the CLI summary parses Playwright's text output and caps the list at 8. The HTML report in Sprint 3 is blocked on B-022.

---

---

# Sprint 1 — Deployable

*Goal: safe to expose beyond a single trusted user, and able to start from an empty database.*

**Started:** 2026-09-26 13:49 CEST
**Baseline commit:** `b1f03eb` (end of Sprint 0)

## Task log

### B-018 · Move Playwright install off the startup path
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 8m |
| **Commit** | `218450d` (1 of 10) |

**What changed**
- `PlaywrightSetupConfig` converted from a `CommandLineRunner` (which runs *before* the app can serve traffic) to an `@EventListener(ApplicationReadyEvent)` that hands off to a daemon executor and returns immediately.
- Each workspace is now **isolated**: a failure is logged and the scan continues, instead of propagating out of the runner and aborting boot.
- Per-workspace duration logging plus start/finish totals.
- The scan is a static method returning a count, so it is testable without starting a Spring context.
- Removed a vestigial constant + unused private method I had added for a per-workspace timeout that `prepareWorkspace` already enforces internally — dead code, not left in.
- `QALAB_AUTO_INSTALL_PLAYWRIGHT` documented in `.env.example`, recommending `false` when browsers are baked into the image.

**Why it mattered:** with N workspaces the app could be unavailable for up to 10×N minutes on boot, and on a throttled cloud volume one hung `npm` could eat the whole budget. A plausible contributor to the "500 while installing Playwright" symptom from the Oracle Cloud deployment.

**How it was tested**
- New `PlaywrightSetupConfigTest` — 6 cases: only `project-*` dirs are treated as workspaces; **a failing workspace does not block its siblings** (the regression guard); missing dir is a no-op; empty dir is a no-op; the hook is inert when disabled; `onApplicationReady` returns in <1 s even when the underlying install throws.
- Full suite: **178 tests, 0 failures** (172 + 6 new).

---

### B-019 · Serve the frontend API base URL at runtime
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 12m |
| **Commit** | `7e2c5ec` (2 of 10) |

**What changed**
- New route handler `src/app/config.js/route.ts` emits `window.__QALAB_CONFIG__` from `QALAB_API_BASE_URL` / `API_BASE_URL` **at request time**, with `Cache-Control: no-store` so a redeploy cannot keep serving the old URL. A route handler rather than a file written into `public/`, because the route is unambiguously dynamic and does not depend on how the server treats post-build additions to `public/`.
- `layout.tsx` loads it as a plain (non-deferred) script, so it runs before Next's deferred bundles and before any client module evaluates — the ordering that makes reading it at module scope in `lib/config.ts` safe. **The ordering requirement is documented at the definition site** so a future refactor to `defer` does not silently break it.
- Resolution order is now runtime config → build-time env → localhost, so dev and any path that skips the script still works.
- Dockerfile documents that the value is deliberately not baked in; compose sets `QALAB_API_BASE_URL` on the frontend and additionally wires `QALAB_CORS_ALLOWED_ORIGINS` on the backend, since **the two must agree or requests are blocked**.
- Both documented in `.env.example` as a required pair.

**How it was tested**
- Consulted the bundled Next 16 docs first, per the repo's `AGENTS.md` agent rules: route handlers are uncached by default, so the explicit `force-dynamic` is correct rather than redundant.
- Against a running `next start`: with the env set, `/config.js` returns it with `no-store` and `application/javascript`, and the served HTML contains `<script src="/config.js">`. Restarted with no env → `{"apiBaseUrl":null}`, degrading rather than breaking. Build output lists `ƒ /config.js` as dynamic.
- `npx tsc --noEmit` clean; `docker compose config` valid.

---

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
### B-020 · Sensible default artifact profile
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 11m |
| **Commit** | `6767e45` (3 of 10) |

**What changed**
- The generated workspace config used `screenshot: 'on', video: 'on', trace: 'on'`. Note `'on'` is **not** Playwright's default for screenshots (`only-on-failure` is) — it captures for passing tests too. A 20-test run produced 20 videos and 20 traces regardless of outcome.
- Defaults are now `screenshot: only-on-failure`, `video: off`, `trace: retain-on-failure` — evidence for what actually broke.
- Configurable per deployment via `qalab.playwright.screenshot|video|trace` (`QALAB_PLAYWRIGHT_*`), documented in `application.yml` and `.env.example`.
- Config extracted into `playwrightConfig()` carrying a comment that it is written **only when absent**, so the "never clobber a user config" contract is visible at the definition site, not only in the caller.
- Removed a duplicate `Initialized project structure` log line left behind by the edit; the surviving line now records the effective profile.

**How it was tested** — 8 cases in `WorkspaceManagerPlaywrightConfigTest`: failures-only defaults; the old all-tests profile **explicitly asserted absent** (the regression guard); profile overridable; rendered config structurally valid with all `%s` placeholders substituted and braces balanced; an existing user config survives `prepareWorkspace` untouched; a missing one is generated with the configured profile; the file advertises that editing is safe; directory layout created. Full suite **186 green**.

**Process feedback worth keeping:** the first version of this test took **151 seconds** because `prepareWorkspace` legitimately shelled out to `npm install`. The tests now satisfy its dependency checks up front and the class runs in **1.7 s**. A test that reaches the network is a CI liability, not merely a slow test.

---
### B-021 · Bounded Playwright concurrency
| | |
|---|---|
| **Status** | **DONE** (+1 self-inflicted bug caught) |
| **Date** | 2026-09-26 |
| **Duration** | 14m |
| **Commit** | `4a0e11a` (4 of 10) |

**What changed**
- `workers` was unset, so Playwright spawned ~half the cores as browser contexts. Each worker is a browser context, so on a small VM the suite thrashes memory and times out rather than finishing — a real cause of the "everything fails" cloud runs. Now defaults to `cores - 1`, overridable via `QALAB_PLAYWRIGHT_WORKERS`.
- `Math.max(1, cores - 1)` matters: on a 1-core host a naive `cores - 1` yields **0**, and Playwright then refuses to run at all. Pinned by a test.
- `retries: 0`, deliberately **not** configurable — a retried failure is a flake, and silently re-running it hides the signal the run exists to produce.
- `timeout` and `expect.timeout` explicit rather than relying on library defaults, and configurable.
- The effective worker count is returned in the execution result as `effectiveWorkers`, so *"how many workers was this actually running with?"* is answerable from `report.json` — the first question when a suite times out or flakes, and previously unanswerable.

**Bug I introduced and the suite caught**
Enriching the result with `putIfAbsent` threw `UnsupportedOperationException`: `toMap()` can return an **immutable** `Map.of(...)` from the early-return paths (no tests of a type, runner error). Two pre-existing tests failed. Fixed by copying into a `LinkedHashMap` first, and pinned with two regression tests — one asserting the immutable-map path still reports `effectiveWorkers`, one asserting a runner-supplied value is not overwritten by our computed default.

**How it was tested**
- 8 new cases: worker default, never zero/negative, explicit override, single-core host, retries disabled, timeouts explicit, timeouts configurable, all `%s`/`%d` placeholders substituted.
- **Stronger than unit tests:** the generated config was rendered and fed to **Playwright's real config loader** (`npx playwright test --list`), which accepted every key and enumerated 11 tests. That validates `only-on-failure` / `off` / `retain-on-failure` / `workers` / `retries` / `expect.timeout` against Playwright itself rather than only asserting the emitted strings look plausible. Probe files removed afterwards.
- Full suite: **196 tests, 0 failures** (186 + 10).

---
### B-015 · Ship a real `qalab.ai` config block
| | |
|---|---|
| **Status** | **DONE** (+1 doc correction) |
| **Date** | 2026-09-26 |
| **Duration** | 16m |
| **Commit** | `ec70594`, docs `3915e57` (5 of 10) |

**What changed**
- There was **no `qalab.ai` block in any configuration file**, which broke two things:
  1. `providers` was empty → `resolveModel()` returned `null` → every BYOK provider got `{"model": null}`, which OpenAI/Gemini/Ollama/Anthropic all reject with an opaque 400. The managed path masked it because `OpenCodeAiProvider` reads its own `@Value` models, so the bug was invisible until you tried BYOK.
  2. `freeMonthlyTokenLimit` fell back to the code default of **4000 tokens/month** — below a single `qalab test` run (6–7 prompts containing full page HTML). Compose had been working around it with an explicit `0`.
- New `qalab.ai` block: default provider + credential mode, token limit, retry/backoff, and per-provider base URL + model for all six provider types, each overridable by env var.
- `resolveModel()` now **throws a configuration error naming the missing key** instead of returning null — a misconfiguration says what is wrong rather than surfacing as a provider 400.
- Added `aiqalab` and `opencode` provider entries: AIQALAB is served by `OpenCodeManagedProviderClient`, which runs its own cascade and reads its own model settings; the entry exists so a model always resolves.

**Typo caught before it shipped:** the enum `AIQALAB` lowercases to `aiqalab`; I wrote `aiqqlab`. The new "every provider type resolves a model" test failed on exactly that.

**How it was tested**
- New `AiGatewayConfigurationTest` — 8 cases: every provider type resolves a model; every OpenAI-style provider resolves a base URL; the free allowance is usable for one run; managed is the default; provider identifiers are distinct. Two tests drive the **public `complete()` path** with a stub client — a configured model reaches the provider, and a missing model raises a configuration error naming the provider **without attempting any provider call**.
- One pre-existing suite had to be adjusted: `AiGatewayBudgetEnforcementTest` built a bare `new AiGatewayProperties()` and was **implicitly relying on `resolveModel` returning null**. It exercises budget enforcement, not model resolution, so it now configures a model, with a comment saying why.
- Full suite: **204 tests, 0 failures** (196 + 8).

**Process note — a failed doc edit nearly shipped as a lie.** The `USER-MANUAL.md` update for this task asserted its search string matched before writing; the pattern was missing a line the file actually contained, so the script exited without writing. The shell then continued and the commit went through with the documentation untouched — leaving the manual asserting, falsely, that the config block was absent and the allowance was 4000. Caught on the next inspection and fixed in `3915e57`. The assertion guard is right; the lesson is that its failure must not pass unnoticed just because the surrounding commit succeeded.

---
### B-012 · Spike: authentication & tenancy ADR
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 10m |
| **Commit** | `510ac28` (6 of 10) |

**What changed**
`docs/adr/0001-authentication-and-tenancy.md` — recorded before implementing, as the backlog instructs.

Context is the verified absence of any auth, made worse by three structural facts: `AccountService.defaultAccount()` returns a single implicit global account (so usage attribution and budget enforcement are meaningless with >1 user); `CredentialStore` is global (so BYOK keys have no owner); and the rate limiter is a no-op (so anonymous callers can drive unbounded paid traffic). The service is already deployed.

**Decision:** layered static API keys now, pluggable identity later. Per-account keys stored as salted hashes, raw value returned once and never again — mirroring the existing write-only `CredentialStore` convention. Account-scoped authorization that no longer trusts a client-supplied `databaseId`, because authentication alone still leaves horizontal privilege escalation by guessing an id.

**Rejected, with reasons:** JWT — unacceptable friction for a self-hosted local tool with no identity provider. Full multi-tenant identity — not achievable in a one-engineer-week sprint, and dangerous to half-build.

**The "negative consequences" section is explicit** that this is a breaking change, and explains the mitigation: a key is enforced only when `require-api-key=true` or one exists, so a local `spring-boot:run` keeps working while the cloud hole closes. The accepted residual risk — an operator who never creates a key stays unprotected — is stated, with a loud startup warning as mitigation.

Also lists five follow-ups (per-user credential ownership, audit log for privileged mutations, key expiry, removing `defaultAccount()`, optional OIDC) and implementation notes for B-013, including that a 401 must keep the `{"error":{...}}` shape or the CLI's error handling degrades.

**How it was tested** — a decision document; verified by review against the code (the three structural claims were each confirmed by reading the relevant classes) and by B-013 landing as described.

---

### B-013 · Auth filter on the API
| | |
|---|---|
| **Status** | **DONE** (+3 bugs found, incl. one that prevented startup) |
| **Date** | 2026-09-26 |
| **Duration** | 34m |
| **Commit** | `862ef98` (7 of 10) |

**What changed**
- Per-account API keys stored as **salted SHA-256**; raw value returned once at creation and unrecoverable; constant-time comparison.
- `ApiKeyAuthenticationFilter` resolves the key to an account and attaches an `AuthPrincipal`. Rejections use the existing `{"error":{...}}` envelope because both clients parse it.
- `SecurityConfig` deny-by-default: no form login, no generated password, no sessions, no CSRF token.
- Conditional enforcement: a key is required as soon as one exists; `QALAB_REQUIRE_API_KEY=true` forces it, and `ApiKeyBootstrapConfig` then prints the first key once — otherwise a fresh install with enforcement on would be unusable.
- Public `/api/v1/account/bootstrap` probe so a client can discover a key is required.
- CLI sends the key on every call via a contained `api_curl` wrapper, and fails fast with instructions when the Core demands one it lacks.

**Three bugs found while implementing — all by the new tests**
1. **The application would not start at all.** Tomcat builds the filter chain *before* the JPA `EntityManagerFactory` exists, so a filter injecting a repository failed the entire context. A class-level `@Lazy` did **not** reliably defer instantiation; holding dependencies as `ObjectProvider` does, because nothing resolves at construction. Documented at the field, since the reason is invisible otherwise.
2. **The filter ignored its own public allowlist**, so `/account/bootstrap` answered 401 — defeating its only purpose. `shouldNotFilter` now bypasses it, and `SecurityConfig` derives its `permitAll` list from the same constant so the chain and the filter — separate mechanisms that **had already drifted** — cannot diverge again.
3. A pointless `securityObjectMapper` bean I added created a circular reference and broke startup. Removed.

**How it was tested** — **32 new tests.**
- `ApiKeyAuthenticationFilterTest` (10): valid key resolves; missing/unknown/malformed/whitespace headers rejected; rejection uses the standard envelope *and says how to authenticate*; a fresh install with no keys stays usable; requiring a key blocks even a fresh install; a key whose account has vanished is rejected rather than trusted; the list view exposes neither hash nor salt.
- `ApiKeyServiceTest` (14): key is prefixed and 256-bit; raw key never persisted; correct keys resolve, wrong ones do not; **salts differ per key so equal keys do not hash equally**; revocation works and is **scoped to the owning account**; revoking twice is a no-op; constant-time comparison handles nulls and differing lengths.
- `ApiKeyHttpIntegrationTest` (8): **boots the real application with enforcement on and exercises actual HTTP.** A security control verified only at unit level can pass while the deployed app serves everyone. Covers the public probe, 401 without a key, 401 with a bogus key, 200 with a valid key, issuing a key over HTTP then using it, the list never returning the secret, and a revoked key ceasing to authenticate.

**Two failures were my own test bugs**, fixed rather than worked around: `/api/v1/projects` does not exist (projects are on the legacy `/api/projects`), and the key list returns a JSON array, not an object.

Full suite: **236 tests, 0 failures** (204 + 32).

---
### B-016 · Real rate limiter
| | |
|---|---|
| **Status** | **DONE** |
| **Date** | 2026-09-26 |
| **Duration** | 22m |
| **Commit** | `e27e127` (8 of 10) |

**What changed**
- `TokenBucketRateLimiter` is now the active bean: a bucket **per provider and per account**, each holding `burst` tokens refilling at `rps`. An empty bucket returns `AI_RATE_LIMITED` before any provider request.
- `RateLimiter` gains an account-scoped overload. The account dimension only became *possible* in B-013 — that is what gave the limiter a tenant key. Its default implementation delegates to the provider-scoped check so a provider-only implementation stays correct.
- `AiGateway` calls the account-scoped check, and the `AI_RATE_LIMITED` message now **names the knobs to turn** rather than just refusing.
- `NoopRateLimiter` keeps its `@Component` **removed** rather than being deleted: the interface contract promises a swappable implementation, and two limiter beans would make injection ambiguous — picking the wrong one silently disables the protection. Reasoning recorded in its javadoc.
- Bursts are deliberate: a workflow fires several LLM calls in quick succession, so a strict rps cap would reject legitimate work, while a leaky bucket alone would not bound cost.
- Off by default so local behaviour is unchanged; five new configuration variables.

**Known limitation, stated not hidden:** state is in memory and per process. Correct for this single-node deployment; a cluster would need a shared store. Recorded in the class javadoc *and* the manual.

**How it was tested** — 16 new.
- `TokenBucketRateLimiterTest` (15): allows up to the burst then rejects; one account cannot drain another's bucket; providers are limited independently; whichever bucket is tighter is the one that binds; a disabled limiter allows everything and reports `-1` availability; a null account falls back to the provider bucket; a null provider is allowed rather than throwing; availability drops after a call; the bucket refills over time; refill is capped at capacity; a zero rate denies after the burst and does not silently recover; capacity/rate coerced to sane values; reset clears state; the noop limiter still allows everything; a provider-only implementation stays correct through the account-scoped call.
- One case added to `AiGatewayBudgetEnforcementTest`: the gateway returns `AI_RATE_LIMITED` with an actionable message and **never reaches the provider**.

**Two failures worth recording — both instructive**
1. **Three tests failed because my fixture was wrong, not the code.** I gave the provider burst 3 and the account burst 2, then asserted three calls would succeed. The account bucket was *correctly* binding first. The tests now use two limiters — one where each bucket is deliberately the tighter one — so each dimension is genuinely exercised instead of accidentally shadowed. Worth resisting the urge to "fix" the limiter here.
2. **`AiGatewayBudgetEnforcementTest` had a latent landmine.** It mocked `RateLimiter` and stubbed only `allow(provider)`. The gateway now calls the account-scoped overload, and **Mockito does not delegate an unstubbed default method to the real implementation** — it returns `false`. Every call in that suite would have been silently rate-limited. Both overloads are now stubbed with a comment, because this failure mode is invisible until it rejects real traffic in production.

Full suite: **252 tests, 0 failures** (236 + 16).

---
### B-014 · Database migrations with Flyway
| | |
|---|---|
| **Status** | **DONE** (+1 real schema defect, +1 footgun I introduced and caught) |
| **Date** | 2026-09-26 |
| **Duration** | 40m |
| **Commit** | `17a3c5f` (9 of 10) |

**What changed**
- Flyway 10.10 added (core + postgresql + hsqldb for tests).
- `V1__baseline.sql`: 19 tables, 3 foreign keys, 1 index — **generated from the JPA entities with the PostgreSQL dialect**, not hand-written. A hand-transcribed baseline is exactly the kind of file that is wrong in a way nobody notices.
- All three profiles use `ddl-auto: validate`. The application now **refuses to start** if migrations and entities have drifted.
- New CI job `flyway-verify` starts the real application against a real PostgreSQL with `validate`. This is the *only* place the two are compared: unit tests run on H2 with Flyway disabled, so without this job a migration/model mismatch would ship silently.
- README gained a "Database Schema" section: ownership rules, a local verification recipe, and why `update` is banned.

**A real defect found by generating the schema**
`locator_observation.element_identity_json` was `@Lob String`, which PostgreSQL maps to **`oid`** — a large-object *reference*, not text. That is the classic Hibernate/Postgres footgun: the column does not behave like text and depends on the server's large-object configuration. Every other free-text column here already declared `columnDefinition = "TEXT"`. Now fixed to match, with the reason in a comment so it is not "helpfully" reverted.

**Verification — proven, not assumed**
- Created an **empty PostgreSQL 18** database.
- Started the app against it: Flyway applied `V1 - baseline` in 92 ms → 19 tables + `flyway_schema_history`, recorded in the history table.
- Hibernate `validate` then passed with **zero** errors. That is a positive proof the migration and the entities agree — not merely that the SQL parsed.
- Confirmed `element_identity_json` is `text` in the migrated schema.

**A footgun I introduced and caught**
My first `application-test.yml` set `ddl-auto: create-drop` **without pinning the datasource**, so tests inherited the developer's Postgres URL from `application.yml` — `create-drop` would have **DROPPED THE REAL DATABASE**. It surfaced as eight unrelated-looking context failures.

Worth recording: the *symptom* (mysterious context failures) pointed nowhere near the *cause* (a destructive setting aimed at the wrong database). Reading the actual error rather than retrying is what found it. The profile now pins H2 in-memory explicitly and the reasoning is in the file so nobody removes the pin.

Full suite: **252 tests, 0 failures**, and tests are now genuinely isolated from any real database.

---
### B-017 · Async job model for the full-test workflow
| | |
|---|---|
| **Status** | **DONE** (+2 bugs, +1 regression, +1 UX gap) |
| **Date** | 2026-09-26 |
| **Duration** | 52m |
| **Commit** | `e7c4187` (10 of 10) |

**What changed**
- `POST /api/v1/workflows/full-test` returns **`202 Accepted`** with an operation id and `statusUrl`; the work runs on a bounded background pool instead of the request thread.
- New `GET /api/v1/workflows/{operationId}`: `200` + full response when terminal, `202` + stage while in flight, `404` unknown, `500` if the workflow threw.
- The pool is **deliberately bounded** — an unbounded queue accepts work the node can never finish, turning a slow instance into an OOM one. A saturated queue returns `429` with a retry hint. Sized by `qalab.workflow.workers`/`queue-capacity` (default 2/20; each worker holds a browser).
- Results retained 2 h so a slow or retried client can still collect them; expired entries evicted on read.
- CLI submits then polls, keeping live stage output on stderr, with a `QALAB_WAIT_SECONDS` cap (default 3600) that reports a timeout instead of hanging.

**Bug 1 — a clean install could not start. Would have hit production.**
The API-key bootstrap runner fired **before** the default-account runner, so `issue(null, ...)` found no account and threw, failing the whole context. B-013's test passed only because my local PostgreSQL already had an account from earlier runs; on a **genuinely fresh database with `QALAB_REQUIRE_API_KEY=true` the app would not boot**. Fixed three ways: `ApiKeyService` establishes its own owning account rather than depending on another bean having run; both runners carry explicit `@Order` so the dependency is documented rather than incidental; and a test now starts from an empty database.

**Bug 2 — invalid `jq` in the CLI, introduced in B-004 and never executed.**
`{files: (.a // []) + (.b // [])}` is a jq **syntax error** — an object value must be a single expression, so the `+` needs the whole value parenthesized. Every `qalab test` since B-004 silently wrote **no generated files** and printed a raw jq error. I had verified those expressions in isolation but never ran the actual command path. **Testing a string is not the same as exercising the code.** Fixed, and the path is now proven end to end.

**A UX gap the end-to-end run exposed.** A workflow that aborts before any step completes leaves only a `workflow` step carrying the reason, and the summary printed `QA RUN FAILED` with nothing else — the least useful possible output. The summary now surfaces that error.

**Verification beyond unit tests — the real server, a fresh database**
- First key auto-issued at startup (proves the fresh-install fix).
- CLI received `202` + `statusUrl`, polled to a terminal `FAILED`, printed the **actual reason**, wrote `report.json`, exited `1`.
- `write_files_from_json` driven directly with a specs + page-objects payload produced the correct `tests/` and `pages/` tree.

**A regression I introduced and caught in the same task:** rewriting `V1WorkflowController` dropped the `/{operationId}/progress` endpoint — the one the CLI polls for live progress. Restored, with a comment stating the async change is not a reason to break a contract a client may already depend on.

**Tests:** 9 new HTTP-level cases + 1 added to `ApiKeyServiceTest`. The workflow service is stubbed so the asynchrony contract is deterministic and fast; the workflow's own behaviour stays covered by `QaWorkflowServiceTest`. Three initial failures were test-design problems (loop stopping at exactly workers+queue so 429 was unreachable; asserting real-workflow completion inside 60 s; a progress assertion that cannot hold with a stub) — fixed in the tests rather than by weakening the assertions.

Full suite: **262 tests, 0 failures** (252 + 10).

---

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-018 | Playwright install off the startup path | P1 | M | **DONE** | `218450d` |
| B-019 | Frontend API base URL at runtime | P1 | S | **DONE** | `7e2c5ec` |
| B-020 | Sensible default artifact profile | P1 | S | **DONE** | `6767e45` |
| B-021 | Bounded Playwright concurrency | P1 | S | **DONE** | `4a0e11a` |
| B-015 | Real `qalab.ai` config block | P0 | M | **DONE** | `ec70594` |
| B-012 | Spike: auth & tenancy ADR | P0 | S | **DONE** | `510ac28` |
| B-013 | Auth filter on the API | P0 | L | **DONE** | `862ef98` |
| B-016 | Real rate limiter | P1 | M | **DONE** | `e27e127` |
| B-014 | Database migrations with Flyway | P0 | L | **DONE** | `17a3c5f` |
| B-017 | Async job model for full-test workflow | P1 | L | **DONE** | `e7c4187` |

**Totals:** 10/10 done · 10 commits · elapsed 219m

### Parallel work: user manual
Started 2026-09-26 alongside the sprint, at the user's request: a comprehensive
`USER-MANUAL.md` with technical implementation detail, to be kept current.

- Created `USER-MANUAL.md` (22 sections) and cross-linked it from `README.md`.
- Grounding rule applied: only verified behaviour is documented. The test count
  (178), the CLI summary shape and every v1 API path were each checked against the
  code before being written down.
- 16 known limitations are tabulated with tracking IDs rather than glossed over.
- Writing it surfaced a **new confirmed defect**: `docs/known-limitations/intent-drops-instruction.md`
  — `POST /api/v1/intent/run` uses the prompt only for intent *detection* and never
  forwards it to the operation it triggers (every branch of
  `V1IntentController.dispatch` passes `null`, and `V1IntentRequest` has no
  instruction field). Same class of bug as B-003, which fixed it for `full-test`.
  Recorded rather than silently patched, as it is outside Sprint 1 scope.
- Maintenance rule adopted: the manual is updated **in the same commit** as any
  change to CLI flags, env vars, API contracts, artifacts or known limitations.

### Ordering note
The backlog lists B-012..B-021 in priority order, but B-012/B-013 (auth) and B-014
(migrations) are the two large architectural items and both benefit from landing on a
cleaned-up base. This sprint therefore runs the four small, independent reliability
items first (B-018..B-021), then the config correctness fix (B-015), then the
architectural work (B-012 → B-013 → B-016, B-014, B-017). All are Sprint 1 scope; only
the sequence differs.

## Sprint 1 outcome

**All 10 tasks delivered in 10 commits.** Tests grew **204 → 262** (+58), all green.

| Sprint | Tasks | Result |
|---|---|---|
| 0 — reliability + readable output | 11/11 | Playwright and AI timeouts bounded; one generation per run; page objects and test plan delivered; CLI summary with a CI exit code; artifacts persisted |
| 1 — deployability | 10/10 | Warm-up off the boot path; runtime frontend config; failures-only artifacts; bounded concurrency; `qalab.ai` block; auth ADR + API-key auth; rate limiter; Flyway; async workflow |

### The pattern worth naming
**Eight of the ten tasks were hiding a real defect, not just an improvement.** In
several cases the stated task was the smaller half of the work:

| Hidden defect | Task |
|---|---|
| `--instruction` reached only page analysis — the planner and generator never saw it | B-003 |
| Delivered specs imported a `pages/` file that was never written | B-004 |
| `extractClassName(null)` NPE failed the entire write | B-005 |
| Page-object splitter sheared classes in half on `getByTestId('a}b{c}')` | B-008 |
| A filter injecting a repository **prevented the app from starting at all** | B-013 |
| Rate limiting would have silently rejected every call in an existing test suite | B-016 |
| `@Lob String` mapped a column to Postgres `oid` instead of `text` | B-014 |
| A clean database could not start with `QALAB_REQUIRE_API_KEY=true` | B-017 |
| Invalid `jq` meant **no generated files were written at all** since B-004 | B-017 |

### Mistakes of mine, recorded rather than smoothed over
- **B-005** NPE and **B-008** malformed fixture — both found by tests I had just written.
- **B-013**: three real mistakes (startup-breaking circular bean, an allowlist the filter ignored, a pointless mapper bean) plus two test bugs.
- **B-014**: a test profile that would have **dropped the developer's real database**, found only because eight context failures looked inexplicable.
- **B-015**: a documentation edit failed its assertion, the shell continued, and the commit shipped a manual asserting something false. Caught on inspection.
- **B-017**: dropped the `/progress` endpoint the CLI depends on — caught in the same task.
- **B-020**: a test that took 151 s because it shelled out to `npm install`; now 1.7 s.

The recurring lesson is the same each time: **a failed assertion or an unexplained failure is information.** Every one of these was found by reading the actual error rather than retrying or working around it.

### Two process notes
1. **Testing a string is not the same as exercising the code.** The B-004 `jq` bug survived because I validated expressions in isolation and never ran the command. B-017 caught it only because the end-to-end run happened to include a jq error. Where a path can be driven cheaply against a real process, it should be.
2. **A test that reaches the network is a CI liability, not merely a slow test** (B-020, 151 s → 1.7 s).

### Exit criteria
- [x] Application serves traffic before any npm/browser install
- [x] Neither a Playwright run nor an AI call can hang indefinitely
- [x] One test generation per run; `--instruction` reaches planner and generator
- [x] Frontend can target a non-localhost backend (CORS + WebSocket + runtime API URL)
- [x] `/api/**` requires a bearer key; raw key returned once and never stored
- [x] AI calls rate-limited per provider and per account
- [x] Schema owned by Flyway, validated in every profile, drift caught in CI
- [x] Long workflows do not occupy request threads; saturated queues reject cleanly
- [x] `mvn verify` green — 262 tests, 0 failures

### What Sprint 1 deliberately did **not** do
- **No multi-tenancy.** `defaultAccount()` is still global, so usage is not per-tenant; `CredentialStore` is still global, so BYOK keys have no owner; `/ws/**` is unauthenticated. All recorded in ADR 0001's follow-ups and in the manual.
- **No circuit breaker** (B-037), **no distributed rate-limit state** (single-node only), **no per-test structured results** (B-022, still the blocker for the HTML report).
- **No analysis caching** between runs — still the largest available latency win, still an unchosen correctness/speed trade.

---


### B-029 · Self-contained HTML report per run
| | |
|---|---|
| **Status** | **DONE** (+2 evidence-destroying defects, +1 B-022 regression) |
| **Date** | 2026-09-26 |
| **Duration** | 149m |
| **Commit** | `a8d84fe` (1 of 5) |

**What changed**
- New `HtmlReportRenderer`: one `report.html` per execution, no CDN, no external
  stylesheet, no scripts. Screenshots embedded as base64 data URIs.
- Evidence is now filed **per test** under `tests/<ordinal>-<slug>/`, with paths recorded
  *relative* to the artifact directory so the report survives being moved or emailed.
- `steps.execution.htmlReport` carries the path, backed by a new `V3` column, and the CLI
  prints it first marked as the file to open.
- `report.json` and `report.md` unchanged — the JSON stays the machine contract.

**Videos and traces are linked, not embedded, on purpose.** They are routinely tens of
megabytes; base64 would inflate them by a third and produce a file no mail client will
open. Screenshots are what a reader actually looks at, so those are inlined, with a 4 MB
per-image cap beyond which they are linked too.

**Two defects in artifact collection, each of which destroyed evidence**
- **Every trace was copied to the same `trace.zip` with `REPLACE_EXISTING`.** A run with
  two failing tests kept one trace and silently discarded the other — and a trace is the
  single most useful artifact for reconstructing a failure. Nobody was told.
- Screenshots became `screenshot.png` / `screenshot-2.png` with **nothing recording which
  test each belonged to**, so a report could say three tests failed and show one
  screenshot. The acceptance criterion was literally unmeetable before this was fixed.

**The significant find: B-022 never worked in the product.**
`ExecutorAgent` copies a hand-picked list of keys out of the runner's result map, and
`summary` was not on the list. So `steps.execution.results` was **never present** in a
workflow response, and **nothing was ever written to `test_case_result`**. B-022's unit
tests were green, the parser was green, and the tool was green — because I verified the
tool directly and the agent with a stub, and never ran the two together. That is exactly
the mistake the B-017 log entry warned about, and I made it anyway.

Now covered by a test that puts a **real Playwright run through the real tool and the
real agent** and asserts the failing test arrives with a screenshot that exists on disk.
It skips itself when browsers are absent rather than failing a build that has none — and
it checks the Linux *and* macOS browser paths, because the first version checked only
Linux and skipped silently on the machine that would have run it.

**Two things caught by inspecting real output rather than by a passing test**
- The first renderer emitted each screenshot's base64 **twice**, once in the anchor `href`
  and once in the `img src`. With 200 KB screenshots that doubles the report for nothing.
  Only visible by rendering a real report and counting the payloads: 4 for 2 screenshots.
- `traceCount` counted how many times the legacy single-value field was assigned, not how
  many traces were collected, so a run with two traces reported one. Found because a
  compile error meant a test had silently run against a **stale class** — which is its own
  lesson: a green test against stale bytecode is not a green test.

**Judgement calls, recorded in code**
- A failed HTML render must not lose `report.json`, so the HTML is written after the JSON
  and its failure is caught separately.
- A run with no per-test results says so in the report. Rendering an empty green table
  would read as a passing run, which is the one thing a QA report must never do.
- Test titles are model-generated text, so everything is HTML-escaped including quotes —
  a title containing `"` would otherwise break out of an attribute. Tested with a hostile
  title.

**How it was tested**
- 25 renderer tests: self-containment (no `http`, no `<script>`, no `<link>`), every count,
  per-test rows, evidence embedding and linking, degradation when files are missing or
  oversized, markup injection, and duration formatting at every scale.
- 11 artifact tests against **real files on disk**: two traces both surviving, each
  screenshot staying with its own test, relative paths, the legacy single fields still
  absolute, and no flattened duplicates.
- **A real Playwright run rendered to a real report directory and inspected**: no external
  references, 2 screenshots embedded once each, both traces linked, all four tests listed
  with statuses and durations, test plan present.
- Migrations V1→V3 on an empty PostgreSQL, `ddl-auto=validate` passing, column verified.
- Full suite **408 green** (was 371). `bash -n cli/qalab` clean.

**B-031 partly done:** the CLI now prints the HTML path first. The UI surface is not
touched yet and remains open.

**Left alone deliberately:** `error-context` attachments (a text page snapshot) are still
not surfaced. They would be a genuine improvement to the failure view, but they are a new
feature rather than part of "a report you can read", so they wait rather than being
quietly bundled in.


### B-031 · Surface reports in CLI and UI
| | |
|---|---|
| **Status** | **DONE** — CLI half shipped in B-029, UI half here |
| **Date** | 2026-09-26 |
| **Duration** | 83m |
| **Commit** | `a8d84fe` (CLI), `dfa5b0f` (UI) |

**What changed**
- New `GET /api/executions/{id}/results`: counts, per-test rows, and the report paths.
  The UI fetches it when a row is expanded.
- The execution panel lists failures first, with each test's duration, retry count and
  first assertion line; the rest of the error is behind a disclosure.
- The CLI half shipped with B-029 — it prints the HTML path first, marked as the file to
  open. This task completed the UI half.

**A separate endpoint, deliberately.** The dashboard lists every run; inlining every test
of every run into `/history` would make the page unloadable on a project with a few
hundred executions. Fetched on expand instead.

**Two things that would have read as a passing run, and now do not**
- A run with no recorded results says so in words. A zeroed counter row reads as
  "everything passed", which is the one conclusion a QA view must never reach without
  evidence.
- An unknown execution returns **404**, not 200 with empty counts, which would be
  indistinguishable from a clean run.

**The total is the number of tests, not the sum of the three buckets.** A status this
code does not recognise — a new Playwright state, say — must still appear in the total
rather than silently vanishing. My first version summed the buckets; the test for an
unknown status caught it.

**Retries are shown separately from status, on purpose.** A test that failed once and
then passed is not a failure, and showing it as one would be a lie about the suite.

**The report is shown as a path, not a link, and this is a question for the user rather
than a decision I made.** The file is on the server's filesystem and nothing serves the
artifact directory to a browser. An endpoint streaming files out of it also exposes every
run's screenshots, videos and traces to anyone who can reach it, so it needs an access
rule of its own rather than being added alongside a UI link. Documented in §9.4 with the
three models to choose from — per-account ownership, a shared read-only token, or an
admin-only route.

**How it was tested**
- 12 tests for the endpoint: counts derived from rows rather than the execution verdict,
  run order, error text, evidence flag, retries, a malformed attachment cell ignored
  rather than fatal, 404 for an unknown execution, null duration tolerated, null ordinal
  falls back to position.
- **Exercised live** against a running server with three real rows inserted: the expected
  counts and per-test detail came back. The 404 and empty-results paths are unit-tested
  rather than live — the API key could not be re-issued against the running instance
  because issuance refuses once a key exists, which is correct behaviour but stopped the
  live check.
- Frontend typechecks and builds. Removed a stray `}` in the dashboard's JSX that had been
  in the original and is only now being parsed as JSX.


### B-032 · Bug reports grounded in real failures
| | |
|---|---|
| **Status** | **DONE** (+2 bugs of my own, +2 tests asserting the old behaviour) |
| **Date** | 2026-09-26 |
| **Duration** | 165m |
| **Commit** | `88e7295` (3 of 5) |

**What changed**
- `FailureSignature` groups a run's failures by a fingerprint of the **normalised** spec
  file plus the **normalised** assertion.
- One report per distinct failure, built from that test's own error, stack and
  screenshot. Title and expected/actual all name the assertion. `"Unknown."` is gone.
- A known bug is counted, not re-filed: `occurrences` increments and the report is
  re-pointed at the run that hit it, **without spending an AI call**.
- `V4__bug_report_dedup.sql`: `dedup_key`, `occurrences`, `screenshot_path`,
  `first_seen_run`, plus a `(project_id, dedup_key)` index.
- The HTML report is re-rendered once the bug reports exist.

**The fingerprint is the whole feature, and normalisation is the load-bearing part.**
The same assertion failing twice usually differs in a line number, a duration, a
Playwright timestamp or an absolute path. An un-normalised key would report the same bug
as new every time — precisely what the key exists to prevent. So the test **name is
deliberately excluded** (renaming a test must not re-file a tracked bug) while the
assertion text is preserved (two failures in one spec stay two bugs).

**The first version of the patterns had a silent bug.** Removing `at line 88` left the
word `at` behind, so `"…failed at"` and `"…failed"` hashed differently. Nothing threw;
the dedup simply never fired. Found by a test asserting the collapse, fixed by making
each pattern swallow its connective and adding a trailing-connective pass as a net.

**Two bugs I introduced in this task, both caught by tests**
- The rewrite silently **dropped the user instruction**, which the backlog explicitly
  requires. The test that caught it existed only because it asserted the *instruction*
  rather than the report's existence — an argument for asserting content, not objects.
  My first fix used a `ThreadLocal`, the same hidden-state smell I removed in B-027, so
  it became a parameter.
- The workflow called `reports.get(0)` **unguarded**, so a run with no attributable
  failure was an `IndexOutOfBounds` that failed the whole report step.

**Two existing tests failed because they asserted the bug.** Both expected
`"Test failed: Login with valid credentials"` — the generic title that *was* the user's
complaint. The behaviour changed deliberately, so the tests were correct to change, and
they now assert the title names the assertion.

**How it was tested**
- 18 signature tests attacking the volatile parts specifically, plus the
  distinguishing parts that must survive.
- 15 service tests: three failures → three reports, identical → one with a count, known
  bug reused rather than replaced, no AI call for a known bug, title names the assertion,
  expected/actual never "Unknown", screenshot attached, passing tests never reported,
  instruction on every report, no-project runs never deduped, and **one AI call per
  distinct failure** — not per test, not per run.
- Migrations V1→V4 on an empty PostgreSQL, `ddl-auto=validate` passing, four columns and
  the index verified present.
- Full suite **459 green** from clean.

**Deliberately not done:** the failure classification is left unset on these reports. A
per-test row carries no locator or action, and inferring one from a stack frame would be
a guess dressed as a finding. The deterministic fallback still states the classification
it has.

---

# Sprint 2 — Reliable and measurable

*Goal: when the user says "it was slow" or "it was wrong", we can answer from data instead of log-scraping.*

**Started:** 2026-09-26
**Baseline commit:** `f8f308e` (end of Sprint 1)

## Task log

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-022 | Playwright JSON reporter → per-test results | P0 | M | **DONE** | `c225c5c` |
| B-024 | Cap the provider cascade | P1 | S | **DONE** | `e55a1b1` |
| B-025 | One shared, tolerant LLM JSON extractor | P1 | M | **DONE** | `56f62d9` |
| B-027 | Fix `AnalysisCache` semantics | P1 | M | **DONE** | `0051410` |
| B-028 | Reuse the browser instead of relaunching per call | P1 | M | **DONE** | `cd0e67f` |
| B-023 | Consolidate the provider clients | P1 | M | **DONE** | `30bb215` |
| B-026 | Structured logging + LLM metrics | P1 | M | **DONE** | `27df82a` |

**Totals:** 4/7 done · 4 commits · elapsed 335m

---

# Sprint 3 — Reporting product

*Goal: the artifact a user actually reads.*

**Started:** 2026-09-26
**Baseline commit:** `00db3fa` (end of Sprint 2)

## Task log

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-029 | Self-contained HTML report per run | P1 | M | **DONE** | `a8d84fe` |
| B-031 | Surface reports in CLI and UI | P1 | M | **DONE** (UI half) | `dfa5b0f` |
| B-032 | Bug reports grounded in real failures | P1 | M | **DONE** | `88e7295` |
| B-033 | Retire the legacy `/api/*` surface | P2 | M | TODO | — |
| B-030 | Allure integration (optional) | P2 | M | TODO | — |

**Totals:** 3/5 done · 3 commits · elapsed 397m



### B-022 · Playwright JSON reporter → structured per-test results
| | |
|---|---|
| **Status** | **DONE** (+2 bugs found by running it for real) |
| **Date** | 2026-09-26 |
| **Duration** | 71m |
| **Commit** | `c225c5c` (1 of 7) |

**What changed**
- `PlaywrightResultParser` turns Playwright's JSON reporter output into per-test
  records: file, title, status, duration, retry count, error message + snippet and
  attachment paths, plus aggregate counts.
- `QaWorkflowService` returns those structurally as `passedCount` / `failedCount` /
  `skippedCount` / `flakyCount` and a `tests` array on the execution step, and keeps
  the **last 50 lines** of Playwright output for diagnostics. The 2000-char *head* it
  used to send is where the generated test source lives, so the failures were cut off
  exactly when they mattered.
- Persisted per test in a new `test_case_result` table (`V2__test_case_results.sql`),
  one row per test against its `TestExecution`, so the detail outlives the request.
- CLI prints real failure titles and first assertion messages from the structured
  results instead of grepping the console text, with a fallback when a run has none.

**The backlog's suggested mechanism does not work, and the replacement has a trap.**
`backlog.md` says to run with `--reporter=json:<file>`. On the targeted Playwright
(1.48) that is a **syntax error** — the value is treated as a module name:
`Cannot find module 'json:/tmp/pw.json'`. The reporter has to be *configured*. The
obvious place is the user's `playwright.config.ts`, which B-020 established must never
be modified. So the run gets a generated companion config that imports the user's,
spreads it and appends one reporter, selected with `--config` and deleted afterwards.

**Bug 1 — the companion config would have broken most workspaces.** I first wrote it
to `<workspace>/.qalab/`. Only running Playwright for real showed why that is wrong:
Node resolves `node_modules` by walking up from the importing file, and Playwright
defaults `testDir` to **the config file's own directory**. A subdirectory therefore
breaks module resolution and silently turns `testDir` into `.qalab/` — "no tests
found" for any workspace that does not set `testDir` explicitly. It now sits at the
workspace root, beside the user's config, where both behave as they already do.

**Bug 2 — a relative import that was not relative.** `Path.relativize` returns
`playwright.config.ts` for a sibling, without a `./` prefix. Node reads that as a
**bare module specifier** and looks in `node_modules`, so the companion could not load
the user's config at all. The unit test asserted only that an import existed, so it
passed; the real run is what caught it. Asserted on the exact specifier now.

**Two design decisions worth recording**
- **Counts come from the parsed detail, not the reporter's `stats` header.** If the two
  disagree, the per-test records win: a stale or partial header must not be able to
  report a green run.
- **The final attempt decides a test's outcome**, with earlier attempts counted as
  retries. A test that failed then passed is `passed`, `retries: 1`, and counted in
  `flakyCount` — not reported as a failure.
- A missing or unparseable report yields an empty summary and a warning, never an
  exception: the caller still has the exit code and the text output, so a reporting
  problem must not turn into a lost run.

**How it was tested**
- 20 new unit tests. The parser is tested against a fixture whose shape was **captured
  from a real `playwright --reporter=json` run**, not invented — including the ANSI
  colour codes Playwright embeds in error messages, which have to be stripped.
- Companion-config tests assert the user's `playwright.config.ts` is byte-identical
  after the run, and that `--config` reaches the process argv.
- **Real end-to-end run through the tool**, not a mock: 4 specs against a live browser
  → `passedCount=2`, `failedCount=2`, and each failure's screenshot, video and trace
  attributed to the correct test, with the companion config removed afterwards. This is
  what found both bugs above.
- **Migration against a real PostgreSQL**: started the app on an empty database, Flyway
  applied `V1 - baseline` then `V2 - test case results`, and `ddl-auto=validate` passed
  — the same check the `flyway-verify` CI job runs. The `test_case_result` table, its
  index and its FK verified present.
- V2's DDL is Hibernate's own generated output for the entity, not hand-transcribed —
  same procedure as V1, so it cannot silently disagree with the model.
- Full suite **282 green** (was 262). `bash -n cli/qalab` clean; every `jq` expression
  in the new CLI summary exercised against a fixture, including the no-results
  fallback and the all-skipped case.

**Known limitations left in place**
- Absolute artifact paths in `screenshots` / `videos` / `traces` match the existing
  behaviour of `steps.execution.workspace`. Making them workspace-relative belongs with
  the report that consumes them (B-029).
- A test that never opened a page produces no screenshot even with `screenshot: 'on'`
  — that is Playwright's behaviour, not a parsing gap.
- `error-context` attachments (a text page snapshot) are not surfaced; only screenshot,
  video and trace are classified. Revisit with B-029 if the HTML report wants them.

---

### B-024 · Cap the provider cascade
| | |
|---|---|
| **Status** | **DONE** (+1 hidden multiplier, +1 config trap) |
| **Date** | 2026-09-26 |
| **Duration** | 97m |
| **Commit** | `e55a1b1` (2 of 7) |

**What changed**
- The cascade is now a **flat loop over the candidates under one shared budget**, not a
  retry loop wrapped around a cascade. The cascade *is* the retry mechanism, so a
  transient failure on one provider is answered by moving to the next.
- `OPENCODE_MAX_PROVIDER_CALLS` (default 6) is a hard ceiling across the whole
  operation. `OPENCODE_MAX_ATTEMPTS_PER_PROVIDER` (default 2) abandons a provider that
  keeps failing, so one broken provider cannot consume the whole budget.
- **An exhausted cascade is no longer retried by the gateway.** Every configured
  provider has been tried within its budget by then.
- Usage reports the **sum across every attempt**, and `ProviderCallResult` carries the
  attempt count.
- Each `AiOperation` declares its own output ceiling (1 500 – 8 000) instead of one
  global 12 000.
- Per request: **Space Bunny Free** is the primary Zen model ahead of Big Pickle; the
  cap default moved to 6 so the whole configured cascade stays reachable.

**The stated worst case was 15 calls. The real one was 45.**
The backlog says `attemptProviders` runs inside "a 3-attempt loop: up to 15 upstream
LLM calls". True as far as it goes — but `AiGateway.executeWithRetry` sits *outside* it
and catches `Exception`, retrying the client call up to `max-retries: 2`. So a managed
cascade that had already spent its budget of 4 was re-run from scratch twice more:
**4 × 3 = 12 upstream calls**, each with a backoff sleep between gateway attempts.
Sizing the fix to the provider alone — the obvious reading of the task — would have
left 12 and produced a commit that claimed to have bounded the cascade.

Fixed by a typed `ProviderCascadeExhaustedException` that the gateway catches
separately from transport errors. The distinction is the whole point: a cascade that
exhausted its providers is a final answer, an ordinary connection reset is not. There is
a test for each, so dropping the retry cannot pass unnoticed.

**A data race I introduced and removed before committing.** The first version
accumulated attempt telemetry in fields on the `OpenCodeAiProvider` bean. It is a
singleton `@Component` shared by concurrent workflows, so per-request state there is a
race — two simultaneous runs would interleave their attempt counts into each other's
usage records. Attempts are now reported through an `AttemptObserver` the caller passes
in, so the state lives with the invocation that owns it.

**A config trap worth recording.** The cap has to be at least the number of configured
candidates, or the tail of the cascade is dead configuration — Gemini and Ollama would
never be tried in the worst case, which is exactly when you would want them. Adding
Space Bunny Free made the candidate list 5 long, so the default moved from 4 to 6:
enough for every candidate plus one retry. Asserted by a test that reads the labels and
requires all five to appear.

**Also: rejected responses were invisible to the budget.** A response rejected by a
validator was still billed, but `AiGateway` recorded only the tokens of the response
that finally succeeded, so a run that drained the allowance looked cheap. The gateway
also *overwrote* the client's reported tokens with a fresh estimate whenever
`estimated` was set — which would have quietly undone the fix from the inside. Both
corrected, with the overwrite now logged rather than silent.

**How it was tested**
- The bound is asserted **directly**, not inferred: an all-failing cascade issues
  exactly `max-provider-calls` requests, and a lone failing provider exactly
  `max-attempts-per-provider`. A fifth request fails the test, so the bound is the
  runner's doing and not a coincidence of the fixtures.
- **Latency measured, not asserted**: the failure path takes **~84 ms** with stubbed
  providers, against a **3 s floor** for the old version from its backoff sleeps alone.
  The regression test asserts a 1.5 s ceiling — 2× clear of the new behaviour and well
  clear of the old, without being flaky.
- Two tests pin the distinct outcomes: a transient client error **is** still retried,
  an exhausted cascade is not.
- The cascade order is asserted by label, including Space Bunny Free ahead of Big
  Pickle.
- Per-operation budgets are asserted per operation, plus a guard that **no** operation
  asks for the old 12 000.
- App boots clean against a fresh database; logs the new order
  (`Go(qwen3.7-plus) → Zen(space-bunny-free) → Zen(big-pickle) → …`).
- Full suite **306 green** (was 282).

**Deviation from the backlog's dependency:** B-024 lists B-023 (consolidate the provider
clients) as a prerequisite. Implemented on the legacy `OpenCodeAiProvider` instead —
the cap is independent of how many HTTP methods exist. B-023 remains worth doing for
its own sake, and this change does not make it harder.

**Left alone:** `mimo-v2.5-free` leaves the cascade at the user's request. B-023 still
owes the same consolidation to the BYOK path, which has no cascade at all.


### B-025 · One shared, tolerant LLM JSON extractor
| | |
|---|---|
| **Status** | **DONE** (+1 latent bug in the validators, +1 behaviour change) |
| **Date** | 2026-09-26 |
| **Duration** | 83m |
| **Commit** | `56f62d9` (3 of 7) |

**What changed**
- New `ai/provider/LlmJson` — one extractor, replacing nine private copies.
- All nine call sites rewired: 5 agents, `ExplorerService`, `BugReportService`,
  `HealingAiEvaluator` and `JsonValidators`.
- Net **−113 lines** of duplicated code.
- Handles fenced blocks (with/without a language tag, wrapped in prose, or never
  closed), prose on either side, trailing commas, a leading BOM, and brace-shaped
  prose. The scan tracks string literals, so a value containing `{"note": "a, b, c"}`
  is not cut short at the comma or the brace.

**The duplication was hiding a second bug.** `JsonValidators` had its own
fence-stripper, so the validator and the parser were separate implementations of the
same idea and had already drifted. Since the validators are what drive the cascade's
fallback decision, a response the validator accepted but the parser rejected would
**spend a fallback provider on a response that was never malformed**. That is the
acceptance criterion that actually mattered, and it is now covered by tests.

**One deliberate behaviour change, in the safer direction.** `HealingAiEvaluator`
substituted `"{}"` for a null response, which parsed to a neutral-looking
`confidence: 0.5` and let `safeToApply` be decided on nothing. A null response now
fails like any other unusable one: **confidence 0.0** with an explicit reason. Noted
in a comment at the call site so nobody "fixes" it back.

**What is deliberately still broken.** Single-quoted keys, unquoted keys and
truncated values are refused, not repaired. Guessing produces a plausible object the
model never wrote, which is worse than a clear failure — and truncated output has no
defensible completion at all. Each case has a test asserting refusal, so the decision
is visible rather than an accident.

One fixture I wrote was wrong in an instructive way: I first asserted that a
*truncated* value inside an unterminated fence should still yield the fields it
contained. It cannot — the value is unbalanced, so there is nothing complete to read.
The real unterminated-fence case is a missing *closing fence* with the JSON intact,
which is what a cut-off stream usually looks like. Split into two tests: one for the
recoverable case, one asserting the truncated value is refused.

**How it was tested**
- **35 corpus cases**, built from response shapes observed in the logs rather than
  invented — the headline case is the literal `Unrecognized token 'I'` failure.
- 6 validator tests, including that prose-wrapped JSON now passes validation and that
  genuine garbage still fails with a diagnosable reason.
- **Extraction is idempotent, and asserted.** A validator that ran the extractor twice
  must not see a different string from a parser that ran it once; without that property
  "shared extractor" is not actually guaranteed.
- A guard that every extracted value loads in Jackson — otherwise the tolerance just
  relocates the parse error somewhere less obvious.
- Full suite **347 green** (was 306). App boots clean against a fresh database.

**Acceptance criteria**
- `grep -rc "private String extractJson"` → **0** (the criterion said 1; the shared
  implementation is a static utility rather than a private method, so the count is
  zero and `LlmJson.extract` is the single entry point — verified by grep, not assumed).
- Corpus tests pass, including the captured real failure.
- Validator and parser share the extractor, with a test for the drift that caused.


### B-027 · Fix `AnalysisCache` semantics
| | |
|---|---|
| **Status** | **DONE** (+1 cross-request credential leak, +1 bug I introduced) |
| **Date** | 2026-09-26 |
| **Duration** | 84m |
| **Commit** | `0051410` (4 of 7) |

**What changed**
- **Credential caching removed entirely**, not bounded. Credentials are threaded through
  the call chain instead: the workflow already holds them in the request, so
  `generateTestsEntities` takes them as parameters.
- The three analysis caches are bounded and expiring: TTL (30 min), size bound (200),
  eldest-entry eviction, and expired entries removed on read.
- `CacheStats` exposes hits, misses, evictions, expirations, size and the configured
  policy, so "should this cache stay?" has a number attached.

**The backlog understated this by a long way.** It says credential caching is "a
liability with no benefit". Reading the code, it was an active **cross-request
credential leak**:

- `ExplorerService` wrote credentials to the cache only **after a successful login**.
- A later **anonymous** run against the same URL therefore never overwrote the entry.
- `CodeGenerationService` read the previous user's password straight back out — keyed by
  URL, not by request — and attached it to the new run's generated tests.

So two users of the same login page shared credentials, and the second user had no way
to know. Characterised with tests **before** touching anything, so the record of what was
wrong outlives the fix.

**Removed rather than bounded, and the guard is structural.** A TTL would shrink the
window without closing it, and the caller is already holding the secret. The regression
guard reflects over the cache and fails if any field or method name mentions
`credential` or `password`, so re-adding credential state **breaks the build** instead
of quietly reaching production.

**A concurrency test caught a bug I had introduced in the same task.** I replaced four
`ConcurrentHashMap`s with insertion-ordered `LinkedHashMap`s to get eldest-entry
eviction, and the javadoc said *"Synchronised because a LinkedHashMap is not
concurrent"* — but I had not synchronised anything. `removeEldestEntry` reads `size()`
during insertion, so concurrent writers each observed a smaller map and all decided
nothing needed evicting: **525 entries in a cache bounded to 500**. Every method touching
the maps is now genuinely `synchronized`, and the comment says why that is load-bearing.
A comment describing an intent I had not implemented was worse than no comment, and the
only reason it surfaced is that the test asserted the *bound* rather than a happy path.
Run five times consecutively to confirm it is stable, not lucky.

**Two smaller judgement calls, both recorded in code**
- A `null` analysis is no longer stored. A bounded map either throws on a null value or
  poisons the entry, and neither is useful.
- Only the **analysis** lookup counts as a hit or miss — it is the one `forceRefresh`
  gates and the one worth judging the cache on. Page-content lookups share the TTL and
  the eviction bound but not the hit rate, so the number stays unambiguous. My first
  version of that test asserted 2× the lookups and was simply wrong about my own design.

**How it was tested**
- 19 tests. TTL expiry runs against a real one-second TTL and a real wait, not a mocked
  clock: the point is that expiry is driven by elapsed time rather than by someone
  remembering to clear.
- Eviction is tested for the bound *and* for keeping the newest entries.
- A concurrency test (8 threads × 200 URLs) asserts the bound holds and every lookup is
  counted exactly once. This is the test that found the bug above.
- A new workflow test asserts credentials reach generation, replacing the old stubs that
  only checked the 4-argument signature.
- Misconfiguration is clamped, not thrown on: a zero/negative TTL and a zero size bound
  both still behave.
- App boots clean and logs the policy; overrides verified to bind
  (`ttl=42s, maxEntries=7`).
- Full suite **371 green** (was 347).

**Deliberately not done here:** persistence. The backlog mentions `PageAnalysisHistory`
with a short TTL as an option; a 30-minute in-memory cache with a size bound covers the
stated problem, and durable caching is a decision that belongs with the caching work in
Sprint 3 rather than as a side effect of a security fix.

### Why B-022 comes first
It is the blocker for everything in Sprint 3. The workflow currently only sees a
truncated blob of Playwright's text output, so it cannot say *which* tests failed,
cannot attach a screenshot to a specific failure, and the CLI has to grep for
`✘`. Every reporting feature downstream — the HTML report, Allure, per-failure bug
reports — needs structured per-test results first.

---
## Notes / deviations

- **Dependency correction (resolved).** `backlog.md` listed B-006 as depending on B-007 and B-007 as depending on B-006 — circular. Resolution: B-006 shipped first as the summary shell; B-007 was then added *into* that summary. B-007's only real dependency is that B-006 exists. **`backlog.md` still needs this corrected.**
- **B-003 and B-004 were implemented as separate commits** even though they touch the same call sites, so the "commit after each task" instruction was honoured literally. B-004 is meaningless without B-003, so the two are sequential by necessity.
- **`.env.example` grew** with the B-001/B-002/B-009/B-011 knobs, all commented out with the reason each matters.

---

# Sprint 4 — Quality moat

*Goal: protect the product's core claim — "it writes good tests" — which is currently unmeasured.*

**Started:** 2026-09-26
**Baseline commit:** `2e494e0` (end of Sprint 3)

## Task log

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-037 | Resilience: circuit breaker + bulkhead | P1 | M | **DONE** | `6a13220` |
| B-034 | Evaluation harness for generated tests | P1 | L | **DONE** (measurement) | `d9a7453` |
| B-035 | Prompt versioning | P1 | M | TODO | — |
| B-036 | Close the test-coverage holes | P2 | M | TODO | — |
| B-038 | Frontend QA sweep | P2 | M | TODO | — |

**Totals:** 2/5 done · 2 commits · elapsed 431m

### B-037 · Resilience: circuit breaker + bulkhead
| | |
|---|---|
| **Status** | **DONE** — the last open entry in known limitations |
| **Date** | 2026-09-26 |
| **Duration** | 128m |
| **Commit** | `6a13220` (1 of 5) |

**What changed**
- `CircuitBreaker` per provider: CLOSED → OPEN after N consecutive failures → HALF_OPEN
  after the cooldown, admitting **one** probe.
- `ProviderResilience` holds breakers and a per-provider concurrency bulkhead.
- `AiGateway` consults the breaker before every attempt, acquires a bulkhead slot, and
  records the outcome. `AI_PROVIDER_UNAVAILABLE` now carries retry guidance.
- The managed cascade keeps a **per-model** breaker, which the gateway-level one cannot
  do.
- 40 new tests.

**Why half-open rather than closing on the cooldown.** Closing outright releases every
waiting caller at the same instant, which is a stampede against a provider that has only
just recovered. One probe decides; the rest wait. Tested directly: 100 concurrent
acquisitions on a half-open breaker admit exactly one.

**Not every failure counts, and that distinction is the design.** A `400` or `404` is our
request being wrong — tripping on it would take a working provider out of service for
something that fixing the request would resolve. A `5xx`, `429`, timeout, connection error
or rejected credential all count: in each case the provider is the problem and hammering it
changes nothing. A rejected response from a model inside the cascade likewise does **not**
open its breaker — the provider is alive and answering, just not acceptably.

**The bulkhead is on by default; the rate limiter is not.** The rate limiter is a cost
policy an operator chooses. The bulkhead is a safety limit, and the failure it prevents is
thread starvation, not an unexpected bill. Past the cap it **refuses** rather than
queueing, because holding a request thread open behind a provider that is not coming back
is the exact failure this prevents.

**The gateway-level breaker cannot see inside the cascade.** It only observes the
aggregate outcome, so a single dead model in the middle of the chain kept costing a
timeout on every call — which is why per-model breakers exist as well as the per-client
one. A test asserts the cascade reaches Gemini while Go and both Zen models are open,
and that only one upstream request is made.

**A test-design lesson worth keeping.** The first version of these tests used a
zero-second cooldown, which made the OPEN state unobservable: it promoted to HALF_OPEN on
the very next read, so an assertion about reopening passed or failed for the wrong reason.
The breaker now takes an **injected monotonic clock**, so the cooldown is tested by
advancing time. A time-based state machine tested with real sleeps is either slow or
flaky, and usually ends up asserted loosely enough to pass either way.

**How it was tested**
- The criterion that matters is asserted **end to end by counting calls** to a stub
  client: a dead provider costs *nothing* once open, not merely "is reported as open".
- Recovery is automatic once the cooldown elapses — no operator action, nothing to clear.
- 32 threads failing concurrently open the breaker exactly **once**, not 32 times.
- 40 threads against a cap of 4 never exceed 4 in flight.
- A double permit release does not inflate the bulkhead, which would hand out capacity
  that does not exist.
- Misconfiguration is clamped: threshold 0 must not mean "never open", cap 0 must not
  deadlock.
- App boots clean and logs the policy; overrides verified to bind (threshold 3, cap 4).
- Full suite **493 green** from clean (was 459).

**Left for B-026:** breaker state is exposed through `AiGateway.circuitState()` and logged
on every transition, but there are no metrics yet. That is B-026's job and the data now
exists to feed it.


### B-026 · Structured logging + queryable metrics

`27df82a` · P1 · M

**What shipped**

A run is now traceable end to end: every response carries `X-Operation-Id` and every
log line for that request carries the same id. The prod profile writes JSON logs, so
the trace can be reconstructed from log storage instead of only from a terminal.

Ten metrics: `qalab.ai.calls`, `.latency`, `.tokens` (by direction), `.cost`,
`.cascade.extra.attempts`, `qalab.playwright.duration`, `.tests`,
`qalab.workflow.duration`, `qalab.ai.circuit.state` and `.circuit.rejections`.

**Decisions worth recording**

- `outcome` is a required label on the latency timer. Without it a timer averages a
  fast failure in with a slow success and answers nothing.
- **No metric carries an account or project label.** Both are unbounded, and that is
  the standard way to take down a metrics backend. `AiMetricsTest` walks every
  registered meter and fails on such a label, so the instinct cannot creep back in.
- Circuit state is a gauge, not a pushed value: a circuit can half-open and recover
  between scrapes, and a pushed value would go stale and report "open" for a healthy
  provider.
- Counters and timers register lazily; the circuit gauges bind eagerly so a fresh
  instance is not indistinguishable from one with no metrics wired up.

**Two gaps found during verification, both fixed**

1. The bulkhead rejection gauge was driven by breakers alone, so a provider that was
   only ever *rate-limited* — where the bulkhead is the mechanism doing the work — was
   invisible. Gauges now bind for every known provider.
2. `/actuator/**` was denied by `anyRequest().denyAll()`. This would have broken a
   container liveness probe with a 401, and a platform kills a pod whose probe fails.
   Health is public; metrics and info require the API key since they reveal model
   names, latencies and costs. Only those three endpoints are exposed at all.

**Verification**

544 tests green, 21 new. Metrics asserted against a real `SimpleMeterRegistry` rather
than against the writing code, because a registered-but-never-incremented meter is
indistinguishable from one that does not exist. The MDC filter is checked on both the
happy and the failing path, since the leak case is the one that matters. The prod
profile was booted for real: JSON lines parse, a request is traceable by its id, and
health answers 200 without a key.

**Still open, deliberately**

`qalab.ai.cascade.extra.attempts` now gives the data the page-analysis caching decision
(B-028's neighbour) was blocked on. Reconsider that trade once real hit-rate numbers
exist rather than guessing now.


### B-028 · Shared browser, and the screenshot that did not fit

`cd0e67f` · P1 · M

**What shipped**

One `BrowserSessionManager` owns the Playwright process for the whole JVM. Each caller
still gets a fresh context and page, so state cannot leak between runs — a shared
*process* is not shared session state. Lazy launch, a page semaphore, relaunch-on-crash,
and an idle close so a quiet server does not hold ~100 MB open.

**The find that mattered more than the perf win**

`AnalysisResponse` carried a full-page PNG as base64, and `ExplorerService` serialises
that record into `page_analysis_history.analysis_json` — `varchar(10000)`. Any real
analysis exceeded the column and the write failed. It read as a payload-size concern in
the backlog; it was actually a broken write on the main analysis path. The field is now
the screenshot path.

`AnalysisResponsePersistenceSizeTest` asserts the serialised record fits the column and
reads the limit from the migration, so widening the column later cannot quietly turn the
guard into a no-op.

**Still true, and worth restating:** base64 survives in one place — the dashboard
`<img>` — because artifacts are not served over HTTP until the access model is chosen. It
is capped at 512 KB, and the file on disk is kept regardless. Losing the artifact to save
a preview would be the wrong trade.

**Verification**

562 tests green, 18 new. The acceptance criterion is a claim about a process, so it was
checked against a real Chromium: 3 real navigations → exactly 1 launch, 0 leaked pages,
and a 400-row page yields a screenshot past the cap that exists on disk and is not
inlined. The mocked suite covers concurrency the real test cannot force.

**Now unblocked:** page-analysis caching was deferred pending latency numbers, and
`qalab.ai.latency` plus `qalab.ai.calls` from B-026 can now supply them. Still a
correctness-for-speed trade, so it stays a decision rather than a default.


### B-023 · One HTTP method, and the regression hiding in the hoist

`30bb215` · P1 · M

**What shipped**

Go, Zen, Gemini and Ollama shared one `callChatApi`. What differs per provider is now
data — an `AuthStrategy` and a `ResponseExtractor` — rather than 180 lines of copied
request building. `restTemplate.exchange` in that class went from 4 to 1.

**The interesting part was a bug I created**

The usage-limit check was Go-specific: a spent Go quota set `goExhausted`, which skips
Go's remaining candidates. Hoisting the check into the shared method let *every* provider
throw it, so a Zen limit would have been logged as "Go is out for this request" and
skipped a Go model that still had quota. Caught by reading the catch block rather than
by a failing test. The typed error now carries the provider family, and only a Go limit
short-circuits Go.

**On deleting the legacy stack**

The backlog gated deletion on "BYOK is proven". It did not need proving or rescuing:
`AiGateway` already resolves a `ProviderClient` per provider type, so BYOK and managed
share one cascade, one breaker, one budget and one set of metrics. What was left was
`OpenAiProvider` — a `@Component` Spring instantiated on every boot that nothing called —
and an `AiProvider` interface with no injectors. Both deleted.

**Verification**

569 tests green, 7 new. They assert the shared request shape rather than each provider's
copy, and pin the two differences that matter: an Anthropic-style endpoint must not also
send a bearer token, and a 500 must not be reported as an exhausted quota — that mistake
would permanently skip a provider instead of letting the breaker observe a transient
fault.

**Sprint 2 is now complete.** All four exit criteria are met: structured per-test results
(B-022), a bounded and measured worst case (B-024), "why was it slow?" answerable from
metrics (B-026), and no plaintext credentials in memory (B-027).
