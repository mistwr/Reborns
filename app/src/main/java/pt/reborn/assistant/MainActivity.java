package pt.reborn.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF101014, CARD = 0xFF1D1C24, INK = 0xFFF4F1FF,
            MUTED = 0xFFADA9BC, PURPLE = 0xFFB9AAFF, LINE = 0xFF37333F;
    private RebornApp app;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button send, mic;
    private TextView status, notice;
    private Switch tools;
    private TextToSpeech speech;
    private boolean speechReady;
    private String exporting;
    private final Runnable changed = this::render;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app = (RebornApp) getApplication();
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(16), 0, dp(16), 0);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets x = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(dp(16) + x.left, x.top, dp(16) + x.right, x.bottom); return insets;
            });
        }
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(760));
        LinearLayout top = row(); top.setPadding(0, dp(8), 0, dp(8));
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_reborn);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout wordmark = column(); wordmark.setPadding(dp(10), 0, 0, 0);
        TextView title = label("Reborn", 22, INK); title.setTypeface(null, Typeface.BOLD); wordmark.addView(title);
        status = label("O teu espaço para pensar e criar", 11, MUTED); wordmark.addView(status);
        top.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("+", false); add.setTextSize(25); add.setContentDescription("Nova conversa");
        add.setOnClickListener(v -> { if (app.busy) toast("Aguarda pela resposta."); else { app.newChat(); input.setText(""); } });
        top.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button menu = button("⋮", false); menu.setTextSize(25); menu.setContentDescription("Menu"); menu.setOnClickListener(this::menu);
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top, new LinearLayout.LayoutParams(width, -2));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        messages = column(); messages.setPadding(0, dp(14), 0, dp(20)); scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(width, 0, 1));
        notice = label("", 12, MUTED); notice.setPadding(0, dp(4), 0, dp(6));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(notice, new LinearLayout.LayoutParams(width, -2));
        LinearLayout composer = column(); composer.setPadding(dp(12), dp(8), dp(12), dp(8)); composer.setBackground(shape(CARD, 22));
        input = new EditText(this); input.setId(1001); input.setTextColor(INK); input.setTextSize(16); input.setHintTextColor(MUTED);
        input.setHint("O que vamos fazer hoje?"); input.setContentDescription("Mensagem para o Reborn");
        input.setBackgroundColor(Color.TRANSPARENT); input.setMinLines(1); input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        input.setPadding(dp(4), dp(6), dp(4), dp(6)); composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = row();
        tools = new Switch(this); tools.setText("Ferramentas  "); tools.setTextSize(12); tools.setTextColor(MUTED);
        tools.setChecked(app.data.optBoolean("tools")); tools.setContentDescription("Ativar pesquisa web e conectores configurados");
        tools.setOnCheckedChangeListener((b, value) -> { RebornApp.put(app.data, "tools", value); app.persist(); });
        controls.addView(tools, new LinearLayout.LayoutParams(0, dp(48), 1));
        mic = button("Ditar", false); mic.setContentDescription("Ditar uma mensagem"); mic.setOnClickListener(v -> dictate());
        controls.addView(mic, new LinearLayout.LayoutParams(dp(64), dp(48)));
        send = button("Enviar", true); send.setId(1002); send.setOnClickListener(v -> send());
        controls.addView(send, new LinearLayout.LayoutParams(dp(84), dp(48)));
        composer.addView(controls); root.addView(composer, new LinearLayout.LayoutParams(width, -2));
        TextView footer = label("Reborn · código aberto · as respostas podem conter erros", 10, MUTED);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(8), 0, dp(8));
        root.addView(footer, new LinearLayout.LayoutParams(width, -2));
        if (saved != null) { input.setText(saved.getString("draft", "")); exporting = saved.getString("exporting"); }
        speech = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int language = speech.setLanguage(Locale.forLanguageTag("pt-PT"));
                speechReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
    }
    @Override protected void onStart() { super.onStart(); app.listeners.add(changed); render(); }
    @Override protected void onStop() { app.listeners.remove(changed); if (speech != null) speech.stop(); super.onStop(); }
    @Override protected void onDestroy() { if (speech != null) speech.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", input.getText().toString()); out.putString("exporting", exporting); super.onSaveInstanceState(out);
    }
    private void send() {
        if (!app.configured()) { settings(); return; }
        String draft = input.getText().toString(); if (draft.trim().isEmpty() || app.busy) return;
        if (app.current().has("pending")) { toast("Decide primeiro as ações pendentes."); return; }
        if (app.current().optJSONArray("messages").length() >= 200) { toast("Cria uma nova conversa para continuar."); return; }
        app.send(draft); input.setText("");
    }
    private void render() {
        if (messages == null) return;
        status.setText(app.busy ? "A trabalhar no teu pedido…" : app.configured() ? "O teu assistente pessoal" : "Liga o teu modelo para começar");
        notice.setText(app.notice); notice.setVisibility(app.notice.isEmpty() ? View.GONE : View.VISIBLE);
        send.setEnabled(!app.busy && !app.storageLocked); mic.setEnabled(!app.busy); tools.setEnabled(!app.busy);
        tools.setChecked(app.data.optBoolean("tools")); messages.removeAllViews();
        JSONObject conversation = app.current(); if (conversation == null) return;
        JSONArray ms = conversation.optJSONArray("messages");
        if (ms.length() == 0) welcome();
        for (int i = 0; i < ms.length(); i++) message(ms.optJSONObject(i));
        JSONObject pending = conversation.optJSONObject("pending");
        if (pending != null) {
            LinearLayout box = card(); box.addView(label("O Reborn precisa da tua decisão", 17, PURPLE));
            TextView description = label("Há ações propostas por ferramentas. Revê o serviço e os dados antes de continuar.", 14, MUTED);
            description.setPadding(0, dp(8), 0, dp(8)); box.addView(description);
            Button review = button("Rever ações", true); review.setEnabled(!app.busy); review.setOnClickListener(v -> reviewActions(pending)); box.addView(review);
            messages.addView(box, spaced());
        }
        if (app.busy) { TextView loading = label("Reborn está a trabalhar…", 14, PURPLE); loading.setPadding(dp(12), dp(18), 0, dp(18)); messages.addView(loading); }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void welcome() {
        TextView small = label("UM NOVO COMEÇO", 11, PURPLE); small.setLetterSpacing(.15f); small.setPadding(dp(8), dp(28), 0, dp(18)); messages.addView(small);
        TextView headline = label("As tuas ideias.\nUm mundo de possibilidades.", 32, INK); headline.setTypeface(null, Typeface.BOLD); headline.setPadding(dp(8), 0, dp(8), dp(16)); messages.addView(headline);
        TextView intro = label("Escreve, cria e resolve. Um assistente com o teu contexto, no teu telemóvel.", 16, MUTED);
        intro.setLineSpacing(dp(4), 1); intro.setPadding(dp(8), 0, dp(8), dp(24)); messages.addView(intro);
        if (!app.configured()) {
            LinearLayout setup = card(); setup.addView(label("Liga o teu assistente", 18, INK));
            TextView info = label("Configura o endereço do servidor e o teu código de acesso para conversar.", 14, MUTED); info.setPadding(0, dp(8), 0, dp(12)); setup.addView(info);
            Button start = button("Configurar Reborn", true); start.setOnClickListener(v -> settings()); setup.addView(start); messages.addView(setup, spaced());
        }
        suggestion("Criar", "Ajuda-me a transformar esta ideia num plano concreto.");
        suggestion("Escrever", "Vamos escrever uma mensagem clara e profissional.");
        suggestion("Programar", "Ajuda-me a planear uma aplicação e a escrever o código.");
    }
    private void suggestion(String title, String prompt) {
        Button b = button(title + "   →", false); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), 0, dp(18), 0); b.setBackground(shape(CARD, 16));
        b.setOnClickListener(v -> { input.setText(prompt); input.setSelection(input.length()); input.requestFocus(); });
        messages.addView(b, spaced());
    }
    private void message(JSONObject m) {
        if (m == null) return; boolean user = "user".equals(m.optString("role"));
        LinearLayout box = card(); if (user) box.setBackground(shape(0xFF2C263D, 18));
        TextView who = label(user ? "TU" : "REBORN", 10, user ? MUTED : PURPLE); who.setLetterSpacing(.1f); box.addView(who);
        String content = m.optString("content"); TextView text = label(content, 16, INK);
        text.setPadding(0, dp(8), 0, dp(4)); text.setLineSpacing(dp(4), 1); text.setTextIsSelectable(true);
        Linkify.addLinks(text, Linkify.WEB_URLS); text.setLinkTextColor(PURPLE); text.setMovementMethod(LinkMovementMethod.getInstance()); box.addView(text);
        JSONArray sources = m.optJSONArray("sources");
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i); if (s == null) continue;
            TextView link = label(s.optString("title") + " ↗", 12, PURPLE); link.setPadding(0, dp(10), 0, dp(4));
            link.setOnClickListener(v -> open(s.optString("url"))); box.addView(link);
        }
        if (m.optBoolean("truncated")) box.addView(label("Resposta parcial. Podes pedir para continuar.", 12, MUTED));
        if (!user) {
            LinearLayout actions = row(); Button copy = button("Copiar", false); copy.setTextSize(12); copy.setOnClickListener(v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Reborn", content)); toast("Resposta copiada.");
            }); actions.addView(copy);
            Button listen = button("Ouvir", false); listen.setTextSize(12); listen.setOnClickListener(v -> speak(content)); actions.addView(listen); box.addView(actions);
        }
        if ("error".equals(m.optString("status"))) {
            box.addView(label(m.optString("error", "Não foi possível obter resposta."), 12, 0xFFFFB9B9));
            JSONArray all = app.current().optJSONArray("messages");
            if (all.optJSONObject(all.length() - 1) == m && !m.optBoolean("actionRisk")) {
                Button retry = button("Tentar novamente", false); retry.setEnabled(!app.busy); retry.setOnClickListener(v -> app.retry()); box.addView(retry);
            }
        }
        messages.addView(box, spaced());
    }
    private void menu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        String[] items = {"Definições", "Conversas", "Estado das ferramentas", "Exportar conversa", "Apagar conversa", "Parar leitura", "Código no GitHub", "Sobre"};
        for (int i = 0; i < items.length; i++) popup.getMenu().add(0, i, i, items[i]);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: settings(); break;
                case 1: history(); break;
                case 2: toolStatus(); break;
                case 3: export(); break;
                case 4: if (app.busy) toast("Aguarda pela resposta."); else new AlertDialog.Builder(this).setTitle("Apagar esta conversa?")
                    .setMessage("A conversa será removida deste telemóvel.").setNegativeButton("Manter", null).setPositiveButton("Apagar", (d,w) -> app.deleteCurrent()).show(); break;
                case 5: if (speech != null) speech.stop(); break;
                case 6: open("https://github.com/mistwr/Reborns"); break;
                case 7: new AlertDialog.Builder(this).setTitle("Reborn 0.1.0").setMessage("Assistente pessoal de código aberto (MIT).\n\nO modelo corre no fornecedor configurado ou num computador com Ollama. Esta app não inclui os pesos GPT nem transfere a sessão do ChatGPT.\n\nO ditado e a leitura usam os serviços de voz do Android. O histórico e as definições ficam cifrados neste telemóvel.")
                    .setPositiveButton("Fechar", null).show(); break;
            } return true;
        }); popup.show();
    }
    private void settings() {
        LinearLayout form = column(); form.setPadding(dp(22), dp(12), dp(22), dp(12));
        form.addView(label("Liga o servidor que guarda a tua chave de IA.", 14, MUTED));
        EditText url = field(form, "Endereço do servidor", "https://o-teu-servidor", app.setting("url"), false, 300);
        EditText token = field(form, "Código de acesso Reborn", "Código criado no servidor", app.setting("token"), true, 256);
        EditText context = field(form, "O que queres que o Reborn saiba sobre ti?", "Preferências, projetos e objetivos…", app.setting("context"), false, 4000);
        context.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); context.setMinLines(3); context.setMaxLines(6);
        form.addView(label("Este contexto acompanha as mensagens enviadas ao modelo. Os conectores recebem os dados das ações que autorizares.", 12, MUTED));
        Button guide = button("Abrir instruções de ligação", false); guide.setOnClickListener(v -> open("https://github.com/mistwr/Reborns#ligar-o-assistente")); form.addView(guide);
        ScrollView area = new ScrollView(this); area.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("O teu Reborn").setView(area)
                .setNegativeButton("Fechar", null).setPositiveButton("Guardar e verificar", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String error = app.saveSettings(url.getText().toString(), token.getText().toString(), context.getText().toString(), tools.isChecked());
            if (error != null) { toast(error); return; } dialog.dismiss(); app.checkConnection();
        })); dialog.show();
    }
    private void history() {
        if (app.busy) { toast("Aguarda pela resposta."); return; }
        JSONArray all = app.chats(); String[] titles = new String[all.length()];
        for (int i = 0; i < all.length(); i++) titles[i] = all.optJSONObject(all.length() - 1 - i).optString("title");
        new AlertDialog.Builder(this).setTitle("As tuas conversas").setItems(titles, (d, i) -> {
            app.select(all.optJSONObject(all.length() - 1 - i).optString("id")); input.setText("");
        }).setNegativeButton("Fechar", null).show();
    }
    private void toolStatus() {
        JSONObject s = app.serverStatus; StringBuilder body = new StringBuilder();
        if (s == null) body.append("Verifica a ligação nas Definições para consultar as ferramentas configuradas.");
        else {
            body.append("Modelo configurado: ").append(s.optString("model")).append("\n\nPesquisa web: ").append(s.optBoolean("webSearch") ? "configurada" : "desligada");
            JSONArray connectors = s.optJSONArray("connectors");
            if (connectors != null) for (int i = 0; i < connectors.length(); i++) body.append("\n\n").append(connectors.optJSONObject(i).optString("name")).append("\n").append(connectors.optJSONObject(i).optString("url"));
            body.append("\n\nAtiva Ferramentas para permitir a utilização. Cada chamada MCP apresenta os dados e pede a tua decisão. A disponibilidade real é verificada ao usar a ferramenta.");
        }
        new AlertDialog.Builder(this).setTitle("Ferramentas").setMessage(body).setPositiveButton("Fechar", null).show();
    }
    private void reviewActions(JSONObject pending) {
        JSONArray approvals = pending.optJSONArray("approvals"); if (approvals == null) return;
        reviewOne(approvals, 0, new JSONArray());
    }
    private void reviewOne(JSONArray approvals, int i, JSONArray decisions) {
        if (i >= approvals.length()) { app.decide(decisions); return; }
        JSONObject action = approvals.optJSONObject(i); if (action == null) return;
        LinearLayout details = column(); details.setPadding(dp(22), dp(12), dp(22), dp(12));
        TextView info = label("Serviço: " + action.optString("server") + "\nFerramenta: " + action.optString("name") + "\n\nDados enviados:\n" + action.optString("arguments"), 14, INK);
        info.setTextIsSelectable(true); details.addView(info); ScrollView area = new ScrollView(this); area.addView(details);
        new AlertDialog.Builder(this).setTitle("Ação " + (i + 1) + " de " + approvals.length()).setView(area)
                .setNeutralButton("Decidir depois", null)
                .setNegativeButton("Recusar", (d, w) -> { decisions.put(decision(action, false)); reviewOne(approvals, i + 1, decisions); })
                .setPositiveButton("Autorizar", (d, w) -> { decisions.put(decision(action, true)); reviewOne(approvals, i + 1, decisions); }).show();
    }
    private JSONObject decision(JSONObject action, boolean approve) { JSONObject d = new JSONObject(); RebornApp.put(d, "id", action.optString("id")); RebornApp.put(d, "approve", approve); return d; }
    private void dictate() {
        if (speech != null) speech.stop();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT"); intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dita a tua mensagem");
        try { startActivityForResult(intent, 201); }
        catch (ActivityNotFoundException e) { toast("Ativa um serviço de reconhecimento de voz nas definições do Android."); }
    }
    private void speak(String text) {
        if (!speechReady) { toast("Instala uma voz em português nas definições de texto para voz do Android."); return; }
        speech.stop(); int chunk = Math.min(3500, TextToSpeech.getMaxSpeechInputLength());
        for (int start = 0; start < text.length(); start += chunk) speech.speak(text.substring(start, Math.min(start + chunk, text.length())), TextToSpeech.QUEUE_ADD, null, "reborn-" + start);
    }
    private void export() {
        exporting = app.exportCurrent(); Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_TITLE, "reborn-conversa.txt");
        try { startActivityForResult(intent, 202); } catch (ActivityNotFoundException e) { toast("Não foi encontrado um gestor de ficheiros."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        if (request == 201) {
            ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (list != null && !list.isEmpty()) { input.append((input.length() > 0 ? " " : "") + list.get(0)); input.setSelection(input.length()); }
        } else if (request == 202 && data.getData() != null && exporting != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception(); out.write(exporting.getBytes(StandardCharsets.UTF_8)); toast("Conversa exportada.");
            } catch (Exception e) { toast("Não foi possível guardar a conversa."); }
            exporting = null;
        }
    }
    private void open(String url) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { toast("Não foi encontrado um navegador."); }
    }
    private EditText field(LinearLayout form, String title, String hint, String value, boolean secret, int max) {
        TextView label = label(title, 13, PURPLE); label.setPadding(0, dp(18), 0, dp(4)); form.addView(label);
        EditText edit = new EditText(this); edit.setTextColor(INK); edit.setHintTextColor(MUTED); edit.setTextSize(15);
        edit.setHint(hint); edit.setContentDescription(title); edit.setText(value); edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)}); form.addView(edit); return edit;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(16), dp(16), dp(16), dp(12)); v.setBackground(shape(CARD, 18)); return v; }
    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); return p; }
    private TextView label(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private Button button(String text, boolean primary) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(14); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(48));
        b.setTextColor(primary ? BG : PURPLE); b.setPadding(dp(10), 0, dp(10), 0); b.setBackground(shape(primary ? PURPLE : Color.TRANSPARENT, 14)); return b;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (color == CARD) d.setStroke(dp(1), LINE); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
