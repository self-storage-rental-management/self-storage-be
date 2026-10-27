package com.storagehub.api.reservation;

import com.storagehub.validation.PhoneNumberPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

public class CreateReservationRequest {

    @Size(max = 50)
    private String acceptedSetupEarlyExitPolicyVersion;
    public String getAcceptedSetupEarlyExitPolicyVersion() { return acceptedSetupEarlyExitPolicyVersion; }
    public void setAcceptedSetupEarlyExitPolicyVersion(String value) { acceptedSetupEarlyExitPolicyVersion = value; }

    @NotNull
    private UUID quoteId;

    @NotBlank
    @Size(min = 9, max = 30)
    @Pattern(regexp = "^[0-9A-Za-z][0-9A-Za-z .-]{8,29}$", message = "identityDocumentNumber contains unsupported characters")
    private String identityDocumentNumber;

    @NotBlank
    @Size(max = 40)
    private String customerPhone;

    @NotBlank
    @Size(max = 500)
    private String customerAddress;

    @NotBlank
    @Size(max = 500)
    private String goodsCondition;

    @Size(max = 2000)
    private String notes;

    @NotEmpty
    @Valid
    private List<@NotNull @Valid GoodsItemRequest> goodsItems;

    private Instant appointmentAt;

    /** Must be explicitly true on the public create endpoint before payment is possible. */
    private Boolean acceptedTerms;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(UUID quoteId, String goodsCondition, String notes,
                                    List<GoodsItemRequest> goodsItems) {
        this(quoteId, goodsCondition, notes, goodsItems, null);
    }

    public CreateReservationRequest(UUID quoteId, String goodsCondition, String notes,
                                    List<GoodsItemRequest> goodsItems, Instant appointmentAt) {
        this.quoteId = quoteId;
        this.goodsCondition = goodsCondition;
        this.notes = notes;
        this.goodsItems = goodsItems;
        this.appointmentAt = appointmentAt;
        this.acceptedTerms = Boolean.TRUE;
    }

    public UUID getQuoteId() { return quoteId; }
    public void setQuoteId(UUID quoteId) { this.quoteId = quoteId; }
    public String getIdentityDocumentNumber() { return identityDocumentNumber; }
    public void setIdentityDocumentNumber(String identityDocumentNumber) { this.identityDocumentNumber = identityDocumentNumber; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<GoodsItemRequest> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<GoodsItemRequest> goodsItems) { this.goodsItems = goodsItems; }
    public Instant getAppointmentAt() { return appointmentAt; }
    public void setAppointmentAt(Instant appointmentAt) { this.appointmentAt = appointmentAt; }
    public Boolean getAcceptedTerms() { return acceptedTerms; }
    public void setAcceptedTerms(Boolean acceptedTerms) { this.acceptedTerms = acceptedTerms; }

    @AssertTrue(message = PhoneNumberPolicy.MESSAGE)
    public boolean isCustomerPhoneValid() {
        return PhoneNumberPolicy.isValid(customerPhone);
    }
}
