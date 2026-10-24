package com.storagehub.api.reservation;

import com.storagehub.security.ActorContext;
import com.storagehub.service.GoodsCatalogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoodsCatalogOrderRoutingTests {
    @Test void categoryOrderUsesDedicatedRouteAndPreservesRequestedIds() throws Exception {
        var actors = mock(ActorContext.class);
        var service = mock(GoodsCatalogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ManagerGoodsCatalogController(actors, service)).build();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.reorderCategories(null, List.of(second, first))).thenReturn(new GoodsCatalogResponse(List.of()));
        mvc.perform(post("/api/manager/goods-catalog/ordering/categories")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\":[\"" + second + "\",\"" + first + "\"]}"))
            .andExpect(status().isOk());
        verify(service).reorderCategories(null, List.of(second, first));
        verify(service, never()).updateCategory(any(), any(), any());
    }
}
