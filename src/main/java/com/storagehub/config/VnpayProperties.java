package com.storagehub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.vnpay")
public class VnpayProperties {
    private boolean enabled;
    private String tmnCode = "";
    private String hashSecret = "";
    private String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String apiUrl = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
    private String returnUrl = "http://localhost:8080/api/public/payments/vnpay/return";
    private String frontendReturnUrl = "http://localhost:8443/";
    private String ipnUrl = "";
}
