package gov.state.missionchat.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PasswordGateInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        HttpSession session = request.getSession(false);
        boolean authenticated = session != null && Boolean.TRUE.equals(session.getAttribute(AuthConstants.SESSION_AUTH_ATTRIBUTE));

        if (authenticated) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/api/chat")
                || uri.startsWith("/api/cableschat")
                || uri.startsWith("/api/documentchat")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required.\"}");
            return false;
        }

        response.sendRedirect(request.getContextPath() + "/unlock");
        return false;
    }
}
