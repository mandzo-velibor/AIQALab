# AI QA Lab

AI-powered QA automation platform that uses AI agents to explore web applications, generate stable locators, create test plans, produce executable Playwright tests, analyze failures, and self-heal broken locators.

## Vision

AI QA Lab is an experimental platform demonstrating how AI can transform the QA automation workflow:

- **Explore** applications intelligently
- **Plan** comprehensive test strategies
- **Generate** stable, maintainable test code
- **Execute** tests and collect artifacts
- **Analyze** failures and suggest fixes
- **Heal** broken locators with approved suggestions
- **Learn** from previous issues

The goal is not to replace Playwright, but to show how AI agents can collaborate to automate the entire QA lifecycle.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        AI QA Lab (Core)                               │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌──────────┐  ┌──────────┐      │
│  │Explorer│→ │Locator │→ │Planner │→ │Generator │→ │Executor  │      │
│  │ Agent  │  │ Agent  │  │ Agent  │  │  Agent   │  │  Agent   │      │
│  └────────┘  └────────┘  └────────┘  └──────────┘  └──────────┘      │
│       ↓           ↓            ↓           ↓             ↓           │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                     Agent Orchestrator                         │   │
│  └────────────────────────────────────────────────────────────────┘   │
│       ↓                                ↓                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │ Browser  │  │   DOM    │  │Playwright│  │   Git    │              │
│  │  Tool    │  │Simplifier│  │  Tool    │  │ Service  │              │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘              │
│       ↓                                ↓                              │
│  ┌───────────────┐        ┌───────────────────────────┐              │
│  │Failure Analyst│        │   Self-Healing Engine     │              │
│  │    Agent      │        │ ElementMatcher            │              │
│  └───────────────┘        │ LocatorSimilarity         │              │
│                           │ HealingApplier            │              │
│                           └───────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────┘
                            │
                            │ manages
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              External QA Project Repositories                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Project A   │  │  Project B   │  │  Project C   │      │
│  │  Repository  │  │  Repository  │  │  Repository  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### Core Platform

The AI QA Lab is the **control plane** that:
- Manages AI agents and their orchestration
- Provides tools for browser automation and code generation
- Stores metadata about projects, tests, and executions
- Never stores project-specific source code

### External Projects

Each application under test has its **own independent repository**:
- Separate Git repositories for each project
- Independent workspaces for generated artifacts
- Project-specific locators, tests, and configurations

## Project Concept

Every application being tested is represented as a **Project**:

```json
{
  "name": "The Internet Login",
  "baseUrl": "https://the-internet.herokuapp.com/login",
  "repositoryUrl": "https://github.com/user/the-internet-login-tests",
  "framework": "PLAYWRIGHT_TYPESCRIPT"
}
```

Projects are isolated:
- Locators are project-specific
- Test plans are project-specific
- Generated tests go to the project's workspace
- Execution history is per-project

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
Creates comprehensive test plans covering:
- Happy paths
- Negative scenarios
- Validation cases
- Security risks
- Reliability tests

### Test Generator Agent
Produces executable Playwright code:
- Page Object Model pattern
- TypeScript with proper types
- Semantic locators from Locator Agent
- Meaningful assertions

### Executor Agent
Runs tests and collects:
- Pass/fail status
- Duration
- Screenshots
- Videos
- Traces
- Console logs

### Failure Analyst Agent
Analyzes failed test executions and determines the root cause:
- Failure type classification (TIMEOUT, ELEMENT_NOT_FOUND, HTTP_ERROR, LOCATOR_INVALID, etc.)
- Confidence score
- Affected element identification
- Whether the failure is a healing candidate
- Stores results in project memory (`FailureAnalysis`, `FailureHistory`)

### Self-Healing Agent
Generates replacement locators for broken elements. Uses two complementary strategies:
1. **AI-based**: inspects the current DOM via Browser Tool and proposes the most probable replacement
2. **Deterministic**: `ElementMatcherService` searches the live page by role/name and ranks candidates with `LocatorSimilarityService`

Safeguards:
- Never modifies test code automatically
- Suggestions start as `PENDING` and require explicit **Approve**
- **Apply** promotes the new locator to the active locator history entry and demotes the old one to `REPLACED`
- Complete history is preserved for every element

## AI Providers

The platform supports multiple AI providers through an abstraction layer:

