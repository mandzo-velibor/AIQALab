# ADR 0001 — Authentication and tenancy

- **Status:** Accepted
- **Date:** 2026-09-26
- **Task:** B-012 (spike) → implemented by B-013
- **Supersedes:** nothing

## Context

The service currently has **no authentication of any kind**. Verified by search across
`backend/src/main/java`: no `SecurityFilterChain`, no `@PreAuthorize`, no API-key
filter, no JWT. Every `/api/**` and `/api/v1/**` endpoint is anonymous.

What that exposes to anyone who can reach the port:

| Endpoint | Consequence |
|---|---|
| `POST /api/v1/workflows/full-test` | Triggers 6–7 **paid** LLM calls per request |
| `POST /api/v1/tests`, `/api/v1/test-plan`, `/api/v1/locators` | Same, individually |
| `GET /api/v1/account/usage` | Token spend and cost attributable to nobody |
| `GET /api/v1/bug-reports`, `/reports/*` | Reads generated reports and console logs |
| `POST /api/v1/healing/{id}/accept` then `/apply` | **Mutates stored test source** |
| `PATCH /api/v1/account/budget-policy` | Changes the global budget policy |
| `GET/POST /api/projects` | Enumerates and mutates registered projects |

Three structural facts make this worse than a missing feature:

1. **`AccountService.defaultAccount()` returns a single implicit global account.** So
   usage attribution and budget enforcement are meaningless with more than one user —
   everyone's spending lands on one bucket.
2. **`CredentialStore` is global, not per-user.** BYOK provider keys have no owner, so
   one user's key would be usable by another.
3. **The rate limiter is a no-op** (`NoopRateLimiter`, B-016). Combined with the above,
   an anonymous caller can drive unbounded paid traffic.

The service is already deployed to a cloud host, so this is live, not theoretical.

## Constraints

- The primary consumer is a **CLI** (`cli/qalab`) plus a **browser dashboard**. Both
  must keep working with minimal ceremony.
- Single-node and self-hosted deployments must work with **no external identity
  provider** — requiring Okta/Auth0 to run a local QA tool is unacceptable friction.
- Must not break the existing workflow: the CLI authenticates once and then makes
  long-running calls.
- Provider keys must stay write-only from the API's perspective (a property
  `CredentialStore` already guarantees and which must be preserved).
- Sprint 1 budget is one engineer-week. A full multi-tenant identity system is not
  achievable in that budget and should not be half-built.

## Decision

**Layered: static API keys now, pluggable identity later.**

### 1. Authentication: bearer API key

Every request to `/api/**` (except an explicit public allowlist) must present
`Authorization: Bearer <key>`.

- Keys are **per account**, stored as a **salted hash** (not the raw value), so a
  database leak does not yield usable keys.
- The raw key is returned **once**, at creation, and never again. This mirrors the
  existing write-only `CredentialStore` convention — consistent and deliberate.
- Comparison is constant-time to avoid a timing oracle.
- Keys are rotatable and revocable without downtime.

**Why an API key and not JWT:** the audience is a local QA tool and a self-hosted
dashboard. A local tool should not need a token refresh flow, and an operator should
not need an identity provider. A static key is the smallest thing that actually closes
the hole. The `AuthPrincipal` abstraction below means JWT can be added later without
touching controllers.

### 2. Authorization: account-scoped, deny by default

- The `AuthPrincipal` resolved from the key is put on the request; controllers take
  the account from it, never from client-supplied input.
- **A client-supplied `databaseId` is no longer trusted for authorization.** It may
  only be used if it belongs to the caller's account. This is the important part:
  authentication alone still leaves horizontal privilege escalation (read another
  tenant's reports by guessing an id).
- The global `defaultAccount()` is replaced by the authenticated account. The existing
  "ensure a default account exists" bootstrap stays, so single-user local setups keep
  working with zero configuration.

### 3. Public allowlist

Only these are reachable without a key:

| Path | Why |
|---|---|
| `GET /actuator/health` (if enabled) | Liveness/readiness probes |
| `GET /api/v1/account/bootstrap` | Returns whether a key is required, so the CLI/UI can show a clear "not configured" state rather than a bare 401 |

Everything else is authenticated.

### 4. What this deliberately does NOT do

Stated plainly so it is not mistaken for done:

- **No signup, login UI, password hashing or user management.** Out of scope.
- **No per-user role/permission matrix.** Everyone with a key is an operator of their
  own account.
- **No JWT/OIDC.** Deliberate; `AuthPrincipal` leaves room.
- **No key rotation schedule or expiry.** Keys do not expire; they are revocable.
- **No audit log of who changed what.** A gap, and the most valuable next addition.

## Consequences

### Positive
- The deployment is no longer open to anonymous spending or cross-tenant reads.
- Per-account budgets become meaningful, so B-016's rate limiter has a tenant key.
- A clear, honest foundation for JWT later without controller changes.

### Negative / accepted
- **A breaking change for existing users.** An unconfigured deployment that used to
  work will start returning 401. Mitigations: the key is only *enforced* when
  `qalab.security.require-api-key=true` **or** at least one key exists; and
  `bootstrap` explains exactly what to do. Defaulting to "enforce only if a key
  exists" keeps local `mvn spring-boot:run` working while closing the cloud hole.
  Trade-off accepted: an operator who never creates a key stays unprotected — hence
  the loud startup warning.
- **Keys must be distributed to CLI users.** One setup step, documented.
- Per-user BYOK ownership is still global in the first cut; noted as follow-up.

## Follow-ups (not in this ADR's scope)

1. Per-user ownership for `CredentialStore`.
2. Audit log for privileged mutations (key creation, healing apply, budget policy).
3. Key expiry and rotation.
4. Replace `defaultAccount()` entirely with explicit account resolution.
5. Optional JWT/OIDC behind the same `AuthPrincipal`.

## Implementation notes for B-013

- `spring-boot-starter-security`, deny-by-default, `permitAll()` on the allowlist
  only, stateless sessions, CSRF disabled (token auth, no cookies).
- Filter: `OncePerRequestFilter` resolving key → `AuthPrincipal` → request attribute.
  A `HandlerMethodArgumentResolver` exposes it to controllers.
- **Must not** register the default Spring login page or a generated password — a
  401 JSON body in the existing `{"error": {...}}` shape is required, or the CLI's
  error handling degrades.
- A one-time key printed to the log at startup when no key exists yet, so a fresh
  install is usable immediately without a manual DB insert.
