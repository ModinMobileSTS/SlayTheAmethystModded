package optispire.debug;

import com.badlogic.gdx.graphics.RamSaverReleaseEvents;

/** Newest-first, scrollable view of the bounded visual history. Never expires during idle time. */
public final class ReleaseNoticeFeed {
    public static final int PAGE_SIZE = 6;
    public final RamSaverReleaseEvents.Event[] page = new RamSaverReleaseEvents.Event[PAGE_SIZE];
    public int size;
    public long overwritten;
    private long latest;
    private int offset;
    private int available;

    public void update(long now) {
        long nextLatest = RamSaverReleaseEvents.latestSequence();
        if (offset > 0) offset = (int) Math.min(RamSaverReleaseEvents.CAPACITY,
                offset + Math.max(0L, nextLatest - latest));
        latest = nextLatest;
        long first = RamSaverReleaseEvents.firstSequence();
        available = (int) Math.max(0L, latest - first + 1L);
        overwritten = Math.max(0L, first - 1L);
        offset = Math.max(0, Math.min(offset, maxOffset()));
        clearPage();
        long sequence = latest - offset;
        while (size < PAGE_SIZE && sequence >= first) {
            RamSaverReleaseEvents.Event event = RamSaverReleaseEvents.event(sequence--);
            if (event != null) page[size++] = event;
        }
    }

    public void scroll(int rows) {
        offset = (int) Math.max(0L, Math.min((long) offset + rows, maxOffset()));
    }

    public void scrollFraction(float fraction) {
        offset = Math.round(Math.max(0f, Math.min(1f, fraction)) * maxOffset());
    }

    public void newest() { offset = 0; }
    public int offset() { return offset; }
    public int available() { return available; }
    public int maxOffset() { return Math.max(0, available - PAGE_SIZE); }
    public long pending() { return Math.max(0, available - offset - size); }

    private void clearPage() {
        for (int i = 0; i < size; i++) page[i] = null;
        size = 0;
    }
}
