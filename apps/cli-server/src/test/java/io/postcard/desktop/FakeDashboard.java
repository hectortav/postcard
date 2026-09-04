package io.postcard.desktop;

/**
 * Test double for {@link Dashboard}: records calls so wiring can be asserted without starting
 * a browser. Mirrors the real contract — {@code open()} is idempotent with respect to state.
 */
public final class FakeDashboard implements Dashboard {
    public int opens;
    public int closes;
    private boolean open;

    @Override public void open() { opens++; open = true; }

    @Override public boolean isOpen() { return open; }

    @Override public void close() { closes++; open = false; }
}
