package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.domain.model.GoodsCategory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoodsSnapshotCodecTests {
    @Test
    void comparesCatalogIdentityInsteadOfMutableDisplayName() {
        var quoted = new GoodsItemRequest();
        quoted.setCategory(GoodsCategory.TOYS);
        quoted.setCatalogCategoryId(UUID.randomUUID());
        quoted.setCatalogMaterialId(UUID.randomUUID());
        quoted.setCategoryName("Tên tại thời điểm báo giá");
        quoted.setMaterialName("Tên vật liệu mới");
        var supplied = GoodsSnapshotCodec.decode(GoodsSnapshotCodec.encode(List.of(quoted))).getFirst();
        supplied.setMaterialName("Tên vật liệu cũ");
        supplied.setCategoryName("Tên khác do trình duyệt gửi");
        assertThat(GoodsSnapshotCodec.sameDeclaration(List.of(quoted), List.of(supplied))).isTrue();
        supplied.setQuantity(2);
        assertThat(GoodsSnapshotCodec.sameDeclaration(List.of(quoted), List.of(supplied))).isFalse();
    }

    @Test
    void legacyClientWithoutIdsMustStillMatchMaterialName() {
        var quoted = new GoodsItemRequest();
        quoted.setCategory(GoodsCategory.TOYS);
        quoted.setCatalogCategoryId(UUID.randomUUID());
        quoted.setCatalogMaterialId(UUID.randomUUID());
        quoted.setMaterialName("Gỗ");
        var supplied = new GoodsItemRequest();
        supplied.setCategory(GoodsCategory.TOYS);
        supplied.setMaterialName("Gỗ");
        assertThat(GoodsSnapshotCodec.sameDeclaration(List.of(quoted), List.of(supplied))).isTrue();
        supplied.setMaterialName("Kim loại");
        assertThat(GoodsSnapshotCodec.sameDeclaration(List.of(quoted), List.of(supplied))).isFalse();
    }
}
