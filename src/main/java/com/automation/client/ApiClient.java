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
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/**
 * RestAssured client factory. Builds reusable RequestSpecification objects.
 */
public final class ApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

    private static final int CONNECTION_TIMEOUT_MS = Integer.getInteger("automation.http.connection.timeout.ms", 30_000);
    private static final int SOCKET_TIMEOUT_MS = Integer.getInteger("automation.http.socket.timeout.ms", 30_000);
    private static final int CONNECTION_MANAGER_TIMEOUT_MS = Integer.getInteger("automation.http.connection.manager.timeout.ms", 30_000);

    private static final String TLS_PROTOCOL = System.getProperty("automation.tls.protocol", "TLSv1.3");
    private static final String TLS_PROTOCOLS = System.getProperty("automation.tls.protocols", "TLSv1.3,TLSv1.2");
    private static final String TLS_CLIENT_PROTOCOLS = System.getProperty("automation.tls.client.protocols", "TLSv1.3");
    private static final String TLS_CIPHER_SUITES = System.getProperty("automation.tls.cipher.suites", "TLS_AES_128_GCM_SHA256");

    private static final String SSL_DEBUG_ENABLED_PROPERTY = "automation.ssl.debug.enabled";
    private static final String SSL_DEBUG_VALUE_PROPERTY = "automation.ssl.debug.value";

    static {
        configureTransportDefaults();
    }

    private ApiClient() {
        // Utility class
    }

    private static void configureTransportDefaults() {
        // Defensive JVM-level defaults to avoid HTTP/2 negotiation issues when integrations rely on JVM HTTP clients.
        System.setProperty("jdk.httpclient.enableHttp2", "false");
        System.setProperty("https.protocols", TLS_PROTOCOLS);
        System.setProperty("jdk.tls.client.protocols", TLS_CLIENT_PROTOCOLS);

        if (!TLS_CIPHER_SUITES.isBlank()) {
            System.setProperty("https.cipherSuites", TLS_CIPHER_SUITES);
            System.setProperty("jdk.tls.client.cipherSuites", TLS_CIPHER_SUITES);
        }

        configureSslDebugLogging();

        LOGGER.info("Transport defaults initialized: jdk.httpclient.enableHttp2={}, https.protocols={}, jdk.tls.client.protocols={}, cipherSuites={}",
                System.getProperty("jdk.httpclient.enableHttp2"),
                System.getProperty("https.protocols"),
                System.getProperty("jdk.tls.client.protocols"),
                System.getProperty("jdk.tls.client.cipherSuites"));
    }

    private static void configureSslDebugLogging() {
        boolean sslDebugEnabled = Boolean.parseBoolean(System.getProperty(SSL_DEBUG_ENABLED_PROPERTY, "false"));
        if (!sslDebugEnabled) {
            LOGGER.info("SSL debug logging is disabled ({}=false).", SSL_DEBUG_ENABLED_PROPERTY);
            return;
        }

        String sslDebugValue = System.getProperty(SSL_DEBUG_VALUE_PROPERTY, "ssl,handshake");
        System.setProperty("javax.net.debug", sslDebugValue);
        LOGGER.info("Enabled javax.net SSL debugging with value '{}'.", sslDebugValue);
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig) {
        return buildBaseRequest(environmentConfig, true);
    }

    public static RequestSpecification buildBaseRequest(EnvironmentConfig environmentConfig, boolean includeDefaultHeaders) {
        HttpClientConfig httpClientConfig = HttpClientConfig.httpClientConfig()
                .setParam("http.protocol.version", HttpVersion.HTTP_1_1)
                .setParam("http.connection.timeout", CONNECTION_TIMEOUT_MS)
                .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS)
                .setParam("http.connection-manager.timeout", (long) CONNECTION_MANAGER_TIMEOUT_MS);

        Optional<SSLConfig.MtlsMaterial> mtlsMaterial = SSLConfig.getMtlsMaterial();
        if (mtlsMaterial.isPresent()) {
            try {
                SSLContext sslContext = buildMtlsSslContext(mtlsMaterial.get());
                RequestConfig requestConfig = RequestConfig.custom()
                        .setConnectTimeout(CONNECTION_TIMEOUT_MS)
                        .setSocketTimeout(SOCKET_TIMEOUT_MS)
                        .setConnectionRequestTimeout(CONNECTION_MANAGER_TIMEOUT_MS)
                        .build();

                httpClientConfig = httpClientConfig.httpClientFactory(() -> HttpClients.custom()
                        .setSSLContext(sslContext)
                        .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .setDefaultRequestConfig(requestConfig)
                        .build());

                LOGGER.info("Configured RestAssured HTTP client with mTLS SSLContext and explicit KeyManager initialization.");
            } catch (Exception exception) {
                LOGGER.error("Failed to configure RestAssured with mTLS SSLContext. Falling back to default TLS client without client certificate.", exception);
            }
        } else {
            LOGGER.warn("No mTLS keystore loaded. Requests will be sent without a client certificate.");
        }

        RestAssuredConfig restAssuredConfig = RestAssuredConfig.config().httpClient(httpClientConfig);

        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(environmentConfig.getBaseUrl())
                .setContentType(ContentType.JSON)
                .setConfig(restAssuredConfig)
                .addHeader("Accept", "application/json")
                .addFilter(new RequestLoggingFilter(LogDetail.ALL))
                .addFilter(new ResponseLoggingFilter(LogDetail.ALL))
                .addFilter((requestSpec, responseSpec, filterContext) -> {
                    LOGGER.info("Dispatching {} {} (configured protocol={}, TLS={}, timeouts: connect={}ms socket={}ms cm={}ms)",
                            requestSpec.getMethod(),
                            requestSpec.getURI(),
                            HttpVersion.HTTP_1_1,
                            TLS_PROTOCOL,
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
                        LOGGER.error("Request failed before a response status line was received (configured protocol={}, TLS={}).", HttpVersion.HTTP_1_1, TLS_PROTOCOL, exception);
                        throw exception;
                    }
                });

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

    private static SSLContext buildMtlsSslContext(SSLConfig.MtlsMaterial material) throws Exception {
        KeyStore keyStore = material.getKeyStore();
        char[] password = material.getKeyStorePassword().toCharArray();

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        } catch (Exception protocolEx) {
            LOGGER.warn("Unable to initialize SSLContext with protocol '{}'. Falling back to 'TLS'.", TLS_PROTOCOL, protocolEx);
            sslContext = SSLContext.getInstance("TLS");
        }

        sslContext.init(keyManagers, trustManagers, new SecureRandom());
        logKeyManagerInitialization(keyManagers);

        LOGGER.info("mTLS SSLContext initialized for RestAssured using protocol '{}' and keystore '{}'.",
                sslContext.getProtocol(),
                material.getKeyStorePath());

        return sslContext;
    }

    private static void logKeyManagerInitialization(KeyManager[] keyManagers) {
        String managerClassNames = Arrays.stream(keyManagers)
                .map(manager -> manager == null ? "null" : manager.getClass().getName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");

        boolean hasDummyManager = Arrays.stream(keyManagers)
                .filter(manager -> manager != null)
                .anyMatch(manager -> manager.getClass().getName().contains("DummyX509KeyManager"));

        LOGGER.info("Initialized KeyManager(s): [{}]", managerClassNames);
        if (hasDummyManager) {
            LOGGER.error("Detected DummyX509KeyManager. Client certificate may not be sent during TLS handshake.");
        } else {
            LOGGER.info("KeyManager initialization looks correct: DummyX509KeyManager not detected.");
        }
    }

    private static String extractProtocol(String statusLine) {
        if (statusLine == null || statusLine.isBlank()) {
            return "unknown";
        }

        String[] parts = statusLine.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : "unknown";
    }
}
