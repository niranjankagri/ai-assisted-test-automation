package com.niranjankagri.aitesting.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The HTTP layer of LeaveDesk: status codes, JSON shape, authentication, static files. */
@Test(groups = "app")
public class LeaveDeskApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private LeaveDeskServer app;

    /** A response: status code plus parsed JSON body. */
    private record Reply(int status, JsonNode body) {}

    @BeforeClass
    public void startApp() throws IOException {
        app = LeaveDeskServer.start(0, false);
    }

    @AfterClass(alwaysRun = true)
    public void stopApp() {
        app.close();
    }

    @BeforeMethod
    public void resetData() throws Exception {
        call("POST", "/api/test/reset", null, null);
    }

    public void healthAnswersOk() throws Exception {
        assertEquals(call("GET", "/api/health", null, null).body().get("status").asText(), "ok");
    }

    public void loginReturnsTokenAndProfile() throws Exception {
        Reply r = call("POST", "/api/login", "{\"username\":\"maria\",\"password\":\"Leave-Test-1\"}", null);
        assertEquals(r.status(), 200);
        assertEquals(r.body().get("role").asText(), "MANAGER");
        assertTrue(r.body().get("token").asText().length() > 20);
    }

    public void wrongPasswordIs401() throws Exception {
        Reply r = call("POST", "/api/login", "{\"username\":\"alice\",\"password\":\"nope\"}", null);
        assertEquals(r.status(), 401);
        assertEquals(r.body().get("error").asText(), "Wrong username or password");
    }

    public void leavesNeedAToken() throws Exception {
        assertEquals(call("GET", "/api/leaves", null, null).status(), 401);
        assertEquals(call("GET", "/api/leaves", null, "made-up-token").status(), 401);
    }

    public void createListApproveFlow() throws Exception {
        String alice = token("alice");
        Reply created = call("POST", "/api/leaves",
                "{\"type\":\"ANNUAL\",\"from\":\"2026-11-02\",\"to\":\"2026-11-06\"}", alice);
        assertEquals(created.status(), 201);
        assertEquals(created.body().get("days").asInt(), 5);
        int id = created.body().get("id").asInt();

        assertEquals(call("GET", "/api/me", null, alice).body().get("annualRemaining").asInt(), 19);
        Reply approved = call("POST", "/api/leaves/" + id + "/approve", null, token("maria"));
        assertEquals(approved.body().get("status").asText(), "APPROVED");
        assertEquals(call("GET", "/api/leaves", null, alice).body().get(0).get("status").asText(), "APPROVED");
    }

    public void ruleViolationsAre400WithAMessage() throws Exception {
        Reply r = call("POST", "/api/leaves", "{\"type\":\"UNPAID\",\"from\":\"2026-11-02\",\"to\":\"2026-11-02\"}",
                token("alice"));
        assertEquals(r.status(), 400);
        assertEquals(r.body().get("error").asText(), "Unpaid leave needs a reason");
    }

    public void badInputIs400NotAServerError() throws Exception {
        String alice = token("alice");
        assertEquals(call("POST", "/api/leaves", "{not json", alice).status(), 400);
        assertEquals(call("POST", "/api/leaves", "{\"type\":\"HOLIDAY\",\"from\":\"2026-11-02\",\"to\":\"2026-11-02\"}",
                alice).body().get("error").asText(), "Unknown leave type: HOLIDAY");
        assertEquals(call("POST", "/api/leaves", "{\"type\":\"SICK\",\"from\":\"02.11.2026\",\"to\":\"2026-11-02\"}",
                alice).body().get("error").asText(), "The start date must look like 2026-11-30");
    }

    public void employeeCannotApprove() throws Exception {
        String alice = token("alice");
        int id = call("POST", "/api/leaves", "{\"type\":\"SICK\",\"from\":\"2026-11-02\",\"to\":\"2026-11-02\"}", alice)
                .body().get("id").asInt();
        assertEquals(call("POST", "/api/leaves/" + id + "/approve", null, token("bob")).status(), 403);
    }

    public void servesTheUiButNoOtherFiles() throws Exception {
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(app.baseUrl() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(page.statusCode(), 200);
        assertTrue(page.body().contains("data-testid=\"login-view\""));
        HttpResponse<String> sneaky = http.send(
                HttpRequest.newBuilder(URI.create(app.baseUrl() + "/../pom.xml")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(sneaky.statusCode(), 404);
    }

    private String token(String user) throws Exception {
        return call("POST", "/api/login", "{\"username\":\"" + user + "\",\"password\":\"Leave-Test-1\"}", null)
                .body().get("token").asText();
    }

    private Reply call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(app.baseUrl() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), JSON.readTree(res.body()));
    }
}
