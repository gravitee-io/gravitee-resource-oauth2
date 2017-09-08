/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.oauth2.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.resource.oauth2.api.OAuth2Resource;
import io.gravitee.resource.oauth2.api.OAuth2Response;
import io.gravitee.resource.oauth2.generic.configuration.OAuth2ResourceConfiguration;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OAuth2GenericResource extends OAuth2Resource<OAuth2ResourceConfiguration> implements ApplicationContextAware {

    private final Logger logger = LoggerFactory.getLogger(OAuth2GenericResource.class);

    private static final String HTTPS_SCHEME = "https";

    private static final char AUTHORIZATION_HEADER_SCHEME_SEPARATOR = ' ';
    private static final char AUTHORIZATION_HEADER_VALUE_BASE64_SEPARATOR = ':';

    private ApplicationContext applicationContext;

    private final Map<Context, HttpClient> httpClients = new HashMap<>();

    private HttpClientOptions httpClientOptions;

    private Vertx vertx;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        logger.info("Starting an OAuth2 resource using authorization server at {}", configuration().getIntrospectionEndpoint());

        URI introspectionUri = URI.create(configuration().getIntrospectionEndpoint());

        int authorizationServerPort = introspectionUri.getPort() != -1 ? introspectionUri.getPort() :
                (HTTPS_SCHEME.equals(introspectionUri.getScheme()) ? 443 : 80);
        String authorizationServerHost = introspectionUri.getHost();

        httpClientOptions = new HttpClientOptions()
                .setDefaultPort(authorizationServerPort)
                .setDefaultHost(authorizationServerHost);

        // Use SSL connection if authorization schema is set to HTTPS
        if (HTTPS_SCHEME.equalsIgnoreCase(introspectionUri.getScheme())) {
            httpClientOptions
                    .setSsl(true)
                    .setVerifyHost(false)
                    .setTrustAll(true);
        }

        vertx = applicationContext.getBean(Vertx.class);
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();

        httpClients.values().forEach(httpClient -> {
            try {
                httpClient.close();
            } catch (IllegalStateException ise) {
                logger.warn(ise.getMessage());
            }
        });
    }

    @Override
    public void introspect(String accessToken, Handler<OAuth2Response> responseHandler) {
        HttpClient httpClient = httpClients.computeIfAbsent(
                Vertx.currentContext(), context -> vertx.createHttpClient(httpClientOptions));

        OAuth2ResourceConfiguration configuration = configuration();
        StringBuilder introspectionUriBuilder = new StringBuilder(configuration.getIntrospectionEndpoint());

        if (configuration.isTokenIsSuppliedByQueryParam()) {
            introspectionUriBuilder
                    .append('?').append(configuration.getTokenQueryParamName())
                    .append('=').append(accessToken);
        }

        String introspectionEndpointURI = introspectionUriBuilder.toString();
        logger.debug("Introspect access token by requesting {} [{}]", introspectionEndpointURI,
                configuration.getIntrospectionEndpointMethod());

        HttpMethod httpMethod = HttpMethod.valueOf(configuration.getIntrospectionEndpointMethod().toUpperCase());

        HttpClientRequest request = httpClient.request(httpMethod, introspectionEndpointURI);

        if (configuration().isUseClientAuthorizationHeader()) {
            String authorizationHeader = configuration.getClientAuthorizationHeaderName();
            String authorizationValue = configuration.getClientAuthorizationHeaderScheme().trim() +
                    AUTHORIZATION_HEADER_SCHEME_SEPARATOR +
                    Base64.getEncoder().encodeToString(
                            (configuration.getClientId() +
                                    AUTHORIZATION_HEADER_VALUE_BASE64_SEPARATOR +
                                    configuration.getClientSecret()).getBytes());
            request.headers().add(authorizationHeader, authorizationValue);
            logger.debug("Set client authorization using HTTP header {} with value {}", authorizationHeader, authorizationValue);
        }

        // Set `Accept` header to ask for application/json content
        request.headers().add(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);

        if (configuration.isTokenIsSuppliedByHttpHeader()) {
            request.headers().add(configuration.getTokenHeaderName(), accessToken);
        }

        request.handler(response -> response.bodyHandler(buffer -> {
            logger.debug("Introspection endpoint returns a response with a {} status code", response.statusCode());
            if (response.statusCode() == HttpStatusCode.OK_200) {
                // According to RFC 7662 : Note that a properly formed and authorized query for an inactive or
                // otherwise invalid token (or a token the protected resource is not
                // allowed to know about) is not considered an error response by this
                // specification.  In these cases, the authorization server MUST instead
                // respond with an introspection response with the "active" field set to
                // "false" as described in Section 2.2.
                String content = buffer.toString();

                try {
                    JsonNode introspectNode = MAPPER.readTree(content);
                    JsonNode activeNode = introspectNode.get("active");
                    if (activeNode != null) {
                        boolean isActive = activeNode.asBoolean();
                        responseHandler.handle(new OAuth2Response(isActive, content));
                    } else {
                        responseHandler.handle(new OAuth2Response(true, content));
                    }
                } catch (IOException e) {
                    logger.error("Unable to validate introspection endpoint payload: {}", content);
                    responseHandler.handle(new OAuth2Response(false, content));
                }
            } else {
                responseHandler.handle(new OAuth2Response(false, buffer.toString()));
            }
        }));

        request.exceptionHandler(event -> {
            logger.error("An error occurs while checking OAuth2 token", event);
            responseHandler.handle(new OAuth2Response(false, event.getMessage()));
        });

        if (httpMethod == HttpMethod.POST && configuration.isTokenIsSuppliedByFormUrlEncoded()) {
            request.headers().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED);
            request.end(configuration.getTokenFormUrlEncodedName() + '=' + accessToken);
        } else {
            request.end();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
