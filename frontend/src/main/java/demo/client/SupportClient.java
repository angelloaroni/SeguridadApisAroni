package demo.client;

import com.fasterxml.jackson.databind.JsonNode;
import demo.client.api.ApiClient;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class SupportClient extends Application {
    private final ApiClient api = new ApiClient();
    private final StackPane content = new StackPane();
    private final Label status = new Label();
    private boolean signedIn;

    @Override
    public void start(Stage stage) {
        var root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setCenter(content);
        status.getStyleClass().add("status");
        status.setWrapText(true);
        status.managedProperty().bind(status.visibleProperty());
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        root.setBottom(status);
        var scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        stage.setTitle("Mesa de soporte");
        stage.setMinWidth(760);
        stage.setMinHeight(600);
        stage.setScene(scene);
        authentication(false);
        stage.show();
    }

    private void authentication(boolean register) {
        signedIn = false;
        var username = field("Ej. maria.perez");
        var password = new PasswordField();
        password.setPromptText("Tu contraseña");
        var name = field("¿Cómo te llamas?");
        var form = new VBox(14);
        form.getStyleClass().add("auth-card");
        form.setMaxWidth(480);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        var brand = label("●  MESA DE SOPORTE", "eyebrow");
        var title = label(register ? "Crea tu cuenta" : "Bienvenido de nuevo", "page-title");
        var intro = label(register ? "Crea una cuenta para iniciar una conversación con soporte."
                : "Ingresa para consultar tus solicitudes y hablar con el equipo de soporte.", "muted");
        intro.setWrapText(true);
        form.getChildren().addAll(brand, title, intro, labeled("Usuario", username));
        if (register) form.getChildren().add(labeled("Nombre visible", name));
        form.getChildren().add(labeled("Contraseña", password));
        if (register) {
            var help = label("Usuario: 3–40 caracteres, minúsculas, números, punto, guion o guion bajo. Contraseña: al menos 12 caracteres y hasta 72 bytes UTF-8.", "helper");
            help.setWrapText(true);
            form.getChildren().add(help);
        }
        var submit = button(register ? "Crear cuenta" : "Iniciar sesión", "primary", () -> {
            String login = username.getText().strip(), secret = password.getText(), displayName = name.getText().strip();
            if (login.isBlank() || secret.isBlank() || (register && displayName.isBlank())) {
                showStatus("Completa todos los campos.", true); return;
            }
            if (register && (!login.matches("[a-z0-9_.-]{3,40}") || displayName.length() > 80
                    || secret.codePointCount(0, secret.length()) < 12 || secret.getBytes(StandardCharsets.UTF_8).length > 72)) {
                showStatus("Revisa el usuario, el nombre y los requisitos de la contraseña.", true); return;
            }
            if (register) {
                run(() -> api.write("POST", "/register", Map.of("username", login, "displayName", displayName, "password", secret)), result -> {
                    authentication(false); showStatus("Cuenta creada. Ya puedes iniciar sesión.", false);
                });
            } else {
                run(() -> { api.login(login, secret); try { return api.get("/profile"); }
                    catch (Exception failure) { api.forgetSession(); throw failure; } }, profile -> {
                    signedIn = true; dashboard(profile);
                });
            }
        });
        submit.setMaxWidth(Double.MAX_VALUE);
        password.setOnAction(e -> submit.fire());
        var toggle = button(register ? "Ya tengo una cuenta" : "Crear una cuenta", "link-button", () -> {
            showStatus("", false); authentication(!register);
        });
        toggle.setMaxWidth(Double.MAX_VALUE);
        form.getChildren().addAll(submit, toggle);
        content.getChildren().setAll(form);
    }

    private void dashboard(JsonNode profile) {
        var heading = new VBox(4, label("MESA DE SOPORTE", "eyebrow"), label("Hola, " + profile.path("displayName").asText(), "header-title"));
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var logout = button("Cerrar sesión", "secondary", () -> run(() -> {
            try { api.logout(); } finally { api.forgetSession(); } return null;
        }, ignored -> authentication(false), () -> authentication(false)));
        var header = new HBox(12, heading, spacer, logout);
        header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("header");

        var tickets = new ListView<JsonNode>();
        tickets.setPlaceholder(label("Aún no hay solicitudes para mostrar.", "muted"));
        tickets.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(JsonNode ticket, boolean empty) {
                super.updateItem(ticket, empty);
                if (empty || ticket == null) { setGraphic(null); setText(null); return; }
                var subject = label(ticket.path("subject").asText(), "ticket-subject"); subject.setWrapText(true);
                var meta = label("SOLICITUD #" + ticket.path("id").asLong(), "ticket-meta");
                setGraphic(new VBox(5, meta, subject)); setText(null);
            }
        });
        var ticketView = new VBox(16); ticketView.getStyleClass().add("panel");
        VBox.setVgrow(tickets, Priority.ALWAYS);
        Runnable load = () -> run(() -> api.get("/tickets"), result -> {
            tickets.getItems().clear(); result.forEach(tickets.getItems()::add);
        });
        var actions = new HBox(10); actions.setAlignment(Pos.CENTER_LEFT);
        actions.getChildren().addAll(button("Actualizar", "secondary", load), button("Abrir solicitud", "secondary", () -> {
            JsonNode selected = tickets.getSelectionModel().getSelectedItem();
            if (selected == null) showStatus("Selecciona una solicitud para abrirla.", true);
            else conversation(selected.path("id").asLong(), ticketView, tickets, actions);
        }));
        if ("USER".equals(profile.path("role").asText())) actions.getChildren().add(button("+ Nueva solicitud", "primary", () -> newTicket(ticketView, tickets, actions)));
        tickets.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && tickets.getSelectionModel().getSelectedItem() != null)
                conversation(tickets.getSelectionModel().getSelectedItem().path("id").asLong(), ticketView, tickets, actions);
        });
        showTickets(ticketView, tickets, actions);

        var name = field("Nombre visible"); name.setText(profile.path("displayName").asText());
        var profileView = new VBox(16, label("Información personal", "section-title"),
                label("Estos datos se utilizan para identificarte en soporte.", "muted"),
                labeled("Usuario", label(profile.path("username").asText(), "value")),
                labeled("Rol", label("AGENT".equals(profile.path("role").asText()) ? "Agente de soporte" : "Usuario", "value")),
                labeled("Nombre visible", name), button("Guardar cambios", "primary", () -> {
                    String updatedName = name.getText().strip();
                    if (updatedName.isBlank() || updatedName.length() > 80) { showStatus("El nombre debe tener entre 1 y 80 caracteres.", true); return; }
                    run(() -> api.write("PATCH", "/profile", Map.of("displayName", updatedName)), updated -> {
                        name.setText(updated.path("displayName").asText());
                        ((Label) heading.getChildren().get(1)).setText("Hola, " + updated.path("displayName").asText());
                        showStatus("Perfil guardado correctamente.", false);
                    });
                }));
        profileView.getStyleClass().add("panel"); name.setMaxWidth(400);
        var tabs = new TabPane(new Tab("Solicitudes", ticketView), new Tab("Mi perfil", profileView));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var page = new BorderPane(); page.setTop(header); page.setCenter(tabs); page.getStyleClass().add("dashboard");
        content.getChildren().setAll(page); load.run();
    }

    private void showTickets(VBox view, ListView<JsonNode> tickets, HBox actions) {
        view.getChildren().setAll(label("Tus solicitudes", "section-title"), label("Selecciona una solicitud para ver la conversación.", "muted"), actions, tickets);
    }

    private void newTicket(VBox view, ListView<JsonNode> tickets, HBox actions) {
        var subject = field("Resume tu solicitud");
        var body = messageBox("Cuéntanos en qué podemos ayudarte");
        view.getChildren().setAll(button("← Volver a solicitudes", "link-button", () -> showTickets(view, tickets, actions)),
                label("Nueva solicitud", "section-title"), label("Comparte los detalles para que podamos ayudarte.", "muted"),
                labeled("Asunto · máximo 120 caracteres", subject), labeled("Descripción · máximo 2000 caracteres", body),
                button("Crear solicitud", "primary", () -> {
                    String s = subject.getText().strip(), b = body.getText().strip();
                    if (!validText(s, 120) || !validText(b, 2000)) { showStatus("Completa el asunto y la descripción respetando los límites indicados.", true); return; }
                    run(() -> api.write("POST", "/tickets", Map.of("subject", s, "body", b)), ticket -> {
                        tickets.getItems().addFirst(ticket); conversation(ticket.path("id").asLong(), view, tickets, actions);
                    });
                }));
    }

    private void conversation(long id, VBox view, ListView<JsonNode> tickets, HBox actions) {
        run(() -> api.get("/tickets/" + id), result -> {
            var messages = new ListView<JsonNode>();
            result.path("messages").forEach(messages.getItems()::add);
            messages.setPlaceholder(label("Todavía no hay mensajes.", "muted"));
            messages.setCellFactory(list -> new ListCell<>() {
                @Override protected void updateItem(JsonNode message, boolean empty) {
                    super.updateItem(message, empty);
                    if (empty || message == null) { setGraphic(null); setText(null); return; }
                    var body = label(message.path("body").asText(), "message-body"); body.setWrapText(true);
                    body.maxWidthProperty().bind(list.widthProperty().subtract(70));
                    var bubble = new VBox(6, label(message.path("author").asText(), "message-author"), body);
                    bubble.getStyleClass().add("message-bubble"); setGraphic(bubble); setText(null);
                }
            });
            VBox.setVgrow(messages, Priority.ALWAYS);
            var reply = messageBox("Escribe tu respuesta");
            var top = new HBox(10, button("← Volver a solicitudes", "link-button", () -> showTickets(view, tickets, actions)),
                    button("Actualizar conversación", "secondary", () -> conversation(id, view, tickets, actions)));
            view.getChildren().setAll(top, label("SOLICITUD #" + id, "eyebrow"),
                    label(result.path("ticket").path("subject").asText(), "section-title"), messages, reply,
                    button("Enviar respuesta", "primary", () -> {
                        String text = reply.getText().strip();
                        if (!validText(text, 2000)) { showStatus("Escribe una respuesta de hasta 2000 caracteres.", true); return; }
                        run(() -> api.write("POST", "/tickets/" + id + "/messages", Map.of("body", text)), saved -> conversation(id, view, tickets, actions));
                    }));
            if (!messages.getItems().isEmpty()) messages.scrollTo(messages.getItems().size() - 1);
        });
    }

    private static boolean validText(String text, int max) { return !text.isBlank() && text.length() <= max; }
    private void showStatus(String text, boolean error) {
        status.setText(text); status.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), error);
    }
    private <T> void run(Callable<T> operation, Consumer<T> success) { run(operation, success, () -> {}); }
    private <T> void run(Callable<T> operation, Consumer<T> success, Runnable failure) {
        content.setDisable(true); showStatus("Cargando…", false);
        var task = new Task<T>() { @Override protected T call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(event -> { content.setDisable(false); showStatus("", false); success.accept(task.getValue()); });
        task.setOnFailed(event -> {
            content.setDisable(false); Throwable error = task.getException();
            if (signedIn && error instanceof ApiClient.ApiError apiError && apiError.status == 401) { api.forgetSession(); authentication(false); }
            failure.run();
            showStatus(error instanceof ApiClient.ApiError ? error.getMessage() : error instanceof ConnectException
                    ? "No se pudo conectar al backend." : "No se pudo completar la solicitud.", true);
        });
        Thread.startVirtualThread(task);
    }
    private static Label label(String text, String style) { var label = new Label(text); label.getStyleClass().add(style); return label; }
    private static VBox labeled(String title, Node node) { return new VBox(7, label(title, "field-label"), node); }
    private static Button button(String text, String style, Runnable action) {
        var button = new Button(text); button.getStyleClass().add(style); button.setOnAction(event -> action.run()); return button;
    }
    private static TextField field(String prompt) { var field = new TextField(); field.setPromptText(prompt); return field; }
    private static TextArea messageBox(String prompt) {
        var area = new TextArea(); area.setPromptText(prompt); area.setWrapText(true); area.setPrefRowCount(3); area.setMaxHeight(120); return area;
    }
    public static void main(String[] args) { launch(args); }
}
