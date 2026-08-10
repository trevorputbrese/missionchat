package gov.state.missionchat.documentchat;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documentchat")
public class DocumentsChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentsChatController.class);
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final String UPSTREAM_ERROR = "DocumentsChat could not get a response from the model endpoint.";

    private final DocumentsChatService documentsChatService;

    public DocumentsChatController(DocumentsChatService documentsChatService) {
        this.documentsChatService = documentsChatService;
    }

    @GetMapping("/rag/status")
    public ResponseEntity<DocumentsRagStatusResponse> ragStatus() {
        return ResponseEntity.ok(this.documentsChatService.ragStatus());
    }

    @GetMapping("/documents")
    public ResponseEntity<DocumentsDocumentListResponse> documents() {
        return ResponseEntity.ok(this.documentsChatService.listDocuments());
    }

    @PostMapping("/documents")
    public ResponseEntity<DocumentsUploadResponse> uploadDocuments(@RequestParam("files") MultipartFile[] files) {
        try {
            DocumentsUploadResponse response = this.documentsChatService.indexDocuments(files);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new DocumentsUploadResponse(0, 0, List.of(), ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new DocumentsUploadResponse(0, 0, List.of(), ex.getMessage()));
        } catch (RuntimeException ex) {
            LOGGER.warn("Documents upload failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DocumentsUploadResponse(0, 0, List.of(), "Upload failed due to an unexpected error."));
        }
    }

    @DeleteMapping("/documents")
    public ResponseEntity<DocumentsUploadResponse> clearDocuments() {
        try {
            this.documentsChatService.clearDocuments();
            return ResponseEntity.ok(new DocumentsUploadResponse(0, 0, List.of(), "All indexed documents were cleared."));
        } catch (RuntimeException ex) {
            LOGGER.warn("Documents clear failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DocumentsUploadResponse(0, 0, List.of(), "Could not clear indexed documents."));
        }
    }

    @PostMapping
    public ResponseEntity<DocumentsChatResponse> chat(@RequestBody DocumentsChatRequest request) {
        String conversationId = resolveConversationId(request == null ? null : request.conversationId());
        String message = normalizeMessage(request == null ? null : request.message());

        if (message == null) {
            return ResponseEntity.badRequest()
                    .body(new DocumentsChatResponse(conversationId, null, "Message is required.", List.of()));
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new DocumentsChatResponse(conversationId, null,
                            "Message is too long. Please keep it under " + MAX_MESSAGE_LENGTH + " characters.",
                            List.of()));
        }

        try {
            DocumentsChatResult result = this.documentsChatService.generateReply(message);
            return ResponseEntity.ok(new DocumentsChatResponse(conversationId, result.reply(), null, result.citations()));
        } catch (RuntimeException ex) {
            LOGGER.warn("DocumentsChat upstream call failed for conversationId={}", conversationId, ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new DocumentsChatResponse(conversationId, null, UPSTREAM_ERROR, List.of()));
        }
    }

    private static String resolveConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = conversationId.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
