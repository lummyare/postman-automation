package com.automation.utils;

import com.automation.config.EnvironmentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility helper for reading Postman collection and environment JSON files.
 */
public final class PostmanParser {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostmanParser() {
        // Utility class
    }

    public static JsonNode readJsonResource(String resourcePath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return MAPPER.readTree(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON resource: " + resourcePath, e);
        }
    }

    public static JsonNode readJsonFile(Path filePath) {
        try {
            return MAPPER.readTree(Files.newBufferedReader(filePath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON file: " + filePath, e);
        }
    }

    public static Optional<JsonNode> findRequestByName(JsonNode collectionRoot, String requestName) {
        ArrayNode rootItems = (ArrayNode) collectionRoot.path("item");
        if (rootItems == null) {
            return Optional.empty();
        }
        return findRequestRecursively(rootItems, requestName);
    }

    public static List<String> listRequestNames(JsonNode collectionRoot) {
        List<String> names = new ArrayList<>();
        collectNames(collectionRoot.path("item"), names);
        return names;
    }

    /**
     * Replaces Postman style variables ({{variableName}}) using environment values.
     */
    public static String resolveVariables(String input, EnvironmentConfig environmentConfig) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuffer resolved = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1).trim();
            String value = environmentConfig.getVariable(variableName).orElse("");
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);

        return resolved.toString();
    }

    private static Optional<JsonNode> findRequestRecursively(ArrayNode items, String requestName) {
        String normalizedRequestedName = normalizeName(requestName);
        Iterator<JsonNode> iterator = items.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            String candidateName = item.path("name").asText();
            if (normalizeName(candidateName).equals(normalizedRequestedName) && item.has("request")) {
                return Optional.of(item);
            }
            if (item.has("item")) {
                Optional<JsonNode> nestedResult = findRequestRecursively((ArrayNode) item.get("item"), requestName);
                if (nestedResult.isPresent()) {
                    return nestedResult;
                }
            }
        }
        return Optional.empty();
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        // Replace non-breaking spaces and collapse repeated spaces to match noisy collection names.
        return value.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static void collectNames(JsonNode items, List<String> names) {
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item.has("request")) {
                names.add(item.path("name").asText());
            }
            if (item.has("item")) {
                collectNames(item.path("item"), names);
            }
        }
    }
}
