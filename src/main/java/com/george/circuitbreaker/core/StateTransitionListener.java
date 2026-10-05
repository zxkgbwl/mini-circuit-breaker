package com.george.circuitbreaker.core;

@FunctionalInterface
public interface StateTransitionListener {
    void onStateTransition(StateTransitionEvent event);
}
