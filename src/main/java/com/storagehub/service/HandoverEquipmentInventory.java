package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.ReturnCaseResponse;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import java.math.BigDecimal;

/** Equipment quantities from the accepted physical-unit layout, never the current mutable layout. */
final class HandoverEquipmentInventory {
    private HandoverEquipmentInventory() {}

    static ReturnCaseResponse.Equipment read(ObjectMapper objectMapper, ReservationPricingSnapshot snapshot, BigDecimal price) {
        try {
            String layoutJson = null;
            if (snapshot != null) {
                for (String json : new String[] { snapshot.getAssignedUnitSnapshotJson(), snapshot.getUnitTypeSnapshotJson() }) {
                    if (json == null || json.isBlank()) continue;
                    var node = objectMapper.readTree(json).get("rackLayoutJson");
                    if (node != null && !node.isNull() && !node.asText().isBlank()) {
                        layoutJson = node.isTextual() ? node.asText() : node.toString();
                        break;
                    }
                }
            }
            if (layoutJson == null) return new ReturnCaseResponse.Equipment(0, 0, null, price);
            var racks = objectMapper.readTree(layoutJson).get("racks");
            if (racks == null || !racks.isArray()) return new ReturnCaseResponse.Equipment(0, 0, null, price);
            int frames = 0, pallets = 0;
            for (var rack : racks) {
                int frameCount = rack.path("uprightFrameCount").asInt();
                int levels = rack.path("levelCount").asInt();
                int perCompartment = rack.path("palletsPerCompartment").asInt();
                if (frameCount < 2 || levels < 1 || perCompartment < 1)
                    return new ReturnCaseResponse.Equipment(0, 0, null, price);
                frames += frameCount;
                pallets += (frameCount - 1) * levels * perCompartment;
            }
            return new ReturnCaseResponse.Equipment(racks.size(), frames, pallets, price);
        } catch (Exception invalidSnapshot) {
            return new ReturnCaseResponse.Equipment(0, 0, null, price);
        }

    }
}
