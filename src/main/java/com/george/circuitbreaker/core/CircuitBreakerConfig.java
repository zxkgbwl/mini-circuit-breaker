package com.george.circuitbreaker.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class CircuitBreakerConfig {

    private final int failureThreshold;
    private final double failureRateThreshold;      //threshold of failure rate to trigger stats change.
    private final int minimumNumberOfCalls;        //mini calls which could trigger the aval
    private final int slidingWindowSizeInSeconds; // sliding window size
    private final long waitDurationNanos;
    private final int permittedCallsInHalfOpen;
    private final Predicate<Throwable> failureFilter;

    private CircuitBreakerConfig(Builder b) {
        this.failureThreshold = b.failureThreshold;
        this.failureRateThreshold = b.failureRateThreshold;
        this.minimumNumberOfCalls = b.minimumNumberOfCalls;
        this.slidingWindowSizeInSeconds = b.slidingWindowSizeInSeconds;
        this.waitDurationNanos = b.waitDurationNanos;
        this.permittedCallsInHalfOpen = b.permittedCallsInHalfOpen;
        this.failureFilter = b.composePredicate();

    }
    public boolean isRecordedAsFailure(Throwable t) {
        return failureFilter.test(t);
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public int getSlidingWindowSizeInSeconds() {
        return slidingWindowSizeInSeconds;
    }

    public long getWaitDurationNanos() {
        return waitDurationNanos;
    }

    public int getPermittedCallsInHalfOpen() {
        return permittedCallsInHalfOpen;
    }

    public static  Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "CircuitBreakerConfig{failureThreshold=" + failureThreshold
                + ", failureRateThreshold=" + failureRateThreshold
                + ", minimumNumberOfCalls=" + minimumNumberOfCalls
                + ", slidingWindowSizeInSeconds=" + slidingWindowSizeInSeconds
                + ", waitDuration=" + Duration.ofNanos(waitDurationNanos)
                + ", permittedCallsInHalfOpen=" + permittedCallsInHalfOpen + '}';
    }


    public static final class Builder {
        private int failureThreshold = 5;
        private double failureRateThreshold = 0.5;
        private int minimumNumberOfCalls = 5;
        private int slidingWindowSizeInSeconds = 5;
        private long waitDurationNanos = Duration.ofSeconds(10).toNanos();
        private int permittedCallsInHalfOpen = 3;
        //exception in recordException will be counted as the failure
        private final List<Class<? extends Throwable>> recordExceptions = new ArrayList<>();
        //exception in ignoreException will NOT be counted as the failure
        private final List<Class<? extends Throwable>> ignoreExceptions = new ArrayList<>();
        private Predicate<Throwable> recordFailurePredicate = t -> true;

        private Builder() {

        }


        public Builder failureThreshold(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("failureThreshold must be positive");
            }
            this.failureThreshold = n;
            return this;

        }

        public Builder failureRateThreshold(double failureRateThreshold) {
            if (Double.isNaN(failureRateThreshold) || failureRateThreshold <= 0.0 || failureRateThreshold > 1.0) {
                throw new IllegalArgumentException("failureRateThreshold must be between 0 and 1");
            }
            this.failureRateThreshold = failureRateThreshold;
            return this;
        }

        public Builder minimumNumberOfCalls(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("minimumNumberOfCalls must be positive");
            }
            this.minimumNumberOfCalls = n;
            return this;
        }

        public Builder slidingWindowSizeInSeconds(int seconds) {
            if (seconds < 1) {
                throw new IllegalArgumentException("slidingWindowSizeInSeconds must be positive");
            }
            this.slidingWindowSizeInSeconds = seconds;
            return this;
        }

        public Builder waitDuration(Duration d) {
            Objects.requireNonNull(d, "waitDuration");
            if (d == null || d.compareTo(Duration.ZERO) <= 0) {
                throw new NullPointerException("waitDuration must be positive");
            }
            this.waitDurationNanos = d.toNanos();
            return this;
        }

        public Builder permittedCallsInHalfOpen(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("permittedCallsInHalfOpen must be positive");
            }
            this.permittedCallsInHalfOpen = n;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(this);
        }

        @SafeVarargs
        public final Builder recordExceptions(Class<? extends Throwable>... types) {
            addAll(recordExceptions,types, "recordException");
            return this;
        }

        @SafeVarargs
        public final Builder ignoreExceptions(Class<? extends Throwable>... types) {
            addAll(ignoreExceptions, types, "ignoreException");
            return this;
        }

        public Builder recordFailurePredicate(Predicate<Throwable> p) {
            this.recordFailurePredicate = Objects.requireNonNull(p,"predicate");
            return this;
        }
        //----------------------------------internals--------------------------
        private static void addAll(List<Class<? extends Throwable>> target,
                                   Class<? extends Throwable>[] types, String name) {
            Objects.requireNonNull(types, name);
            for (Class<? extends Throwable> type : types) {
                // Reject null now: a null here would otherwise blow up later, inside the call path.
                target.add(Objects.requireNonNull(type, name + " must not contain null"));
            }
        }



        private Predicate<Throwable> composePredicate() {
            List<Class<? extends Throwable>> record= List.copyOf(recordExceptions);
            List<Class<? extends Throwable>> ignore= List.copyOf(ignoreExceptions);
            Predicate<Throwable> custom = recordFailurePredicate;

            return t -> {
                if (matchesAny(ignore,t)) return false;
                if (!record.isEmpty() && !matchesAny(record, t)) return false;
                return custom.test(t);
            };
        }

        private static boolean matchesAny(List<Class<? extends Throwable>> types, Throwable t) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            return false;
        }
    }
}
