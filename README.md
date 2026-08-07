# AI QA Lab

AI-powered QA automation platform that uses AI agents to explore web applications, generate stable locators, create test plans, and produce executable Playwright tests.

## Vision

AI QA Lab is an experimental platform demonstrating how AI can transform the QA automation workflow:

- **Explore** applications intelligently
- **Plan** comprehensive test strategies
- **Generate** stable, maintainable test code
- **Execute** tests and collect artifacts
- **Analyze** failures and suggest fixes
- **Learn** from previous issues

The goal is not to replace Playwright, but to show how AI agents can collaborate to automate the entire QA lifecycle.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      AI QA Lab (Core)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Explorer │→ │ Locator  │→ │ Planner  │→ │Generator │    │
│  │  Agent   │  │  Agent   │  │  Agent   │  │  Agent   │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│       ↓              ↓             ↓             ↓          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Agent Orchestrator                       │   │
│  └──────────────────────────────────────────────────────┘   │
│       ↓                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Browser  │  │   DOM    │  │Playwright│  │   Git    │    │
│  │  Tool    │  │Simplifier│  │  Tool    │  │ Service  │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└─────────────────────────────────────────────────────────────┘
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

## AI Providers

The platform supports multiple AI providers through an abstraction layer:

| Provider | Status | Notes |
|----------|--------|-------|
| OpenCode Go | **Default** | Primary provider with qwen3.7-plus |
| OpenCode Zen | Fallback | Free models like big-pickle |
| OpenAI | Available | GPT-4o-mini |
| Claude | Planned | Future |
| Gemini | Planned | Future |
| Ollama | Planned | Local models |

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
- OpenCode AI (primary)
- OpenAI (fallback)

## Quick Start

### Prerequisites
- Java 21+
- Node.js 20+
- PostgreSQL 15+
- Git

### 1. Clone and Setup

```bash
git clone https://github.com/your-org/ai-qa-lab.git
cd ai-qa-lab

# Create .env file
cat > .env << EOF
OPENCODE_GO_API_KEY=your-key-here
OPENCODE_ZEN_API_KEY=your-key-here
DATABASE_URL=jdbc:postgresql://localhost:5432/qalab
DATABASE_USERNAME=qalab
DATABASE_PASSWORD=qalab
EOF
```

### 2. Start Backend

```bash
cd backend
mvn spring-boot:run
```

The backend will automatically:
- Start the frontend dev server
- Open browser at http://localhost:3000

### 3. Create a Project

1. Navigate to **Projects** page
2. Click **New Project**
3. Enter project details:
   - Name: "My App Tests"
   - Base URL: "https://my-app.com"
   - Framework: "Playwright TypeScript"
4. Click **Create Project**

### 4. Analyze Application

1. Go to **Analyze Page**
2. Enter the URL to analyze
3. Click **Analyze**
4. Review the AI analysis results

### 5. Generate Tests

1. Click **Generate Locators** - AI creates stable locators
2. Click **Generate Test Plan** - AI creates test scenarios
3. Click **Generate Tests** - AI produces Playwright code

## Roadmap

### Completed
- [x] Sprint 1: Backend & Frontend foundation
- [x] Sprint 2: Explorer AI with LLM analysis
- [x] Sprint 3: Locator Intelligence Agent
- [x] Sprint 4: Test Planning Agent
- [x] Sprint 5: Test Generator Agent
- [x] Sprint 6: Executor Agent
- [x] Sprint 7: Project Workspace Architecture

### Next
- [ ] Sprint 8: Failure Analyst Agent
- [ ] Sprint 9: Self-Healing Agent
- [ ] Sprint 10: Vector Memory
- [ ] Sprint 11: Git Automation
- [ ] Sprint 12: Multi-provider support (Claude, Gemini, Ollama)

## Project Structure

```
ai-qa-lab/
├── backend/
│   ├── src/main/java/com/qalab/qalabai/
│   │   ├── agent/          # AI agents
│   │   ├── ai/             # AI provider abstraction
│   │   ├── controller/     # REST endpoints
│   │   ├── dto/            # Data transfer objects
│   │   ├── model/          # JPA entities
│   │   ├── repository/     # Spring Data repos
│   │   ├── service/        # Business logic
│   │   └── tool/           # Agent tools
│   └── src/main/resources/
│       ├── prompts/        # AI prompt templates
│       └── application.yml # Configuration
├── frontend/
│   └── src/
│       ├── app/            # Next.js pages
│       ├── components/     # React components
│       └── lib/            # API clients
├── workspaces/             # External project workspaces
├── .env                    # Environment variables (not committed)
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
