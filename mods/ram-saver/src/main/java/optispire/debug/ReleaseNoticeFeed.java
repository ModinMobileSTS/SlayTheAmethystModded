package optispire.debug;

import com.badlogic.gdx.graphics.RamSaverReleaseEvents;

/** Pages a burst without sampling it away; explicitly reports bounded-history overruns. */
public final class ReleaseNoticeFeed {
    public static final int PAGE_SIZE = 6;
    private static final long PAGE_NANOS = 1_200_000_000L;
    private static final long LINGER_NANOS = 4_000_000_000L;
    public final RamSaverReleaseEvents.Event[] page = new RamSaverReleaseEvents.Event[PAGE_SIZE];
    public int size;
    public long overwritten;
    private long next = 1L;
    private long pageUntil;
    private long hideAfter;

    public void update(long now) {
        if (now < pageUntil) return;
        long first = RamSaverReleaseEvents.firstSequence();
        if (next < first) {
            overwritten += first - next;
            next = first;
        }
        long latest = RamSaverReleaseEvents.latestSequence();
        if (next > latest) {
            if (now >= hideAfter) clearPage();
            return;
        }
        clearPage();
        while (size < PAGE_SIZE && next <= latest) {
            RamSaverReleaseEvents.Event event = RamSaverReleaseEvents.event(next++);
            if (event == null) overwritten++;
            else page[size++] = event;
        }
        pageUntil = now + PAGE_NANOS;
        hideAfter = now + LINGER_NANOS;
    }

    public long pending() { return Math.max(0L, RamSaverReleaseEvents.latestSequence() - next + 1L); }

    private void clearPage() {
        for (int i = 0; i < size; i++) page[i] = null;
        size = 0;
    }
}
