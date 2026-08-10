package gov.state.missionchat.cableschat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class CablesChatService {

    private static final String SYSTEM_PROMPT = """
            You are CablesChat, a helpful assistant for enterprise users.
            Provide clear, concise answers. If context is missing, ask a short clarifying question.
            If tool calls are available through MCP, use them when they can improve factual accuracy.
            """;

    private final ChatClient chatClient;
    private final CablesMcpRegistry cablesMcpRegistry;

    public CablesChatService(ChatClient.Builder chatClientBuilder, CablesMcpRegistry cablesMcpRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.cablesMcpRegistry = cablesMcpRegistry;
    }

    public String generateReply(String userMessage) {
        ChatClient.ChatClientRequestSpec requestSpec = this.chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage);

        requestSpec = this.cablesMcpRegistry.toolCallbackProvider()
                .map(requestSpec::toolCallbacks)
                .orElse(requestSpec);

        return requestSpec.call().content();
    }
}
