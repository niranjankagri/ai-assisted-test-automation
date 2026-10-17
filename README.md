# AI-assisted test automation (Java, Playwright, Claude)

[![CI](https://github.com/niranjankagri/ai-assisted-test-automation/actions/workflows/ci.yml/badge.svg)](https://github.com/niranjankagri/ai-assisted-test-automation/actions/workflows/ci.yml)

A worked example of using an LLM in test automation **with guardrails**: Claude drafts Playwright tests from
plain-language test cases, automatic quality gates decide which drafts are worth a person's time, a person
approves every test that gets merged, and Claude triages failed runs into *product bug*, *test bug*,
*environment* or *flaky*.

Everything is Java: the demo app, the AI tooling (official [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java)),
and the tests (Playwright for Java + TestNG).

> **Status:** the app, the framework, the generator, the quality gate, the approve step, the triage step,
> their tests and CI are complete. The first live generation run (TC-103 to TC-108) and the triage demo
> recording are still to do; until then the CI jobs `ai-replay` and `triage-demo` skip themselves.

## The idea in one picture

```text
 test-cases/TC-103.md            Claude Opus 5.5                    Quality gate (no AI)
 (plain language, like a   ──►   drafts a Playwright test   ──►    1. rules: page objects only, no sleeps,
  TargetProcess test case)       from the case + framework          no JS, no file/network/process access,
                                 code (structured output             every expected result covered
                                 into a Java record)                 2. javac
                                       ▲                             3. three runs against the real app
                                       │ gate findings                       │
                                       └──── fail: up to 2 repair rounds ◄───┤
                                                                             │ pass
                                                                             ▼
                                                    ai-drafts/TC-103/review.md  ──►  a person reviews
                                                    (coverage vs. expected results,     │ ai approve TC-103 <name>
                                                     assumptions, gate table, cost)     ▼
                                                                     src/test/.../tests/ai/  @AiDrafted(model,
                                                                     reviewedBy, reviewedOn) ── runs in CI

 UI run fails ──► evidence per failure (error, console, DOM, screenshot, trace)
              ──► Claude: PRODUCT_BUG | TEST_BUG | ENVIRONMENT | FLAKY_OR_TIMING | NEEDS_HUMAN
                  + quoted evidence + draft bug ticket   ──►  target/ai-triage/triage.md
```

**Why the guardrails matter more than the generation:** an LLM writes a plausible test in seconds. The expensive
failure is a test that *passes and checks the wrong thing*. So the gate rejects drafts that break framework rules,
don't compile or are not stable, the review file puts each expected result of the test case next to the line that
checks it, and nothing reaches `src/test` without a named reviewer.

## What is in the repo

| Part | Where | What it does |
|---|---|---|
| Demo app **LeaveDesk** | `src/main/java/.../app`, `src/main/resources/web` | Leave requests: working-day counting, annual balance, overlap rule, 20-day limit, unpaid needs a reason, manager approval. JDK `HttpServer` + plain JS UI with `data-testid`s. A switch plants an overlap bug for the triage demo. |
| Test framework | `src/test/java/.../framework` | `BaseUiTest` (app in the test JVM, fresh browser context and data reset per test, trace kept on failure), page objects, `FailureEvidenceListener`, `@TestCase`, `@AiDrafted`. |
| Test cases | `test-cases/TC-101..108.md` | Plain-language cases. TC-101/102 are automated by hand (`tests/handwritten/LoginTest.java`, the style reference for Claude). |
| AI gateway | `ai/AiGateway.java` | The only class that calls Claude. Live mode records every answer under `ai/recordings/`; replay mode answers from recordings (CI, no key). Typed answers via structured outputs, cached system prompt, refusal fallback, token and cost log. |
| Generator | `ai/generate` | Draft → gate → repair rounds → `ai-drafts/<id>/` with `review.md`. |
| Quality gate | `ai/gate/QualityGate.java` | Rules, in-process `javac`, three TestNG runs, each with its own evidence folder. |
| Approve | `ai/generate/Approver.java` | Gate-passed drafts only; re-checks the rules after reviewer edits; adds `@AiDrafted`. |
| Triage | `ai/triage` | Reads `target/test-evidence/*`, asks for a verdict with evidence and a bug ticket, writes `target/ai-triage/triage.md`. |
| Prompts | `src/main/resources/prompts/*.md` | Reviewed like code; part of every recording's key. |

## Run it (Windows cmd)

Needs JDK 21+ (the Maven wrapper downloads Maven). First time only, install the browsers:

```bat
cd /d D:\path\to\ai-assisted-test-automation
mvnw.cmd -q compile exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium firefox webkit"
```

| Goal | Command |
|---|---|
| App + tooling tests (no key needed) | `mvnw.cmd test` |
| UI tests (hand-written + approved AI tests) | `mvnw.cmd test -Dsuite=ui` (add `-Dheadless=false` to watch, `-Dbrowser=firefox`) |
| Try the app yourself | `ai app` then open http://localhost:8080 (alice / bob / maria, password `Leave-Test-1`) |
| Which test cases are automated | `ai status` |
| Draft tests with Claude | `ai generate TC-103 TC-104` or `ai generate --open` |
| Re-gate a draft you edited | `ai gate TC-103` |
| Approve a draft | `ai approve TC-103 Your Name` |
| Triage the last UI run | `ai triage` |
| Planted-bug demo | `mvnw.cmd test -Dsuite=ui -Dleavedesk.bug=true` then `ai triage` |

On macOS/Linux use `./mvnw` and `./ai.sh`.

**Live vs. replay.** With `ANTHROPIC_API_KEY` set, `generate` and `triage` call the API and save each answer in
`ai/recordings/`. Without a key they replay those recordings, so anyone who clones the repo (and CI) gets the same
answers for free. A recording is keyed on the model, the prompt, the test case and the framework code sent as
context; change any of them and replay says which recording to refresh. `AI_MODE=replay` forces replay;
`AI_MODEL` switches the model (default `claude-opus-5-5`). Each live call prints its tokens and cost, and appends
them to `target/ai-usage.csv`.

## The quality gate rules

| Rule | Why |
|---|---|
| Package `...tests.ai`, class extends `BaseUiTest`, has `@Test`, linked with `@TestCase("<id>")` | the draft must plug into the framework and be traceable to its case |
| Uses `assertThat` | a test without an assertion proves nothing |
| No `page()`, `getByTestId`, `getByText`, `getByRole`, `.locator(` | only page objects know selectors |
| No `Thread.sleep`, `waitForTimeout` | Playwright assertions already wait |
| No `evaluate(` / init scripts | a UI test must use the UI |
| No `java.io`, `java.nio`, `java.net`, reflection, `ProcessBuilder`, `Runtime`, `System.exit`, `System.getenv` | generated code stays inside the test |
| No `enabled = false`, `SkipException`, catch-all blocks | a draft must run and must not hide failures |
| `coverage` has one line per expected result of the case | the reviewer compares the two lists |
| compiles, then passes **3 of 3** runs | flaky drafts are not review candidates |

The rules are a cheap filter, not a sandbox. The real control is the reviewer, which is why approval is a separate,
named step.

## CI

| Job | What |
|---|---|
| App + AI tooling tests | 48 tests: app rules and API, every gate rule, real gate runs on hand-made drafts, replay, approve marking |
| UI tests ×3 | Chromium, Firefox, WebKit; evidence uploaded on failure |
| AI drafts replayed | regenerates every approved AI test from its recording and gates it again (skips until recordings exist) |
| AI triage demo | planted overlap bug → the overlap test fails → replayed triage must say `PRODUCT_BUG` (skips until recorded) |

No CI job needs an API key.

## Design notes

- **Structured outputs instead of parsing.** Each answer is a Java record (`DraftTest`, `TriageVerdict`); the SDK
  derives the JSON schema from it, so a reply always has every field.
- **Prompt caching.** The system prompt and the framework code are the same for every test case, so they sit behind
  a cache breakpoint; the second and later drafts of a run read them from the cache.
- **Repair rounds are keyed on the draft, not on gate text**, because gate messages can contain ports and timings
  that would make replay impossible.
- **Triage evidence is normalised before hashing** (ports, retry counts, timings), for the same reason.
- **The gate runs the draft in-process** with TestNG and a separate evidence folder per run, so it uses exactly the
  framework the merged test will use.
- **Sequential UI suite.** Every test resets the one in-JVM app, and a Playwright instance must not be shared between
  threads. The suite takes seconds.

## Interview guide

[docs/interview-questions.md](docs/interview-questions.md) ([PDF](docs/interview-questions.pdf), 13 pages):
82 questions on LLM basics for testers, where AI helps in testing, generating tests with guardrails, prompting,
review and risk, failure triage, and reproducibility and cost, each answered with a pointer into this repo.
The design reasoning is in [docs/framework-design.md](docs/framework-design.md).

## Limitations

- The rule stage is text matching; a determined author could get around it. Review stays mandatory.
- One small demo app; a real product needs a larger page-object surface and a way to keep the context size in check.
- Triage sees one failure at a time; it does not correlate failures across tests or runs.
- Recordings make CI deterministic, but they show what the model answered on the recording day, not today.

## License

MIT
