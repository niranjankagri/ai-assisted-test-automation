package com.niranjankagri.aitesting.app;

import com.niranjankagri.aitesting.app.LeaveStore.Leave;
import com.niranjankagri.aitesting.app.LeaveStore.RuleException;
import com.niranjankagri.aitesting.app.LeaveStore.Status;
import com.niranjankagri.aitesting.app.LeaveStore.Type;
import com.niranjankagri.aitesting.app.LeaveStore.User;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.LocalDate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

/** The business rules of LeaveDesk, tested without HTTP or a browser. */
@Test(groups = "app")
public class LeaveStoreTest {

    // 2026-11-02 is a Monday; fixed dates keep the working-day counts stable.
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    private LeaveStore store;
    private User alice;
    private User bob;
    private User maria;

    @BeforeMethod
    public void freshStore() {
        store = new LeaveStore(false);
        alice = store.user("alice").orElseThrow();
        bob = store.user("bob").orElseThrow();
        maria = store.user("maria").orElseThrow();
    }

    public void countsOnlyMondayToFriday() {
        assertEquals(LeaveStore.workingDays(MON, MON.plusDays(4)), 5, "Mon-Fri");
        assertEquals(LeaveStore.workingDays(MON, MON.plusDays(6)), 5, "Mon-Sun: the weekend is free");
        assertEquals(LeaveStore.workingDays(MON.plusDays(5), MON.plusDays(6)), 0, "Sat-Sun");
    }

    public void annualRequestReducesTheBalanceWhilePending() {
        Leave leave = store.create(alice, Type.ANNUAL, MON, MON.plusDays(4), "");
        assertEquals(leave.status(), Status.PENDING);
        assertEquals(leave.days(), 5);
        assertEquals(store.remainingAnnualDays("alice"), LeaveStore.ANNUAL_ALLOWANCE - 5);
    }

    public void sickLeaveDoesNotTouchTheBalance() {
        store.create(alice, Type.SICK, MON, MON.plusDays(1), "");
        assertEquals(store.remainingAnnualDays("alice"), LeaveStore.ANNUAL_ALLOWANCE);
    }

    public void rejectsEndBeforeStart() {
        RuleException e = expectThrows(RuleException.class,
                () -> store.create(alice, Type.ANNUAL, MON.plusDays(1), MON, ""));
        assertEquals(e.getMessage(), "End date must not be before the start date");
    }

    public void rejectsAWeekendOnlyRequest() {
        assertThrows(RuleException.class, () -> store.create(alice, Type.SICK, MON.plusDays(5), MON.plusDays(6), ""));
    }

    public void allowsExactlyTwentyWorkingDaysButNotTwentyOne() {
        // Four full weeks = 20 working days; one more Monday makes 21.
        store.create(alice, Type.SICK, MON, MON.plusDays(25), "");
        RuleException e = expectThrows(RuleException.class,
                () -> store.create(bob, Type.SICK, MON, MON.plusDays(28), ""));
        assertEquals(e.getMessage(), "A request may cover at most 20 working days");
    }

    public void unpaidLeaveNeedsAReason() {
        RuleException e = expectThrows(RuleException.class,
                () -> store.create(alice, Type.UNPAID, MON, MON, "   "));
        assertEquals(e.getMessage(), "Unpaid leave needs a reason");
    }

    public void annualLeaveCannotExceedTheBalance() {
        store.create(alice, Type.ANNUAL, MON, MON.plusDays(18), "");          // 15 days
        RuleException e = expectThrows(RuleException.class,
                () -> store.create(alice, Type.ANNUAL, MON.plusDays(21), MON.plusDays(32), "")); // 10 days
        assertEquals(e.getMessage(), "Not enough annual leave left (9 days remaining)");
    }

    public void overlappingRequestsOfTheSameEmployeeAreRejected() {
        store.create(alice, Type.ANNUAL, MON, MON.plusDays(4), "");
        RuleException e = expectThrows(RuleException.class,
                () -> store.create(alice, Type.SICK, MON.plusDays(4), MON.plusDays(7), ""));
        assertEquals(e.getMessage(), "These dates overlap another request");
    }

    public void otherEmployeesAndCancelledRequestsDoNotBlockTheDates() {
        Leave first = store.create(alice, Type.ANNUAL, MON, MON.plusDays(4), "");
        store.create(bob, Type.ANNUAL, MON, MON.plusDays(4), "");
        store.cancel(alice, first.id());
        store.create(alice, Type.ANNUAL, MON, MON.plusDays(4), "");
    }

    public void plantedBugLetsOverlapsThrough() {
        LeaveStore buggy = new LeaveStore(true);
        User a = buggy.user("alice").orElseThrow();
        buggy.create(a, Type.ANNUAL, MON, MON.plusDays(4), "");
        buggy.create(a, Type.ANNUAL, MON.plusDays(2), MON.plusDays(3), "");
        assertEquals(buggy.visibleTo(a).size(), 2);
    }

    public void managerApprovesAndRejects() {
        Leave one = store.create(alice, Type.ANNUAL, MON, MON, "");
        Leave two = store.create(bob, Type.ANNUAL, MON, MON, "");
        assertEquals(store.decide(maria, one.id(), true).status(), Status.APPROVED);
        assertEquals(store.decide(maria, two.id(), false).status(), Status.REJECTED);
        assertEquals(store.remainingAnnualDays("bob"), LeaveStore.ANNUAL_ALLOWANCE, "a rejection frees the days");
    }

    public void employeesCannotDecideAndDecidedRequestsStayDecided() {
        Leave leave = store.create(alice, Type.ANNUAL, MON, MON, "");
        assertEquals(expectThrows(RuleException.class, () -> store.decide(bob, leave.id(), true)).httpStatus(), 403);
        store.decide(maria, leave.id(), true);
        assertEquals(expectThrows(RuleException.class, () -> store.decide(maria, leave.id(), false)).httpStatus(), 400);
    }

    public void onlyTheOwnerCancelsAndOnlyWhilePending() {
        Leave leave = store.create(alice, Type.ANNUAL, MON, MON, "");
        assertEquals(expectThrows(RuleException.class, () -> store.cancel(bob, leave.id())).httpStatus(), 404);
        store.decide(maria, leave.id(), true);
        assertEquals(expectThrows(RuleException.class, () -> store.cancel(alice, leave.id())).getMessage(),
                "Only pending requests can be cancelled");
    }

    public void managersSeeEveryoneEmployeesOnlyThemselves() {
        store.create(alice, Type.SICK, MON, MON, "");
        store.create(bob, Type.SICK, MON, MON, "");
        assertEquals(store.visibleTo(maria).size(), 2);
        assertEquals(store.visibleTo(alice).size(), 1);
    }

    public void loginIsCaseInsensitiveForTheNameOnly() {
        assertEquals(store.authenticate(" Alice ", "Leave-Test-1").orElseThrow().username(), "alice");
        assertEquals(store.authenticate("alice", "leave-test-1"), java.util.Optional.empty());
    }
}
