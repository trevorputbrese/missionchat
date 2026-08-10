package gov.state.missionchat.documentchat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentsChatService {

    private static final String SYSTEM_PROMPT = """
            You are DocumentsChat, a helpful assistant for enterprise users.
            Provide clear, concise answers grounded in available context.
            If the local context is missing, answer honestly and ask a short clarifying question when needed.
            """;
    private static final String RAG_PREFIX = """
            I searched local documents first. Here is relevant context to help answer the user's query:
            """;

    private final ChatClient chatClient;
    private final DocumentsRagService documentsRagService;

    public DocumentsChatService(ChatClient.Builder chatClientBuilder, DocumentsRagService documentsRagService) {
        this.chatClient = chatClientBuilder.build();
        this.documentsRagService = documentsRagService;
    }

    public DocumentsChatResult generateReply(String userMessage) {
        DocumentsRagContext ragContext = this.documentsRagService.retrieveContext(userMessage);
        String effectivePrompt = ragContext.hasContext()
                ? userMessage + "\n\n" + RAG_PREFIX + "\n\n" + ragContext.contextBlock()
                : userMessage;

        String reply = this.chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(effectivePrompt)
                .call()
                .content();
        return new DocumentsChatResult(reply, ragContext.citations());
    }

    public DocumentsRagStatusResponse ragStatus() {
        return this.documentsRagService.status();
    }

    public DocumentsUploadResponse indexDocuments(MultipartFile[] files) {
        return this.documentsRagService.indexDocuments(files);
    }

    public DocumentsDocumentListResponse listDocuments() {
        return this.documentsRagService.listDocuments();
    }

    public DocumentsDocumentListResponse clearDocuments() {
        return this.documentsRagService.clearDocuments();
    }
}
