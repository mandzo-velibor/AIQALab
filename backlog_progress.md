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

## Sprint summary

| # | Task | Priority | Size | Status | Commit |
|---|---|---|---|---|---|
| B-001 | Playwright runner real timeout | P0 | S | IN PROGRESS | — |
| B-002 | AI provider HTTP timeouts | P0 | S | TODO | — |
| B-003 | Generate test suite once per run | P0 | M | TODO | — |
| B-004 | Ship page objects to client workspace | P0 | M | TODO | — |
| B-005 | Unify CLI/backend workspace path | P0 | M | TODO | — |
| B-006 | CLI human summary + `--json` | P0 | M | TODO | — |
| B-007 | Write test plan to disk | P0 | S | TODO | — |
| B-008 | Regression tests for shipped fixes | P0 | M | TODO | — |
| B-009 | Configurable CORS origins | P0 | S | TODO | — |
| B-010 | WebSocket URL from config | P0 | S | TODO | — |
| B-011 | Persist artifacts directory | P0 | S | TODO | — |

**Totals:** 0/11 done · 0 commits · elapsed 0m

---

## Notes / deviations

- **Dependency correction.** `backlog.md` lists B-006 as depending on B-007, and B-007 as depending on B-006 — circular. Resolution: B-006 (summary shell) is implemented first, B-007 (test plan) is then added *into* that summary. B-007's real dependency is only on B-006 existing. Backlog to be corrected at sprint end.
