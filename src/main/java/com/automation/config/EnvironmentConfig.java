package com.automation.config;

import com.automation.utils.PostmanParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a Postman environment JSON and exposes variables with convenience getters.
 */
public class EnvironmentConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentConfig.class);

    private final String environmentName;
    private final Map<String, String> variables;

    private EnvironmentConfig(String environmentName, Map<String, String> variables) {
        this.environmentName = environmentName;
        this.variables = Collections.unmodifiableMap(variables);
    }

    public static EnvironmentConfig fromResource(String resourcePath) {
        JsonNode root = PostmanParser.readJsonResource(resourcePath);
        String name = root.path("name").asText("unknown");

        Map<String, String> parsedVariables = new HashMap<>();
        JsonNode values = root.path("values");

        if (values.isArray()) {
            for (JsonNode valueNode : values) {
                boolean enabled = !valueNode.path("enabled").isBoolean() || valueNode.path("enabled").asBoolean(true);
                if (!enabled) {
                    continue;
                }
                String key = valueNode.path("key").asText("").trim();
                String value = valueNode.path("value").asText("");
                if (!key.isBlank()) {
                    parsedVariables.put(key, value);
                }
            }
        }

        LOGGER.info("Loaded environment '{}' with {} variables from {}", name, parsedVariables.size(), resourcePath);
        return new EnvironmentConfig(name, parsedVariables);
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public Map<String, String> getAllVariables() {
        return variables;
    }

    public Optional<String> getVariable(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String direct = variables.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }

        // Case-insensitive fallback for convenience.
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public String getRequiredVariable(String key) {
        return getVariable(key)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Missing required environment variable: " + key));
    }

    public String getBaseUrl() {
        return getVariable("BaseUrl")
                .or(() -> getVariable("baseUrl"))
                .orElseThrow(() -> new IllegalStateException("Base URL not found in environment (BaseUrl/baseUrl)"));
    }

    public Optional<String> getApiKey() {
        return getVariable("API_Key")
                .or(() -> getVariable("apiKey"))
                .or(() -> getVariable("x-api-key"));
    }

    public Optional<String> getChannel() {
        return getVariable("x-channel1")
                .or(() -> getVariable("x-channel"))
                .or(() -> getVariable("channel"));
    }

    public Optional<String> getAuthorizationToken() {
        return getVariable("Token")
                .filter(v -> !v.isBlank())
                .or(() -> getVariable("access_token").filter(v -> !v.isBlank()));
    }
}
