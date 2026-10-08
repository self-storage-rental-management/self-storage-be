package com.storagehub.api.rental;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.address=127.0.0.1")
@ActiveProfiles("test")
class RentalSwaggerTests {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Test void openApiPublishesFourReadOperationsAndBearerScheme() throws Exception {
        String base="http://localhost:"+environment.getProperty("local.server.port");
        var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var json=mapper.readTree(response.body());
        assertThat(json.path("components").path("schemas").toString()).contains("securityDepositAmount","billingMode");
        for(String role:new String[]{"customer","manager"}) {
            var list=json.path("paths").path("/api/"+role+"/rentals").path("get");
            var detail=json.path("paths").path("/api/"+role+"/rentals/{id}").path("get");
            assertThat(list.isMissingNode()).isFalse(); assertThat(detail.isMissingNode()).isFalse();
            assertThat(list.path("parameters").toString()).contains("page","size","sort","endFrom");
            assertThat(list.path("responses").has("200")).isTrue();
            assertThat(detail.path("responses").has("404")).isTrue();
            assertThat(list.path("security").toString()).contains("bearerAuth");
        }
        assertThat(json.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+"/api/customer/rentals")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
    }
}
