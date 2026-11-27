package tpf;

import java.util.Map;
import java.util.Set;

/** Validação sem efeitos sobre o arquivo: este sprint apenas confirma a receção. */
public final class CommandHandler {
    private final ArquivoLider arquivo;

    public CommandHandler() { this.arquivo = null; }
    public CommandHandler(ArquivoLider arquivo) { this.arquivo = arquivo; }

    private static final Set<String> ADMIN = Set.of("config", "replica-token", "replica-remover", "rajada");
    public record Reply(String text, boolean error) {}

    public Reply handle(String command, String subcommand, Map<String, String> args, boolean admin) {
        if (ADMIN.contains(command) && !admin)
            return new Reply("Acesso recusado: é necessário o papel admin-arquivo.", true);
        try {
            String details = switch (command) {
                case "registar" -> {
                    try {
                        long lsn = arquivo.registar("slash", "comando", required(args, "texto"));
                        yield "LSN=" + lsn;
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Falha ao persistir o registo.", e);
                    }
                }
                case "estado", "replica-token" -> "sem argumentos";
                case "config" -> {
                    if (!"r".equals(subcommand)) throw new IllegalArgumentException("Subcomando esperado: /config r.");
                    yield "r=" + positive(args, "n");
                }
                case "recuperar" -> {
                    long de = positive(args, "de"), ate = positive(args, "ate");
                    if (de > ate) throw new IllegalArgumentException("de deve ser menor ou igual a ate.");
                    yield "de=" + de + ", ate=" + ate;
                }
                case "replica-remover" -> "id=" + required(args, "id");
                case "rajada" -> {
                    int n = (int) positive(args, "n");
                    int intervalo = (int) positive(args, "intervaloms");
                    try {
                        yield "n=" + arquivo.rajada(n, intervalo, "sintetico");
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Falha na rajada.", e);
                    }
                }
                default -> throw new IllegalArgumentException("Comando desconhecido.");
            };
            String text = "/" + command + " recebido: " + details;
            if (text.length() > 1800) text = text.substring(0, 1800) + "… [texto abreviado]";
            return new Reply(text, false);
        } catch (IllegalArgumentException e) {
            return new Reply("Erro: " + e.getMessage(), true);
        }
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " é obrigatório e não pode estar vazio.");
        return value;
    }

    private static long positive(Map<String, String> args, String key) {
        try {
            long value = Long.parseLong(required(args, key));
            if (value < 1 || value > 9007199254740991L) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " deve ser um inteiro positivo válido.");
        }
    }
}
