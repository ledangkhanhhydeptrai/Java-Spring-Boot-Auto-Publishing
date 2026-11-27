package com.socialhub.mockqueue;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synthetic-only state machine. Never invokes a real publisher.
 */
public final class MockJobEngine {
    public enum Outcome {SUCCESS, TRANSIENT_FAILURE, TIMEOUT_AFTER_ACCEPT}

    public enum Status {COMPLETED, RETRY_PENDING, NEEDS_REVIEW}

    public record Result(String jobId, Status status, int attempts, boolean accepted,
                         @JsonSerialize(using = ViDisplayTextSerializer.class) String detail) {
    }

    /**
     * Distinguishes a redelivered terminal job from a newly processed one.
     */
    public record Handled(Result result, boolean duplicate) {
    }

    private final Map<String, Result> terminal = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> accepted = new ConcurrentHashMap<>();

    public synchronized Result handle(String jobId, Outcome outcome) {
        return handleTracked(jobId, outcome).result();
    }

    public synchronized Handled handleTracked(String jobId, Outcome outcome) {
        if (jobId == null || !jobId.matches("MOCK-[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Only synthetic MOCK-* identifiers are allowed");
        Result previous = terminal.get(jobId);
        if (previous != null) return new Handled(previous, true);
        int n = attempts.merge(jobId, 1, Integer::sum);
        Result result;
        switch (outcome) {
            case SUCCESS -> {
                accepted.put(jobId, true);
                result = new Result(jobId, Status.COMPLETED, n, true, "Synthetic accepted; no external request");
            }
            case TIMEOUT_AFTER_ACCEPT -> {
                accepted.put(jobId, true);
                result = new Result(jobId, Status.NEEDS_REVIEW, n, true,
                        "Outcome unknown; never auto-retry after acceptance");
            }
            case TRANSIENT_FAILURE -> {
                if (n >= 3) result = new Result(jobId, Status.NEEDS_REVIEW, n, false, "Retry limit reached");
                else result = new Result(jobId, Status.RETRY_PENDING, n, false, "Retry allowed");
            }
            default -> throw new IllegalStateException();
        }
        if (result.status() != Status.RETRY_PENDING) terminal.put(jobId, result);
        return new Handled(result, false);
    }

    public int acceptedCount() {
        return accepted.size();
    }

    public int terminalCount() {
        return terminal.size();
    }
}
