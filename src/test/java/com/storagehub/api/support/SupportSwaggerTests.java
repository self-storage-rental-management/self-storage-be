package com.storagehub.api.support;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1")
@ActiveProfiles("test")
class SupportSwaggerTests {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Test void swaggerPublishesD5SecurityStateGatesAndSeparateRoleResponsibilities() throws Exception {
        var base="http://127.0.0.1:"+environment.getProperty("local.server.port");var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var paths=mapper.readTree(response.body()).path("paths");
        for(String role:new String[]{"customer","manager","staff"}){
            var list=paths.path("/api/"+role+"/support-tickets").path("get");
            assertThat(list.isMissingNode()).isFalse();assertThat(list.path("security").toString()).contains("bearerAuth");
            assertThat(list.path("parameters").toString()).contains("page","size","status","search");
        }
        for(String suffix:new String[]{"accept","resolution","request-information","escalations"}){
            var op=paths.path("/api/staff/support-tickets/{id}/"+suffix).path("post");
            assertThat(op.isMissingNode()).as(suffix).isFalse();assertThat(op.path("parameters").toString()).contains("Idempotency-Key");
            assertThat(op.path("responses").has("409")).isTrue();
        }
        assertThat(paths.has("/api/manager/support-tickets/{id}/resolution")).isFalse();
        assertThat(paths.has("/api/customer/support-tickets/{id}/events")).isFalse();
        for(String role:new String[]{"customer","manager","staff"}){
            var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+"/api/"+role+"/support-tickets")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
    }
}
