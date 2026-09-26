# Evaluation harness

**What this measures:** whether the product still finds defects.

The product's claim is defect detection. Until now nothing measured it, so a prompt
change that quietly made the generator worse looked exactly like a prompt change that
improved a summary. "Tests ran" is not "tests found anything", and only the second number
is worth anything.

## Run it

```bash
cd backend
mvn test -Dtest=DefectRecallHarnessTest
```

Prints the score and fails the build if recall drops below `evals/baseline.json`.

The harness **skips** when Playwright or its browsers are absent, because a machine that
cannot run the suite cannot answer the question and reporting that as a regression would be
a lie. CI installs them, so the check is real there.

First-time local setup:

```bash
cd evals/golden
npm install
npx playwright install chromium
```

## How the measurement works

**One defect per fixture, with a correct twin.** That is the whole design. Each fixture is
a small static page with exactly one planted, documented defect, plus a `correct.html` that
is genuinely correct. The golden suite is run **twice**:

| Result on defective page | Result on correct twin | Meaning |
|---|---|---|
| fails | passes | **detected** — the suite caught it |
| fails | fails | **false positive** — the suite is wrong about a working page |
| passes | passes | **missed** |

One run cannot tell a detection from a false positive, which is why the twins exist. And
because there is one defect per fixture, any detection is attributed unambiguously — no
heuristic matching of error messages to defect names, which would be guesswork dressed as
measurement.

**Flakes are counted and never rewarded.** A test that failed once and passed on retry has
not demonstrated a detection, so it cannot count toward recall. It is reported separately.

**A skipped test is not a miss.** "Did not run" and "ran and found nothing" are opposite
findings, and collapsing them would let a suite that silently skips its hardest tests score
as a clean run.

## What is in the corpus

| Fixture | Category | Severity | Defect |
|---|---|---|---|
| `01-missing-label` | accessibility | MEDIUM | Password field has no `<label for>`, so it has no accessible name |
| `02-wrong-role` | accessibility | HIGH | Payment method is a clickable `div` — no role, not focusable, no keyboard |
| `03-no-error-state` | error handling | HIGH | Empty submit produces no message, no alert, no focus move |
| `04-xss-reflection` | security | CRITICAL | Query written with `innerHTML`, so a crafted query executes script |
| `05-cross-field-validation` | validation | HIGH | Password confirmation is never compared to the password |

`evals/fixtures/manifest.json` is the human-readable record, including *why each one
matters*. Every defective page carries a `PLANTED DEFECT` comment naming its id, so reading
a fixture tells you what is wrong with it.

The mix is deliberate. A corpus of only structural defects would reward a generator that
only produces structural assertions.

`05-cross-field-validation` replaced an earlier `min`/`max` age-range fixture: the browser
enforces that range natively, so the "defect" was unobservable and the oracle test failed
against *both* variants. A defect the browser silently prevents is not a defect a test can
find, and an oracle that cannot discriminate is worse than no oracle.

## What the committed baseline covers, and what it does not

`evals/baseline.json` records the recall of the **committed golden suite**. That is the
instrument's own health check and the floor any change must not fall below.

It is **not** a measurement of the generator. To score *generated* tests you need a
provider key, because generation calls an LLM:

```bash
export OPENAI_API_KEY=…          # or GEMINI_API_KEY / ANTHROPIC_API_KEY
mvn test -Dtest=DefectRecallHarnessTest -Dqalab.eval.generated=true
```

That path is not wired up yet. The harness to run it exists (`DefectRecallScorer` takes
statuses and does not care where they came from); what is missing is the driver that
generates a suite per fixture and runs it through the same two-pass scoring. Until then,
**a prompt change can be measured locally but not in CI** — which is the honest limit of
this task, not a property of the design.

## Raising the baseline

Never automatically. A baseline that moves itself cannot detect a regression. Record the
new number deliberately, in the same commit as the change that earned it.
