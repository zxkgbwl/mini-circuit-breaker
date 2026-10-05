package com.george.circuitbreaker.core;

/**
 * Thrown when a {@link CircuitBreaker} rejects a call without executing it, because the breaker is
 * OPEN, or HALF_OPEN with no trial permits left.
 *
 * <p>It is raised by the breaker itself, never by the protected downstream call.
 */
public class CallNotPermittedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CallNotPermittedException(String message)
    {
        super(message);
    }
}
