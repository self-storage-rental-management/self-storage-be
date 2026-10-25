package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.reservation.GoodsItemRequest;
import java.util.List;

final class GoodsSnapshotCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<GoodsItemRequest>> TYPE = new TypeReference<>() {};

    private GoodsSnapshotCodec() {}

    static boolean sameDeclaration(List<GoodsItemRequest> quoted, List<GoodsItemRequest> supplied) {
        var left = JSON.valueToTree(quoted);
        var right = JSON.valueToTree(supplied);
        for (var items : List.of(left, right)) {
            for (var item : items) {
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) item;
                object.remove(List.of("categoryCode", "categoryName", "materialCode"));
                if (supplied.stream().anyMatch(value -> value.getCatalogMaterialId() != null
                    && value.getCatalogMaterialId().toString().equals(object.path("catalogMaterialId").asText()))) {
                    // Display names can change while the selected catalog identity stays the same.
                    object.remove("materialName");
                }
                if (supplied.stream().allMatch(value -> value.getCatalogCategoryId() == null)) {
                    object.remove(List.of("catalogCategoryId", "catalogMaterialId"));
                }
            }
        }
        return left.equals(right);
    }

    static String encode(List<GoodsItemRequest> items) {
        try { return JSON.writeValueAsString(items); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not snapshot quote goods", exception); }
    }

    static List<GoodsItemRequest> decode(String snapshot) {
        try { return JSON.readValue(snapshot, TYPE); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not read quote goods snapshot", exception); }
    }

    static String encodeObject(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create immutable snapshot", exception); }
    }
}
