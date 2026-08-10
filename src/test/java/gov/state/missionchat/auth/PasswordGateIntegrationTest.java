package gov.state.missionchat.auth;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "missionchat.auth.shared-password=" + PasswordGateIntegrationTest.TEST_PASSWORD)
@AutoConfigureMockMvc
class PasswordGateIntegrationTest {

    static final String TEST_PASSWORD = "test-only-password";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootRedirectsToUnlockWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/unlock")));
    }

    @Test
    void missionChatRedirectsToUnlockWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/missionchat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/unlock")));
    }

    @Test
    void cablesChatRedirectsToUnlockWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/cableschat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/unlock")));
    }

    @Test
    void documentsChatRedirectsToUnlockWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/documentchat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/unlock")));
    }

    @Test
    void apiChatReturnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Authentication required")));
    }

    @Test
    void apiCablesChatReturnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/cableschat")
                        .contentType("application/json")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Authentication required")));
    }

    @Test
    void apiDocumentsChatReturnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/documentchat")
                        .contentType("application/json")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Authentication required")));
    }

    @Test
    void apiCablesChatMcpStatusReturnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/cableschat/mcp/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Authentication required")));
    }

    @Test
    void wrongPasswordRedirectsBackToUnlockWithError() throws Exception {
        mockMvc.perform(post("/unlock")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/unlock?error=1")));
    }

    @Test
    void correctPasswordUnlocksSessionForTwoHours() throws Exception {
        MvcResult unlockResult = mockMvc.perform(post("/unlock")
                        .param("password", TEST_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) unlockResult.getRequest().getSession(false);
        assertNotNull(session);
        assertEquals(Boolean.TRUE, session.getAttribute(AuthConstants.SESSION_AUTH_ATTRIBUTE));
        assertEquals(AuthConstants.SESSION_TIMEOUT_SECONDS, session.getMaxInactiveInterval());

        mockMvc.perform(get("/missionchat").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/cableschat").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/documentchat").session(session))
                .andExpect(status().isOk());
    }
}
