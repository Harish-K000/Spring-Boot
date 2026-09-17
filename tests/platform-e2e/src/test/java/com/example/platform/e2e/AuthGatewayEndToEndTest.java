package com.example.platform.e2e;

import com.example.platform.auth.AuthServiceApplication;
import com.example.platform.gateway.EdgeGatewayApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthGatewayEndToEndTest {

    private static final String JWT_SECRET =
            "auth-gateway-e2e-shared-secret-with-more-than-32-bytes";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY_ROOT = findRepositoryRoot();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static ConfigurableApplicationContext authContext;
    private static ConfigurableApplicationContext gatewayContext;
    private static int gatewayPort;

    @BeforeAll
    static void startApplications() {
        authContext = new SpringApplicationBuilder(AuthServiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.config.location=" + configLocation(
                                "services/auth-service/src/main/resources/application.yaml"),
                        "--server.port=0",
                        "--spring.main.web-application-type=servlet",
                        "--spring.cloud.gateway.server.webflux.enabled=false",
                        "--security.jwt.secret=" + JWT_SECRET,
                        "--spring.datasource.url=jdbc:h2:mem:authGatewayE2e;"
                                + "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--management.otlp.tracing.export.enabled=false",
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.cloud.gateway.config."
                                + "GatewayClassPathWarningAutoConfiguration");
        int authPort = port(authContext);

        gatewayContext = new SpringApplicationBuilder(EdgeGatewayApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--spring.config.location=" + configLocation(
                                "services/edge-gateway/src/main/resources/application.yaml"),
                        "--server.port=0",
                        "--spring.main.web-application-type=reactive",
                        "--security.jwt.secret=" + JWT_SECRET,
                        "--AUTH_SERVICE_URI=http://127.0.0.1:" + authPort,
                        "--gateway.rate-limit.requests-per-window=100",
                        "--management.otlp.tracing.export.enabled=false",
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        gatewayPort = port(gatewayContext);
    }

    @AfterAll
    static void stopApplications() {
        if (gatewayContext != null) {
            gatewayContext.close();
        }
        if (authContext != null) {
            authContext.close();
        }
    }

    @Test
    void registeredUserCanLoginAndUseIssuedTokenThroughGateway() throws Exception {
        String email = "auth-gateway-e2e@example.test";
        String password = "correct-horse-battery-staple";

        HttpResponse<String> registration = post("/api/v1/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(registration.statusCode()).isEqualTo(201);
        JsonNode registeredUser = JSON.readTree(registration.body());
        String userId = registeredUser.path("id").asText();
        assertThat(userId).isNotBlank();
        assertThat(registeredUser.path("email").asText()).isEqualTo(email);
        assertThat(registeredUser.path("roles").get(0).asText()).isEqualTo("USER");

        HttpResponse<String> login = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode tokens = JSON.readTree(login.body());
        String accessToken = tokens.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();
        assertThat(tokens.path("tokenType").asText()).isEqualTo("Bearer");

        HttpRequest protectedRequest = HttpRequest.newBuilder(gatewayUri("/api/v1/protected/me"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Correlation-ID", "auth-gateway-e2e")
                .GET()
                .build();
        HttpResponse<String> protectedResponse = HTTP.send(
                protectedRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(protectedResponse.statusCode()).isEqualTo(200);
        assertThat(protectedResponse.headers().firstValue("X-Correlation-ID"))
                .contains("auth-gateway-e2e");
        JsonNode currentUser = JSON.readTree(protectedResponse.body());
        assertThat(currentUser.path("userId").asText()).isEqualTo(userId);
        assertThat(currentUser.path("roles").isArray()).isTrue();
        assertThat(currentUser.path("roles").get(0).asText()).isEqualTo("ROLE_USER");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static String configLocation(String relativePath) {
        return REPOSITORY_ROOT.resolve(relativePath).toUri().toString();
    }

    private static Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("pom.xml"))
                    && Files.isDirectory(directory.resolve("services/edge-gateway"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate the backend-platform repository root");
    }
}
