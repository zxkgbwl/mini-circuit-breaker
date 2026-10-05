package com.george.circuitbreaker.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class SlidingWindowTest {
    private final AtomicLong now = new AtomicLong();

    private SlidingWindow window(int seconds) {
        return new SlidingWindow(seconds, now::get);
    }
    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    @DisplayName("empty window: all fields are 0")
    void emptySnapshot() {
        SlidingWindow.Snapshot s= window(5).getSnapshot();
        assertEquals(0l, s.getTotalSuccess());
        assertEquals(0l, s.getTotalFailure());
        assertEquals(0l, s.getTotalCalls());
        assertEquals(0.0, s.getFailureRate());

    }

    @Test
    @DisplayName("record success/failure and calculate rate")
    void recordsAndCalculateFailureRate() {
        SlidingWindow w = window(5);
        for (int i = 0; i < 3; i++) w.recordSuccess();
        w.recordFailure();

        SlidingWindow.Snapshot s= w.getSnapshot();
        assertEquals(3L, s.getTotalSuccess());
        assertEquals(1L, s.getTotalFailure());
        assertEquals(4L, s.getTotalCalls());
        assertEquals(0.25, s.getFailureRate(), 1e-9);

    }

    @Test
    @DisplayName("count the calls when clock at 0")
    void countsAtTimeZero() {
        now.set(0L);
        SlidingWindow w = window(5);
        w.recordSuccess();
        w.recordFailure();

        SlidingWindow.Snapshot s= w.getSnapshot();
        assertEquals(1l, s.getTotalSuccess());
        assertEquals(1l, s.getTotalFailure());
        assertEquals(0.5, s.getFailureRate(), 1e-9);

    }
    @Test
    @DisplayName("differnt nanos in 1s fall in the same bucket")
    void sameSecondShareBucket() {
        SlidingWindow w= window(1);
        w.recordSuccess();
        advance(Duration.ofMillis(999));
        w.recordSuccess();
        assertEquals(2l, w.getSnapshot().getTotalSuccess());

        advance(Duration.ofMillis(1));
        w.recordSuccess();
        assertEquals(1l, w.getSnapshot().getTotalSuccess() );
    }

    @Test
    @DisplayName("accumulate data across window range")
    void accumulateDataAcrossWindowRange() {
        SlidingWindow w = window(5);
        for (int i = 0; i < 5; i++) {
            w.recordSuccess();
            if (i < 4) {
                advance(Duration.ofSeconds(1));
            }

        }
        assertEquals(5l, w.getSnapshot().getTotalSuccess());
        advance(Duration.ofSeconds(1));
        //first bucket out
        assertEquals(4l, w.getSnapshot().getTotalSuccess());
    }

    @Test
    @DisplayName("window boudnary: diff < windowSize count in, diff = windowSize count out")
    void expiryBoudnary() {
        SlidingWindow w = window(3);
        w.recordFailure();
        advance(Duration.ofSeconds(3));
        w.recordSuccess();

        SlidingWindow.Snapshot s= w.getSnapshot();
        assertEquals(1l, s.getTotalSuccess());
        assertEquals(0l, s.getTotalFailure());
    }

    @Test
    @DisplayName("reset all buckets")
    void resetAllBuckets() {
        SlidingWindow w = window(5);
        w.recordSuccess();
        w.recordFailure();
        w.reset();
        SlidingWindow.Snapshot s= w.getSnapshot();
        assertEquals(0l, s.getTotalCalls());

        w.recordFailure();
        assertEquals(1l, w.getSnapshot().getTotalFailure());

    }

    @Test
    @DisplayName("work normally when nanao time is negative")
    void workWithNegtiveClock() {
        now.set(-10L * 1_000_000_000L);
        SlidingWindow w = window(5);
        w.recordSuccess();
        w.recordFailure();

        SlidingWindow.Snapshot s = w.getSnapshot();
        assertEquals(1l, s.getTotalSuccess());
        assertEquals(1l, s.getTotalFailure());
    }

    @Test
    @DisplayName("window size should be positive")
    void rejectNonPositiveWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(0, now::get));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(-1, now::get));
    }

    @Test
    @DisplayName("concurrent in 1s, count will be recorded")
    void concurrentWritesdoNotLoseCounts() throws Exception {
        SlidingWindow w = window(5);
        int threads = 8;
        int preThreads = 10_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            for (int t= 0; t < threads; t++) {
                Callable<Void> c = ()-> {
                    readyLatch.countDown();
                    startLatch.await();
                    for (int i  = 0; i < preThreads; i++) {
                        w.recordSuccess();
                        w.recordFailure();
                    }
                    return null;
                };
                futures.add(pool.submit(c));

            }
            assertTrue(readyLatch.await(5,TimeUnit.SECONDS));
            startLatch.countDown();

            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

        }finally {
            pool.shutdown();
        }

        SlidingWindow.Snapshot s= w.getSnapshot();
        assertEquals(threads * preThreads, s.getTotalSuccess());
        assertEquals(threads * preThreads, s.getTotalFailure());

    }
}
