package com.storagehub.common.api;

import java.util.*;

/** Visit the authorized queue once, retaining only the requested sorted prefix. */
public final class SortedQueuePage<T> {
    private final Comparator<T> order;
    private final PriorityQueue<T> retained;
    private final long offset, limit;
    private final int size;
    private long total;
    public SortedQueuePage(int page, int size, Comparator<T> order) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page/size");
        this.order = order; this.size = size; offset = (long) page * size; limit = offset + size;
        retained = new PriorityQueue<>(11, order.reversed());
    }
    public void accept(T value) {
        total++;
        if (retained.size() < limit) retained.add(value);
        else if (order.compare(value, retained.peek()) < 0) { retained.poll(); retained.add(value); }
    }
    public List<T> rows() {
        var rows = new ArrayList<>(retained); rows.sort(order);
        int start = (int) Math.min(offset, rows.size());
        return List.copyOf(rows.subList(start, Math.min(start + size, rows.size())));
    }
    public long total() { return total; }
}
