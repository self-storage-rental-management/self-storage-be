package com.storagehub.api.support;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.SupportTicketStatus;
import java.util.*;
import org.springframework.util.MultiValueMap;

public record SupportQuery(int page,int size,SupportTicketStatus status,UUID facilityId,UUID staffId,
                           String search,String sort,boolean descending) {
    public static SupportQuery parse(MultiValueMap<String,String> values,boolean manager,boolean timeline) {
        Set<String> allowed=timeline?Set.of("page","size"):manager
            ?Set.of("page","size","status","facilityId","staffId","search","sort")
            :Set.of("page","size","status","search","sort");
        if(values.entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().size()!=1))throw invalid();
        try {
            int page=Integer.parseInt(value(values,"page","0")),size=Integer.parseInt(value(values,"size","20"));
            if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw invalid();
            String search=value(values,"search","").trim();if(search.length()>200)throw invalid();
            String[] order=value(values,"sort","createdAt,desc").split(",",-1);
            if(order.length!=2||!Set.of("createdAt","updatedAt","id","subject").contains(order[0])||!Set.of("asc","desc").contains(order[1]))throw invalid();
            return new SupportQuery(page,size,values.containsKey("status")?SupportTicketStatus.valueOf(values.getFirst("status")):null,
                uuid(values,"facilityId"),uuid(values,"staffId"),search,order[0],order[1].equals("desc"));
        } catch(IllegalArgumentException e) {throw invalid();}
    }
    public static void none(MultiValueMap<String,String> values) {if(!values.isEmpty())throw invalid();}
    private static String value(MultiValueMap<String,String> p,String key,String fallback){return p.containsKey(key)?p.getFirst(key):fallback;}
    private static UUID uuid(MultiValueMap<String,String> p,String key){return p.containsKey(key)?UUID.fromString(p.getFirst(key)):null;}
    private static RuntimeException invalid(){return ApiExceptions.validation("Unsupported, repeated or invalid Support query",null);}
}
