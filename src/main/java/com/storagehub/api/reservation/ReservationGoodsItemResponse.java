package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsReviewStatus;
import java.math.BigDecimal;
import java.util.UUID;

public class ReservationGoodsItemResponse {

    private UUID id;
    private GoodsCategory category;
    private String customGoodsName;
    private String materialName;
    private String customMaterial;
    private String description;
    private int quantity;
    private BigDecimal lengthCm;
    private BigDecimal widthCm;
    private BigDecimal heightCm;
    private BigDecimal weightPerItemKg;
    private boolean fragile;
    private String customerNote;
    private boolean requiresStaffReview;
    private GoodsReviewStatus reviewStatus;
    private String reviewNote;

    public ReservationGoodsItemResponse() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public GoodsCategory getCategory() { return category; }
    public void setCategory(GoodsCategory category) { this.category = category; }
    public String getCustomGoodsName() { return customGoodsName; }
    public void setCustomGoodsName(String customGoodsName) { this.customGoodsName = customGoodsName; }
    public String getMaterialName() { return materialName; }
    public void setMaterialName(String materialName) { this.materialName = materialName; }
    public String getCustomMaterial() { return customMaterial; }
    public void setCustomMaterial(String customMaterial) { this.customMaterial = customMaterial; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public void setLengthCm(BigDecimal lengthCm) { this.lengthCm = lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public void setWidthCm(BigDecimal widthCm) { this.widthCm = widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public void setHeightCm(BigDecimal heightCm) { this.heightCm = heightCm; }
    public BigDecimal getWeightPerItemKg() { return weightPerItemKg; }
    public void setWeightPerItemKg(BigDecimal weightPerItemKg) { this.weightPerItemKg = weightPerItemKg; }
    public boolean isFragile() { return fragile; }
    public void setFragile(boolean fragile) { this.fragile = fragile; }
    public String getCustomerNote() { return customerNote; }
    public void setCustomerNote(String customerNote) { this.customerNote = customerNote; }
    public boolean isRequiresStaffReview() { return requiresStaffReview; }
    public void setRequiresStaffReview(boolean requiresStaffReview) { this.requiresStaffReview = requiresStaffReview; }
    public GoodsReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(GoodsReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
