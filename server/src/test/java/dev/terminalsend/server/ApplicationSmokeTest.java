package dev.terminalsend.server;

import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationSmokeTest extends IntegrationTest {

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
}
