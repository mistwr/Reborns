package pt.reborn.assistant;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    static String normalizeUrl(String raw) throws Exception {
        URI uri = new URI(raw.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new Exception("Usa o endereço HTTPS do teu servidor, sem credenciais no URL.");
        return uri.toASCIIString().replaceAll("/+$", "");
    }
    static JSONObject request(String base, String token, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URI(normalizeUrl(base) + path).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(110000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) throw new Exception("O servidor redirecionou a ligação. Introduz o URL HTTPS final.");
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new Exception("O servidor não devolveu uma resposta.");
            String text;
            try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = input.read(chunk)) != -1) {
                    if (out.size() + n > 1000000) throw new Exception("Resposta demasiado grande.");
                    out.write(chunk, 0, n);
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject result;
            try { result = new JSONObject(text); }
            catch (Exception e) { throw new Exception("O endereço não devolve a API Reborn. Verifica o URL do servidor."); }
            if (code >= 400) throw new Exception(result.optString("message", "Erro de ligação (" + code + ")."));
            return result;
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("A ligação demorou demasiado. Verifica a rede e o servidor.");
        } catch (javax.net.ssl.SSLException e) {
            throw new Exception("O certificado HTTPS do servidor não é válido.");
        } catch (java.net.UnknownHostException e) {
            throw new Exception("Não foi possível encontrar o servidor. Verifica o endereço e a internet.");
        } finally { connection.disconnect(); }
    }
}
