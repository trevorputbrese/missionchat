package gov.state.missionchat.documentchat;

import java.util.List;

public record DocumentsRagContext(String contextBlock, List<String> citations) {

    public static DocumentsRagContext empty() {
        return new DocumentsRagContext(null, List.of());
    }

    public boolean hasContext() {
        return contextBlock != null && !contextBlock.isBlank();
    }
}
