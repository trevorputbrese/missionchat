package gov.state.missionchat.documentchat;

import java.util.List;

public record DocumentsUploadResponse(int filesIndexed, int chunksIndexed, List<String> documents, String message) {
}
