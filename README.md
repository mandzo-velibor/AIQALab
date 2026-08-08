# AI QA Lab

An AI-powered QA automation **engine** that explores web applications, generates stable
locators, creates test plans, produces executable Playwright tests, analyzes failures, and
suggests self-healing fixes — exposed as a service via a versioned REST API.

## Concept

AI QA Lab is built on a strict separation of ownership:

- **AI QA Lab Core** contains the intelligence and orchestration only. It never stores or
  owns target-project source code.
- **The target QA project** owns its own source code, tests, and Git repository. AI QA Lab
  can *read* that code to run tests, but generated files are returned to the client — the
  client decides where they are written.

```
┌─────────────────────────────────────────────────────────────────────┐
│                     AI QA LAB CORE (intelligence)                    │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐         │
│  │Explorer │ │Locator  │ │ Planner │ │Generator│ │Executor │         │
│  │  Agent  │ │ Agent   │ │  Agent  │ │  Agent  │ │  Agent  │         │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘         │
│  ┌──────────────┐ ┌───────────────┐ ┌──────────────┐                 │
│  │Failure       │ │ Self-Healing  │ │  QaWorkflow  │                 │
│  │Analyst Agent │ │    Agent      │ │   Service    │                 │
│  └──────────────┘ └───────────────┘ └──────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘
                            │
                      REST API / SDK / CLI  (/api/v1)
                            │
┌─────────────────────────────────────────────────────────────────────┐
│                    USER QA PROJECTS (source owned)                   │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐   │
│  │ my-playwright-   │  │ another-project  │  │      ...         │   │
│  │ tests (repo)     │  │ (repo)           │  │                  │   │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Ownership rules

- The Core stores AI knowledge: explorations, analyses, locators, test plans, executions,
  failure analyses, and healing suggestions.
- Generated test source is **returned to the client** as files (`path` + `content`). The
  Core writes nothing into the target project unless the client explicitly provides a
  workspace path for execution.
- Agents never construct filesystem paths, never access the database, and never select an
  execution provider. They receive everything they need through `Task` objects and return
  results in `AgentResult` data.

## Capabilities (service API)

Every operation returns an `operationId` and an `OperationStatus`
(`PENDING` / `RUNNING` / `COMPLETED` / `FAILED`).

| Capability | Endpoint | Description |
|------------|----------|-------------|
| Explore | `POST /api/v1/explore` | Explore a URL with the Browser Tool (element map) |
| Analyze | `POST /api/v1/analyze` | AI analysis of a page |
| Generate locators | `POST /api/v1/locators` | Generate stable Playwright locators |
| Generate test plan | `POST /api/v1/test-plan` | Generate test scenarios |
| Generate tests | `POST /api/v1/tests` | Generate Playwright code, returned as files |
| Run tests | `POST /api/v1/run` | Run a test or the full suite in the target workspace |
| Analyze failure | `POST /api/v1/failures/analyze` | Root-cause analysis of a failed execution |
| Healing | `POST /api/v1/healing/analyze` | Propose a replacement locator for a broken element |
| Full QA workflow | `POST /api/v1/workflows/full-test` | Explore → analyze → locators → plan → generate → run → failure analysis → healing |
| Intent detection | `POST /api/v1/intent` | Detect what a natural-language request wants |
| Intent execution | `POST /api/v1/intent/run` | Detect the intent, then dispatch to the matching operation |
| Reports | `GET /api/v1/reports` | List execution reports |
| Report | `GET /api/v1/reports/{executionId}` | Full report incl. artifact paths |
| Budget policy | `GET /api/v1/account/budget-policy` | Current budget policy and usage |
| Budget policy | `PATCH /api/v1/account/budget-policy` | Update policy (`HARD`, `SOFT` or `NONE`) |

### Request shape

All `/api/v1` requests carry a minimal `project` block — identity/context only, **not** source code:

```json
{
  "project": {
    "projectId": "my-app-1",
    "name": "My App",
    "baseUrl": "https://my-app.com",
    "framework": "PLAYWRIGHT_TYPESCRIPT",
    "language": "TypeScript",
    "workspacePath": "/home/dev/my-app-tests"
  },
  "url": "https://my-app.com/login"
}
```

- `projectId` is required. `workspacePath` is optional: it is only used when the Core must
  execute tests locally.
- Responses embed the same `projectId` and the generated artifacts; generated tests come as
  `files: [{ "path": "login.spec.ts", "content": "..." }]` for the client to write.

### Error model

Errors follow a single shape with an operation scope:

```json
{
  "error": {
    "code": "INVALID_PROJECT_CONTEXT",
    "message": "projectId is required",
    "operationId": "op_2026..."
  }
}
```

| Code | HTTP status |
|------|-------------|
| `INVALID_REQUEST`, `INVALID_PROJECT_CONTEXT`, `INVALID_PROVIDER` | 400 |
| `PROJECT_NOT_FOUND` | 404 |
| `AI_BUDGET_EXCEEDED` | 429 |
| `AI_PROVIDER_UNAVAILABLE` | 503 |
| `INTERNAL_ERROR` | 500 |

Security rules: no arbitrary filesystem paths are ever accepted from clients, no API keys
are returned in responses, and API keys are never logged.

## Agents

### Explorer Agent
Analyzes web pages using AI to understand:
- Page type (Login, Dashboard, Checkout, etc.)
- Forms, buttons, navigation elements
- Possible user flows
- Risk areas

### Locator Agent
Generates stable Playwright locators following priority:
1. `data-testid` attributes
2. ARIA roles + accessible names
3. Labels
4. Placeholders
5. Text content
6. CSS selectors
7. XPath (last resort)

### Planner Agent
Creates comprehensive test plans covering happy paths, negative scenarios, validation
cases, security risks, and reliability tests.

### Test Generator Agent
Produces executable Playwright code — Page Object Model pattern, TypeScript with proper
types, semantic locators, and meaningful assertions.

### Executor Agent
Runs tests through a `WorkspaceProvider` (local workspace, remote targets later) and
collects pass/fail status, duration, screenshots, videos, traces, and console logs. It
never knows where files live or how to reach the target — it only asks the provider.

### Failure Analyst Agent
Classifies failure types (TIMEOUT, ELEMENT_NOT_FOUND, HTTP_ERROR, LOCATOR_INVALID, …),
computes a confidence score, identifies the affected element, and decides whether the
failure is a healing candidate.

### Self-Healing Agent
Proposes replacement locators for broken elements using two complementary strategies:
1. **AI-based**: inspects the current DOM and proposes the most probable replacement
2. **Deterministic**: `ElementMatcherService` ranks live-page candidates by
   `LocatorSimilarityService`

Safeguards: it **never** modifies test code automatically; suggestions start as `PENDING`
and require explicit approval/apply to become active, while the full locator history is
preserved.

## Services

Application logic lives in focused services (no monolithic `QaService`):

| Service | Responsibility |
|---------|----------------|
| `ExplorationService` | EXPLORE capability |
| `AnalysisService` | ANALYZE capability |
| `LocatorService` | LOCATORS capability + persistence |
| `PlanningService` | TEST PLAN capability + persistence |
| `CodeGenerationService` | TEST GENERATION (content-only and entity variants) |
| `ExecutionService` | RUN capability + execution records |
| `FailureAnalysisService` | FAILURE ANALYSIS + project memory |
| `HealingService` | HEALING + suggestion lifecycle |
| `QaWorkflowService` | FULL_TEST orchestration with branching |

`ProjectContextResolver` converts the client `project` block into a `ProjectContext` (an
optional database lookup enriches it). `WorkspaceProvider` / `TestExecutionTarget`
abstract where tests run.

## AI Providers

Multiple AI providers are supported through an abstraction layer with a fallback chain:

| Provider | Role |
|----------|------|
| OpenCode Go | Primary |
| OpenCode Zen | Fallback |
| OpenAI | Available |
| Gemini | Fallback |
| Ollama | Fallback |

Every provider response is validated (valid JSON matching the required schema); invalid or
empty responses are rejected with a logged reason and the chain moves to the next provider.

### Token budget

All AI calls flow through `AiGateway`, which tracks managed-token usage per account and
month. When the allowance is exhausted the enforcement depends on the account's
`budgetPolicy`:

| Policy | Behavior when exhausted |
|--------|-------------------------|
| `HARD` | Call refused with `AI_BUDGET_EXCEEDED` (HTTP 429); no provider call (FREE default) |
| `SOFT` | Call proceeds, usage recorded, workflow context flagged `budgetSoftExceeded` |
| `NONE` | Allowance not enforced (PRO/TEAM default) |

View/update the policy with `GET/PATCH /api/v1/account/budget-policy` (or
`qalab budget-policy`). Only MANAGED calls consume the allowance; BYOK and LOCAL calls
are recorded but never blocked.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.3, Spring Data JPA, PostgreSQL, Playwright Java
- **Frontend** (first client of the engine): Next.js, TypeScript, Tailwind CSS, shadcn/ui

## Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21+ | Backend |
| Maven | 3.9+ | Backend build |
| Node.js | 20+ | Frontend and generated Playwright tests |
| npm | 10+ | Comes with Node.js |
| PostgreSQL | 15+ | App database (or use Docker) |

Playwright + Chromium are installed automatically per workspace on first use
(`PlaywrightSetupConfig`, guarded by a `.playwright-ready` marker). Disable with
`QALAB_AUTO_INSTALL_PLAYWRIGHT=false`.

### 1. Setup

```bash
cp .env.example .env      # add your AI provider keys
docker compose up --build # full stack: PostgreSQL + backend + frontend
```

- Backend: http://localhost:8080
- Frontend: http://localhost:3000
- PostgreSQL: `localhost:5432` (user/password `qalab`/`qalab`, database `qalab`)

Or run the backend directly: `cd backend && mvn spring-boot:run`.

### 2. Use the engine

```bash
# Register a project (optional — the Core can operate on a projectId alone)
curl -X POST http://localhost:8080/api/projects \
  -H 'Content-Type: application/json' \
  -d '{"name":"The Internet Tests","baseUrl":"https://the-internet.herokuapp.com","framework":"PLAYWRIGHT_TYPESCRIPT"}'

