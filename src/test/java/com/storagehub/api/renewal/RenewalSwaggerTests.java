package com.storagehub.api.renewal;

import static org.assertj.core.api.Assertions.assertThat;
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
class RenewalSwaggerTests {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Test void publishesReadFiltersAndDocumentsCommandsAsDeferred() throws Exception {
        String base="http://localhost:"+environment.getProperty("local.server.port");
        var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var paths=mapper.readTree(response.body()).path("paths");
        for(String role:new String[]{"customer","manager"}) {
            var list=paths.path("/api/"+role+"/renewals").path("get");
            assertThat(list.path("parameters").toString()).contains("page","size","status","rentalId","sort");
            assertThat(list.path("security").toString()).contains("bearerAuth");
            var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+"/api/"+role+"/renewals")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
        for(String path:new String[]{"/api/customer/rentals/{id}/renewal-quote","/api/customer/rentals/{id}/renewal-requests","/api/customer/renewals/{id}/cancel","/api/manager/renewals/{id}/decision"}) {
            var responses=paths.path(path).path("post").path("responses");
            assertThat(responses.has("409")).isTrue();
            assertThat(responses.has(path.endsWith("renewal-requests")?"201":"200")).isTrue();
        }
    }
}
