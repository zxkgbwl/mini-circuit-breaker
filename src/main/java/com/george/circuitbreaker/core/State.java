package com.george.circuitbreaker.core;

public enum State {
    /** Normal operation: calls pass through and results are recorded. */
    CLOSED,
    /** Tripped: calls are rejected immediately without touching the downstream. */
    OPEN,
    /** Probing: a limited number of trial calls decide whether to close or re-open. */
    HALF_OPEN
}
