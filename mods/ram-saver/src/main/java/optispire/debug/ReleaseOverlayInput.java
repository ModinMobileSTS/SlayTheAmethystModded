package optispire.debug;

import com.badlogic.gdx.InputProcessor;

/** Consumes only gestures that begin inside the panel; all other input keeps its original delegate. */
public final class ReleaseOverlayInput implements InputProcessor {
    private final ReleaseNoticeFeed feed;
    private InputProcessor delegate;
    private float x, y, width, height, row, lastY, dragRows;
    private int pointer = -1;
    private int forwardedPointers;
    private boolean released, scrollbar;

    public ReleaseOverlayInput(ReleaseNoticeFeed feed) { this.feed = feed; }
    public void delegate(InputProcessor value) { delegate = value; }
    public InputProcessor delegate() { return delegate; }

    public void bounds(float x, float y, float width, float height, float row) {
        this.x = x; this.y = y; this.width = width; this.height = height; this.row = row;
    }

    public boolean contains(float px, float py) {
        return width > 0f && px >= x && px <= x + width && py >= y && py <= y + height;
    }

    public boolean suppressGamePointer(float px, float py) {
        return pointer >= 0 || released || (forwardedPointers == 0 && contains(px, py));
    }

    public void finishInputFrame() { released = false; }

    public boolean wheel(int amount, float px, float py) {
        if (!contains(px, py)) return false;
        feed.scroll(amount);
        return true;
    }

    @Override public boolean touchDown(int px, int py, int id, int button) {
        if (pointer >= 0) return true;
        if (forwardedPointers != 0 || button != 0 || !contains(px, py)) {
            forwardedPointers |= 1 << (id & 31);
            return delegate != null && delegate.touchDown(px, py, id, button);
        }
        pointer = id; lastY = py; dragRows = 0f;
        scrollbar = px >= x + width - row;
        if (py <= y + row && px >= x + width - row * 5f) feed.newest();
        else if (scrollbar) scrollAt(py);
        return true;
    }

    @Override public boolean touchDragged(int px, int py, int id) {
        if (pointer < 0) return delegate != null && delegate.touchDragged(px, py, id);
        if (pointer == id) {
            if (scrollbar) scrollAt(py);
            else {
                dragRows += (lastY - py) / Math.max(1f, row);
                int rows = (int) dragRows;
                if (rows != 0) { feed.scroll(rows); dragRows -= rows; }
            }
            lastY = py;
        }
        return true;
    }

    @Override public boolean touchUp(int px, int py, int id, int button) {
        if (pointer < 0) {
            forwardedPointers &= ~(1 << (id & 31));
            return delegate != null && delegate.touchUp(px, py, id, button);
        }
        if (pointer == id) { pointer = -1; released = true; }
        return true;
    }

    private void scrollAt(float py) {
        feed.scrollFraction((py - y - row) / Math.max(1f, height - row * 2f));
    }

    @Override public boolean keyDown(int key) { return delegate != null && delegate.keyDown(key); }
    @Override public boolean keyUp(int key) { return delegate != null && delegate.keyUp(key); }
    @Override public boolean keyTyped(char value) { return delegate != null && delegate.keyTyped(value); }
    @Override public boolean mouseMoved(int px, int py) { return delegate != null && delegate.mouseMoved(px, py); }
    @Override public boolean scrolled(int amount) {
        com.badlogic.gdx.Input input = com.badlogic.gdx.Gdx.input;
        if (input != null && wheel(amount, input.getX(), input.getY())) return true;
        return delegate != null && delegate.scrolled(amount);
    }
}
