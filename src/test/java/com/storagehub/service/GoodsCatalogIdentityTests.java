package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GoodsCatalogIdentityTests {
    @Test
    void managerDefinedCategoryUsesCatalogIdentityAndDoesNotRequireOtherGoodsReview() {
        var types = mock(GoodsTypeCatalogRepository.class);
        var materials = mock(GoodsMaterialCatalogRepository.class);
        var service = new GoodsCatalogService(types, materials, null, null,
            mock(org.springframework.context.ApplicationEventPublisher.class));
        var type = new GoodsTypeCatalog();
        org.springframework.test.util.ReflectionTestUtils.setField(type, "id", UUID.randomUUID());
        type.setCode("CAT-ABC123");
        type.setDisplayName("Thiết bị kiểm thử");
        type.setStatus(GoodsCatalogStatus.ACTIVE);
        var material = new GoodsMaterialCatalog();
        org.springframework.test.util.ReflectionTestUtils.setField(material, "id", UUID.randomUUID());
        material.setGoodsType(type);
        material.setCode("MAT-ABC123");
        material.setDisplayName("Hợp kim");
        material.setStatus(GoodsCatalogStatus.ACTIVE);
        material.setFragile(true);
        when(types.findById(type.getId())).thenReturn(Optional.of(type));
        when(materials.findAllByGoodsType_IdAndStatusOrderByDisplayNameAsc(type.getId(), GoodsCatalogStatus.ACTIVE))
            .thenReturn(List.of(material));
        var unit = new UnitType();
        unit.setAcceptedGoodsJson("{\"categoryIds\":[\""+type.getId()+"\"],\"materialIds\":[\""+material.getId()+"\"]}");
        var request = new GoodsItemRequest();
        request.setCategory(GoodsCategory.OTHER);
        request.setCatalogCategoryId(type.getId());
        request.setCatalogMaterialId(material.getId());
        request.setCategoryName("Tên giả mạo");
        service.validateForUnitType(unit, request, 0);
        assertThat(request.getCategoryName()).isEqualTo(type.getDisplayName());
        assertThat(request.getMaterialName()).isEqualTo(material.getDisplayName());
        assertThat(request.isFragile()).isFalse();
        assertThat(ReservationCompatibilityService.isOther(request)).isFalse();
        var snapshot = GoodsSnapshotCodec.decode(GoodsSnapshotCodec.encode(List.of(request))).getFirst();
        type.setDisplayName("Tên mới");
        material.setDisplayName("Vật liệu mới");
        assertThat(snapshot.getCategoryName()).isEqualTo("Thiết bị kiểm thử");
        assertThat(snapshot.getMaterialName()).isEqualTo("Hợp kim");
        request.setCatalogMaterialId(UUID.randomUUID());
        assertThatThrownBy(() -> service.validateForUnitType(unit, request, 0))
            .isInstanceOf(com.storagehub.common.api.ApiException.class);
    }
}