# Explore a page
curl -X POST http://localhost:8080/api/v1/explore \
  -H 'Content-Type: application/json' \
  -d '{"project":{"projectId":"my-internet-project"},"url":"https://the-internet.herokuapp.com/login"}'

# Generate tests (files are returned, never written by the Core)
curl -X POST http://localhost:8080/api/v1/tests \
  -H 'Content-Type: application/json' \
  -d '{"project":{"projectId":"my-internet-project"},"url":"https://the-internet.herokuapp.com/login"}'

# Run a generated test in a client-owned workspace
curl -X POST http://localhost:8080/api/v1/run \
  -H 'Content-Type: application/json' \
  -d '{"project":{"projectId":"my-internet-project","workspacePath":"/home/dev/my-internet-tests"},"testId":"login-test"}'
```

To execute tests locally, the client owns a workspace (e.g. `my-playwright-tests/`) with
its own `package.json` and `playwright.config.ts`; the generated `files` are written there
by the client, then passed back to the Core via `workspacePath` for execution.

### 3. CLI & SDK

The engine ships with a CLI and a Java SDK for scripted/CI use.

```bash
# CLI
export QALAB_BASE_URL=https://the-internet.herokuapp.com/login
export QALAB_WORKSPACE=/home/dev/my-internet-tests

