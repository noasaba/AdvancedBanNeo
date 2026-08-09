package me.leoko.advancedban.network.protocol;

import java.util.concurrent.atomic.AtomicLong;

/** Accepts each positive inbound sequence at most once and rejects stale/out-of-order frames. */
final class ReplayGuard {
    private final AtomicLong highestAccepted = new AtomicLong(0L);

    boolean accept(long sequence) {
        if (sequence <= 0) {
            return false;
        }
        long current;
        do {
            current = highestAccepted.get();
            if (sequence <= current) {
                return false;
            }
        } while (!highestAccepted.compareAndSet(current, sequence));
        return true;
    }
}
