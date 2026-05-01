package com.automation.client;

import com.automation.config.EnvironmentConfig;
import com.automation.config.SSLConfig;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.LogDetail;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RestAssured client factory. Builds reusable RequestSpecification objects.
 */
public final class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

    private static final int CONNECTION_TIMEOUT_MS = Integer.getInteger("automation.http.connection.timeout.ms", 30_000);
    private static final int SOCKET_TIMEOUT_MS = Integer.getInteger("automation.http.socket.timeout.ms", 30_000);
    private static final int CONNECTION_MANAGER_TIMEOUT_MS = Integer.getInteger("automation.http.connection.manager.timeout.ms", 30_000);
    private static final String TLS_PROTOCOL = System.getProperty("automation.tls.protocol", "TLSv1.3");

    static {
        configureTransportDefaults();
    }

    private ApiClient() {
        // Utility class
    }

    private static void configureTransportDefaults() {
        // Defensive JVM-level defaults to avoid HTTP/2 negotiation issues when integrations rely on JVM HTTP clients.
        System.setProperty("jdk.httpclient.enableHttp2", "false");
        System.setProperty("https.protocols", "TLSv1.3,TLSv1.2");

        LOGGER.info("Transport defaults initialized: jdk.httpclient.enableHttp2={}, https.protocols={}",
                System.getProperty("jdk.httpclient.enableHttp2"),
                System.getProperty("https.protocols"));
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig) {
        return buildBaseRequest(environmentConfig, true);
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig, boolean includeDefaultHeaders) {
        RestAssuredConfig restAssuredConfig = RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.protocol.version", HttpVersion.HTTP_1_1)
                .setParam("http.connection.timeout", CONNECTION_TIMEOUT_MS)
                .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS)
                .setParam("http.connection-manager.timeout", (long) CONNECTION_MANAGER_TIMEOUT_MS));

        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(environmentConfig.getBaseUrl())
                .setContentType(ContentType.JSON)
                .setConfig(restAssuredConfig)
                .setRelaxedHTTPSValidation(TLS_PROTOCOL)
                .addHeader("Accept", "application/json")
                .addFilter(new RequestLoggingFilter(LogDetail.ALL))
                .addFilter(new ResponseLoggingFilter(LogDetail.ALL))
                .addFilter((requestSpec, responseSpec, filterContext) -> {
                    LOGGER.info("Dispatching {} {} (configured protocol={}, timeouts: connect={}ms socket={}ms cm={}ms)",
                            requestSpec.getMethod(),
                            requestSpec.getURI(),
                            HttpVersion.HTTP_1_1,
                            CONNECTION_TIMEOUT_MS,
                            SOCKET_TIMEOUT_MS,
                            CONNECTION_MANAGER_TIMEOUT_MS);

                    try {
                        Response response = filterContext.next(requestSpec, responseSpec);
                        LOGGER.info("Response status line='{}' (reported protocol={})",
                                response.getStatusLine(),
                                extractProtocol(response.getStatusLine()));
                        return response;
                    } catch (Exception exception) {
                        LOGGER.error("Request failed before a response status line was received (configured protocol={}).", HttpVersion.HTTP_1_1, exception);
                        throw exception;
                    }
                });

        SSLConfig.getMtlsMaterial().ifPresentOrElse(material -> {
                    builder.setKeyStore(material.getKeyStorePath().toString(), material.getKeyStorePassword());
                    LOGGER.info("Applied mTLS keystore from {}", material.getKeyStorePath());
                },
                () -> LOGGER.warn("No mTLS keystore loaded. Requests will be sent without a client certificate."));

        if (includeDefaultHeaders) {
            environmentConfig.getChannel()
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> builder.addHeader("x-channel", value));

            environmentConfig.getApiKey()
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> builder.addHeader("x-api-key", value));

            environmentConfig.getAuthorizationToken()
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> builder.addHeader("Authorization", value.startsWith("Bearer ") ? value : "Bearer " + value));
        }

        return RestAssured.given().spec(builder.build());
    }

    private static String extractProtocol(String statusLine) {
        if (statusLine == null || statusLine.isBlank()) {
            return "unknown";
        }

        String[] parts = statusLine.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : "unknown";
    }
}
