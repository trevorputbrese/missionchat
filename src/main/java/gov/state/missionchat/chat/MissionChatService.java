package gov.state.missionchat.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class MissionChatService {

    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String SYSTEM_PROMPT = """
            You are MissionChat, a helpful assistant for enterprise users.
            Provide clear, concise answers. If context is missing, ask a short clarifying question.
            """;

    private final ChatClient chatClient;
    private final String configuredModel;
    private final String configuredBaseUrl;
    private final String configuredApiKey;

    public MissionChatService(ChatClient.Builder chatClientBuilder, Environment environment) {
        this.chatClient = chatClientBuilder.build();
        this.configuredModel = normalize(environment.getProperty("spring.ai.openai.chat.options.model"));
        this.configuredBaseUrl = normalize(environment.getProperty("spring.ai.openai.base-url"));
        this.configuredApiKey = normalize(environment.getProperty("spring.ai.openai.api-key"));
    }

    public String generateReply(String userMessage) {
        return this.chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .content();
    }

    public MissionModelStatusResponse modelStatus() {
        boolean hasModelConfigured = this.configuredModel != null;
        boolean hasApiKey = this.configuredApiKey != null && !"not-configured".equalsIgnoreCase(this.configuredApiKey);
        boolean hasNonDefaultBaseUrl = this.configuredBaseUrl != null
                && !DEFAULT_OPENAI_BASE_URL.equalsIgnoreCase(this.configuredBaseUrl);
        boolean available = hasModelConfigured && (hasApiKey || hasNonDefaultBaseUrl);

        return new MissionModelStatusResponse(available, available ? this.configuredModel : null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
