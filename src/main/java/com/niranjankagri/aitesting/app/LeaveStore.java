package com.niranjankagri.aitesting.app;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The business rules of LeaveDesk, kept in memory and free of HTTP so they can be unit-tested directly.
 *
 * <p>Rules: a request covers working days (Mon-Fri) only; at most 20 working days per request; an
 * unpaid request needs a reason; annual leave cannot exceed the remaining balance; a new request may
 * not overlap another pending or approved request of the same employee; only a manager can approve
 * or reject, and never their own request; only the owner can cancel, and only while it is pending.
 */
public final class LeaveStore {

    /** Annual-leave days every employee starts the year with. */
    public static final int ANNUAL_ALLOWANCE = 24;
    /** Upper limit of one request, in working days. */
    public static final int MAX_DAYS_PER_REQUEST = 20;

    public enum Role { EMPLOYEE, MANAGER }

    public enum Type { ANNUAL, SICK, UNPAID }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    public record User(String username, String password, String displayName, Role role) {}

    public record Leave(int id, String owner, Type type, LocalDate from, LocalDate to, int days,
                        String reason, Status status) {

        Leave withStatus(Status newStatus) {
            return new Leave(id, owner, type, from, to, days, reason, newStatus);
        }

        /** Pending and approved requests block the calendar; rejected and cancelled ones don't. */
        boolean isActive() {
            return status == Status.PENDING || status == Status.APPROVED;
        }
    }

    /** A rule was broken: the HTTP layer turns this into 400/403/404 with the message as the error text. */
    public static final class RuleException extends RuntimeException {
        private final int httpStatus;

        RuleException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Map<String, User> users = Map.of(
            "alice", new User("alice", "Leave-Test-1", "Alice Meyer", Role.EMPLOYEE),
            "bob", new User("bob", "Leave-Test-1", "Bob Schmidt", Role.EMPLOYEE),
            "maria", new User("maria", "Leave-Test-1", "Maria Keller", Role.MANAGER));

    private final Map<Integer, Leave> leaves = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final boolean overlapBug;

    /**
     * @param overlapBug true switches the overlap rule off. It is the planted product bug that the
     *                   AI triage demo has to recognise as a product bug and not as a test problem.
     */
    public LeaveStore(boolean overlapBug) {
        this.overlapBug = overlapBug;
    }

    /** Back to an empty calendar. Tests call this (through POST /api/test/reset) before each test. */
    public synchronized void reset() {
        leaves.clear();
        nextId.set(1);
    }

    public Optional<User> authenticate(String username, String password) {
        User user = users.get(username == null ? "" : username.trim().toLowerCase());
        return user != null && user.password().equals(password) ? Optional.of(user) : Optional.empty();
    }

    public Optional<User> user(String username) {
        return Optional.ofNullable(users.get(username));
    }

    /** Employees see their own requests, managers see everybody's; newest first. */
    public List<Leave> visibleTo(User user) {
        return leaves.values().stream()
                .filter(l -> user.role() == Role.MANAGER || l.owner().equals(user.username()))
                .sorted(Comparator.comparingInt(Leave::id).reversed())
                .toList();
    }

    /** Annual days still free: the allowance minus every pending or approved annual request. */
    public int remainingAnnualDays(String username) {
        int used = leaves.values().stream()
                .filter(l -> l.owner().equals(username) && l.type() == Type.ANNUAL && l.isActive())
                .mapToInt(Leave::days)
                .sum();
        return ANNUAL_ALLOWANCE - used;
    }

    /** Validates a new request against every rule and stores it as PENDING. */
    public synchronized Leave create(User owner, Type type, LocalDate from, LocalDate to, String reason) {
        if (type == null || from == null || to == null) {
            throw new RuleException(400, "Type, start date and end date are required");
        }
        if (to.isBefore(from)) {
            throw new RuleException(400, "End date must not be before the start date");
        }
        int days = workingDays(from, to);
        if (days == 0) {
            throw new RuleException(400, "The request must include at least one working day");
        }
        if (days > MAX_DAYS_PER_REQUEST) {
            throw new RuleException(400, "A request may cover at most " + MAX_DAYS_PER_REQUEST + " working days");
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (type == Type.UNPAID && cleanReason.isEmpty()) {
            throw new RuleException(400, "Unpaid leave needs a reason");
        }
        if (type == Type.ANNUAL && days > remainingAnnualDays(owner.username())) {
            throw new RuleException(400, "Not enough annual leave left (" + remainingAnnualDays(owner.username())
                    + " days remaining)");
        }
        if (!overlapBug) {
            boolean overlaps = leaves.values().stream()
                    .anyMatch(l -> l.owner().equals(owner.username()) && l.isActive()
                            && !l.to().isBefore(from) && !l.from().isAfter(to));
            if (overlaps) {
                throw new RuleException(400, "These dates overlap another request");
            }
        }
        Leave leave = new Leave(nextId.getAndIncrement(), owner.username(), type, from, to, days, cleanReason,
                Status.PENDING);
        leaves.put(leave.id(), leave);
        return leave;
    }

    /** Manager decision on a pending request of somebody else. */
    public synchronized Leave decide(User manager, int id, boolean approve) {
        if (manager.role() != Role.MANAGER) {
            throw new RuleException(403, "Only a manager can approve or reject requests");
        }
        Leave leave = find(id);
        if (leave.owner().equals(manager.username())) {
            throw new RuleException(403, "You cannot decide on your own request");
        }
        if (leave.status() != Status.PENDING) {
            throw new RuleException(400, "Only pending requests can be decided");
        }
        Leave decided = leave.withStatus(approve ? Status.APPROVED : Status.REJECTED);
        leaves.put(id, decided);
        return decided;
    }

    /** The owner withdraws a request that nobody has decided on yet. */
    public synchronized Leave cancel(User owner, int id) {
        Leave leave = find(id);
        // Somebody else's request answers 404, so the API does not reveal which ids exist.
        if (!leave.owner().equals(owner.username())) {
            throw new RuleException(404, "Request not found");
        }
        if (leave.status() != Status.PENDING) {
            throw new RuleException(400, "Only pending requests can be cancelled");
        }
        Leave cancelled = leave.withStatus(Status.CANCELLED);
        leaves.put(id, cancelled);
        return cancelled;
    }

    private Leave find(int id) {
        Leave leave = leaves.get(id);
        if (leave == null) {
            throw new RuleException(404, "Request not found");
        }
        return leave;
    }

    /** Monday to Friday between both dates, both included. Public holidays are out of scope. */
    public static int workingDays(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }
}
