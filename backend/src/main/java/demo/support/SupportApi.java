package demo.support;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
class SupportApi {
    private final JdbcClient db;
    private final PasswordEncoder passwords;

    SupportApi(JdbcClient db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    record Registration(@NotBlank @Pattern(regexp = "[a-z0-9_.-]{3,40}") String username,
                        @NotBlank @Size(max = 80) String displayName, @NotBlank String password) {}
    record Profile(String username, String displayName, String role) {}
    record UpdateProfile(@NotBlank @Size(max = 80) String displayName) {}
    record NewTicket(@NotBlank @Size(max = 120) String subject,
                     @NotBlank @Size(max = 2000) String body) {}
    record NewMessage(@NotBlank @Size(max = 2000) String body) {}
    record Ticket(long id, String subject, String owner, String agent) {}
    record Message(String author, String body, OffsetDateTime createdAt) {}
    record Conversation(Ticket ticket, List<Message> messages) {}

    @GetMapping("/csrf")
    CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Registration form) {
        if ("agente".equals(form.username()) || !validPassword(form.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            db.sql("INSERT INTO accounts VALUES (?, ?, ?, 'USER')")
                    .params(form.username(), form.displayName().strip(), passwords.encode(form.password()))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    static boolean validPassword(String password) {
        return !password.isBlank() && password.codePointCount(0, password.length()) >= 12
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @GetMapping("/profile")
    @SecurityRequirement(name = "session")
    Profile profile(Principal user) {
        return db.sql("SELECT username, display_name, role FROM accounts WHERE username = ?")
                .param(user.getName()).query(Profile.class).single();
    }

    @PatchMapping("/profile")
    @SecurityRequirement(name = "session")
    Profile updateProfile(@Valid @RequestBody UpdateProfile form, Principal user) {
        db.sql("UPDATE accounts SET display_name = ? WHERE username = ?")
                .params(form.displayName().strip(), user.getName()).update();
        return profile(user);
    }

    @GetMapping("/tickets")
    @SecurityRequirement(name = "session")
    List<Ticket> tickets(Principal user) {
        return db.sql("SELECT * FROM tickets WHERE owner = ? OR agent = ? ORDER BY id DESC")
                .params(user.getName(), user.getName()).query(Ticket.class).list();
    }

    @PostMapping("/tickets")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "session")
    Ticket create(@Valid @RequestBody NewTicket form, Principal user) {
        var key = new GeneratedKeyHolder();
        db.sql("""
                INSERT INTO tickets(subject, owner, agent)
                VALUES (?, ?, (SELECT username FROM accounts WHERE role = 'AGENT' ORDER BY username FETCH FIRST 1 ROW ONLY))
                """).params(form.subject().strip(), user.getName()).update(key, "ID");
        long id = key.getKey().longValue();
        addMessage(id, user.getName(), form.body());
        return visibleTicket(id, user);
    }

    @GetMapping("/tickets/{id}")
    @SecurityRequirement(name = "session")
    Conversation conversation(@PathVariable long id, Principal user) {
        Ticket ticket = visibleTicket(id, user);
        var messages = db.sql("SELECT author, body, created_at FROM messages WHERE ticket_id = ? ORDER BY id")
                .param(id).query(Message.class).list();
        return new Conversation(ticket, messages);
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "session")
    void reply(@PathVariable long id, @Valid @RequestBody NewMessage form, Principal user) {
        visibleTicket(id, user);
        addMessage(id, user.getName(), form.body());
    }

    private Ticket visibleTicket(long id, Principal user) {
        return db.sql("SELECT * FROM tickets WHERE id = ? AND (owner = ? OR agent = ?)")
                .params(id, user.getName(), user.getName()).query(Ticket.class)
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void addMessage(long id, String author, String body) {
        db.sql("INSERT INTO messages(ticket_id, author, body) VALUES (?, ?, ?)")
                .params(id, author, body.strip()).update();
    }
}
