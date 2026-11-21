# Mesa de soporte

Aplicación de escritorio para demostrar autenticación, autorización por ticket y protección de datos en una API REST. El proyecto usa Java 21, Maven, JavaFX, Spring Boot, Spring Security y H2.

- `frontend/` contiene la aplicación JavaFX. Se comunica con la API por HTTP y mantiene la cookie de sesión en memoria.
- `backend/` contiene la API, la configuración de seguridad y el esquema de base de datos.

Consulta el [informe de seguridad de la API](SEGURIDAD-API.md) para ver las pruebas, los resultados y la demostración.

## Ejecutar en local

Instala un **JDK 21** y **Maven 3.9**. Comprueba la versión que usa Maven con `mvn -v`.

1. Desde la raíz del repositorio, en una terminal Bash, inicia el backend.

   ```bash
   cd backend
   read -rsp 'Contraseña del agente: ' SUPPORT_AGENT_PASSWORD
   printf '\n'
   export SUPPORT_AGENT_PASSWORD
   mvn spring-boot:run
   ```

   En PowerShell, ejecuta `cd backend`, luego asigna la variable mediante `$env:SUPPORT_AGENT_PASSWORD = Read-Host -MaskInput "Contraseña del agente"` (PowerShell 7.2+) y ejecuta `mvn spring-boot:run`.

   El agente tiene el usuario `agente`. Su contraseña debe tener al menos 12 caracteres y como máximo 72 bytes UTF-8. La variable crea la cuenta en el primer arranque en que se define. H2 guarda la cuenta en `backend/data/` y conserva la contraseña original en reinicios. Puedes omitir la variable si solo quieres probar el flujo de usuario. Los tickets se asignan al agente si la cuenta ya existe al crearlos.

2. En otra terminal, desde la raíz del repositorio, abre el cliente.

   ```bash
   cd frontend
   mvn javafx:run
   ```

3. Registra una cuenta e inicia sesión. Crea un ticket y envía un mensaje. Cierra sesión y entra como `agente` para responder. Vuelve a la cuenta inicial y abre el ticket para ver la respuesta.

## Verificar los controles

| Control | Cómo comprobarlo |
| --- | --- |
| Autenticación | `GET /api/profile` responde `401` sin sesión y `200` con una sesión válida. |
| Autorización sobre tickets | Crea dos cuentas de usuario y un ticket con la primera. La lista de la segunda omite el ticket. Una prueba de acceso con la segunda cuenta espera `404`. El agente asignado puede responder. |
| Autorización por rol | El agente puede responder tickets asignados. La creación de tickets requiere el rol de usuario. |
| Validación | El perfil acepta `displayName`. Los campos no definidos se rechazan con `400`. |
| Gestión de sesión | La cookie es HttpOnly y SameSite Strict. El cierre de sesión invalida la sesión. El tiempo de inactividad permitido es de 20 minutos. |
| CSRF | Las operaciones de escritura requieren un token CSRF asociado a la sesión. |

Swagger UI está en http://127.0.0.1:8080/swagger-ui.html y el contrato OpenAPI en http://127.0.0.1:8080/v3/api-docs. Puedes descargar el contrato y auditarlo con 42Crunch. Registra el hallazgo, el cambio aplicado y el resultado de la segunda revisión. Para las dependencias Maven, OWASP Dependency-Check puede complementar esa auditoría.

Para usar un cliente HTTP, conserva el mismo almacén de cookies a lo largo del flujo. Antes de una escritura, consulta `GET /api/csrf` y usa el `headerName` y el `token` de la respuesta. El login de Spring Security recibe `username` y `password` mediante `POST /api/login` con `application/x-www-form-urlencoded`. El cierre de sesión usa `POST /api/logout`. Ambas rutas las implementa Spring Security y se describen aquí. Las operaciones de negocio están en el contrato OpenAPI.

La configuración de demo escucha por HTTP en la interfaz local. Para exponer el servicio fuera del equipo, configura HTTPS y activa el atributo Secure de la cookie.
