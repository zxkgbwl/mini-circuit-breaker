package com.george.circuitbreaker.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final AtomicLong now = new AtomicLong(0l);
    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .failureRateThreshold(0.5)
                .minimumNumberOfCalls(3)
                .slidingWindowSizeInSeconds(5)
                .waitDuration(WAIT)
                .permittedCallsInHalfOpen(2)
                .build();
        cb = new CircuitBreaker(config,now::get);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    private void fail() {
        assertThrows(IllegalStateException.class, ()->cb.call(
                ()-> {
                    throw new IllegalStateException("boom");
                }
                ));
    }

    private String ok() {
        return cb.call(()->"ok");
    }

    private void tripOpen() {
        for (int i=0; i < 3; i++) {
            fail();
        }
        assertEquals(State.OPEN, cb.getState());
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

            for (Future<Void> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

        }finally {
            pool.shutdownNow();
        }
    }

    //-------------------CLOSED-------------------------------
    @Test
    @DisplayName(("initial state is CLOSED"))
    void initialStateIsClosed() {
        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("closed state, call success and return result, state remains CLOSED")
    void closedPassedThroughAndReturnResult() {
        assertEquals("ok", ok());
        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("failed call throws original exception")
    void failedCallRethrowsOriginalException() {
        IllegalStateException original = new IllegalStateException("original");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, ()-> cb.call(
                ()-> { throw original;}
        ));
        assertSame(original, thrown);
    }

    @Test
    @DisplayName("below minimum number of calls")
    void staysClosedBelowMinimumNumberOfCalls() {
        fail();
        fail();
        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("many success never trip")
    void manySuccessNeverTrip() {
        for (int i = 0; i< 100; i++) {
            ok();
        }

        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("trip by faillure count ")
    void tripByFailureCount() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .failureRateThreshold(1.0)
                .slidingWindowSizeInSeconds(5)
                .minimumNumberOfCalls(3)
                .waitDuration(WAIT)
                .build();

        CircuitBreaker cb2 = new CircuitBreaker(cfg,now::get);
        for (int i= 0; i< 4; i++) {
            cb2.call(()->"ok");
        }

        for (int i = 0; i < 2; i++) {
            assertThrows(IllegalStateException.class, ()-> cb2.call(
                    ()-> {
                        throw new IllegalStateException("boom");
                    }
            ));

        }
        assertEquals(State.CLOSED, cb2.getState());
        assertThrows(IllegalStateException.class, ()-> cb2.call(
                ()-> {
                    throw new IllegalStateException("boom");
                }
        ));

        assertEquals(State.OPEN, cb2.getState());
    }

    @Test
    @DisplayName("trip by failure rate ")
    void tripsByFailureRate() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.builder()
                .failureThreshold(100)
                .failureRateThreshold(0.5)
                .slidingWindowSizeInSeconds(5)
                .minimumNumberOfCalls(4)
                .waitDuration(WAIT)
                .build();
        CircuitBreaker cb2 = new CircuitBreaker(cfg,now::get);
        cb2.call(()->"ok");
        cb2.call(()->"ok");

        assertThrows(IllegalStateException.class, ()->cb2.call(
                ()-> {
                    throw new IllegalStateException("boom");
                }
        ));
        assertEquals(State.CLOSED, cb2.getState());

        assertThrows(IllegalStateException.class, ()-> cb2.call(
                ()-> {
                    throw new IllegalStateException("boom");
                }
                ));
        assertEquals(State.OPEN, cb2.getState());
    }

    @Test
    @DisplayName("old Failures are not counted ")
    void oldFailuresAreNotCounted() {
        fail();
        fail();
        advance(Duration.ofSeconds(6));
        fail();
        assertEquals(State.CLOSED, cb.getState());
    }

    //-------------------OPEN------------------

    @Test
    @DisplayName("open state, call fails fast")
    void openRejectsCallsWithoutInvokingDownStream() {
        tripOpen();
        AtomicInteger downStreamInvocation = new AtomicInteger(0);
        CallNotPermittedException ex = assertThrows(CallNotPermittedException.class, ()->
            cb.call(()-> {
            downStreamInvocation.incrementAndGet();
            return "should not run";
        }));

        assertEquals(0, downStreamInvocation.get());
        assertTrue(ex.getMessage().contains("OPEN"));

    }

    @Test
    @DisplayName("keep Open before wait duration expires")
    void keepOpenBeforeWaitDurationExpires() {
        tripOpen();
        advance(Duration.ofSeconds(1));
        assertFalse(cb.tryAcquirePermission());
        assertEquals(State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("enter halp_open after wait duration")
    void enterHalpOpenAfterWaitDuration() {
        tripOpen();
        advance(WAIT);
        assertTrue(cb.tryAcquirePermission());
        assertEquals(State.HALF_OPEN, cb.getState());

    }

    //------------------HALF_OPEN----------------------

    @Test
    @DisplayName("permittedCallsInHalOPen calls be permitted")
    void permittedCallsInHalOPenCallsBePermitted() {
        tripOpen();
        advance(WAIT);
        assertTrue(cb.tryAcquirePermission());
        assertTrue(cb.tryAcquirePermission());
        assertFalse(cb.tryAcquirePermission());
        assertEquals(State.HALF_OPEN, cb.getState());
    }

    @Test
    @DisplayName("HALF_OPEN --> CLOSED when probes all succeed")
    void halfOpenAllSucessClosed() {
        tripOpen();
        advance(WAIT);
        ok();
        assertEquals(State.HALF_OPEN, cb.getState());
        ok();
        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("HALF_OPEN --> OPEN when any probe fails")
    void halfOpenSingleFailureToOpenAndRestartWait() {
        tripOpen();
        advance(WAIT);
        fail();
        assertEquals(State.OPEN, cb.getState());

        advance(WAIT.minusSeconds(1));
        assertFalse(cb.tryAcquirePermission());
        assertEquals(State.OPEN, cb.getState());

        advance(Duration.ofSeconds(1));
        assertTrue(cb.tryAcquirePermission());
        assertEquals(State.HALF_OPEN, cb.getState());
    }

    @Test
    @DisplayName("After Closed, sliding windos cleared, ")
    void closingREsetSlidingWindow() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .failureRateThreshold(0.5)
                .minimumNumberOfCalls(3)
                .slidingWindowSizeInSeconds(5)
                .waitDuration(Duration.ofSeconds(1))
                .permittedCallsInHalfOpen(2)
                .build();
        CircuitBreaker cb2 = new CircuitBreaker(cfg,now::get);

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class, ()-> cb2.call(()->{
                throw new IllegalStateException("boom");
            }));
        }

        assertEquals(State.OPEN, cb2.getState());

        advance(Duration.ofSeconds(1));
        cb2.call(()->"ok");
        cb2.call(()->"ok");
        assertEquals(State.CLOSED, cb2.getState());

        // after closed, sliding window should be cleared, so new failures should not trip immediately
        for (int i=0; i < 2; i++) {
            assertThrows(IllegalStateException.class,
                    ()-> cb2.call(()->{throw new IllegalStateException("boom");}));
        }
        assertEquals(State.CLOSED, cb2.getState());

        assertThrows(IllegalStateException.class,
                ()->cb2.call(()->{throw new IllegalStateException("boom");}));
        assertEquals(State.OPEN, cb2.getState());

    }

    //----------------------Concurrency-----------------
    @Test
    @DisplayName("In Concurrency: HALF_OPEN will not permit more than permitted")
    void inConcurrencyHalfOpenNeverExceedPermits()  throws Exception {
        tripOpen();
        advance(WAIT);

        AtomicInteger granted = new  AtomicInteger(0);
        runConcurrently(50, ()-> {
            if (cb.tryAcquirePermission()) {
                granted.incrementAndGet();
            }
        });

        assertTrue(granted.get() <= 2, "granted="+ granted.get());
        assertTrue(granted.get() >=1, "granted="+ granted.get());
        assertEquals(State.HALF_OPEN, cb.getState());

    }

    @Test
    @DisplayName("Concurrency: massive thread failed at the same time, OPEN")
    void concurrencyMassiveThreadFailedOpen() throws Exception {
        runConcurrently(100, ()-> {
            try {
                cb.call(()-> {throw new IllegalStateException("boom");});

            }catch (IllegalStateException | CallNotPermittedException e) {

            }
        });

        assertEquals(State.OPEN, cb.getState());
    }

}
