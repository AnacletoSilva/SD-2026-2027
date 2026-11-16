package tpf;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;

public final class LeaderBot extends ListenerAdapter {
    private final String guildId;
    private final CommandHandler handler = new CommandHandler();

    public LeaderBot(String guildId) { this.guildId = guildId; }

    public static void main(String[] args) throws InterruptedException {
        String token = environment("DISCORD_TOKEN");
        String guildId = environment("DISCORD_GUILD_ID");
        if (!guildId.matches("[0-9]{17,20}")) throw new IllegalArgumentException("DISCORD_GUILD_ID inválido.");
        var jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new LeaderBot(guildId)).build();
        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdown));
        jda.awaitReady();
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            jda.shutdown();
            throw new IllegalStateException("Servidor não encontrado: confirma o convite e DISCORD_GUILD_ID.");
        }
        guild.updateCommands().addCommands(commandDefinitions()).queue(
                commands -> System.out.println("Líder online; " + commands.size() + " comandos registados no servidor de teste."),
                error -> System.err.println("Falha ao registar comandos: " + error.getMessage()));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Define a variável de ambiente " + name + ".");
        return value.trim();
    }

    static List<SlashCommandData> commandDefinitions() {
        return List.of(
            Commands.slash("registar", "Receber texto para registo (provisório)")
                .addOption(OptionType.STRING, "texto", "Texto a registar", true),
            Commands.slash("estado", "Consultar estado (provisório)"),
            Commands.slash("config", "Configuração administrativa (provisória)")
                .addSubcommands(new SubcommandData("r", "Configurar fator de replicação")
                    .addOptions(integer("n", "Fator de replicação"))),
            Commands.slash("recuperar", "Recuperar intervalo (provisório)")
                .addOptions(integer("de", "Primeiro registo"), integer("ate", "Último registo")),
            Commands.slash("replica-token", "Pedir token de réplica (provisório)"),
            Commands.slash("replica-remover", "Remover réplica (provisório)")
                .addOption(OptionType.STRING, "id", "Identificador da réplica", true),
            Commands.slash("rajada", "Receber parâmetros de rajada (provisório)")
                .addOptions(integer("n", "Número de mensagens"), integer("intervaloms", "Intervalo em milissegundos"))
        );
    }

    private static OptionData integer(String name, String description) {
        return new OptionData(OptionType.INTEGER, name, description, true).setMinValue(1);
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        long received = System.nanoTime();
        // Confirmar antes da validação. Todas as respostas ficam visíveis só ao autor.
        event.deferReply(true).queue(hook -> {
            long elapsedMs = (System.nanoTime() - received) / 1_000_000;
            System.out.println("/" + event.getName() + " confirmado em " + elapsedMs + " ms.");
            String text;
            try {
                if (!event.isFromGuild() || !guildId.equals(event.getGuild().getId())) {
                    text = "Comando disponível apenas no servidor de teste configurado.";
                } else {
                    boolean admin = event.getMember() != null && event.getMember().getRoles().stream()
                            .anyMatch(role -> role.getName().equals("admin-arquivo"));
                    var args = new HashMap<String, String>();
                    event.getOptions().forEach(option -> args.put(option.getName(), option.getAsString()));
                    text = handler.handle(event.getName(), event.getSubcommandName(), args, admin).text();
                }
            } catch (RuntimeException error) {
                reportFailure(event, "processamento", received, error);
                text = "Erro interno ao processar o comando. Consulta a consola do líder.";
            }
            hook.editOriginal(text).setAllowedMentions(Collections.emptyList()).queue(
                    message -> {}, error -> reportFailure(event, "resposta", received, error));
        }, error -> reportFailure(event, "confirmação", received, error));
    }

    private static void reportFailure(SlashCommandInteractionEvent event, String phase,
                                      long received, Throwable error) {
        long elapsedMs = (System.nanoTime() - received) / 1_000_000;
        String code = error instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException response
                ? Integer.toString(response.getErrorCode()) : error.getClass().getSimpleName();
        // Não registar argumentos, tokens ou URLs de interação.
        System.err.println("Falha na " + phase + " de /" + event.getName()
                + " após " + elapsedMs + " ms; erro=" + code + ".");
    }

    @Override public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || !guildId.equals(event.getGuild().getId())
                || event.getAuthor().isBot() || event.isWebhookMessage()
                || !event.getChannel().getName().equals("arquivo")) return;
        event.getChannel().sendMessage("Mensagem recebida em #arquivo — registo ainda não implementado.")
                .setAllowedMentions(Collections.emptyList()).queue();
    }
}
