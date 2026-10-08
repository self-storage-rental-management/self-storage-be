package com.storagehub.api.renewal;

import com.storagehub.common.api.ApiExceptions;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record RenewalOperationQuery(int page,int size) {
    public static RenewalOperationQuery parse(MultiValueMap<String,String> query) {
        if(query.entrySet().stream().anyMatch(e->!Set.of("page","size").contains(e.getKey())||e.getValue().size()!=1))throw invalid();
        try {int page=Integer.parseInt(query.getFirst("page")==null?"0":query.getFirst("page"));int size=Integer.parseInt(query.getFirst("size")==null?"20":query.getFirst("size"));
            if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw invalid();return new RenewalOperationQuery(page,size);
        }catch(IllegalArgumentException e){throw invalid();}
    }
    public static void none(MultiValueMap<String,String> query){if(!query.isEmpty())throw invalid();}
    private static RuntimeException invalid(){return ApiExceptions.validation("Unsupported operation query",null);}
}
