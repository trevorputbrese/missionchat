package gov.state.missionchat.documentchat;

import java.util.List;

public record DocumentsChatResponse(String conversationId, String reply, String error, List<String> citations) {

    public DocumentsChatResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
