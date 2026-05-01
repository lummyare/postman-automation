package com.automation.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pretty-prints API responses for easy debugging in console logs.
 */
public final class ResponseLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseLogger() {
        // Utility class
    }

    public static void logResponse(String testName, Response response) {
        String body = response.getBody() == null ? "" : response.getBody().asString();

        LOGGER.info("\n==================== {} ====================", testName);
        LOGGER.info("Status Code : {}", response.getStatusCode());
        LOGGER.info("Status Line : {}", response.getStatusLine());

        if (body == null || body.isBlank()) {
            LOGGER.info("Response Body: <empty>");
            return;
        }

        try {
            JsonNode jsonNode = MAPPER.readTree(body);
            String prettyJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.info("Response Body:\n{}", prettyJson);
        } catch (Exception ex) {
            LOGGER.info("Response Body (raw):\n{}", body);
        }
    }
}
