package miniredis.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public final class MutableClock extends Clock {
    private final AtomicLong now;

    public MutableClock(long now) {
        this.now = new AtomicLong(now);
    }

    public void set(long millis) {
        now.set(millis);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    @Override public long millis() { return now.get(); }
}
