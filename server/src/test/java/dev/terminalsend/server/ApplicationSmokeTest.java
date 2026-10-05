package dev.terminalsend.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No SMTP in this test; GreenMail-backed mail tests arrive with the auth milestone.
@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApplicationSmokeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
}
