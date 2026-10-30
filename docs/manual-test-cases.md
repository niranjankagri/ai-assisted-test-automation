# Manual test cases

In this project the manual test cases are the **input** of the AI pipeline, so each one lives in its
own file under [`test-cases/`](../test-cases) (the generator and the quality gate read them there).
This page is the index. Every case has preconditions, steps and expected results in its file; an
automated test links to its case with `@TestCase("TC-101")` and starts its method name with the
case number (`tc101_...`), so a failure in the report leads straight to the case.

## Setup for manual runs

Start LeaveDesk as described in the [README](../README.md) and use the users and password named in
each case (`TestUsers` in the code). The data is reset before each automated test; for a manual run,
restart the app for a clean calendar.

## Cases

| ID | Title | Feature | Priority | Automated by |
|---|---|---|---|---|
| [TC-101](../test-cases/TC-101.md) | Employee signs in and sees the dashboard | Login | High | `LoginTest.tc101_employeeLogsInAndSeesTheirBalance` (by hand) |
| [TC-102](../test-cases/TC-102.md) | Wrong password is refused | Login | High | `LoginTest.tc102_wrongPasswordShowsAnErrorAndStaysOnTheLoginForm` (by hand) |
| [TC-103](../test-cases/TC-103.md) | Employee submits annual leave and the balance drops | Leave requests | High | Drafted by Claude, reviewed by a person (`ai generate TC-103`) |
| [TC-104](../test-cases/TC-104.md) | Overlapping request is refused | Leave requests | High | Drafted by Claude, reviewed by a person |
| [TC-105](../test-cases/TC-105.md) | Manager approves a pending request | Approval | High | Drafted by Claude, reviewed by a person |
| [TC-106](../test-cases/TC-106.md) | Employee cancels a pending request and gets the days back | Leave requests | Medium | Drafted by Claude, reviewed by a person |
| [TC-107](../test-cases/TC-107.md) | Unpaid leave without a reason is refused | Validation | Medium | Drafted by Claude, reviewed by a person |
| [TC-108](../test-cases/TC-108.md) | A request longer than 20 working days is refused | Validation | Medium | Drafted by Claude, reviewed by a person |

TC-103 to TC-108 are automated through the pipeline: `ai generate` drafts the test, the quality
gate checks it (rules, compile, three runs), a person approves it into `src/test`. The generation
prompt asks for the same naming (`tc103_...`). Until a live run is recorded, the drafts in
`ai/samples/` are hand-written sample answers and are never approved into `src/test`.

Not listed: the framework and tooling unit tests (`tooling/`, gate rules, AI gateway); they test this
project's own code, not LeaveDesk.
