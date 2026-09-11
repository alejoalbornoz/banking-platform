package com.portfolio.banking.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

/**
 * The gateway's entire job is to forward requests somewhere else, so it
 * cannot be tested without a somewhere. WireMock plays every downstream at
 * once: the four {@code *_SERVICE_PORT} variables in application.yml exist
 * precisely so that a test can point all of them at one in-process fake.
 * <p>
 * Two things are worth pinning, and both are the kind that break silently.
 * The {@code RewritePath} filters behind the aggregated Swagger UI are
 * regexes, and a typo in one would leave the UI's dropdown fetching a 404
 * with no test anywhere else to notice. And the {@code Authorization} header
 * must reach the backend untouched - every downstream service validates it,
 * and a gateway that dropped it would turn every request into a 401 while
 * looking perfectly healthy itself.
 * <p>
 * Until this class existed, api-gateway had no tests at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWireMock(port = 0)
@TestPropertySource(properties = {
        "AUTH_SERVICE_PORT=${wiremock.server.port}",
        "ACCOUNT_SERVICE_PORT=${wiremock.server.port}",
        "TRANSACTION_SERVICE_PORT=${wiremock.server.port}",
        "NOTIFICATION_SERVICE_PORT=${wiremock.server.port}"
})
class GatewayRoutingTest {

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void cleanSlate() {
        reset();
    }

    @Test
    void apiRoutes_reachTheBackendAtTheSamePath() {
        stubFor(get(urlPathEqualTo("/api/v1/accounts")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"items\":[],\"nextCursor\":null}")));
        stubFor(post(urlPathEqualTo("/api/v1/transfers")).willReturn(aResponse().withStatus(201)));
        stubFor(get(urlPathEqualTo("/api/v1/notifications")).willReturn(aResponse().withStatus(200)));
        stubFor(post(urlPathEqualTo("/api/v1/auth/login")).willReturn(aResponse().withStatus(200)));
        stubFor(get(urlPathEqualTo("/.well-known/jwks.json")).willReturn(aResponse().withStatus(200)));

        client.get().uri("/api/v1/accounts?limit=5").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items").isArray();
        client.post().uri("/api/v1/transfers").exchange().expectStatus().isCreated();
        client.get().uri("/api/v1/notifications?accountId=x").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();

        // The query string travels too - a gateway that dropped it would break
        // every paginated read while returning 200.
        verify(getRequestedFor(urlEqualTo("/api/v1/accounts?limit=5")));
    }

    /**
     * The gateway does not validate tokens itself - each service does - so
     * it is essential that it does not strip or mangle the header on the
     * way through.
     */
    @Test
    void theAuthorizationHeader_isForwardedUntouched() {
        stubFor(get(urlPathEqualTo("/api/v1/accounts")).willReturn(aResponse().withStatus(200)));

        client.get().uri("/api/v1/accounts")
                .header("Authorization", "Bearer the-users-token")
                .exchange().expectStatus().isOk();

        verify(getRequestedFor(urlPathEqualTo("/api/v1/accounts"))
                .withHeader("Authorization", equalTo("Bearer the-users-token")));
    }

    /**
     * The four docs routes are the aggregated Swagger UI's only way to fetch
     * each service's document from a single origin. Each one is a regex
     * rewrite, and a wrong one fails as a 404 in a dropdown that nothing else
     * tests.
     */
    @Test
    void docsRoutes_areRewrittenToEachServicesOwnApiDocsPath() {
        stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"openapi\":\"3.0.1\"}")));

        for (String service : new String[] {"auth-service", "account-service", "transaction-service", "notification-service"}) {
            client.get().uri("/docs/" + service + "/v3/api-docs").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.openapi").isEqualTo("3.0.1");
        }

        // Four fetches, all landing on the bare /v3/api-docs the services
        // actually serve - never on the /docs/... prefix the browser used.
        verify(4, getRequestedFor(urlEqualTo("/v3/api-docs")));
        verify(0, getRequestedFor(urlPathEqualTo("/docs/account-service/v3/api-docs")));
    }

    @Test
    void anUnknownPath_is404FromTheGatewayItself_andNeverReachesABackend() {
        client.get().uri("/api/v2/anything").exchange().expectStatus().isNotFound();
        client.get().uri("/admin").exchange().expectStatus().isNotFound();

        verify(0, getRequestedFor(urlPathEqualTo("/api/v2/anything")));
        verify(0, getRequestedFor(urlPathEqualTo("/admin")));
    }

    @Test
    void aBackendFailure_isPassedThroughAsIs_notMaskedAsAGatewayError() {
        // A 403 from account-service means "not your account" and must reach
        // the client as exactly that; a gateway that turned it into a 502
        // would hide the one thing the client needs to know.
        stubFor(get(urlPathEqualTo("/api/v1/accounts/abc")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json").withBody("{\"errorCode\":\"FORBIDDEN\"}")));

        client.get().uri("/api/v1/accounts/abc").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo("FORBIDDEN");

        verify(getRequestedFor(urlPathEqualTo("/api/v1/accounts/abc")));
    }
}
