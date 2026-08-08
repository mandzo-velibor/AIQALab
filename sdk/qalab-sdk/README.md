# QALabAI SDK (Java)

Thin Java client for the QALabAI Core API. Synchronous, built on
`java.net.http.HttpClient` and Jackson.

## Usage

Add the module to your build, then:

```java
import com.qalab.sdk.ApiResult;
import com.qalab.sdk.QalabClient;

QalabClient qalab = new QalabClient("http://localhost:8080")
        .projectId("the-internet-tests")
        .baseUrl("https://the-internet.herokuapp.com/login")
        .workspacePath("/tmp/qa");

// detect what a natural-language request wants, then run it
qalab.intent("generate tests for the login page");

// explicit operations
ApiResult tests   = qalab.generate("https://the-internet.herokuapp.com/login");
ApiResult plan    = qalab.plan("https://the-internet.herokuapp.com/login");
ApiResult run     = qalab.executeAll();
ApiResult report  = qalab.report(1L);

// budget policy
qalab.budgetPolicy();
qalab.updateBudgetPolicy("SOFT");   // HARD | SOFT | NONE
```

Every method returns an `ApiResult(status, body, operationId)` where `body` is
the raw JSON string. Parse it with your own Jackson `ObjectMapper`:

```java
JsonNode json = new ObjectMapper().readTree(result.body());
```

## Build

```bash
cd sdk/qalab-sdk
mvn package
```

Artifact: `target/qalab-sdk-0.1.0.jar` (requires `jackson-databind` on the
classpath at runtime).

## Methods

| Method | Endpoint |
| ------ | -------- |
| `detectIntent(prompt)` | `POST /api/v1/intent` |
| `intent(prompt)` | `POST /api/v1/intent/run` |
| `generate(url)` | `POST /api/v1/tests` |
| `plan(url)` | `POST /api/v1/test-plan` |
| `explore(url)` | `POST /api/v1/explore` |
| `executeAll()` | `POST /api/v1/run` (`runAll: true`) |
| `execute(testId)` | `POST /api/v1/run` (`testId`) |
| `reports()` | `GET /api/v1/reports` |
| `report(executionId)` | `GET /api/v1/reports/{id}` |
| `budgetPolicy()` | `GET /api/v1/account/budget-policy` |
| `updateBudgetPolicy(policy)` | `PATCH /api/v1/account/budget-policy` |

All operations run through the `AiGateway`, so managed-token budgets are
enforced before any provider call regardless of which surface invokes them.
