package com.george.circuitbreaker.core;

import java.util.Objects;
import java.util.function.Consumer;

public class LoggingStateTransitionsListener implements StateTransitionListener {
    private final String name;
    private final Consumer<String> sink;

    public LoggingStateTransitionsListener(String name, Consumer<String> sink) {
        this.name = Objects.requireNonNull(name);
        this.sink = Objects.requireNonNull(sink);
    }

    public static LoggingStateTransitionsListener toStdout(String name) {
        return new LoggingStateTransitionsListener(name, System.out::println);
    }

    @Override
    public void onStateTransition(StateTransitionEvent event) {
        sink.accept(String.format("[CircuitBreaker:%s] %s -> %s (%s)",
                        name, event.getFrom(), event.getTo(), event.getReason()));
    }
}
