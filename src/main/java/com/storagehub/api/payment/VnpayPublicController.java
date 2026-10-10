package com.storagehub.api.payment;

import com.storagehub.config.VnpayProperties;
import com.storagehub.service.VnpayService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/payments/vnpay")
@RequiredArgsConstructor
public class VnpayPublicController {

    private final VnpayService service;
    private final VnpayProperties properties;

    @GetMapping("/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> query) {
        VnpayService.CallbackResult result = service.processCallback(query);
        return Map.of("RspCode", result.rspCode(), "Message", result.message());
    }

    @GetMapping("/return")
    public ResponseEntity<Void> returnFromVnpay(@RequestParam Map<String, String> query) {
        VnpayService.CallbackResult result = service.processCallback(query);
        Map<String, String> target = new LinkedHashMap<>();
        target.put("vnpayReturn", "true");
        target.put("status", result.success() ? "success" : "failed");
        target.put("code", result.rspCode());
        if (result.reservationId() != null) target.put("reservationId", result.reservationId().toString());
        String separator = properties.getFrontendReturnUrl().contains("?") ? "&" : "?";
        String params = target.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .reduce((left, right) -> left + "&" + right).orElse("");
        return ResponseEntity.status(302).location(java.net.URI.create(properties.getFrontendReturnUrl() + separator + params)).build();
    }
}
