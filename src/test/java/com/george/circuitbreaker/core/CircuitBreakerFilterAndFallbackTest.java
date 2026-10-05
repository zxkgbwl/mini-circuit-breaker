package com.george.circuitbreaker.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerFilterAndFallbackTest {
    static class BusinessException extends RuntimeException {
        BusinessException(String m) { super(m); }
    }

    static class InfraException extends RuntimeException {
        InfraException(String m) {super(m);}
    }

    private static final Duration WAIT = Duration.ofSeconds(10);
    private final AtomicLong now = new  AtomicLong(0l);

    //----------------helpers------------------------

    private CircuitBreakerConfig.Builder baseConfig() {
        return CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .failureRateThreshold(0.5)
                .minimumNumberOfCalls(3)
                .slidingWindowSizeInSeconds(5)
                .waitDuration(WAIT)
                .permittedCallsInHalfOpen(2);
    }

    private CircuitBreaker breaker(CircuitBreakerConfig.Builder b) {
        return new CircuitBreaker(b.build(), now::get);
    }

    // invoke a downstream which
    private void callFailing(CircuitBreaker cb, RuntimeException run_ex) {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                ()-> cb.call(()->{throw run_ex;}));
        assertSame(run_ex, thrown);
    }

    private void tripOpenWithInfraFailing(CircuitBreaker cb) {
        for (int i = 0; i < 3; i++) {
            callFailing(cb, new InfraException("infra"));
        }
        assertEquals(State.OPEN, cb.getState());
    }

    //----------------------exception filter--------------------
    @Test
    @DisplayName("ignored exceptions do not count towards failure, but still propagate")
    void ignoredExceptionDoNotTrip() {
        CircuitBreaker cb = breaker(baseConfig().ignoreExceptions(BusinessException.class));
        for (int i = 0; i < 10; i++) {
            callFailing(cb, new BusinessException("business"));
        }
        assertEquals(State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("ignored ex it not counted as fail or succ")
    void ignoredExAreNeutral() {
        //close the path of failureThreahold,
        CircuitBreaker cb = breaker(baseConfig()
                .failureThreshold(100)
                .ignoreExceptions(BusinessException.class));

        callFailing(cb, new InfraException("infra"));
        for (int i = 0; i < 5; i++) {
            callFailing(cb, new BusinessException("business"));
        }
        callFailing(cb, new InfraException("infra"));
        assertEquals(State.CLOSED, cb.getState());

        callFailing(cb, new InfraException("infra"));
        assertEquals(State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("exception in recordException will be counted as failure")
    void recordExceptionWhitelist() {
        CircuitBreaker cb = breaker(baseConfig().recordExceptions(InfraException.class));
        for (int i= 0; i< 10; i++) {
            callFailing(cb, new IllegalArgumentException("not in whitelist"));            
        }
        assertEquals(State.CLOSED, cb.getState());
        tripOpenWithInfraFailing(cb);
    }

    @Test
    @DisplayName("ignore take precedence over record")
    void ignoreTakePrecedenceOverRecord() {
        CircuitBreaker cb = breaker(baseConfig()
            .ignoreExceptions(BusinessException.class)
            .recordExceptions(RuntimeException.class));

        for (int i= 0 ; i< 10; i++) {
            callFailing(cb, new BusinessException("business"));
        }
        assertEquals(State.CLOSED, cb.getState());
        tripOpenWithInfraFailing(cb);
    }

    @Test
    @DisplayName("customize recordFailurePredicate")
    void customizeRecordFailurePredicate() {
        CircuitBreaker cb = breaker(baseConfig()
                .recordFailurePredicate(t->t.getMessage() != null &&
                        t.getMessage().startsWith("5xx")));
        for (int i= 0 ; i< 10; i++) {
            callFailing(cb, new IllegalStateException("4xx client error"));
        }
        assertEquals(State.CLOSED, cb.getState());

        for (int i= 0; i< 3; i++) {
            callFailing(cb, new IllegalStateException("5xx server error"));
        }
        assertEquals(State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("HALF_OPEN state ignored exception return the decreased permit")
    void halfOpenIgnoredExceptionReturnTheDecreasedPermit() {
        CircuitBreaker cb = breaker(baseConfig()
                .ignoreExceptions(BusinessException.class));
        tripOpenWithInfraFailing(cb);
        assertEquals(State.OPEN, cb.getState());
        now.addAndGet(WAIT.toNanos());

        //do not occupy the permit
        for (int i = 0; i < 3; i++) {
            callFailing(cb, new BusinessException("business"));
        }
        assertEquals(State.HALF_OPEN, cb.getState());

        //check if all permit available
        cb.call(()-> "ok");
        cb.call(()-> "ok");
        assertEquals(State.CLOSED, cb.getState());
    }

    //-------------------fallback----------------------
    @Test
    @DisplayName("when downstream failed, go fallback and transfer the exception")
    void fallbackUsedWhenDownstreamFails() {
        CircuitBreaker cb = breaker(baseConfig());
        IllegalStateException ex = new IllegalStateException("boom");
        AtomicReference<Throwable> seen = new AtomicReference<>();
        String result = cb.callWithFallback(
                ()-> {throw ex;}, t-> {seen.set(t); return "fallback";}
        );
        assertEquals("fallback", result);
        assertSame(ex, seen.get());
    }

    @Test
    @DisplayName("if downstream call is successful, fallback is not used")
    void fallbackNotUseOnSuccess() {
        CircuitBreaker cb = breaker(baseConfig());
        AtomicInteger fallbackCalls = new  AtomicInteger();

        String result = cb.callWithFallback(
                () -> "real", t->{fallbackCalls.incrementAndGet(); return "fallback";}
        );
        assertEquals("real", result);
        assertEquals(0, fallbackCalls.get());

    }

    @Test
    @DisplayName("on State.OPEN, fallback is used, and downstream is not called")
    void fallbackUsedOnStateOpen() {
        CircuitBreaker cb = breaker(baseConfig());
        tripOpenWithInfraFailing(cb);

        AtomicInteger downstreamCalls = new  AtomicInteger();
        AtomicReference<Throwable> seen = new AtomicReference<>();

        String result = cb.callWithFallback(
                ()->{downstreamCalls.incrementAndGet(); return "real";}, t->{seen.set(t); return "fallback";}
        );

        assertEquals("fallback", result);
        assertEquals(0, downstreamCalls.get());
        assertInstanceOf(CallNotPermittedException.class, seen.get());
    }

    @Test
    @DisplayName("ignored exception dose not trigger fallback, rethrow as original")
    void ignoreExceptionDoesNotTriggerFallback() {
        CircuitBreaker cb = breaker(baseConfig()
                .ignoreExceptions(BusinessException.class));
        BusinessException ex = new BusinessException("user not found");
        AtomicInteger fallbackCalls = new  AtomicInteger();
        BusinessException thrown = assertThrows(
                BusinessException.class, () -> cb.callWithFallback(
                        ()->{throw ex;}, t->{fallbackCalls.incrementAndGet(); return "fallback";}
                )
        );
        assertSame(ex, thrown);
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    @DisplayName("failure masked by fallback, but still trip breaker")
    void failureMaskedByFallbackStillTripBreaker() {
        CircuitBreaker cb = breaker(baseConfig());
        for (int i = 0; i< 3; i++) {
            String r = cb.callWithFallback(
                    ()-> {throw new InfraException("infra");}, t -> "fallback"
            );
            assertEquals("fallback", r);
        }
        assertEquals(State.OPEN, cb.getState());
    }

    @Test
    @DisplayName("exception from fallback propagate to outside")
    void exceptionInFallbackPropagate() {
        CircuitBreaker cb = breaker(baseConfig());
        assertThrows(IllegalArgumentException.class, ()->
                cb.callWithFallback(
                        ()->{throw new InfraException("infra");},
                        t->{throw new IllegalArgumentException("fallback failed");}
                ));
    }









}
