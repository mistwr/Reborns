package pt.reborn.assistant;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Estado sobrevive a rotações. Só a thread principal altera os objetos de conversa. */
public final class RebornApp extends Application {
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final List<Runnable> listeners = new ArrayList<>();
    JSONObject data;
    JSONObject serverStatus;
    String notice = "";
    boolean busy = false;
    boolean storageLocked = false;
    private Vault vault;
    @Override public void onCreate() {
        super.onCreate(); vault = new Vault(this);
        try { String raw = vault.read(); data = raw == null ? fresh() : new JSONObject(raw); }
        catch (Exception e) { data = fresh(); storageLocked = true; notice = "Não foi possível abrir os dados locais. Os dados existentes foram preservados."; }
        JSONArray chats = data.optJSONArray("chats");
        if (chats == null) { chats = new JSONArray(); put(data, "chats", chats); }
        for (int i = 0; i < chats.length(); i++) {
            JSONObject c = chats.optJSONObject(i); if (c == null) continue;
            JSONArray messages = c.optJSONArray("messages"); if (messages == null) continue;
            for (int j = 0; j < messages.length(); j++) {
                JSONObject m = messages.optJSONObject(j);
                if (m != null && "pending".equals(m.optString("status"))) {
                    put(m, "status", "error"); put(m, "error", "A app foi interrompida. Se havia uma ação aprovada, confirma o resultado no serviço antes de repetir.");
                }
            }
            // Aprovações nunca são executadas automaticamente após reinício.
            c.remove("pending");
        }
        if (chats.length() == 0) newChat();
    }
    static JSONObject fresh() {
        JSONObject d = new JSONObject(); put(d, "chats", new JSONArray()); put(d, "tools", false); return d;
    }
    static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) {} }
    String setting(String key) { return data.optString(key, ""); }
    boolean configured() { return !setting("url").isEmpty() && setting("token").length() >= 32 && !storageLocked; }
    JSONArray chats() { return data.optJSONArray("chats"); }
    JSONObject current() {
        JSONArray all = chats(); String id = setting("current");
        for (int i = 0; i < all.length(); i++) { JSONObject c = all.optJSONObject(i); if (c.optString("id").equals(id)) return c; }
        return all.optJSONObject(0);
    }
    void persist() {
        if (storageLocked) return;
        try { vault.write(data.toString()); }
        catch (Exception e) { notice = "Não foi possível guardar os dados no telemóvel. Liberta espaço e tenta novamente."; }
    }
    void notifyChanged() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    void newChat() {
        if (busy) return;
        if (chats().length() >= 30) { notice = "Limite de 30 conversas. Exporta e apaga uma conversa para criar outra."; notifyChanged(); return; }
        JSONObject c = new JSONObject(); put(c, "id", UUID.randomUUID().toString()); put(c, "title", "Nova conversa");
        put(c, "messages", new JSONArray()); chats().put(c); put(data, "current", c.optString("id")); persist(); notifyChanged();
    }
    void select(String id) { if (!busy) { put(data, "current", id); persist(); notifyChanged(); } }
    void deleteCurrent() {
        if (busy) return;
        String id = current().optString("id");
        JSONArray all = chats(); for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i).optString("id").equals(id)) { all.remove(i); break; }
        if (all.length() == 0) newChat(); else put(data, "current", all.optJSONObject(all.length() - 1).optString("id"));
        persist(); notifyChanged();
    }
    String saveSettings(String url, String token, String context, boolean tools) {
        if (busy) return "Aguarda pela resposta antes de alterar a ligação.";
        if (storageLocked) return notice;
        try {
            url = ApiClient.normalizeUrl(url); token = token.trim();
            if (token.length() < 32 || !token.matches("[\\x21-\\x7E]+")) return "O código de acesso deve ter pelo menos 32 caracteres, sem espaços.";
            put(data, "url", url); put(data, "token", token); put(data, "context", context); put(data, "tools", tools);
            serverStatus = null; notice = "Definições guardadas."; persist(); notifyChanged(); return null;
        } catch (Exception e) { return e.getMessage(); }
    }
    void checkConnection() {
        if (busy || !configured()) return;
        busy = true; notice = "A verificar o servidor…"; notifyChanged();
        String url = setting("url"), token = setting("token");
        worker.execute(() -> {
            try {
                JSONObject status = ApiClient.request(url, token, "/v1/status", null);
                main.post(() -> { serverStatus = status; busy = false; notice = "Servidor ligado. Envia uma mensagem para testar o modelo."; notifyChanged(); });
            } catch (Exception e) { main.post(() -> { serverStatus = null; busy = false; notice = e.getMessage(); notifyChanged(); }); }
        });
    }
    void send(String text) {
        if (busy || !configured() || text.trim().isEmpty()) return;
        JSONObject c = current();
        if (c.has("pending")) { notice = "Decide primeiro as ações pendentes."; notifyChanged(); return; }
        JSONArray ms = c.optJSONArray("messages");
        if (ms.length() >= 200) { notice = "Esta conversa atingiu o limite. Cria uma nova conversa."; notifyChanged(); return; }
        JSONObject user = new JSONObject(); put(user, "role", "user"); put(user, "content", text.trim()); put(user, "status", "pending");
        ms.put(user); if (ms.length() == 1) put(c, "title", text.substring(0, Math.min(42, text.length())).replace('\n', ' '));
        request(c, user, "/v1/chat", payload(c), false);
    }
    void retry() {
        if (busy || !configured()) return;
        JSONObject c = current(); JSONArray ms = c.optJSONArray("messages"); JSONObject last = ms.optJSONObject(ms.length() - 1);
        if (last == null || !"error".equals(last.optString("status")) || last.optBoolean("actionRisk")) return;
        put(last, "status", "pending"); last.remove("error"); request(c, last, "/v1/chat", payload(c), false);
    }
    JSONObject payload(JSONObject c) {
        JSONArray ms = c.optJSONArray("messages"); List<JSONObject> list = new ArrayList<>(); int length = 0;
        for (int i = ms.length() - 1; i >= 0; i--) {
            JSONObject m = ms.optJSONObject(i); if ("error".equals(m.optString("status"))) continue;
            if (list.size() >= 40 || length + m.optString("content").length() > 60000) break;
            JSONObject item = new JSONObject(); put(item, "role", m.optString("role")); put(item, "content", m.optString("content"));
            list.add(0, item); length += m.optString("content").length();
        }
        JSONArray input = new JSONArray(); for (JSONObject item : list) input.put(item);
        JSONObject body = new JSONObject(); put(body, "messages", input); put(body, "context", setting("context")); put(body, "useTools", data.optBoolean("tools")); return body;
    }
    void decide(JSONArray decisions) {
        JSONObject c = current(), pending = c.optJSONObject("pending"); if (busy || pending == null) return;
        JSONObject body = new JSONObject(); put(body, "flowId", pending.optString("flowId")); put(body, "decisions", decisions);
        JSONArray ms = c.optJSONArray("messages"); JSONObject user = ms.optJSONObject(ms.length() - 1);
        for (int i = ms.length() - 1; i >= 0; i--) if ("user".equals(ms.optJSONObject(i).optString("role"))) { user = ms.optJSONObject(i); break; }
        c.remove("pending"); put(user, "status", "pending");
        request(c, user, "/v1/approve", body, true);
    }
    private void request(JSONObject c, JSONObject user, String path, JSONObject body, boolean actionRisk) {
        busy = true; notice = ""; put(user, "actionRisk", actionRisk); persist(); notifyChanged();
        String url = setting("url"), token = setting("token");
        worker.execute(() -> {
            try {
                JSONObject result = ApiClient.request(url, token, path, body);
                main.post(() -> {
                    put(user, "status", "sent");
                    String answer = result.optString("text");
                    if (!answer.isEmpty()) {
                        JSONObject m = new JSONObject(); put(m, "role", "assistant"); put(m, "content", answer);
                        put(m, "sources", result.optJSONArray("sources")); put(m, "truncated", result.optBoolean("truncated"));
                        c.optJSONArray("messages").put(m);
                    }
                    if (!result.optString("flowId").isEmpty()) put(c, "pending", result);
                    busy = false; notice = result.optString("model", "Reborn"); persist(); notifyChanged();
                });
            } catch (Exception e) {
                main.post(() -> {
                    busy = false; put(user, "status", "error");
                    String error = actionRisk ? "Não foi possível confirmar a ação. Verifica o resultado no serviço antes de repetir. " + e.getMessage() : e.getMessage();
                    put(user, "error", error); notice = error; persist(); notifyChanged();
                });
            }
        });
    }
    String exportCurrent() {
        JSONObject c = current(); StringBuilder out = new StringBuilder("Reborn — ").append(c.optString("title")).append("\n\n");
        JSONArray ms = c.optJSONArray("messages");
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i); out.append("user".equals(m.optString("role")) ? "Tu" : "Reborn").append(":\n").append(m.optString("content")).append("\n\n");
        }
        return out.toString();
    }
}
