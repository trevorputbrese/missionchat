package gov.state.missionchat.cableschat;

import java.util.List;

public record CablesMcpStatusResponse(boolean registered, List<CablesMcpServerStatus> servers) {
}
