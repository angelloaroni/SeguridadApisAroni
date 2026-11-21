package demo.client;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class SupportClient extends Application {
    private final ApiClient api = new ApiClient();
    private final Label status = new Label();
    private final VBox content = new VBox(12);
    private boolean signedIn;

    @Override
    public void start(Stage stage) {
        var root = new VBox(12, content, status);
        root.setPadding(new Insets(20));
        VBox.setVgrow(content, Priority.ALWAYS);
        status.setWrapText(true);
        stage.setTitle("Mesa de soporte");
        stage.setScene(new Scene(root, 760, 570));
        authentication(false);
        stage.show();
    }

    private void authentication(boolean register) {
        signedIn = false;
        var username = field("Usuario");
        var password = new PasswordField();
        password.setPromptText("Contraseña");
        var name = field("Nombre visible");
        var form = new VBox(10, new Label(register ? "Crear cuenta" : "Iniciar sesión"), username);
        form.setMaxWidth(420);
        if (register) {
            form.getChildren().addAll(name, new Label("Usuario de 3 a 40 caracteres. Usa minúsculas, dígitos, punto, guion o guion bajo."),
                    new Label("Contraseña de al menos 12 caracteres y hasta 72 bytes UTF-8."));
        }
        form.getChildren().addAll(password,
                button(register ? "Registrarme" : "Entrar", () -> {
                    String login = username.getText().strip();
                    String secret = password.getText();
                    String displayName = name.getText();
                    if (register) {
                        run(() -> api.write("POST", "/register", Map.of(
                                "username", login, "displayName", displayName, "password", secret)), result -> {
                            authentication(false);
                            status.setText("Cuenta creada. Inicia sesión.");
                        });
                    } else {
                        run(() -> {
                            api.login(login, secret);
                            try {
                                return api.get("/profile");
                            } catch (Exception failure) {
                                api.forgetSession();
                                throw failure;
                            }
                        }, profile -> {
                            signedIn = true;
                            dashboard(profile);
                        });
                    }
                }),
                button(register ? "Volver al inicio de sesión" : "Crear una cuenta", () -> {
                    status.setText("");
                    authentication(!register);
                }));
        for (var child : form.getChildren()) {
            if (child instanceof Label label) {
                label.setWrapText(true);
            }
        }
        content.getChildren().setAll(form);
    }

    private void dashboard(JsonNode profile) {
        var tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var name = field("Nombre visible");
        name.setText(profile.path("displayName").asText());
        var profileView = new VBox(10, new Label("Usuario: " + profile.path("username").asText()),
                new Label("Rol: " + profile.path("role").asText()), name,
                button("Guardar perfil", () -> {
                    String updatedName = name.getText();
                    run(() -> api.write("PATCH", "/profile", Map.of("displayName", updatedName)), updated -> {
                        name.setText(updated.path("displayName").asText());
                        status.setText("Perfil guardado.");
                    });
                }));
        profileView.setPadding(new Insets(12));

        var tickets = new ListView<JsonNode>();
        tickets.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(JsonNode ticket, boolean empty) {
                super.updateItem(ticket, empty);
                setText(empty || ticket == null ? null :
                        "#" + ticket.path("id").asLong() + " · " + ticket.path("subject").asText());
            }
        });
        var ticketView = new VBox(10);
        ticketView.setPadding(new Insets(12));
        VBox.setVgrow(tickets, Priority.ALWAYS);
        Runnable load = () -> run(() -> api.get("/tickets"), result -> {
            tickets.getItems().clear();
            result.forEach(tickets.getItems()::add);
        });
        var actions = new HBox(8);
        actions.getChildren().addAll(button("Actualizar", load), button("Abrir", () -> {
            JsonNode selected = tickets.getSelectionModel().getSelectedItem();
            if (selected != null) {
                conversation(selected.path("id").asLong(), ticketView, tickets, actions);
            } else {
                status.setText("Selecciona un ticket.");
            }
        }));
        if ("USER".equals(profile.path("role").asText())) {
            actions.getChildren().add(button("Nuevo ticket", () -> newTicket(ticketView, tickets, actions)));
        }
        ticketView.getChildren().setAll(actions, tickets);
        tabs.getTabs().addAll(new Tab("Tickets", ticketView), new Tab("Mi perfil", profileView));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        content.getChildren().setAll(button("Cerrar sesión", () -> run(() -> {
            try {
                api.logout();
            } finally {
                api.forgetSession();
            }
            return null;
        }, result -> authentication(false), () -> authentication(false))), tabs);
        load.run();
    }

    private void newTicket(VBox view, ListView<JsonNode> tickets, HBox actions) {
        var subject = field("Asunto (máximo 120 caracteres)");
        var body = messageBox("Describe tu solicitud (máximo 2000 caracteres)");
        view.getChildren().setAll(button("Volver a los tickets", () -> view.getChildren().setAll(actions, tickets)),
                subject, body, button("Crear ticket", () -> {
                    Map<String, String> data = Map.of("subject", subject.getText(), "body", body.getText());
                    run(() -> api.write("POST", "/tickets", data), ticket -> {
                        tickets.getItems().addFirst(ticket);
                        conversation(ticket.path("id").asLong(), view, tickets, actions);
                    });
                }));
    }

    private void conversation(long id, VBox view, ListView<JsonNode> tickets, HBox actions) {
        run(() -> api.get("/tickets/" + id), result -> {
            var messages = new ListView<String>();
            result.path("messages").forEach(message ->
                    messages.getItems().add(message.path("author").asText() + "\n" + message.path("body").asText()));
            messages.setCellFactory(list -> new ListCell<>() {
                @Override
                protected void updateItem(String message, boolean empty) {
                    super.updateItem(message, empty);
                    if (empty || message == null) {
                        setGraphic(null);
                        return;
                    }
                    var bubble = new Label(message);
                    bubble.setWrapText(true);
                    bubble.maxWidthProperty().bind(list.widthProperty().subtract(36));
                    setGraphic(bubble);
                }
            });
            VBox.setVgrow(messages, Priority.ALWAYS);
            var reply = messageBox("Escribe una respuesta");
            view.getChildren().setAll(
                    new HBox(8, button("Volver a los tickets", () -> view.getChildren().setAll(actions, tickets)),
                            button("Actualizar conversación", () -> conversation(id, view, tickets, actions))),
                    new Label(result.path("ticket").path("subject").asText()), messages, reply,
                    button("Enviar respuesta", () -> {
                        var data = Map.of("body", reply.getText());
                        run(() -> api.write("POST", "/tickets/" + id + "/messages", data),
                                saved -> conversation(id, view, tickets, actions));
                    }));
        });
    }

    private <T> void run(Callable<T> operation, Consumer<T> success) {
        run(operation, success, () -> {});
    }

    private <T> void run(Callable<T> operation, Consumer<T> success, Runnable failure) {
        content.setDisable(true);
        status.setText("Cargando…");
        var task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(event -> {
            content.setDisable(false);
            status.setText("");
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            content.setDisable(false);
            Throwable error = task.getException();
            if (signedIn && error instanceof ApiClient.ApiError apiError && apiError.status == 401) {
                api.forgetSession();
                authentication(false);
            }
            failure.run();
            status.setText(error instanceof ApiClient.ApiError ? error.getMessage()
                    : error instanceof ConnectException ? "No se pudo conectar al backend."
                    : "No se pudo completar la solicitud.");
        });
        Thread.startVirtualThread(task);
    }

    private static Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static TextField field(String prompt) {
        var field = new TextField();
        field.setPromptText(prompt);
        return field;
    }

    private static TextArea messageBox(String prompt) {
        var area = new TextArea();
        area.setPromptText(prompt);
        area.setWrapText(true);
        area.setPrefRowCount(3);
        return area;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
