package com.automation.client;

import com.automation.config.EnvironmentConfig;
import com.automation.config.SSLConfig;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RestAssured client factory. Builds reusable RequestSpecification objects.
 */
public final class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

    private ApiClient() {
        // Utility class
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig) {
        return buildBaseRequest(environmentConfig, true);
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig, boolean includeDefaultHeaders) {
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(environmentConfig.getBaseUrl())
                .setContentType(ContentType.JSON)
                .addHeader("Accept", "application/json")
                .addFilter(new RequestLoggingFilter(LogDetail.ALL))
                .addFilter(new ResponseLoggingFilter(LogDetail.ALL))
                .setRelaxedHTTPSValidation();

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
}
