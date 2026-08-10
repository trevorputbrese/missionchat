package gov.state.missionchat.cableschat;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cableschat")
public class CablesChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CablesChatController.class);
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final String UPSTREAM_ERROR = "CablesChat could not get a response from the model endpoint.";

    private final CablesChatService cablesChatService;
    private final CablesMcpRegistry cablesMcpRegistry;

    public CablesChatController(CablesChatService cablesChatService, CablesMcpRegistry cablesMcpRegistry) {
        this.cablesChatService = cablesChatService;
        this.cablesMcpRegistry = cablesMcpRegistry;
    }

    @GetMapping("/mcp/status")
    public ResponseEntity<CablesMcpStatusResponse> mcpStatus() {
        return ResponseEntity.ok(this.cablesMcpRegistry.status());
    }

    @PostMapping
    public ResponseEntity<CablesChatResponse> chat(@RequestBody CablesChatRequest request) {
        String conversationId = resolveConversationId(request == null ? null : request.conversationId());
        String message = normalizeMessage(request == null ? null : request.message());

        if (message == null) {
            return ResponseEntity.badRequest()
                    .body(new CablesChatResponse(conversationId, null, "Message is required."));
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new CablesChatResponse(conversationId, null,
                            "Message is too long. Please keep it under " + MAX_MESSAGE_LENGTH + " characters."));
        }

        try {
            String reply = cablesChatService.generateReply(message);
            return ResponseEntity.ok(new CablesChatResponse(conversationId, reply, null));
        } catch (RuntimeException ex) {
            LOGGER.warn("CablesChat upstream call failed for conversationId={}", conversationId, ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new CablesChatResponse(conversationId, null, UPSTREAM_ERROR));
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
