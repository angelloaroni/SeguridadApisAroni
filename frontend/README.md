# Frontend de mesa de soporte

Aplicación de escritorio JavaFX. Desde este directorio, con JDK 21 y Maven, se inicia con `mvn javafx:run`. El backend debe estar en ejecución; por defecto se usa `http://127.0.0.1:8080`. Para cambiar la dirección, configura la propiedad Java `support.api`.

La interfaz y la gestión de pantallas están en `src/main/java/demo/client/SupportClient.java`; las peticiones, cookies de sesión y tokens CSRF se concentran en `src/main/java/demo/client/api/ApiClient.java`. Los estilos están en `src/main/resources/styles/app.css`.

La aplicación usa `/api/register`, `/api/login`, `/api/logout`, `/api/profile`, `/api/tickets` y `/api/tickets/{id}/messages`. Antes de cada escritura solicita `/api/csrf` y conserva las cookies en memoria. Las cuentas con rol `USER` pueden crear solicitudes; los agentes pueden consultar y responder las solicitudes asignadas.
