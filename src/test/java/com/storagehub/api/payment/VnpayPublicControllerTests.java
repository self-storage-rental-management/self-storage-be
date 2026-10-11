package com.storagehub.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import com.storagehub.config.VnpayProperties;
import com.storagehub.service.VnpayService;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VnpayPublicControllerTests {

    @Test
    void returnUrlOpensTheReservationPageAndPreservesTheReservationTarget() {
        VnpayProperties properties = new VnpayProperties();
        properties.setFrontendReturnUrl("http://localhost:8443/");
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        URI location = new VnpayPublicController(null, properties).frontendLocation(
            new VnpayService.CallbackResult("00", "Confirm Success", paymentId, reservationId, true)
        );

        assertThat(location).isNotNull();
        assertThat(location.toString()).contains(
            "page=reservations",
            "vnpayReturn=true",
            "status=success",
            "code=00",
            "reservationId=" + reservationId
        );
    }
}
