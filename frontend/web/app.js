const byId = id => document.getElementById(id);
let registering = false;
let signedIn = false;
let selectedId;
let busy = false;

async function api(path, method = "GET", data) {
    const headers = {Accept: "application/json"};
    let body;
    if (method !== "GET") {
        const csrf = await api("/csrf");
        headers[csrf.headerName] = csrf.token;
        if (data instanceof URLSearchParams) {
            body = data;
        } else if (data) {
            headers["Content-Type"] = "application/json";
            body = JSON.stringify(data);
        }
    }
    const result = await fetch("/api" + path, {method, headers, body});
    if (!result.ok) {
        const messages = {
            400: "Revisa los campos. La contraseña debe tener al menos 12 caracteres y un máximo de 72 bytes.",
            401: "Credenciales incorrectas o sesión vencida.",
            403: "La API rechazó la operación.",
            404: "El ticket no está disponible.",
            409: "El usuario ya está registrado."
        };
        throw Object.assign(new Error(messages[result.status] || "La operación no pudo completarse."), {status: result.status});
    }
    const content = await result.text();
    return content ? JSON.parse(content) : null;
}

async function run(action) {
    if (busy) return;
    busy = true;
    byId("status").textContent = "";
    document.querySelectorAll("button").forEach(button => button.disabled = true);
    try {
        await action();
    } catch (error) {
        if (error.status === 401 && signedIn) showAuth();
        byId("status").textContent = error.message;
    } finally {
        document.querySelectorAll("button").forEach(button => button.disabled = false);
        busy = false;
    }
}

function authMode(mode) {
    registering = mode;
    byId("auth-title").textContent = mode ? "Crear cuenta" : "Iniciar sesión";
    byId("registration-name").hidden = !mode;
    const {displayName, password} = byId("credentials").elements;
    displayName.required = mode;
    password.autocomplete = mode ? "new-password" : "current-password";
    byId("login").textContent = mode ? "Registrarme" : "Entrar";
    byId("toggle").textContent = mode ? "Volver a iniciar sesión" : "Crear una cuenta";
}

function showAuth() {
    signedIn = false;
    selectedId = undefined;
    byId("credentials").reset();
    byId("workspace").hidden = true;
    byId("logout").hidden = true;
    byId("auth").hidden = false;
    authMode(false);
    panel("empty");
}

function panel(active) {
    for (const id of ["empty", "create", "conversation"]) byId(id).hidden = id !== active;
}

function profileView(profile) {
    byId("identity").textContent = profile.displayName + " · " + (profile.role === "AGENT" ? "Agente" : "Usuario");
    byId("profile").elements.displayName.value = profile.displayName;
    byId("new-ticket").hidden = profile.role !== "USER";
}

async function tickets() {
    const items = await api("/tickets");
    const list = byId("tickets");
    list.replaceChildren();
    if (!items.length) list.append("Aún no hay tickets.");
    for (const ticket of items) {
        const li = document.createElement("li");
        const button = document.createElement("button");
        button.textContent = "#" + ticket.id + " · " + ticket.subject;
        button.onclick = () => run(() => conversation(ticket.id));
        li.append(button);
        list.append(li);
    }
}

async function conversation(id) {
    const result = await api("/tickets/" + id);
    selectedId = id;
    byId("subject").textContent = result.ticket.subject;
    byId("messages").replaceChildren(...result.messages.map(message => {
        const card = document.createElement("article");
        const author = document.createElement("strong");
        const body = document.createElement("p");
        author.textContent = message.author;
        body.textContent = message.body;
        card.append(author, body);
        return card;
    }));
    panel("conversation");
}

async function showWorkspace(profile) {
    signedIn = true;
    profileView(profile);
    byId("auth").hidden = true;
    byId("workspace").hidden = false;
    byId("logout").hidden = false;
    await tickets();
}

function submit(id, action) {
    byId(id).addEventListener("submit", event => {
        event.preventDefault();
        const form = event.currentTarget;
        run(() => action(Object.fromEntries(new FormData(form)), form));
    });
}

submit("credentials", async ({username, password, displayName}, form) => {
    if (registering) {
        await api("/register", "POST", {username, password, displayName});
        form.elements.password.value = "";
        authMode(false);
        byId("status").textContent = "Cuenta creada. Ya puedes iniciar sesión.";
    } else {
        await api("/login", "POST", new URLSearchParams({username, password}));
        form.reset();
        await showWorkspace(await api("/profile"));
    }
});
submit("profile", async data => {
    profileView(await api("/profile", "PATCH", data));
    byId("status").textContent = "Perfil guardado.";
});
submit("create", async (data, form) => {
    const created = await api("/tickets", "POST", data);
    form.reset();
    await tickets();
    await conversation(created.id);
});
submit("reply", async (data, form) => {
    await api("/tickets/" + selectedId + "/messages", "POST", data);
    form.reset();
    await conversation(selectedId);
});
byId("toggle").onclick = () => {
    authMode(!registering);
    byId("status").textContent = "";
};
byId("new-ticket").onclick = () => panel("create");
byId("reload").onclick = () => run(async () => {
    await tickets();
    if (selectedId !== undefined && !byId("conversation").hidden) await conversation(selectedId);
});
byId("logout").onclick = () => run(async () => {
    await api("/logout", "POST");
    showAuth();
});
run(async () => {
    try {
        await showWorkspace(await api("/profile"));
    } catch (error) {
        if (error.status !== 401) throw error;
    }
});
