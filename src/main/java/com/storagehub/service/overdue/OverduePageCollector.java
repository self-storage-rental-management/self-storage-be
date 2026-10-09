package com.storagehub.service.overdue;

import java.util.*;

/** Retains only the requested sorted prefix; still visits EVERY scoped case for accurate totals. */
final class OverduePageCollector<T> {
    private final Comparator<T> order;
    private final PriorityQueue<T> retained;
    private final long offset, limit;
    private final int size;
    private long total;
    OverduePageCollector(int page,int size,Comparator<T> order) {
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Invalid page/size");
        this.order=Objects.requireNonNull(order);this.size=size;
        offset=(long)page*size;limit=offset+size;
        retained=new PriorityQueue<>(11,order.reversed());
    }
    void accept(T value) {
        total++;
        if(retained.size()<limit)retained.add(value);
        else if(order.compare(value,retained.peek())<0){retained.poll();retained.add(value);}
    }
    List<T> page() {
        var sorted=new ArrayList<>(retained);sorted.sort(order);
        int from=(int)Math.min(offset,sorted.size());
        return List.copyOf(sorted.subList(from,from+Math.min(size,sorted.size()-from)));
    }
    long total(){return total;}
    int retainedCount(){return retained.size();}
}
