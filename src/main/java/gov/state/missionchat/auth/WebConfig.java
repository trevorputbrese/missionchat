package gov.state.missionchat.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PasswordGateInterceptor passwordGateInterceptor;

    public WebConfig(PasswordGateInterceptor passwordGateInterceptor) {
        this.passwordGateInterceptor = passwordGateInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(passwordGateInterceptor)
                .addPathPatterns(
                        "/",
                        "/index.html",
                        "/missionchat",
                        "/missionchat/",
                        "/missionchat.html",
                        "/cableschat",
                        "/cableschat/",
                        "/cableschat.html",
                        "/documentchat",
                        "/documentchat/",
                        "/documentchat.html",
                        "/aisandbox",
                        "/aisandbox/",
                        "/aisandbox.html",
                        "/api/chat/**",
                        "/api/cableschat/**",
                        "/api/documentchat/**");
    }
}
