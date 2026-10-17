package com.niranjankagri.aitesting.framework.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * The page after sign-in: balance, the new-request form (employees only) and the request table.
 *
 * <p>Methods that return a {@link Locator} are for assertions with Playwright's auto-retrying
 * {@code assertThat(...)}; methods that return nothing perform an action.
 */
public class DashboardPage {

    private final Page page;

    public DashboardPage(Page page) {
        this.page = page;
    }

    /** Waits until the dashboard is visible (after login or a reload). */
    public void waitUntilShown() {
        page.getByTestId("dashboard-view").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /** The whole dashboard section. */
    public Locator view() {
        return page.getByTestId("dashboard-view");
    }

    /** Top-bar text such as "Alice Meyer (Employee)". */
    public Locator currentUser() {
        return page.getByTestId("current-user");
    }

    /** Remaining annual-leave days, as shown in the balance line. */
    public Locator annualRemaining() {
        return page.getByTestId("annual-remaining");
    }

    /**
     * Fills the new-request form and submits it.
     *
     * @param type   ANNUAL, SICK or UNPAID
     * @param from   start date, ISO format (2026-11-02)
     * @param to     end date, ISO format
     * @param reason free text; may be empty
     */
    public void submitRequest(String type, String from, String to, String reason) {
        page.getByTestId("leave-type").selectOption(type);
        page.getByTestId("leave-from").fill(from);
        page.getByTestId("leave-to").fill(to);
        page.getByTestId("leave-reason").fill(reason);
        page.getByTestId("leave-submit").click();
    }

    /** The new-request form; hidden for managers. */
    public Locator requestForm() {
        return page.getByTestId("leave-form");
    }

    /** Red message under the form when the app refuses a request. */
    public Locator formError() {
        return page.getByTestId("leave-error");
    }

    /** Green message under the form, for example "Request #1 submitted (5 working days)". */
    public Locator formSuccess() {
        return page.getByTestId("leave-success");
    }

    /** All rows of the request table. */
    public Locator rows() {
        return page.getByTestId("leave-rows").locator("tr");
    }

    /** One row of the request table, by request id. */
    public Locator row(int requestId) {
        return page.getByTestId("leave-row-" + requestId);
    }

    /** Status badge of one request: PENDING, APPROVED, REJECTED or CANCELLED. */
    public Locator status(int requestId) {
        return page.getByTestId("leave-status-" + requestId);
    }

    /** "No requests yet." text shown for an empty table. */
    public Locator emptyListMessage() {
        return page.getByTestId("empty-list");
    }

    /** Approve button of a request (managers only, pending requests of other people). */
    public Locator approveButton(int requestId) {
        return page.getByTestId("approve-" + requestId);
    }

    /** Reject button of a request (managers only). */
    public Locator rejectButton(int requestId) {
        return page.getByTestId("reject-" + requestId);
    }

    /** Cancel button of a request (owner only, while pending). */
    public Locator cancelButton(int requestId) {
        return page.getByTestId("cancel-" + requestId);
    }

    public void approve(int requestId) {
        approveButton(requestId).click();
    }

    public void reject(int requestId) {
        rejectButton(requestId).click();
    }

    public void cancel(int requestId) {
        cancelButton(requestId).click();
    }

    /** Logs out; the sign-in form shows again. */
    public void logout() {
        page.getByTestId("logout").click();
    }
}
