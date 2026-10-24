package vip.mystery0.spw.navidrome.ui;

/** 页面级请求代次：切页、重试和退出后忽略旧结果。 */
public final class ViewRequest implements AutoCloseable {
    private long generation;
    private boolean closed;

    public synchronized long begin() {
        return ++generation;
    }

    public synchronized boolean accept(long token) {
        return !closed && token == generation;
    }

    @Override
    public synchronized void close() {
        closed = true;
        ++generation;
    }
}
