package com.storagehub.api.renewal;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1") @ActiveProfiles("test")
class RenewalOperationsSwaggerTests {
    @Autowired Environment environment;@Autowired ObjectMapper mapper;
    @Test void publishesD3D4RoutesAndProtectsThemWithoutMockSources() throws Exception {
        String base="http://localhost:"+environment.getProperty("local.server.port");var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        var paths=mapper.readTree(response.body()).path("paths");
        for(String path:List.of("/api/customer/renewals/{id}/simulated-payment","/api/customer/renewals/{id}/appointment","/api/customer/renewals/{id}/exception-confirmations","/api/staff/renewals/{id}/arrival","/api/staff/renewals/{id}/cash-receipts","/api/staff/renewals/{id}/completion","/api/manager/renewals/{id}/exception-decisions","/api/manager/renewals/{id}/refund-decisions","/api/manager/overdue-cases/{id}/follow-ups","/api/manager/overdue-cases/{id}/recovery-handoffs")){
            var operation=paths.path(path).path("post");assertThat(operation.isMissingNode()).isFalse();assertThat(operation.path("security").toString()).contains("bearerAuth");assertThat(operation.path("parameters").toString()).contains("Idempotency-Key");assertThat(operation.path("responses").has("409")).isTrue();
            String actual=path.replace("{id}",path.contains("overdue-cases")?"RENTAL_TERM:"+UUID.randomUUID():UUID.randomUUID().toString());
            var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+actual)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
        for(String path:List.of("/api/manager/renewals/{id}/staff-assignment","/api/manager/renewals/{id}/facility-fault-reviews")) {
            var operation=paths.path(path).path("post");assertThat(operation.isMissingNode()).isFalse();assertThat(operation.path("security").toString()).contains("bearerAuth");assertThat(operation.path("parameters").toString()).contains("Idempotency-Key");
            var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+path.replace("{id}",UUID.randomUUID().toString()))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
        var policyPath="/api/business/facilities/{facilityId}/renewal-policy";
        for(String method:List.of("get","put"))assertThat(paths.path(policyPath).path(method).path("security").toString()).contains("bearerAuth");
        var deniedPolicy=client.send(HttpRequest.newBuilder(URI.create(base+policyPath.replace("{facilityId}",UUID.randomUUID().toString()))).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(deniedPolicy.statusCode()).isEqualTo(401);
        assertThat(paths.path("/api/staff/renewal-appointments").path("get").path("parameters").toString()).contains("facilityId","date","status","page","size");
        assertThat(paths.path("/api/manager/overdue-cases").path("get").path("parameters").toString()).contains("kind","search","sort");
        assertThat(paths.has("/api/manager/renewals/{id}/completion")).isFalse();assertThat(paths.has("/api/manager/overdue-cases/{id}/fee-assessment")).isFalse();
    }
}
