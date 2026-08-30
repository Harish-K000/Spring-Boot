package com.example.platform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the routing table. A typo in the {@code spring.cloud.gateway.server.webflux.routes}
 * property path binds nothing at all and the gateway would start with zero routes, so this
 * asserts the routes are actually present and point at the expected services.
 */
@SpringBootTest(properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    private Map<String, String> routeUrisById() {
        Flux<Route> routes = routeLocator.getRoutes();
        return routes.collectList().block().stream()
                .collect(Collectors.toMap(Route::getId, r -> r.getUri().toString(), (a, b) -> a));
    }

    @Test
    void allFourServiceRoutesAreConfigured() {
        Map<String, String> routes = routeUrisById();

        assertThat(routes).containsOnlyKeys(
                "auth-service", "orders-service", "inventory-service", "payments-service");
    }

    @Test
    void routesPointAtTheDefaultServicePorts() {
        Map<String, String> routes = routeUrisById();

        assertThat(routes.get("auth-service")).isEqualTo("http://localhost:8081");
        assertThat(routes.get("orders-service")).isEqualTo("http://localhost:8082");
        assertThat(routes.get("inventory-service")).isEqualTo("http://localhost:8083");
        assertThat(routes.get("payments-service")).isEqualTo("http://localhost:8084");
    }

    @Test
    void authRouteMatchesBothAuthAndProtectedPaths() {
        Route authRoute = routeLocator.getRoutes()
                .filter(r -> "auth-service".equals(r.getId()))
                .blockFirst();

        assertThat(authRoute).isNotNull();
        assertThat(matches(authRoute, "/api/v1/auth/login")).isTrue();
        assertThat(matches(authRoute, "/api/v1/protected/me")).isTrue();
        assertThat(matches(authRoute, "/api/v1/orders/123")).isFalse();
    }

    @Test
    void ordersRouteMatchesOnlyOrderPaths() {
        Route ordersRoute = routeLocator.getRoutes()
                .filter(r -> "orders-service".equals(r.getId()))
                .blockFirst();

        assertThat(ordersRoute).isNotNull();
        assertThat(matches(ordersRoute, "/api/v1/orders")).isTrue();
        assertThat(matches(ordersRoute, "/api/v1/orders/abc")).isTrue();
        assertThat(matches(ordersRoute, "/api/v1/products/abc")).isFalse();
    }

    /** Route predicates are asynchronous, so the result has to be subscribed to, not cast. */
    private boolean matches(Route route, String path) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block());
    }
}
