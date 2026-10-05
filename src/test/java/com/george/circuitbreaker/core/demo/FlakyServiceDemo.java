package com.george.circuitbreaker.core.demo;

import com.george.circuitbreaker.core.CircuitBreaker;
import com.george.circuitbreaker.core.CircuitBreakerConfig;
import com.george.circuitbreaker.core.LoggingStateTransitionsListener;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a circuit breaker through its whole life cycle against a simulated flaky service:
 * CLOSED -> OPEN -> HALF_OPEN -> OPEN -> HALF_OPEN -> CLOSED.
 *
 * <p>It lives in a different package than the library on purpose, so it only compiles if
 * everything a user needs is part of the public API.
 *
 * <p>Run it from the IDE, or with
 * {@code mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java
 * -Dexec.mainClass=com.george.circuitbreaker.demo.FlakyServiceDemo -Dexec.classpathScope=test}.
 */
public final class FlakyServiceDemo {

    /** The simulated downstream: can be switched between healthy and down. */
    static final class InventoryClient {
        private volatile boolean healthy = true;
        private final AtomicInteger invocations = new AtomicInteger();

        String fetchStock(String sku) {
            invocations.incrementAndGet();
            if (!healthy) {
                throw new ServiceUnavailableException("inventory service is down");
            }
            if (sku.startsWith("unknown")) {
                throw new UnknownSkuException(sku);          // a business error, not an outage
            }
            return "stock(" + sku + ")=42";
        }

        int invocations() {
            return invocations.get();
        }
    }

    /** Infrastructure failure: counts towards tripping the breaker. */
    static final class ServiceUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ServiceUnavailableException(String message) {
            super(message);
        }
    }

    /** Business error: the service answered correctly, so it must never trip the breaker. */
    static final class UnknownSkuException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownSkuException(String sku) {
            super("unknown sku " + sku);
        }
    }

    private final InventoryClient client = new InventoryClient();
    private final CircuitBreaker breaker;
    private int callCounter = 0;

    private FlakyServiceDemo() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureRateThreshold(0.5)
                .failureThreshold(5)
                .minimumNumberOfCalls(5)
                .slidingWindowSizeInSeconds(10)
                .waitDuration(Duration.ofSeconds(2))
                .permittedCallsInHalfOpen(3)
                .ignoreExceptions(UnknownSkuException.class)
                .build();

        breaker = new CircuitBreaker(config);
        breaker.addListener(LoggingStateTransitionsListener.toStdout("inventory"));
    }

    public static void main(String[] args) throws InterruptedException {
        new FlakyServiceDemo().run();
    }

    private void run() throws InterruptedException {
        section("1. Healthy service: calls pass through");
        for (int i = 1; i <= 5; i++) {
            call("sku-" + i);
        }

        section("2. Business errors are ignored: they never trip the breaker");
        for (int i = 1; i <= 6; i++) {
            call("unknown-" + i);
        }

        section("3. Outage: failures are answered by the fallback until the breaker trips");
        client.healthy = false;
        for (int i = 6; i <= 13; i++) {
            call("sku-" + i);
        }

        section("4. OPEN: calls are rejected without touching the service");
        int before = client.invocations();
        for (int i = 14; i <= 17; i++) {
            call("sku-" + i);
        }
        System.out.println("  -> calls that reached the service during this phase: "
                + (client.invocations() - before));

        section("5. After the wait duration the first trial call still fails: back to OPEN");
        Thread.sleep(2_200);
        call("sku-18");

        section("6. The service recovers: trial calls succeed and the breaker closes");
        client.healthy = true;
        Thread.sleep(2_200);
        for (int i = 19; i <= 22; i++) {
            call("sku-" + i);
        }

        System.out.printf("%nSummary: %d calls issued, %d reached the service, %d were rejected by the breaker.%n",
                callCounter, client.invocations(), callCounter - client.invocations());
    }

    private void call(String sku) {
        int n = ++callCounter;
        try {
            String result = breaker.callWithFallback(
                    () -> client.fetchStock(sku),
                    error -> "FALLBACK(" + error.getClass().getSimpleName() + ")");
            System.out.printf("  call %2d  %-10s -> %-38s [state=%s]%n",
                    n, sku, result, breaker.getState());
        } catch (RuntimeException e) {
            // Ignored (business) exceptions bypass the fallback and arrive here unchanged.
            System.out.printf("  call %2d  %-10s -> %-38s [state=%s]%n",
                    n, sku, "THREW " + e.getClass().getSimpleName(), breaker.getState());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }
}
