package com.george.circuitbreaker.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerListenerTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final AtomicLong now = new AtomicLong(0L);
    private final List<StateTransitionEvent> events = new CopyOnWriteArrayList<>();

    //------------------helpers-----------------------
    private CircuitBreakerConfig.Builder baseConfig() {
        return CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .failureRateThreshold(0.5)
                .minimumNumberOfCalls(3)
                .slidingWindowSizeInSeconds(5)
                .waitDuration(WAIT)
                .permittedCallsInHalfOpen(2);
    }

    //break with fake clock and a recoding listener already attached
    private CircuitBreaker breaker(CircuitBreakerConfig.Builder b) {
        CircuitBreaker cb = new CircuitBreaker(b.build(), now::get);
        cb.addListener(events::add);
        return cb;
    }

    private void fail(CircuitBreaker cb) {
        try {
            cb.call(()-> {
                throw new IllegalStateException("boom");
            });

            Assertions.fail("Excepted an exception to be thrown");

        } catch (IllegalStateException e) {

        } catch (CallNotPermittedException e) {

        }

    }

    private void ok(CircuitBreaker cb) {
        cb.call(() -> "ok");
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    private List<String> transitions() {
        return events.stream()
                .map(e->e.getFrom()+"->"+e.getTo())
                .collect(Collectors.toList());
    }

    private static void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Void> c = () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    task.run();
                    return null;
                };
                futures.add(pool.submit(c));
            }
            assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
            startLatch.countDown();
            for (Future<Void> f :futures) {
                f.get(10, TimeUnit.SECONDS);
            }

        }finally {
            pool.shutdown();
        }
    }

    //===================event sequence and reasons=======================

    @Test
    @DisplayName("whole cycle: ")
    void fullCycleEmitsEventsInOrder() {
        CircuitBreaker cb = breaker(baseConfig());
        for (int i = 0; i < 3; i++) {
            fail(cb);
        }
        advance(WAIT);
        ok(cb);
        ok(cb);

        assertEquals(List.of("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED"), transitions());
        assertEquals(TransitionReason.FAILURE_RATE_EXCEEDED, events.get(0).getReason());
        assertEquals(TransitionReason.WAIT_DURATION_ELAPSED, events.get(1).getReason());
        assertEquals(TransitionReason.PROBES_SUCCEEDED, events.get(2).getReason());
    }

    @Test
    @DisplayName("reason = FALIURE_RATE_EXCEEDED when failure rate exceeded")
    void reasonFailureRate() {
        CircuitBreaker cb = breaker(baseConfig()
                .failureThreshold(100)
                .failureRateThreshold(0.5)
                .minimumNumberOfCalls(4));
        ok(cb);
        ok(cb);
        fail(cb);
        fail(cb);
        assertEquals(1, events.size());
        assertEquals(TransitionReason.FAILURE_RATE_EXCEEDED, events.get(0).getReason());
    }

    @Test
    @DisplayName("when failure count reach the threahold:  reason = FAILURE_COUNT_EXCEEDED")
    void reasonFailureCount() {
        CircuitBreaker cb = breaker(baseConfig()
                .failureThreshold(3)
                .failureRateThreshold(1.0)
                .minimumNumberOfCalls(3));

        for (int i = 0; i < 4; i++) ok(cb);
        for (int i = 0; i < 3; i++) fail(cb);

        assertEquals(1, events.size());
        assertEquals(TransitionReason.FAILURE_COUNT_EXCEEDED, events.get(0).getReason());
    }

    @Test
    @DisplayName(":HALF_OPEN->OPEN,reason = PROBE_FAILED")
    void reasonProbeFailed() {
        CircuitBreaker cb = breaker(baseConfig());
        for (int i = 0; i < 3; i++) fail(cb);
        advance(WAIT);
        fail(cb);

        assertEquals(List.of("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->OPEN"), transitions());
        assertEquals(TransitionReason.PROBE_FAILED, events.get(2).getReason());
    }

    @Test
    @DisplayName("there is no event is not status change(success, failure not reach the threshold,rejected call)")
    void noEventWithoutTransition() {
        CircuitBreaker cb = breaker(baseConfig());
        ok(cb);
        ok(cb);
        fail(cb);                                  // 3 次调用,1 次失败:率 0.33 < 0.5,次数 1 < 3
        assertTrue(events.isEmpty());

        fail(cb);
        fail(cb);

        assertEquals(1, events.size());

        for (int i = 0; i < 5; i++) {              // OPEN 期间被拒绝,不产生事件
            assertThrows(CallNotPermittedException.class, () -> cb.call(() -> "x"));
        }
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("timestamp from the injected clock")
    void timestampComesFromInjectedClock() {
        now.set(5_000_000_000L);
        CircuitBreaker cb = breaker(baseConfig());

        for (int i = 0; i < 3; i++) fail(cb);
        assertEquals(5_000_000_000L, events.get(0).getTimestampNanos());

        advance(WAIT);
        ok(cb);
        assertEquals(5_000_000_000L + WAIT.toNanos(), events.get(1).getTimestampNanos());
    }

    // ---------- notification timing ----------

    @Test
    @DisplayName("listener oberserve new state")
    void listenerObservesNewState() {
        CircuitBreaker cb = new CircuitBreaker(baseConfig().build(), now::get);
        AtomicReference<State> seenInsideListener = new AtomicReference<>();
        cb.addListener(e -> {
            if (e.getTo() == State.OPEN) {
                seenInsideListener.set(cb.getState());
            }
        });

        for (int i = 0; i < 3; i++) fail(cb);

        assertEquals(State.OPEN, seenInsideListener.get());
    }

    // ---------- registration ----------

    @Test
    @DisplayName("multiple listeners all notified")
    void multipleListenersAllNotified() {
        CircuitBreaker cb = new CircuitBreaker(baseConfig().build(), now::get);
        List<StateTransitionEvent> a = new ArrayList<>();
        List<StateTransitionEvent> b = new ArrayList<>();
        cb.addListener(a::add);
        cb.addListener(b::add);

        for (int i = 0; i < 3; i++) fail(cb);

        assertEquals(1, a.size());
        assertEquals(1, b.size());
    }

    @Test
    @DisplayName("removeListener,then the listener will not receive the notice")
    void removedListenerIsNotNotified() {
        CircuitBreaker cb = new CircuitBreaker(baseConfig().build(), now::get);
        List<StateTransitionEvent> received = new ArrayList<>();
        StateTransitionListener l = received::add;
        cb.addListener(l);
        cb.removeListener(l);

        for (int i = 0; i < 3; i++) fail(cb);

        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("addListener reject null")
    void addListenerRejectsNull() {
        CircuitBreaker cb = new CircuitBreaker(baseConfig().build(), now::get);
        assertThrows(NullPointerException.class, () -> cb.addListener(null));
    }

    // ---------- fault isolation ----------

    @Test
    @DisplayName("faulty listener does not break the call or other listeners")
    void faultyListenerDoesNotBreakCallOrOtherListeners() {
        CircuitBreaker cb = new CircuitBreaker(baseConfig().build(), now::get);
        cb.addListener(e -> { throw new UnsupportedOperationException("listener bug"); });
        cb.addListener(events::add);

        RuntimeException downstream = new IllegalStateException("downstream");
        for (int i = 0; i < 3; i++) {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> cb.call(() -> { throw downstream; }));
            assertSame(downstream, thrown);          // 不是监听器的 UnsupportedOperationException
        }

        assertEquals(State.OPEN, cb.getState());
        assertEquals(1, events.size());              // 后面的监听器依然收到了
    }

    // ---------- concurrency ----------

    @Test
    @DisplayName("concurrent:100 threads failed at the same time,CLOSED->OPEN event count is 1")
    void exactlyOneTripEventUnderConcurrentFailures() throws Exception {
        CircuitBreaker cb = breaker(baseConfig());

        runConcurrently(100, () -> {
            try {
                cb.call(() -> { throw new IllegalStateException("boom"); });
            } catch (IllegalStateException | CallNotPermittedException expected) {
                // ignore
            }
        });

        assertEquals(State.OPEN, cb.getState());
        assertEquals(List.of("CLOSED->OPEN"), transitions());
    }

    // ---------- LoggingStateTransitionListener ----------

    @Test
    @DisplayName("LoggingStateTransitionListener output: name、status and reason")
    void loggingListenerFormatsLine() {
        List<String> lines = new ArrayList<>();
        LoggingStateTransitionsListener l = new LoggingStateTransitionsListener("payment", lines::add);

        l.onStateTransition(new StateTransitionEvent(
                State.CLOSED, State.OPEN, TransitionReason.FAILURE_RATE_EXCEEDED, 0L));

        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("payment"));
        assertTrue(line.contains("CLOSED"));
        assertTrue(line.contains("OPEN"));
        assertTrue(line.contains("FAILURE_RATE_EXCEEDED"));
    }
}
