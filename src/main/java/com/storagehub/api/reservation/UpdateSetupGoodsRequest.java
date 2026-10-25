package com.storagehub.api.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The single customer goods-declaration revision allowed during setup. */
public record UpdateSetupGoodsRequest(
    @NotEmpty @Size(max = 100) List<@Valid GoodsItemRequest> goodsItems,
    @Size(max = 500) String goodsCondition
) {
}
