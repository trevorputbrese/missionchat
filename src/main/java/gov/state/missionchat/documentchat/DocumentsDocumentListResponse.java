package gov.state.missionchat.documentchat;

import java.util.List;

public record DocumentsDocumentListResponse(List<String> documents, long chunkCount) {
}
