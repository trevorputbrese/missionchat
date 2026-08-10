package gov.state.missionchat.cableschat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class CablesMcpRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CablesMcpRegistry.class);
    private static final Duration MCP_TIMEOUT = Duration.ofSeconds(8);
    private static final String STATUS_AVAILABLE = "AVAILABLE";

    private final List<McpSyncClient> mcpClients;
    private final List<CablesMcpServerStatus> serverStatuses;
    private final ToolCallbackProvider toolCallbackProvider;

    public CablesMcpRegistry(ObjectMapper objectMapper, Environment environment) {
        List<DiscoveredServer> discoveredServers = discoverServers(environment, objectMapper);
        List<McpSyncClient> connectedClients = new ArrayList<>();
        List<CablesMcpServerStatus> statuses = new ArrayList<>();

        for (DiscoveredServer discoveredServer : discoveredServers) {
            try {
                ParsedEndpoint endpoint = parseEndpoint(discoveredServer.url());
                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                        .builder(endpoint.baseUrl())
                        .endpoint(endpoint.endpoint())
                        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                        .connectTimeout(MCP_TIMEOUT)
                        .build();

                McpSyncClient client = McpClient.sync(transport)
                        .clientInfo(new McpSchema.Implementation(
                                "cableschat-" + sanitizeName(discoveredServer.name()),
                                "CablesChat",
                                "0.0.1"))
                        .requestTimeout(MCP_TIMEOUT)
                        .initializationTimeout(MCP_TIMEOUT)
                        .build();

                client.initialize();
                connectedClients.add(client);
                statuses.add(new CablesMcpServerStatus(discoveredServer.name(), discoveredServer.url(), STATUS_AVAILABLE));
                LOGGER.info("Registered MCP server for CablesChat: {} ({})",
                        discoveredServer.name(), discoveredServer.url());
            } catch (Exception ex) {
                LOGGER.warn("Skipping MCP server '{}' for CablesChat: {}",
                        discoveredServer.name(), ex.getMessage());
            }
        }

        this.mcpClients = List.copyOf(connectedClients);
        this.serverStatuses = List.copyOf(statuses);
        this.toolCallbackProvider = this.mcpClients.isEmpty() ? null : new SyncMcpToolCallbackProvider(this.mcpClients);
    }

    public Optional<ToolCallbackProvider> toolCallbackProvider() {
        return Optional.ofNullable(this.toolCallbackProvider);
    }

    public CablesMcpStatusResponse status() {
        return new CablesMcpStatusResponse(!this.serverStatuses.isEmpty(), this.serverStatuses);
    }

    @PreDestroy
    public void closeClients() {
        for (McpSyncClient client : this.mcpClients) {
            try {
                client.close();
            } catch (Exception ex) {
                LOGGER.debug("Failed to close MCP client cleanly: {}", ex.getMessage());
            }
        }
    }

    private static List<DiscoveredServer> discoverServers(Environment environment, ObjectMapper objectMapper) {
        Map<String, DiscoveredServer> discovered = new LinkedHashMap<>();

        parseVcapServices(environment.getProperty("VCAP_SERVICES"), objectMapper, discovered);
        parseLocalOverride(environment.getProperty("CABLESCHAT_MCP_URLS"), discovered);

        return List.copyOf(discovered.values());
    }

    private static void parseVcapServices(String vcapServicesRaw, ObjectMapper objectMapper,
                                          Map<String, DiscoveredServer> discovered) {
        if (vcapServicesRaw == null || vcapServicesRaw.isBlank()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(vcapServicesRaw);
            if (!root.isObject()) {
                return;
            }

            root.fields().forEachRemaining(serviceTypeEntry -> {
                JsonNode serviceInstances = serviceTypeEntry.getValue();
                if (!serviceInstances.isArray()) {
                    return;
                }

                int index = 0;
                for (JsonNode serviceInstance : serviceInstances) {
                    index++;
                    JsonNode credentials = serviceInstance.path("credentials");
                    if (!credentials.isObject()) {
                        continue;
                    }

                    String mcpServiceUrl = firstNonBlank(
                            text(credentials.get("mcpServiceURL")),
                            text(credentials.get("mcpServiceUrl"))
                    );
                    if (mcpServiceUrl == null) {
                        continue;
                    }

                    String serviceName = firstNonBlank(
                            text(serviceInstance.get("name")),
                            text(serviceInstance.get("instance_name")),
                            serviceTypeEntry.getKey() + "-" + index
                    );

                    discovered.putIfAbsent(mcpServiceUrl, new DiscoveredServer(serviceName, mcpServiceUrl));
                }
            });
        } catch (Exception ex) {
            LOGGER.warn("Could not parse VCAP_SERVICES for MCP discovery: {}", ex.getMessage());
        }
    }

    private static void parseLocalOverride(String localMcpUrlsRaw, Map<String, DiscoveredServer> discovered) {
        if (localMcpUrlsRaw == null || localMcpUrlsRaw.isBlank()) {
            return;
        }

        String[] candidates = localMcpUrlsRaw.split(",");
        int index = 0;
        for (String candidate : candidates) {
            String url = candidate == null ? null : candidate.trim();
            if (url == null || url.isBlank()) {
                continue;
            }
            index++;
            discovered.putIfAbsent(url, new DiscoveredServer("local-" + index, url));
        }
    }

    private static ParsedEndpoint parseEndpoint(String rawUrl) {
        URI uri = URI.create(rawUrl.trim());
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException("Invalid MCP URL: " + rawUrl);
        }

        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/mcp";
        }
        String query = uri.getRawQuery();
        String endpoint = query == null ? path : path + "?" + query;
        return new ParsedEndpoint(baseUrl, endpoint);
    }

    private static String sanitizeName(String input) {
        String normalized = input == null ? "server" : input.toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "server" : slug;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record DiscoveredServer(String name, String url) {
    }

    private record ParsedEndpoint(String baseUrl, String endpoint) {
    }
}
