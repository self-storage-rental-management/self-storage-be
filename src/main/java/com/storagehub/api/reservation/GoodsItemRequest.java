package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class GoodsItemRequest {

    @NotNull private GoodsCategory category;
    private java.util.UUID catalogCategoryId;
    public java.util.UUID getCatalogCategoryId() { return catalogCategoryId; }
    public void setCatalogCategoryId(java.util.UUID value) { this.catalogCategoryId = value; }
    private java.util.UUID catalogMaterialId;
    public java.util.UUID getCatalogMaterialId() { return catalogMaterialId; }
    public void setCatalogMaterialId(java.util.UUID value) { this.catalogMaterialId = value; }
    private String categoryCode;
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String value) { this.categoryCode = value; }
    private String categoryName;
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String value) { this.categoryName = value; }
    private String materialCode;
    public String getMaterialCode() { return materialCode; }
    public void setMaterialCode(String value) { this.materialCode = value; }
    @Size(max = 160) private String customGoodsName;
    @Size(max = 160) private String materialName;
    @Size(max = 120) private String customMaterial;
    @NotBlank @Size(max = 1000) private String description;
    @Size(max = 1000) private String customerNote;
    @Min(1) private int quantity;
    @NotNull @DecimalMin(value = "0", inclusive = false) private BigDecimal lengthCm;
    @NotNull @DecimalMin(value = "0", inclusive = false) private BigDecimal widthCm;
    @NotNull @DecimalMin(value = "0", inclusive = false) private BigDecimal heightCm;
    @NotNull @DecimalMin(value = "0", inclusive = false) private BigDecimal weightPerItemKg;
    private boolean fragile;

    public GoodsItemRequest() {
    }

    public GoodsItemRequest(GoodsCategory category, String customGoodsName, String materialName,
                            String customMaterial, String description, String customerNote,
                            int quantity, BigDecimal lengthCm, BigDecimal widthCm,
                            BigDecimal heightCm, BigDecimal weightPerItemKg, boolean fragile) {
        this.category = category;
        this.customGoodsName = customGoodsName;
        this.materialName = materialName;
        this.customMaterial = customMaterial;
        this.description = description;
        this.customerNote = customerNote;
        this.quantity = quantity;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.weightPerItemKg = weightPerItemKg;
        this.fragile = fragile;
    }

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
    public String getCustomerNote() { return customerNote; }
    public void setCustomerNote(String customerNote) { this.customerNote = customerNote; }
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
}
