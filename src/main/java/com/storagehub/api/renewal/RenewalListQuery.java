package com.storagehub.api.renewal;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.RenewalStatus;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.util.MultiValueMap;

public record RenewalListQuery(int page, int size, RenewalStatus status, UUID rentalId,
    UUID facilityId, String search, Sort sort) {
    public static RenewalListQuery parse(MultiValueMap<String,String> p, boolean manager) {
        Set<String> keys=manager?Set.of("page","size","status","rentalId","facilityId","search","sort")
            :Set.of("page","size","status","rentalId","sort");
        if(p.entrySet().stream().anyMatch(e->!keys.contains(e.getKey())||e.getValue().size()!=1)) throw invalid();
        try {
            int page=Integer.parseInt(p.getFirst("page")==null?"0":p.getFirst("page"));
            int size=Integer.parseInt(p.getFirst("size")==null?"20":p.getFirst("size"));
            String search=p.getFirst("search")==null?"":p.getFirst("search").trim();
            if(page<0||size<1||size>100||search.length()>200) throw invalid();
            String[] pair=(p.getFirst("sort")==null?"createdAt,desc":p.getFirst("sort")).split(",",-1);
            if(pair.length!=2||!Set.of("createdAt","newEndDate","amount","id").contains(pair[0])
                ||!Set.of("asc","desc").contains(pair[1]))throw invalid();
            Sort sort=Sort.by(Sort.Direction.fromString(pair[1]),pair[0]);
            if(!pair[0].equals("id"))sort=sort.and(Sort.by("id"));
            return new RenewalListQuery(page,size,p.containsKey("status")?RenewalStatus.valueOf(p.getFirst("status")):null,
                uuid(p,"rentalId"),uuid(p,"facilityId"),search,sort);
        }catch(IllegalArgumentException e){throw invalid();}
    }
    public Pageable pageable(){return PageRequest.of(page,size,sort);}
    private static UUID uuid(MultiValueMap<String,String> p,String key){return p.containsKey(key)?UUID.fromString(p.getFirst(key)):null;}
    private static RuntimeException invalid(){return ApiExceptions.validation("Invalid or unsupported renewal query",null);}
}
