package com.george.circuitbreaker.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Sliding window of success/failure counts with one-second resolution.
 *
 * <h2>Design</h2>
 * <p>The window is a ring of {@code windowSizeInSeconds} buckets. A call made during second
 * {@code s} is counted in bucket {@code s mod N}. Every bucket remembers which second it belongs
 * to. The first write of a new second lazily clears and re-tags the bucket ("rotation"), so no
 * background thread is needed. A bucket contributes to a snapshot only if its second lies within
 * the last {@code N} seconds.
 *
 * <p>Writes are lock-free ({@link LongAdder}) except for rotation, which is a short synchronized
 * block executed at most once per bucket and second. Reads take no lock: for every bucket the
 * snapshot reads the bucket's second, then its counters, then the second again, and skips the
 * bucket if the second changed in between (an optimistic, seqlock-style version check).
 *
 * <h2>Accuracy</h2>
 * <p>Statistics are <em>approximate</em> while a bucket is being rotated: a write racing with the
 * rotation may be attributed to the new second, and a snapshot racing with it may skip the bucket
 * or see it briefly cleared. That is acceptable for circuit breaking, where thresholds are
 * statistical anyway.
 *
 * <h2>Time</h2>
 * <p>Time comes from an injected nano clock (monotonic, arbitrary origin, possibly negative), so
 * tests can drive time deterministically. Only differences between readings are meaningful.
 *
 * <p>This class is an implementation detail of {@link CircuitBreaker} and is not public API.
 */
final class SlidingWindow {

    private static final long EMPTY = Long.MIN_VALUE;

    private final int windowSizeInSeconds;
    private final Bucket[] buckets;
    private final LongSupplier nanoClock;


    SlidingWindow(int windowSizeInSeconds, LongSupplier nanoClock) {
        if (windowSizeInSeconds <= 0) {
            throw new IllegalArgumentException("windowSizeInSeconds must be positive");
        }
        this.windowSizeInSeconds = windowSizeInSeconds;
        this.nanoClock = nanoClock;
        this.buckets = new Bucket[windowSizeInSeconds];
        for (int i = 0; i < windowSizeInSeconds; i++) {
            this.buckets[i] = new Bucket(EMPTY);
        }
    }

    private long getCurrentSecond() {
        return TimeUnit.NANOSECONDS.toSeconds(nanoClock.getAsLong());
    }

    private Bucket bucketFor(long second) {
        int index = Math.floorMod(second, windowSizeInSeconds);
        Bucket b = buckets[index];
        b.rotateIfStale(second);
        return b;
    }

    public void recordSuccess() {
        long second = getCurrentSecond();
        bucketFor(second).successCount.increment();
    }

    public void recordFailure() {
        long second = getCurrentSecond();
        bucketFor(second).failureCount.increment();
    }

    /**
     * get teh snapshot of the sliding window
     *
     */
    Snapshot getSnapshot() {
        long currentSecond = getCurrentSecond();
        long totalSuccess = 0L;
        long totalFailure = 0L;
        for (Bucket bucket : buckets) {
            // read verison 1
            long  epochBefore = bucket.getEpochSecond();
            //read data without lock
            long succ = bucket.successCount.sum();
            long fail = bucket.failureCount.sum();
            //read version 2
            long epochAfter = bucket.getEpochSecond();
            //check version
            if (epochBefore == epochAfter && epochBefore != EMPTY) {
                long diff = currentSecond - epochBefore;
                if ( diff >=0 && diff < windowSizeInSeconds) {
                    totalSuccess += succ;
                    totalFailure += fail;
                }

            }

        }
        return new Snapshot(totalSuccess, totalFailure);
    }

    void reset() {
        for (Bucket bucket : buckets) {
            bucket.clear();
        }
    }

    /**
     * Bucket nested class
     */
     private static final class Bucket {
        private volatile long epochSecond;
        private final LongAdder successCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();

        private Bucket(long epochSecond) {
            this.epochSecond = epochSecond;
        }

        /**
         * check if the bucket is expired based on the current epoch second
         */
        synchronized void rotateIfStale(long second) {
            if (this.epochSecond != second) {
                this.successCount.reset();
                this.failureCount.reset();
                this.epochSecond = second;
            }
        }

        synchronized void clear() {
            this.successCount.reset();
            this.failureCount.reset();
            this.epochSecond = EMPTY;
        }

        long getEpochSecond() {
            return epochSecond;
        }

    }

    /**
     * Snapshot nest class
     */
     static final class Snapshot {
        private final long totalSuccess;
        private final long totalFailure;

        public Snapshot(long totalSuccess, long totalFailure) {
            this.totalSuccess = totalSuccess;
            this.totalFailure = totalFailure;
        }

        long getTotalSuccess() {
            return totalSuccess;
        }

        long getTotalFailure() {
            return totalFailure;
        }

        long getTotalCalls() {
            return totalSuccess + totalFailure;
        }

        double getFailureRate() {
            long total = getTotalCalls();
            if (total == 0) {
                return 0.0;

            }
            else {
                return (double) totalFailure/total;
            }

        }

    }



}