cli/qalab init ~/qa-project            # create a .qalab.json config
cli/qalab explore                      # page map
cli/qalab generate                     # Playwright test files
cli/qalab execute                      # run in the configured workspace
cli/qalab report                       # list reports
cli/qalab report 12                    # one report
cli/qalab intent "generate tests for the login page"   # natural language
cli/qalab budget-policy                # show policy + usage
cli/qalab budget-policy set SOFT       # HARD | SOFT | NONE
```

```java
// SDK (sdk/qalab-sdk)
QalabClient qalab = new QalabClient("http://localhost:8080")
        .projectId("the-internet-tests")
        .baseUrl("https://the-internet.herokuapp.com/login")
        .workspacePath("/tmp/qa");

qalab.intent("generate tests for the login page");
qalab.generate("https://the-internet.herokuapp.com/login");
qalab.executeAll();
qalab.report(1L);
qalab.updateBudgetPolicy("SOFT");
```

Every generated test run stores artifacts (screenshots, traces, videos, console
logs) in `./artifacts/execution-<id>/` and renders a `report.json` + `report.md`,
exposed through `GET /api/v1/reports`.

### 4. CI/CD

`.github/workflows/ci.yml` runs on push/PR to `main`/`master`: backend build (JDK 21 +
Node 20, Playwright Chromium, `mvn -B verify`), frontend build (typecheck + `next build`),
a security check that fails if `.env` or hardcoded API keys are committed, and Docker image
builds (push only).

## Roadmap

### Completed
- Sprint 1: Backend & Frontend foundation
- Sprint 2: Explorer AI with LLM analysis
- Sprint 3: Locator Intelligence Agent
- Sprint 4: Test Planning Agent
- Sprint 5: Test Generator Agent
- Sprint 6: Executor Agent
- Sprint 7: Project Workspace Architecture
- Sprint 8: Failure Analyst Agent & Project Memory
- Sprint 9: Self-Healing Locator Engine
- Sprint 10: Service-oriented core — `QaAgent`-based agents without DB access,
  `WorkspaceProvider` abstraction, `ProjectContextResolver`, and the `/api/v1` service API
  with the `QaWorkflowService` FULL_TEST pipeline
- Sprint 11: Multi-provider gateway (`AiGateway`), managed/BYOK/local credentials,
  token usage accounting and budget enforcement (`AI_BUDGET_EXCEEDED`)
- Sprint 12: Budget policy (`HARD`/`SOFT`/`NONE`), natural-language intent detection,
  execution reports + artifact store, and the `qalab` CLI + Java SDK

### Next
- Vector memory
- Git automation
- Claude support
- Remote execution targets (`TestExecutionTarget.REMOTE`)

## Project Structure

```
ai-qa-lab/
├── backend/
│   └── src/main/java/com/qalab/qalabai/
│       ├── agent/          # AI agents (no DB access, no paths)
│       ├── ai/             # AI provider abstraction
│       ├── api/            # Operation/status + error model, v1 DTOs & controllers
│       ├── config/         # Startup setup, WS config
│       ├── controller/     # Legacy UI REST endpoints
│       ├── dto/            # Data transfer objects
│       ├── model/          # JPA entities
│       ├── repository/     # Spring Data repos
│       ├── service/        # Application services, workspace abstraction
│       └── tool/           # Agent tools
├── frontend/               # Next.js UI (first client)
├── cli/qalab               # CLI client for the Core API
├── sdk/qalab-sdk/          # Java SDK for the Core API
├── workspaces/             # Local test workspaces (generated on demand)
├── artifacts/              # Per-execution reports + screenshots/traces/videos
├── .github/workflows/ci.yml
├── docker-compose.yml
└── README.md
```

## License

MIT License - see LICENSE file for details.