| Provider | Status | Notes |
|----------|--------|-------|
| OpenCode Go | **Default** | Primary provider with qwen3.7-plus |
| OpenCode Zen | Fallback | big-pickle, fallback to mimo-v2.5-free |
| OpenAI | Available | GPT-4o-mini |
| Gemini | Fallback | gemini-1.5-flash |
| Ollama | Fallback | Local/remote models (gpt-oss:20b) |
| Claude | Planned | Future |

The fallback chain is **Go → Zen (big-pickle) → Zen (mimo-v2.5-free) → Gemini → Ollama**. Every
provider response is validated (valid JSON matching the required schema); invalid or empty
responses are rejected with a logged reason and the chain moves to the next provider.

## API Overview

### Projects
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/projects` | Create a project |
| GET | `/api/projects` | List all projects |
| GET | `/api/projects/{id}` | Get a single project |
| GET | `/api/projects/{id}/history` | Full project memory (executions, page analyses, locators, failures, healings) |

### Analysis & Generation
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/explore` | Explore a URL with the Browser Tool |
| POST | `/api/analyze` | AI analysis of a page |
| POST | `/api/locators/generate` | Generate stable locators |
| POST | `/api/test-plans/generate` | Generate test plan scenarios |
| POST | `/api/tests/generate` | Generate Playwright test code |

### Execution
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/executions/run` | Run a test (or all tests) for a project |
| GET | `/api/executions/history` | Execution history (filterable by `projectId`) |
| POST | `/api/executions/{executionId}/analyze` | Analyze a failed execution |

### Healing
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/healing/analyze/{executionId}` | Generate a healing suggestion |
| POST | `/api/healing/{id}/approve` | Approve a suggestion |
| POST | `/api/healing/{id}/reject` | Reject a suggestion |
| POST | `/api/healing/{id}/apply` | Apply an approved suggestion to locator history |
| GET | `/api/healing/suggestions` | List suggestions (filterable by `projectId`) |

## Tech Stack

### Backend
- Java 21
- Spring Boot 3.3
- Spring Data JPA
- PostgreSQL
- Playwright Java

### Frontend
- Next.js 16
- TypeScript
- Tailwind CSS
- shadcn/ui

### AI
- OpenCode AI (primary, 12000 max tokens)
- OpenAI (fallback)
- Gemini (fallback)
- Ollama (fallback)

## Quick Start

### Prerequisites

These must be installed **before** first run:

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21+ | Required for the Spring Boot backend |
| Maven | 3.9+ | Builds the backend |
| Node.js | 20+ | Required for the frontend **and** for running generated Playwright tests |
| npm | 10+ | Comes with Node.js |
| PostgreSQL | 15+ | App database (or use Docker below) |
| Git | any | Cloning project repositories |

**Playwright + browsers** are installed automatically:
- On backend startup, `PlaywrightSetupConfig` checks each project workspace and runs
  `npm install` (once) and `npx playwright install chromium` (once per workspace,
  guarded by a `.playwright-ready` marker) if they are missing.
- A new project workspace is prepared the first time it is used (`WorkspaceManager.getProjectContext`).
- Disable auto-install with `QALAB_AUTO_INSTALL_PLAYWRIGHT=false`.

Verify manually with:
```bash
node --version   # v20+
npm --version    # 10+
mvn --version    # 3.9+
npx playwright install chromium   # download browser binaries if needed
```

### 1. Clone and Setup

```bash
git clone https://github.com/your-org/ai-qa-lab.git
cd ai-qa-lab

# Create .env file (see .env.example)
cp .env.example .env
# Then edit .env and add your AI provider keys
```

### 2. Start Backend

```bash
cd backend
mvn spring-boot:run
```

The backend will automatically:
- Start the frontend dev server
- Open browser at http://localhost:3000
- Install Playwright browsers for project workspaces on first use (see Prerequisites)

### 3. Run with Docker

The whole stack (PostgreSQL + backend + frontend) runs in Docker.
Requires **Docker Engine** with Compose v2 — use `docker compose` (the older
`docker-compose` v1 command is **not** supported).

```bash
# 1. Add your AI keys to .env (docker compose reads it automatically)

# 2. Build and start everything
docker compose up --build

# 3. Open the app
open http://localhost:3000

# Stop everything
docker compose down

# Stop and also delete the database volume
docker compose down -v
```

- Backend: http://localhost:8080
- Frontend: http://localhost:3000
- PostgreSQL: `localhost:5432` (user/password `qalab`/`qalab`, database `qalab`)
- Project workspaces and screenshots are stored in named Docker volumes
  (`qalab-workspaces`, `qalab-screenshots`) so generated tests persist across restarts.
