package gov.state.missionchat.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final String sharedPassword;

    public AuthController(Environment environment) {
        this.sharedPassword = normalize(environment.getProperty("missionchat.auth.shared-password"));
    }

    @GetMapping("/unlock")
    public String unlockPage() {
        return "forward:/unlock.html";
    }

    @PostMapping("/unlock")
    public String unlock(@RequestParam(name = "password", required = false) String password, HttpSession session) {

        // No password configured means the gate stays shut rather than open.
        if (this.sharedPassword == null || !this.sharedPassword.equals(password)) {
            return "redirect:/unlock?error=1";
        }

        session.setAttribute(AuthConstants.SESSION_AUTH_ATTRIBUTE, Boolean.TRUE);
        session.setMaxInactiveInterval(AuthConstants.SESSION_TIMEOUT_SECONDS);

        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/unlock";
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
