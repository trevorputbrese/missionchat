package gov.state.missionchat.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class MissionChatService {

    private static final String SYSTEM_PROMPT = """
            You are MissionChat, a helpful assistant for enterprise users.
            Provide clear, concise answers. If context is missing, ask a short clarifying question.
            """;

    private final ChatClient chatClient;

    public MissionChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String generateReply(String userMessage) {
        return this.chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .content();
    }
}
