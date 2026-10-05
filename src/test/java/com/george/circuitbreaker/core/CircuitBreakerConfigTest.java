package com.george.circuitbreaker.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerConfigTest {

    @Test
    @DisplayName("Default value")
    void defaultValues() {
        CircuitBreakerConfig c = CircuitBreakerConfig.builder().build();;

        assertEquals(5, c.getFailureThreshold());
        assertEquals(0.5, c.getFailureRateThreshold());
        assertEquals(5, c.getMinimumNumberOfCalls());
        assertEquals(5, c.getSlidingWindowSizeInSeconds());
        assertEquals(Duration.ofSeconds(10).toNanos(), c.getWaitDurationNanos());
        assertEquals(3, c.getPermittedCallsInHalfOpen());
    }

    @Test
    @DisplayName("Custom value")
    void customValues() {
        CircuitBreakerConfig c = CircuitBreakerConfig.builder()
                .failureThreshold(10)
                .failureRateThreshold(0.8)
                .minimumNumberOfCalls(20)
                .slidingWindowSizeInSeconds(30)
                .waitDuration(Duration.ofSeconds(500))
                .permittedCallsInHalfOpen(7)
                .build();
        assertEquals(10, c.getFailureThreshold());
        assertEquals(0.8, c.getFailureRateThreshold(), 1e-9);
        assertEquals(20, c.getMinimumNumberOfCalls());
        assertEquals(30, c.getSlidingWindowSizeInSeconds());
        assertEquals(Duration.ofSeconds(500).toNanos(), c.getWaitDurationNanos());
        assertEquals(7, c.getPermittedCallsInHalfOpen());
    }

    @Test
    @DisplayName("sliding window size should be positive")
    void rejectNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, ()-> CircuitBreakerConfig
                .builder()
                .slidingWindowSizeInSeconds(0)
                .build());
    }

    @Test
    @DisplayName("failure rate between [0,1]")
    void rejectFailureRateOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                ()->CircuitBreakerConfig
                        .builder()
                        .failureRateThreshold(-0.1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                ()->CircuitBreakerConfig
                        .builder()
                        .failureRateThreshold(1.1)
                       .build());
    }

    @Test
    @DisplayName("minimumNumberOfCalls should be positive")
    void rejectsNonPositiveMinimumNumberOfCalls() {
        assertThrows(IllegalArgumentException.class,
                ()->CircuitBreakerConfig
                        .builder()
                        .minimumNumberOfCalls(0)
                        .build());
    }

    @Test
    @DisplayName("permittedCallsInHalfOpen should be positive")
    void rejectsNonPositivePermittedCallsInHalfOpen() {
        assertThrows(IllegalArgumentException.class,
                ()->CircuitBreakerConfig
                        .builder().permittedCallsInHalfOpen(0).build());

    }

    @Test
    @DisplayName("waitDuration should be positive")
    void rejectsNonPositiveWaitDuration() {
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerConfig.builder().waitDuration(Duration.ZERO).build());
    }

    @Test
    @DisplayName("failureThreshold exception include right message")
    void failureThresholdMessageNamesTheParameter() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CircuitBreakerConfig.builder().failureThreshold(0));
        assertTrue(ex.getMessage().startsWith("failureThreshold must"));
    }

    @Test
    @DisplayName("failureRateThreshold reject NaN")
    void rejectsNaNFailureRate() {
        assertThrows(IllegalArgumentException.class,
                () -> CircuitBreakerConfig.builder().failureRateThreshold(Double.NaN));
    }

    @Test
    @DisplayName("waitDuration reject null")
    void rejectsNullWaitDuration() {
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerConfig.builder().waitDuration(null));
    }

    @Test
    @DisplayName("reject exeception list includes null,throw NullPointerException")
    void rejectsNullExceptionTypes() {
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerConfig.builder().ignoreExceptions(IllegalStateException.class, null));
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerConfig.builder().recordExceptions(IllegalStateException.class, null));
    }

    @Test
    @DisplayName("build then modify builder, don not impac the built config")
    void builtConfigIsIndependentOfLaterBuilderChanges() {
        CircuitBreakerConfig.Builder b = CircuitBreakerConfig.builder()
                .ignoreExceptions(IllegalStateException.class);
        CircuitBreakerConfig c = b.build();

        b.ignoreExceptions(IllegalArgumentException.class);

        assertFalse(c.isRecordedAsFailure(new IllegalStateException()));
        assertTrue(c.isRecordedAsFailure(new IllegalArgumentException()));   // 后加的规则不影响 c
    }
}
