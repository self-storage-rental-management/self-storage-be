package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.domain.model.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialContractSpecificationsTests {
    static BigDecimal money(String v){return new BigDecimal(v);}
    static ReservationPricingSnapshot snapshot(){var s=new ReservationPricingSnapshot();s.setPricingPackageCode("3M");s.setPricingPolicyVersion("v1");s.setRentalMonths(3);s.setMonthlyPrice(money("100"));s.setGrossRentalAmount(money("300"));s.setDiscountRate(money("0.1"));s.setDiscountAmount(money("30"));s.setNetRentalAmount(money("270"));s.setReservationDepositAmount(money("108"));s.setRemainingRentalAmount(money("162"));s.setSecurityDepositAmount(money("100"));s.setDueAtCheckIn(money("262"));s.setTotalInitialObligation(money("370"));return s;}
    @Test void acceptedInitialAmountsValidateWithoutInventingDueDate(){var s=snapshot();FinancialContractSpecifications.validateInitial(s);var spec=new FinancialContractSpecifications.Specification(UUID.randomUUID(),null,LedgerStore.Kind.RENT,s.getNetRentalAmount(),"snapshot:RENT");assertThatThrownBy(()->spec.requireDue(null)).hasMessageContaining("authoritative effective event and due date");}
    @Test void reservationDepositIsPartOfRentAndNotAdditionalDebt(){var s=snapshot();assertThat(s.getReservationDepositAmount().add(s.getRemainingRentalAmount())).isEqualByComparingTo(s.getNetRentalAmount());assertThat(s.getNetRentalAmount().add(s.getSecurityDepositAmount())).isEqualByComparingTo(s.getTotalInitialObligation());}
    @Test void inconsistentTotalsNegativeAndUnsupportedPrecisionRejected(){var s=snapshot();s.setTotalInitialObligation(money("478"));var inconsistent=s;assertThatThrownBy(()->FinancialContractSpecifications.validateInitial(inconsistent)).hasMessageContaining("inconsistent");s=snapshot();s.setSecurityDepositAmount(money("-1"));var neg=s;assertThatThrownBy(()->FinancialContractSpecifications.validateInitial(neg)).hasMessageContaining("negative");s=snapshot();s.setNetRentalAmount(money("270.001"));var precision=s;assertThatThrownBy(()->FinancialContractSpecifications.validateInitial(precision)).hasMessageContaining("rounding");}
    static RenewalQuoteResponse.Terms terms(BigDecimal deposit,BigDecimal remainder){var end=LocalDate.of(2026,10,25);return new RenewalQuoteResponse.Terms(end,end.plusDays(1),end.plusDays(1).plusMonths(3),end.plusMonths(3),UUID.randomUUID(),"3M",UUID.randomUUID(),"v1",3,money("100"),money("0.1"),money("300"),money("30"),money("270"),deposit,remainder,"VND","policy","v1",UUID.randomUUID(),UUID.randomUUID());}
    @Test void acceptedRenewalDecompositionRejectsMismatchedRemainder(){FinancialContractSpecifications.validateRenewal(terms(money("108"),money("162")));assertThatThrownBy(()->FinancialContractSpecifications.validateRenewal(terms(money("108"),money("163")))).hasMessageContaining("inconsistent");}
    @Test void quoteAloneCannotProduceObligationSpecifications(){assertThatThrownBy(()->FinancialContractSpecifications.accepted(null,terms(money("108"),money("162")))).hasMessageContaining("Accepted revision missing");}
}
