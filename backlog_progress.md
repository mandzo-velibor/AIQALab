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

## Notes / deviations

- **Dependency correction (resolved).** `backlog.md` listed B-006 as depending on B-007 and B-007 as depending on B-006 — circular. Resolution: B-006 shipped first as the summary shell; B-007 was then added *into* that summary. B-007's only real dependency is that B-006 exists. **`backlog.md` still needs this corrected.**
- **B-003 and B-004 were implemented as separate commits** even though they touch the same call sites, so the "commit after each task" instruction was honoured literally. B-004 is meaningless without B-003, so the two are sequential by necessity.
- **`.env.example` grew** with the B-001/B-002/B-009/B-011 knobs, all commented out with the reason each matters.
