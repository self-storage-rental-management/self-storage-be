package com.storagehub.api.overdue;

import com.storagehub.common.api.ApiExceptions;
import java.util.*;
import org.springframework.util.MultiValueMap;

public record OverdueQuery(int page,int size,UUID facilityId,String kind,String search,String sort,boolean descending) {
    public static OverdueQuery parse(MultiValueMap<String,String> query){
        if(query.entrySet().stream().anyMatch(e->!Set.of("page","size","facilityId","kind","search","sort").contains(e.getKey())||e.getValue().size()!=1))throw invalid();
        try {int page=Integer.parseInt(query.getFirst("page")==null?"0":query.getFirst("page"));int size=Integer.parseInt(query.getFirst("size")==null?"20":query.getFirst("size"));String kind=query.getFirst("kind")==null?"ALL":query.getFirst("kind");String search=query.getFirst("search")==null?"":query.getFirst("search").trim();String[] sort=(query.getFirst("sort")==null?"priority,desc":query.getFirst("sort")).split(",",-1);
            if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE||!Set.of("ALL","RENTAL_TERM","PAYMENT_DUE").contains(kind)||search.length()>200||sort.length!=2||!Set.of("priority","overdueDays","caseRef").contains(sort[0])||!Set.of("asc","desc").contains(sort[1]))throw invalid();
            return new OverdueQuery(page,size,query.containsKey("facilityId")?UUID.fromString(query.getFirst("facilityId")):null,kind,search,sort[0],sort[1].equals("desc"));
        }catch(IllegalArgumentException e){throw invalid();}
    }
    private static RuntimeException invalid(){return ApiExceptions.validation("Unsupported overdue query",null);}
}
