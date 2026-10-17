# Framework design

This document explains why the repo is built the way it is. The README covers what it does and how to run it.

## 1. The problem this solves

Turning a manual test case into an automated UI test is mostly mechanical: read the steps, find the page object
methods, write the assertions. An LLM does that part fast. What it does not do reliably is notice when its test is
subtly wrong: an assertion on the wrong element, a missed expected result, a check that can never fail. A pipeline
that only generates tests moves that risk into the suite, where it is hard to see.

So the design question was not "how do we generate tests?" but "how do we make sure a person spends review time
only on drafts that are worth it, and can review them quickly?" Every part below answers that question.

## 2. Pipeline

```text
test case ──► draft (Claude) ──► gate: rules ─► compile ─► 3 runs ──► review.md ──► approve (person) ──► src/test
                    ▲                                         │
                    └────────── findings, max 2 repairs ◄─────┘
```

| Stage | Decides | Cost of a wrong decision |
|---|---|---|
| Rules | framework conventions and safety, from text | low: a false alarm costs one repair round |
| Compile | the draft is valid Java against the real framework | none |
| Three runs | the draft passes and is stable | low |
| Review | the draft checks what the test case says | high: this is where a wrong test is stopped |

The automatic stages are ordered cheapest first and stop at the first failure (a failed run skips the other runs).

## 3. Why the app lives in the repo

The tests need behaviour that a public demo site cannot give: a product bug that can be switched on for the triage
demo, a data reset per test, and the guarantee that the site still exists next year. LeaveDesk is a JDK `HttpServer`
with no framework, so it starts inside the test JVM in milliseconds on a free port. The rules (working days,
balance, overlap, 20-day limit, unpaid needs a reason, manager approval) give the test cases real logic to check.

## 4. The AI gateway

All model calls go through `AiGateway`. Keeping one door has four effects:

1. **Typed answers.** `client.messages().create(...)` with `outputConfig(DraftTest.class)` makes the API return JSON
   that matches the record's schema (structured outputs). No regex over free text, no "the model forgot a field".
2. **Recording and replay.** In live mode every answer is written to `ai/recordings/<purpose>/<label>-<hash>.json`.
   The hash covers the model and everything that should invalidate the answer: the system prompt, the test case,
   the framework code sent as context. Replay mode answers from these files. CI and every clone get identical
   answers without a key, and a changed prompt shows up as a missing recording instead of a silent drift.
3. **Cost visibility.** Each live call logs input, cache, output tokens and an estimate in USD.
4. **One place for failure handling.** `refusal` and `max_tokens` stop reasons become clear errors, never a half
   answer; a server-side fallback is requested for refusals.

### What goes into the context

The generator sends the base test, the page objects, the annotations, the users, one hand-written test as the style
reference and the page's HTML. The HTML helps the model understand the screen; the rules forbid using selectors
directly, so it cannot leak into the test. The system prompt and this context are identical for every test case and
sit behind a cache breakpoint.

### Replay keys must ignore volatile data

A repair round sends the gate findings back. Those findings can contain run-specific values, so the repair request
is keyed on the *draft being repaired*, which is deterministic. Triage evidence is normalised (ports, retry counts,
milliseconds) before hashing for the same reason. The screenshot is sent but not hashed.

## 5. The quality gate

### Rules

Text rules are fast and explain themselves ("found `Thread.sleep` on line 18 (Playwright assertions already wait)"),
which matters because the same message goes back to the model in a repair round. They encode three things:

- **Framework conventions**: package, base class, page objects only, auto-waiting assertions.
- **Safety**: no file, network, process, environment or reflection access. Generated code is compiled and run on a
  developer machine and in CI, so it must not be able to do more than a UI test needs.
- **Reviewability**: the `coverage` list must have exactly one line per expected result of the case.

They are a filter, not a sandbox. That is stated in the code and the README on purpose, so nobody mistakes them for
a security boundary.

### Compile and run in-process

`javax.tools.JavaCompiler` compiles the draft against `java.class.path` (the CLI runs with the test classpath for
this reason). TestNG then runs the compiled class three times through a child class loader, each run with its own
evidence folder via the `evidence.dir` system property. Running in-process means the draft uses the real
`BaseUiTest`, the real page objects and the real listener: what passes the gate behaves the same after approval.

## 6. Review and approval

`review.md` is written for a reviewer with five minutes: the test case's expected results next to the model's
description of how each is checked, the assumptions the model had to make, page object methods it missed, the gate
table and the cost. The checklist at the top lists the questions a gate cannot answer.

`ai approve <id> <name>` refuses drafts that failed the gate, re-runs the rule stage (the reviewer may have edited the
file), and adds `@AiDrafted(model, reviewedBy, reviewedOn)`. The annotation makes AI-written tests visible in the code
and in failure evidence, which the triage step reports.

## 7. Failure triage

`FailureEvidenceListener` saves, per failed test, what a person would look at: the error and stack, the browser
console, the URL, the DOM, a full-page screenshot and the Playwright trace. Triage sends the same evidence plus the
test case and the test code, and asks for one of five categories with quoted evidence. `NEEDS_HUMAN` is an explicit
option and the prompt says a wrong confident verdict costs more than an honest "not sure", because a `PRODUCT_BUG`
verdict creates work for developers.

The planted overlap bug gives a reproducible check: with the rule off, the overlap test fails, and the expected
verdict is `PRODUCT_BUG`. CI replays that triage and fails if the verdict changes.

## 8. Things deliberately left out

- **No autonomous agent.** The model gets one well-defined task per call. An agent that browses the app and writes
  tests on its own is possible, but harder to review and to make reproducible.
- **No self-healing locators.** Healing hides UI changes that a person should see; failures go to triage instead.
- **No auto-merge.** Even a perfect gate result is a review candidate, not a merge.
