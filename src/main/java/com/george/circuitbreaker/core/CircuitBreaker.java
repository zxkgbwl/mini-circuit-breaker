package com.george.circuitbreaker.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * A lock-free circuit breaker.
 *
 * <p>The breaker is a state machine with three states (see {@link State}):
 * <pre>
 *   CLOSED --(failure rate or count exceeded)--> OPEN
 *   OPEN   --(wait duration elapsed)-----------> HALF_OPEN
 *   HALF_OPEN --(all trial calls succeeded)----> CLOSED
 *   HALF_OPEN --(any trial call failed)--------> OPEN
 * </pre>
 *
 * <p>Usage:
 * <pre>{@code
 * CircuitBreakerConfig config = CircuitBreakerConfig.builder()
 *         .failureRateThreshold(0.5)
 *         .minimumNumberOfCalls(10)
 *         .waitDuration(Duration.ofSeconds(30))
 *         .build();
 * CircuitBreaker breaker = new CircuitBreaker(config);
 *
 * String body = breaker.callWithFallback(() -> client.fetch(), error -> "default");
 * }</pre>
 *
 * <p>Which exceptions count as failures is controlled by the configuration (see
 * {@link CircuitBreakerConfig}). State changes can be observed with
 * {@link #addListener(StateTransitionListener)}.
 *
 * <p>This class is thread-safe. State transitions are performed with compare-and-set, so each
 * transition happens, and is reported to listeners, exactly once.
 */

public class CircuitBreaker {
    private static final Logger LOG= Logger.getLogger(CircuitBreaker.class.getName());

    private final CircuitBreakerConfig config;
    private final LongSupplier nanoClock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final SlidingWindow slidingWindow;
    private final AtomicLong openedAtNanos = new AtomicLong(0L);

    //half-open bookkeeping
    private final AtomicInteger halfOpenPermitsRemaining = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

    private final List<StateTransitionListener> listeners = new CopyOnWriteArrayList<>();

    public CircuitBreaker(CircuitBreakerConfig config) {
        this(config, System::nanoTime);
    }

    //constructor
    CircuitBreaker(CircuitBreakerConfig config, LongSupplier nanoClock) {
        this.config = Objects.requireNonNull(config, "config");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.slidingWindow = new SlidingWindow(config.getSlidingWindowSizeInSeconds(), nanoClock);
    }

    public void addListener(StateTransitionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(StateTransitionListener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        return state.get();
    }

    boolean tryAcquirePermission() {
        State current = state.get();

        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            long elapsed = nanoClock.getAsLong() - openedAtNanos.get();
            if (elapsed < config.getWaitDurationNanos()) {
                return false;
            }
            // Transition to HALF_OPEN
            transition(State.OPEN, State.HALF_OPEN, TransitionReason.WAIT_DURATION_ELAPSED, ()->{
                halfOpenPermitsRemaining.set(config.getPermittedCallsInHalfOpen());
                halfOpenSuccesses.set(0);
            });

            current = state.get();

        }

        if (current == State.HALF_OPEN) {
            int before = halfOpenPermitsRemaining.getAndUpdate(p-> p > 0 ? p-1 : p);
            return before > 0;
        }

        return false;
    }

    void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            int successes = halfOpenSuccesses.incrementAndGet();
            if (successes == config.getPermittedCallsInHalfOpen()) {
                slidingWindow.reset();
                transition(State.HALF_OPEN, State.CLOSED, TransitionReason.PROBES_SUCCEEDED);

            }
        } else  if (current == State.CLOSED){
            slidingWindow.recordSuccess();
        }
    }

    void onFailure() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            tripToOpen(State.HALF_OPEN, TransitionReason.PROBE_FAILED);

        } else if (current == State.CLOSED) {
            slidingWindow.recordFailure();
            checkAndTripIfNecessary();
        }
    }

    void onError(Throwable t) {
        if (config.isRecordedAsFailure(t)) {
            onFailure();
        } else {
            onIgnored();
        }
    }

    void onIgnored() {
       if (state.get() == State.HALF_OPEN) {
            halfOpenPermitsRemaining.incrementAndGet();
        }
    }
    //check if need to trip the circuit breaker to OPEN state based on failure rate or failure count
    private void checkAndTripIfNecessary() {
        SlidingWindow.Snapshot snapshot = slidingWindow.getSnapshot();
        if (snapshot.getTotalCalls() < config.getMinimumNumberOfCalls()) {
            return;
        }
        if (snapshot.getFailureRate() >= config.getFailureRateThreshold()) {
            tripToOpen(State.CLOSED, TransitionReason.FAILURE_RATE_EXCEEDED);

        } else if (snapshot.getTotalFailure() >= config.getFailureThreshold()) {
            tripToOpen(State.CLOSED, TransitionReason.FAILURE_COUNT_EXCEEDED);
        }

    }
    void tripToOpen(State from, TransitionReason reason) {
        openedAtNanos.set(nanoClock.getAsLong());
        transition(from, State.OPEN, reason);
    }

    boolean transition(State from, State to, TransitionReason reason) {
        return transition(from, to, reason, null);
    }

    boolean transition(State from, State to, TransitionReason reason, Runnable afterCas) {
        if (!state.compareAndSet(from, to)) {
            return false;
        }
        if (afterCas != null) {
            afterCas.run();
        }
        publish(new StateTransitionEvent(from, to, reason, nanoClock.getAsLong()));
        return true;
    }

    void publish(StateTransitionEvent event) {
        for (StateTransitionListener listener : listeners) {
            try {
                listener.onStateTransition(event);

            }catch (Exception e) {
                LOG.log(Level.WARNING, "StateTransitionListener failed for " + event, e);
            }
        }
    }


    public <T> T call(Supplier<T> downstreamCall) {
        if (downstreamCall == null) {
            throw new IllegalArgumentException("downstreamCall is null");
        }
        if (!tryAcquirePermission()) {
            throw new CallNotPermittedException("circuit breaker is " + state.get()
            + " --call rejected");
        }
        //got permission
        T result;
        try {
            result = downstreamCall.get();
        }catch (Throwable t) {
            onError(t);
            throw t;
        }
        onSuccess();
        return result;
    }

    public <T> T callWithFallback(Supplier<T> downstreamCall, Function<? super Throwable, ? extends T> fallback) {
        try {
            return call(downstreamCall);
        }catch (CallNotPermittedException e) {
            return fallback.apply(e);
        }catch (Throwable t) {
            if (config.isRecordedAsFailure(t)) {
                return fallback.apply(t);
            }
            throw t;
        }

    }





}
