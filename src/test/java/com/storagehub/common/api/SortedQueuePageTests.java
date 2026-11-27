package com.storagehub.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class SortedQueuePageTests {
    @Test void findsUrgentWorkEvenWhenItArrivesAfterTheFirstPageAndPreservesTotals() {
        var first=new SortedQueuePage<Integer>(0,2,Comparator.naturalOrder());
        var second=new SortedQueuePage<Integer>(1,2,Comparator.naturalOrder());
        for(int value:new int[]{20,30,40,2,1,3}) { first.accept(value); second.accept(value); }
        assertThat(first.rows()).containsExactly(1,2);
        assertThat(second.rows()).containsExactly(3,20);
        assertThat(first.total()).isEqualTo(6);
    }
}
