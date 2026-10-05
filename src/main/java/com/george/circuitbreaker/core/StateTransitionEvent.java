package com.george.circuitbreaker.core;

import java.util.Objects;

public final class StateTransitionEvent {
        private final State from;
        private final State to;
        private final TransitionReason reason;
        private final long timestampNanos;

    public StateTransitionEvent(State from, State to, TransitionReason reason, long timestampNanos) {
        this.from = Objects.requireNonNull(from);
        this.to=  Objects.requireNonNull(to);
        this.reason = Objects.requireNonNull(reason);
        this.timestampNanos = timestampNanos;
    }

    public State getFrom() {
        return from;
    }

    public State getTo() {
        return to;
    }

    public TransitionReason getReason() {
        return reason;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    @Override
    public String toString() {
        return "StateTransitionEvent{"+from +"->" + to
                +", reason=" + reason + ", timestampNanos=" + timestampNanos+ '}';
    }
}
