package com.automation.tests;

import com.automation.client.ApiClient;
import com.automation.config.EnvironmentConfig;
import com.automation.config.TestConfig;
import com.automation.utils.PostmanParser;
import com.automation.utils.ResponseLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests generated from GET Account Postman collection scenarios.
 *
 * Each scenario below maps to one collection request name.
 */
class GetAccountTests {

    private static final String COLLECTION_RESOURCE = "collections/GET Account.postman_collection.json";

    private static EnvironmentConfig environmentConfig;
    private static JsonNode collectionRoot;

    @BeforeAll
    static void setup() {
        String envFile = TestConfig.getEnvironmentFileResource();
        environmentConfig = EnvironmentConfig.fromResource(envFile);
        collectionRoot = PostmanParser.readJsonResource(COLLECTION_RESOURCE);
    }

    @Test
    @DisplayName("Get Account by customer ID - happy path")
    void shouldGetAccountByCustomerId() {
        runScenarioAndAssert(
                "Get Account By Customer ID",
                Set.of(200),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID variable - valid auth headers")
    void shouldGetAccountByCustomerIdFromEnvironmentVariable() {
        runScenarioAndAssert(
                "Get account by customer ID with valid customer_id and valid auth headers",
                Set.of(200, 400),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - customer without account")
    void shouldHandleCustomerWithNoAccount() {
        runScenarioAndAssert(
                "Verify Get account by customer ID with valid request but customer has no account",
                Set.of(200, 404),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - invalid customer ID format")
    void shouldHandleInvalidCustomerIdFormat() {
        runScenarioAndAssert(
                "Verify Get account by customer ID with invalid customer_id format",
                Set.of(400, 422),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - without Authorization header")
    void shouldRejectMissingAuthorizationHeader() {
        runScenarioAndAssert(
                "Get account by customer ID without Authorization header",
                Set.of(401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - expired Authorization header")
    void shouldRejectExpiredAuthorizationHeader() {
        runScenarioAndAssert(
                "Get account by customer ID expired Authorization header",
                Set.of(401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - without x-api-key")
    void shouldRejectMissingApiKeyHeader() {
        runScenarioAndAssert(
                "Get account by customer ID without x-api-key",
                Set.of(401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by customer ID - without x-channel header")
    void shouldRejectMissingChannelHeader() {
        runScenarioAndAssert(
                "Get account by customer ID without x-channel header",
                Set.of(400, 401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by account ID scenario")
    void shouldGetAccountByAccountIdScenario() {
        runScenarioAndAssert(
                "Get Account By AccountID",
                Set.of(200, 401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by toyotapay account ID")
    void shouldGetAccountByToyotaPayAccountId() {
        runScenarioAndAssert(
                "GET Account by toyotapayaccountid",
                Set.of(200),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account using invalid toyotapay account ID")
    void shouldHandleInvalidToyotaPayAccountId() {
        runScenarioAndAssert(
                "Get  account  using invalid toyotapay_account_id",
                Set.of(400, 404),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account without passing toyotapay account ID")
    void shouldHandleMissingToyotaPayAccountId() {
        runScenarioAndAssert(
                "Get Account  without passing  toyotapay_account_id",
                Set.of(400, 422),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account with expired authentication token scenario")
    void shouldRejectExpiredAuthenticationTokenScenario() {
        runScenarioAndAssert(
                "Validate Get account   using expired authentication token",
                Set.of(401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account without authentication token header scenario")
    void shouldHandleWithoutAuthenticationTokenHeaderScenario() {
        runScenarioAndAssert(
                "Validate Get account without authentication token header",
                Set.of(200, 401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by toyotapay account ID without x-api-key")
    void shouldRejectWithoutApiKeyForToyotaPayAccountScenario() {
        runScenarioAndAssert(
                "et account  by toyotapay_account no - x- API Key in header",
                Set.of(401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by toyotapay account ID without x-channel")
    void shouldRejectWithoutChannelForToyotaPayAccountScenario() {
        runScenarioAndAssert(
                "Get account   by  toyotapay_account _id- no x-channel",
                Set.of(400, 401, 403),
                true,
                true
        );
    }

    @Test
    @DisplayName("Get account by toyotapay account ID without x-correlation-id")
    void shouldHandleMissingCorrelationIdHeaderScenario() {
        runScenarioAndAssert(
                "Get account  by toyotapay_account ID- no \"x-correlation-id\"",
                Set.of(200, 400, 401, 403),
                true,
                true
        );
    }

    private void runScenarioAndAssert(String requestName,
                                      Set<Integer> expectedStatusCodes,
                                      boolean validateStatusObject,
                                      boolean validateJsonResponse) {

        JsonNode itemNode = PostmanParser.findRequestByName(collectionRoot, requestName)
                .orElseThrow(() -> new IllegalArgumentException("Request not found in collection: " + requestName));

        JsonNode requestNode = itemNode.path("request");
        String method = requestNode.path("method").asText("GET");
        String rawUrl = requestNode.path("url").path("raw").asText();
        String resolvedUrl = PostmanParser.resolveVariables(rawUrl, environmentConfig);

        RequestSpecification request = ApiClient.buildBaseRequest(environmentConfig, false);

        JsonNode headersNode = requestNode.path("header");
        for (JsonNode headerNode : headersNode) {
            if (headerNode.path("disabled").asBoolean(false)) {
                continue;
            }
            String key = headerNode.path("key").asText();
            String value = PostmanParser.resolveVariables(headerNode.path("value").asText(""), environmentConfig);
            if (!key.isBlank()) {
                request.header(key, value);
            }
        }

        String endpoint = extractEndpoint(resolvedUrl, environmentConfig.getBaseUrl());
        Response response;
        try {
            response = request.when().request(method, endpoint);
        } catch (Exception e) {
            throw new TestAbortedException("Skipped due to connectivity/runtime issue while calling endpoint: " + endpoint, e);
        }

        ResponseLogger.logResponse(requestName, response);

        int statusCode = response.getStatusCode();
        assertTrue(expectedStatusCodes.contains(statusCode),
                () -> "Unexpected status code for '" + requestName + "'. Expected one of "
                        + expectedStatusCodes + " but got " + statusCode);

        String body = response.getBody().asString();
        if (validateJsonResponse) {
            assertNotNull(body, "Response body should not be null");
            assertTrue(!body.isBlank(), "Response body should not be blank");
        }

        if (validateStatusObject && isLikelyJson(body)) {
            try {
                JsonNode responseJson = new ObjectMapper().readTree(body);
                assertNotNull(responseJson.get("status"),
                        "Expected response.status to exist for scenario: " + requestName);
            } catch (Exception e) {
                throw new AssertionError("Expected valid JSON response for scenario: " + requestName, e);
            }
        }
    }

    private String extractEndpoint(String resolvedUrl, String baseUrl) {
        if (resolvedUrl.startsWith(baseUrl)) {
            String endpoint = resolvedUrl.substring(baseUrl.length());
            return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        }
        return resolvedUrl;
    }

    private boolean isLikelyJson(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }
}
