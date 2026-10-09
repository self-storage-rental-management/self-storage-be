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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1")
@ActiveProfiles("test")
class SupportSwaggerTests {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerMapping mappings;
    @Test void swaggerPublishesD5SecurityStateGatesAndSeparateRoleResponsibilities() throws Exception {
        var base="http://127.0.0.1:"+environment.getProperty("local.server.port");var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var paths=mapper.readTree(response.body()).path("paths");
        for(String role:new String[]{"customer","manager","staff"}){
            var list=paths.path(workflowPath(role)).path("get");
            assertThat(list.isMissingNode()).isFalse();assertThat(list.path("security").toString()).contains("bearerAuth");
            assertThat(list.path("parameters").toString()).contains("page","size","status","search");
        }
        for(String suffix:new String[]{"accept","resolution","request-information","escalations"}){
            var op=paths.path("/api/staff/support-workflows/{id}/"+suffix).path("post");
            assertThat(op.isMissingNode()).as(suffix).isFalse();assertThat(op.path("parameters").toString()).contains("Idempotency-Key");
            assertThat(op.path("responses").has("409")).isTrue();
        }
        assertThat(paths.has("/api/manager/support-tickets/{id}/resolution")).isFalse();
        assertThat(paths.has("/api/customer/support-workflows/{id}/events")).isFalse();
        assertThat(paths.has("/api/customer/support-tickets/{id}/close")).isFalse();
        assertThat(paths.has("/api/staff/support-tickets/{id}/accept")).isFalse();
        assertThat(paths.fieldNames()).toIterable().noneMatch(path ->
            path.startsWith("/api/customer/support-tickets") || path.startsWith("/api/staff/support-tickets"));
        for(String role:new String[]{"customer","manager","staff"}){
            var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+workflowPath(role))).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
    }

    @Test void d5WorkflowRoutesHaveOneHandlerAndRemovedLegacyRoutesStayAbsent() {
        assertThat(mappings.getHandlerMethods().keySet().stream()
            .flatMap(mapping -> mapping.getPatternValues().stream()).toList())
            .noneMatch(path -> path.startsWith("/api/customer/support-tickets")
                || path.startsWith("/api/staff/support-tickets"));
        assertHandler(RequestMethod.GET,workflowPath("customer"),CustomerSupportController.class);
        assertHandler(RequestMethod.POST,workflowPath("customer"),CustomerSupportController.class);
        assertHandler(RequestMethod.GET,workflowPath("customer")+"/{id}",CustomerSupportController.class);
        assertHandler(RequestMethod.POST,workflowPath("customer")+"/{id}/messages",CustomerSupportController.class);
        assertHandler(RequestMethod.GET,workflowPath("staff"),StaffSupportController.class);
        assertHandler(RequestMethod.POST,workflowPath("staff")+"/{id}/accept",StaffSupportController.class);
        assertHandler(RequestMethod.GET,workflowPath("manager"),ManagerSupportController.class);
    }

    private void assertHandler(RequestMethod method,String path,Class<?> controller) {
        var handlers=mappings.getHandlerMethods().entrySet().stream()
            .filter(entry->entry.getKey().getPatternValues().contains(path)
                && entry.getKey().getMethodsCondition().getMethods().contains(method))
            .map(entry->entry.getValue().getBeanType().getName()).toList();
        assertThat(handlers).as("%s %s",method,path).containsExactly(controller.getName());
    }

    private static String workflowPath(String role) {
        return "/api/"+role+(role.equals("manager")?"/support-tickets":"/support-workflows");
    }
}
