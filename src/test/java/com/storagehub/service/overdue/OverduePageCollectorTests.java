package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OverduePageCollectorTests {
    record Row(int priority,String ref) {}
    @Test void everyPageMatchesFullSortForBothDirectionsAndEqualPriorities() {
        var rows=IntStream.range(0,257).mapToObj(i->new Row(i%5,String.format("TEST-%04d",i))).toList();
        for(boolean desc:List.of(true,false))for(int page=0;page<17;page++) {
            Comparator<Row> primary=Comparator.comparingInt(Row::priority);if(desc)primary=primary.reversed();
            var order=primary.thenComparing(Row::ref);var collector=new OverduePageCollector<Row>(page,20,order);
            var shuffled=new ArrayList<>(rows);Collections.shuffle(shuffled,new Random(73+page));shuffled.forEach(collector::accept);
            var sorted=rows.stream().sorted(order).toList();int from=Math.min(page*20,rows.size());
            assertThat(collector.page()).containsExactlyElementsOf(sorted.subList(from,Math.min(from+20,rows.size())));
            assertThat(collector.total()).isEqualTo(rows.size());assertThat(collector.retainedCount()).isLessThanOrEqualTo(Math.min((page+1)*20,rows.size()));
        }
    }
    @Test void filteringBeforeCollectionPreservesTotalNotRentalCount() {
        var c=new OverduePageCollector<Row>(1,2,Comparator.comparing(Row::ref));
        IntStream.range(0,12).mapToObj(i->new Row(i%2,"TEST-"+i)).filter(r->r.priority()==1).forEach(c::accept);
        assertThat(c.total()).isEqualTo(6);assertThat(c.page()).extracting(Row::ref).containsExactly("TEST-3","TEST-5");
    }
    @Test void emptyAndOutOfRangePagesRemainEmptyWithoutIntegerOverflow() {
        var c=new OverduePageCollector<Integer>(Integer.MAX_VALUE,100,Comparator.naturalOrder());
        assertThat(c.page()).isEmpty();c.accept(1);assertThat(c.page()).isEmpty();assertThat(c.total()).isEqualTo(1);
    }
    @Test void syntheticFirstPageRetainsOnlyPageSizeWhileCountingEveryCase() {
        var c=new OverduePageCollector<Integer>(0,20,Comparator.naturalOrder());long start=System.nanoTime();
        for(int i=100000;i>0;i--)c.accept(i);
        assertThat(c.total()).isEqualTo(100000);assertThat(c.retainedCount()).isEqualTo(20);
        assertThat(c.page()).containsExactlyElementsOf(IntStream.rangeClosed(1,20).boxed().toList());
        System.out.println("TEST_ONLY collector: cases=100000 retained=20 elapsedMs="+(System.nanoTime()-start)/1_000_000);
    }
    @Test void invalidParametersAreRejected() {
        assertThatThrownBy(()->new OverduePageCollector<Integer>(-1,20,Comparator.naturalOrder())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OverduePageCollector<Integer>(0,101,Comparator.naturalOrder())).isInstanceOf(IllegalArgumentException.class);
    }
}
