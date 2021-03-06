/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.owasp.dependencytrack.util;

import alpine.Config;
import alpine.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

public final class HttpClientFactory {

    private static final String PROXY_ADDRESS = Config.getInstance().getProperty(Config.AlpineKey.HTTP_PROXY_ADDRESS);
    private static final int PROXY_PORT = Config.getInstance().getPropertyAsInt(Config.AlpineKey.HTTP_PROXY_PORT);
    private static final String PROXY_USERNAME = Config.getInstance().getProperty(Config.AlpineKey.HTTP_PROXY_USERNAME);
    private static final String PROXY_PASSWORD = Config.getInstance().getProperty(Config.AlpineKey.HTTP_PROXY_PASSWORD);
    private static final Logger LOGGER = Logger.getLogger(HttpClientFactory.class);

    private HttpClientFactory() { }

    /**
     * Factory method that create a HttpClient object. This method will attempt to use
     * proxy settings defined in application.properties first. If they are not set,
     * this method will attempt to use proxy settings from the environment by looking
     * for 'https_proxy' and 'http_proxy'.
     * @return a HttpClient object with optional proxy settings
     */
    public static HttpClient createClient() {
        HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        clientBuilder.useSystemProperties();

        ProxyInfo proxyInfo = createProxyInfo();

        if (proxyInfo != null) {
            clientBuilder.setProxy(new HttpHost(proxyInfo.host, proxyInfo.port));
            if (StringUtils.isNotBlank(proxyInfo.username) && StringUtils.isNotBlank(proxyInfo.password)) {
                credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxyInfo.username, proxyInfo.password));
            }
        }

        clientBuilder.setDefaultCredentialsProvider(credsProvider);
        clientBuilder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
        Lookup<AuthSchemeProvider> authProviders = RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                .build();
        clientBuilder.setDefaultAuthSchemeRegistry(authProviders);
        return clientBuilder.build();
    }

    /**
     * Attempt to use application specific proxy settings if they exist.
     * Otherwise, attempt to use environment variables if they exist.
     * @return ProxyInfo object, or null if proxy is not configured
     */
    public static ProxyInfo createProxyInfo() {
        ProxyInfo proxyInfo = fromConfig();
        if (proxyInfo == null) {
            proxyInfo = fromEnvironment();
        }
        return proxyInfo;
    }

    /**
     * Creates a ProxyInfo object from the application.properties configuration.
     * @return a ProxyInfo object, or null if proxy is not configured
     */
    private static ProxyInfo fromConfig() {
        ProxyInfo proxyInfo = null;
        if (PROXY_ADDRESS != null) {
            proxyInfo = new ProxyInfo();
            proxyInfo.host = StringUtils.trimToNull(PROXY_ADDRESS);
            proxyInfo.port = PROXY_PORT;
            if (PROXY_USERNAME != null) {
                proxyInfo.username = StringUtils.trimToNull(PROXY_USERNAME);
            }
            if (PROXY_PASSWORD != null) {
                proxyInfo.password = StringUtils.trimToNull(PROXY_PASSWORD);
            }
        }
        return proxyInfo;
    }

    /**
     * Creates a ProxyInfo object from the environment.
     * @return a ProxyInfo object, or null if proxy is not defined
     */
    private static ProxyInfo fromEnvironment() {
        ProxyInfo proxyInfo = null;
        try {
            proxyInfo = buildfromEnvironment(System.getenv("https_proxy"));
            if (proxyInfo == null) {
                proxyInfo = buildfromEnvironment(System.getenv("http_proxy"));
            }
        } catch (MalformedURLException | SecurityException e) {
            LOGGER.warn("Could not parse proxy settings from environment", e);
        }
        return proxyInfo;
    }

    /**
     * Retrieves and parses the https_proxy and http_proxy settings. This method ignores the
     * case of the variables in the environment.
     * @param variable the name of the environment variable
     * @return a ProxyInfo object, or null if proxy is not defined
     * @throws MalformedURLException if the URL of the proxy setting cannot be parsed
     * @throws SecurityException if the environment variable cannot be retrieved
     */
    private static ProxyInfo buildfromEnvironment(String variable) throws MalformedURLException, SecurityException {
        if (variable == null) {
            return null;
        }
        ProxyInfo proxyInfo = null;

        String proxy = null;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (variable.toUpperCase().equals(entry.getKey().toUpperCase())) {
                proxy = System.getenv(entry.getKey());
                break;
            }
        }

        if (proxy != null) {
            final URL proxyUrl = new URL(proxy);
            proxyInfo = new ProxyInfo();
            proxyInfo.host = proxyUrl.getHost();
            proxyInfo.port = proxyUrl.getPort();
            if (proxyUrl.getUserInfo() != null) {
                final String[] credentials = proxyUrl.getUserInfo().split(":");
                if (credentials.length > 0) {
                    proxyInfo.username = credentials[0];
                }
                if (credentials.length == 2) {
                    proxyInfo.password = credentials[1];
                }
            }
        }
        return proxyInfo;
    }

    /**
     * A simple holder class for proxy configuration.
     */
    public static class ProxyInfo {
        private String host;
        private int port;
        private String username;
        private String password;

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

}
