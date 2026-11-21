package demo.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

final class ApiClient {
    private final String base = System.getProperty("support.api", "http://127.0.0.1:8080");
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    JsonNode get(String path) throws IOException, InterruptedException {
        return send(request(path));
    }

    JsonNode write(String method, String path, Map<String, String> data)
            throws IOException, InterruptedException {
        return mutate(method, path, "application/json", json.writeValueAsString(data));
    }

    void login(String username, String password) throws IOException, InterruptedException {
        mutate("POST", "/login", "application/x-www-form-urlencoded",
                "username=" + encode(username) + "&password=" + encode(password));
    }

    void logout() throws IOException, InterruptedException {
        mutate("POST", "/logout", "application/x-www-form-urlencoded", "");
    }

    void forgetSession() {
        cookies.getCookieStore().removeAll();
    }

    private JsonNode mutate(String method, String path, String contentType, String data)
            throws IOException, InterruptedException {
        JsonNode csrf = get("/csrf");
        return send(request(path).header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(data)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + "/api" + path))
                .header("Accept", "application/json").timeout(Duration.ofSeconds(12));
    }

    private JsonNode send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ApiError(status);
        }
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }


    static final class ApiError extends IOException {
        final int status;

        ApiError(int status) {
            super(switch (status) {
                case 400 -> "Revisa los campos y sus límites.";
                case 401 -> "Credenciales incorrectas o sesión vencida.";
                case 403 -> "Solicitud rechazada por la API.";
                case 404 -> "El ticket no está disponible.";
                case 409 -> "El nombre de usuario ya está en uso.";
                default -> "La API respondió con HTTP " + status + ".";
            });
            this.status = status;
        }
    }
}
