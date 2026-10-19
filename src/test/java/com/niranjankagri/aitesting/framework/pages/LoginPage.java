package com.niranjankagri.aitesting.framework.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** The sign-in form. Locators use data-testid only, so text or layout changes don't break tests. */
public class LoginPage {

    private final Page page;

    /** Wraps the page of the running test. */
    public LoginPage(Page page) {
        this.page = page;
    }

    /** Opens the app's start page (the base URL is set on the browser context). */
    public void open() {
        page.navigate("/");
    }

    /** Fills both fields and presses "Sign in". Does not wait for the result: tests assert it. */
    public void loginAs(String username, String password) {
        page.getByTestId("username").fill(username);
        page.getByTestId("password").fill(password);
        page.getByTestId("login-submit").click();
    }

    /** The red message under the form, for example "Wrong username or password". */
    public Locator errorMessage() {
        return page.getByTestId("login-error");
    }

    /** The whole sign-in section; visible while nobody is logged in. */
    public Locator view() {
        return page.getByTestId("login-view");
    }
}
