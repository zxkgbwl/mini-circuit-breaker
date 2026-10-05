package com.george.circuitbreaker.core;

public enum TransitionReason {
    FAILURE_RATE_EXCEEDED,

    FAILURE_COUNT_EXCEEDED,

    WAIT_DURATION_ELAPSED,

    PROBE_FAILED,

    PROBES_SUCCEEDED

}
