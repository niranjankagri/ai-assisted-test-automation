package com.niranjankagri.aitesting.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.aitesting.app.LeaveStore.Leave;
import com.niranjankagri.aitesting.app.LeaveStore.RuleException;
import com.niranjankagri.aitesting.app.LeaveStore.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LeaveDesk: the small leave-request web app the tests run against. It lives in this repo so the
 * tests never depend on a public demo site, and the planted bug for the triage demo is under our
 * control. JDK HttpServer only: no web framework, starts in milliseconds inside the test JVM.
 *
 * <p>API (JSON): POST /api/login, GET /api/me, GET|POST /api/leaves, POST /api/leaves/{id}/approve,
 * POST /api/leaves/{id}/reject, POST /api/leaves/{id}/cancel, POST /api/test/reset, GET /api/health.
 * Everything else is served from the classpath folder /web (the single-page UI).
 */
public final class LeaveDeskServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DECISION = Pattern.compile("^/api/leaves/(\\d+)/(approve|reject|cancel)$");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8");

    private final HttpServer server;
    private final LeaveStore store;
    /** Bearer token to username. Tokens survive a data reset, so a logged-in browser stays logged in. */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    /**
     * Binds to 127.0.0.1 only (a test app must not be reachable from the network) and handles requests on 8
     * threads.
     */
    private LeaveDeskServer(int port, boolean overlapBug) throws IOException {
        this.store = new LeaveStore(overlapBug);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
    }

    /**
     * Starts the app. Port 0 picks a free port (what the tests use).
     *
     * @param overlapBug true plants the overlap bug (see {@link LeaveStore#LeaveStore(boolean)})
     */
    public static LeaveDeskServer start(int port, boolean overlapBug) throws IOException {
        LeaveDeskServer app = new LeaveDeskServer(port, overlapBug);
        app.server.start();
        return app;
    }

    /** http://127.0.0.1:<port>, the port the server really got. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Stops the server at once and its request threads with it. */
    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    /**
     * Every request: files for non-API paths, otherwise the API, with broken rules and bad input answered as JSON
     * errors (400/403/404) instead of server errors.
     */
    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            try {
                route(ex, method, path);
            } catch (RuleException e) {
                send(ex, e.httpStatus(), Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                send(ex, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    /**
     * The public endpoints (health, data reset for tests, login); everything else needs a valid token (401
     * otherwise).
     */
    private void route(HttpExchange ex, String method, String path) throws IOException {
        if (method.equals("GET") && path.equals("/api/health")) {
            send(ex, 200, Map.of("status", "ok"));
        } else if (method.equals("POST") && path.equals("/api/test/reset")) {
            store.reset();
            send(ex, 200, Map.of("reset", true));
        } else if (method.equals("POST") && path.equals("/api/login")) {
            login(ex);
        } else {
            // Every other endpoint needs a logged-in user.
            Optional<User> user = currentUser(ex);
            if (user.isEmpty()) {
                send(ex, 401, Map.of("error", "Please log in"));
                return;
            }
            routeAuthenticated(ex, method, path, user.get());
        }
    }

    /**
     * The endpoints for a logged-in user: profile, list, create, and approve / reject / cancel by id; anything
     * else is 404.
     */
    private void routeAuthenticated(HttpExchange ex, String method, String path, User user) throws IOException {
        Matcher decision = DECISION.matcher(path);
        if (method.equals("GET") && path.equals("/api/me")) {
            send(ex, 200, me(user));
        } else if (method.equals("GET") && path.equals("/api/leaves")) {
            send(ex, 200, store.visibleTo(user).stream().map(LeaveDeskServer::toJson).toList());
        } else if (method.equals("POST") && path.equals("/api/leaves")) {
            JsonNode body = readBody(ex);
            Leave leave = store.create(user,
                    parseType(text(body, "type")),
                    parseDate(text(body, "from"), "start date"),
                    parseDate(text(body, "to"), "end date"),
                    text(body, "reason"));
            send(ex, 201, toJson(leave));
        } else if (method.equals("POST") && decision.matches()) {
            int id = Integer.parseInt(decision.group(1));
            Leave leave = switch (decision.group(2)) {
                case "approve" -> store.decide(user, id, true);
                case "reject" -> store.decide(user, id, false);
                default -> store.cancel(user, id);
            };
            send(ex, 200, toJson(leave));
        } else {
            send(ex, 404, Map.of("error", "Not found"));
        }
    }

    /**
     * Checks the credentials and answers with the profile plus a new random token, or 401 with the error text the
     * UI shows.
     */
    private void login(HttpExchange ex) throws IOException {
        JsonNode body = readBody(ex);
        Optional<User> user = store.authenticate(text(body, "username"), text(body, "password"));
        if (user.isEmpty()) {
            send(ex, 401, Map.of("error", "Wrong username or password"));
            return;
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, user.get().username());
        Map<String, Object> out = new LinkedHashMap<>(me(user.get()));
        out.put("token", token);
        send(ex, 200, out);
    }

    /** The profile the UI shows: user name, display name, role and annual days left. */
    private Map<String, Object> me(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.username());
        out.put("displayName", user.displayName());
        out.put("role", user.role().name());
        out.put("annualRemaining", store.remainingAnnualDays(user.username()));
        return out;
    }

    /**
     * The user behind the request's "Authorization: Bearer <token>" header, or empty for a missing or unknown
     * token.
     */
    private Optional<User> currentUser(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String username = sessions.get(header.substring("Bearer ".length()).trim());
        return username == null ? Optional.empty() : store.user(username);
    }

    /** A request as the JSON object the API returns, with ISO dates and enum names. */
    private static Map<String, Object> toJson(Leave l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", l.id());
        out.put("owner", l.owner());
        out.put("type", l.type().name());
        out.put("from", l.from().toString());
        out.put("to", l.to().toString());
        out.put("days", l.days());
        out.put("reason", l.reason());
        out.put("status", l.status().name());
        return out;
    }

    /**
     * The leave type from the request body, case-insensitive; empty means missing (the store reports that), an
     * unknown value is a 400.
     */
    private static LeaveStore.Type parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LeaveStore.Type.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown leave type: " + value);
        }
    }

    /** An ISO date from the request body; a wrong format is a 400 whose message shows the expected format. */
    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The " + label + " must look like 2026-11-30");
        }
    }

    /** A field as text, or null when it is missing or JSON null. */
    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** The request body as JSON; an empty body is an empty object, invalid JSON a 400. */
    private static JsonNode readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                return JSON.createObjectNode();
            }
            try {
                return JSON.readTree(bytes);
            } catch (IOException e) {
                throw new IllegalArgumentException("The request body is not valid JSON");
            }
        }
    }

    /** Writes a JSON response with the given status. */
    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Serves the UI files from /web on the classpath. Only plain file names are allowed, so a path like
     * /../pom.xml gets 404 instead of a file from outside the web folder.
     */
    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        String file = path.equals("/") ? "index.html" : path.substring(1);
        // Only plain file names from /web; anything with a folder or ".." is refused.
        InputStream in = file.matches("[a-z-]+\\.(html|js|css)")
                ? LeaveDeskServer.class.getResourceAsStream("/web/" + file) : null;
        if (in == null) {
            byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, notFound.length);
            ex.getResponseBody().write(notFound);
            return;
        }
        try (in) {
            byte[] bytes = in.readAllBytes();
            String ext = file.substring(file.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.get(ext));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }

    /** Stand-alone start for manual exploring: `ai app` (see Cli). */
    public static void runForever(int port, boolean overlapBug) throws IOException, InterruptedException {
        try (LeaveDeskServer app = start(port, overlapBug)) {
            System.out.println("LeaveDesk running at " + app.baseUrl()
                    + (overlapBug ? "  (planted overlap bug ON)" : "")
                    + "\nUsers: alice / bob (employees), maria (manager); password Leave-Test-1. Ctrl+C stops it.");
            Thread.currentThread().join();
        }
    }
}
