package com.automation.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Test configuration resolver.
 *
 * Priority:
 * 1. JVM system property: -Denv=staging|dev|prod
 * 2. test.properties default.environment
 */
public final class TestConfig {

    private static final String DEFAULT_PROPERTIES_FILE = "test.properties";
    private static final Properties PROPERTIES = loadProperties();

    private TestConfig() {
        // Utility class
    }

    public static String getActiveEnvironment() {
        String fromSystemProperty = System.getProperty("env");
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return fromSystemProperty.trim().toLowerCase();
        }
        return PROPERTIES.getProperty("default.environment", "staging").trim().toLowerCase();
    }

    public static String getEnvironmentFileResource() {
        String activeEnvironment = getActiveEnvironment();
        String key = "env." + activeEnvironment + ".file";
        String file = PROPERTIES.getProperty(key);

        if (file == null || file.isBlank()) {
            return PROPERTIES.getProperty("env.staging.file", "environments/TC_Agent.postman_environment.json");
        }
        return file.trim();
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(DEFAULT_PROPERTIES_FILE)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load " + DEFAULT_PROPERTIES_FILE, e);
        }
        return properties;
    }
}
