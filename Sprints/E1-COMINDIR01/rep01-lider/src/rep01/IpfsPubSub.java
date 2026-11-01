package rep01;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class IpfsPubSub {
    private final String api; // ex.: http://127.0.0.1:5001/api/v0
    private final HttpClient http = HttpClient.newHttpClient();

    public IpfsPubSub(String api) {
        this.api = api;
    }

    // o Kubo exige o tópico e os dados em multibase: base64url sem padding, com prefixo 'u'
    static String toMultibase(String s) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String fromMultibase(String s) {
        if (!s.startsWith("u")) {
            throw new IllegalArgumentException("Codificação multibase não suportada: " + s);
        }
        return new String(Base64.getUrlDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
    }

    // publica uma mensagem no tópico (a mensagem segue no corpo, como ficheiro multipart)
    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "rep01-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("pub falhou (" + resp.statusCode() + "): " + resp.body());
        }
    }

    // subscreve o tópico; NÃO TERMINA: cada linha da resposta é uma mensagem JSON recebida
    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            throw new IOException("sub falhou (" + resp.statusCode() + ")");
        }
        resp.body() // bloqueia à espera de cada linha
                .filter(json -> json.contains("\"data\""))
                .forEach(json -> onMessage.accept(fromMultibase(extractField(json, "data"))));
    }

    // extração mínima de um campo de texto de um objeto JSON simples (evita bibliotecas externas)
    static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Campo '" + field + "' ausente: " + json);
        }
        start += key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}