- AI provider keys are passed from the host `.env` into the backend container;
  the `.env` file is **never** committed or baked into images.
- Inside the backend image Playwright + Chromium are pre-installed, so generated
  tests run out of the box.

### 4. CI/CD (GitHub Actions)

A workflow at `.github/workflows/ci.yml` runs on push/PR to `main`/`master`:

| Job | What it does |
|-----|-------------|
| `backend-build` | JDK 21 + Node 20, installs Playwright Chromium, `mvn -B verify` |
| `frontend-build` | Node 20, `npm ci`, typecheck, `next build` |
| `security-check` | Fails if `.env` or hardcoded API keys are committed; validates `docker compose config` |
| `docker-build` | Builds both Docker images (push only) |

### 5. Create a Project

1. Navigate to **Projects** page
2. Click **New Project**
3. Enter project details:
   - Name: "My App Tests"
   - Base URL: "https://my-app.com"
   - Framework: "Playwright TypeScript"
4. Click **Create Project**

### 6. Analyze Application

1. Go to **Analyze Page**
2. Enter the URL to analyze
3. Click **Analyze**
4. Review the AI analysis results

### 7. Generate Tests

1. Click **Generate Locators** - AI creates stable locators
2. Click **Generate Test Plan** - AI creates test scenarios
3. Click **Generate Tests** - AI produces Playwright code

### 8. Analyze Failures & Heal Locators

1. Open the project page at **Projects → Open Project**
2. Run tests and open a failed execution
3. Click **Analyze** - Failure Analyst determines the root cause
4. Click **Generate Healing** - Self-Healing Agent proposes a replacement locator
5. **Approve** the suggestion, then **Apply** it to update the active locator history
6. Re-run the test to confirm the fix

## Roadmap

### Completed
- [x] Sprint 1: Backend & Frontend foundation
- [x] Sprint 2: Explorer AI with LLM analysis
- [x] Sprint 3: Locator Intelligence Agent
- [x] Sprint 4: Test Planning Agent
- [x] Sprint 5: Test Generator Agent
- [x] Sprint 6: Executor Agent
- [x] Sprint 7: Project Workspace Architecture
- [x] Sprint 8: Failure Analyst Agent & Project Memory
- [x] Sprint 9: Self-Healing Locator Engine
- [x] Sprint 12: Multi-provider support (Gemini, Ollama, fallback chain)

### Next
- [ ] Sprint 10: Vector Memory
- [ ] Sprint 11: Git Automation
- [ ] Sprint 13: Claude support

## Project Structure

```
ai-qa-lab/
├── backend/
│   ├── Dockerfile
│   └── src/main/java/com/qalab/qalabai/
│       ├── agent/          # AI agents
│       │   ├── explorer/   # Explorer Agent
│       │   ├── locator/    # Locator Agent
│       │   ├── planner/    # Planner Agent
│       │   ├── testgen/    # Test Generator Agent
│       │   ├── executor/   # Executor Agent
│       │   ├── failure/    # Failure Analyst Agent
│       │   └── healing/    # Self-Healing Agent
│       ├── ai/             # AI provider abstraction
│       ├── config/         # Startup setup (Playwright auto-install), WS config
│       ├── controller/     # REST endpoints
│       ├── dto/            # Data transfer objects
│       ├── model/          # JPA entities
│       ├── repository/     # Spring Data repos
│       ├── service/        # Business logic
│       │   ├── healing/    # Matcher, similarity, applier
│       │   └── workspace/  # WorkspaceManager, TestWorkspaceService
│       └── tool/           # Agent tools
│   └── src/main/resources/
│       ├── prompts/        # AI prompt templates
│       └── application.yml # Configuration
├── frontend/
│   ├── Dockerfile
│   └── src/
│       ├── app/            # Next.js pages
│       ├── components/     # React components
│       └── lib/            # API clients
├── .github/workflows/ci.yml # CI/CD pipeline
├── docker-compose.yml       # Full stack (db + backend + frontend)
├── workspaces/             # External project workspaces
├── .env                    # Environment variables (not committed)
├── .env.example            # Safe template for .env
└── README.md
```

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

## License

MIT License - see LICENSE file for details.

## Acknowledgments

- [Playwright](https://playwright.dev/) for browser automation
- [OpenCode](https://opencode.ai) for AI provider infrastructure
- [shadcn/ui](https://ui.shadcn.com/) for UI components
