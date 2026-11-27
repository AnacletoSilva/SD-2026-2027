package tpf;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CommandHandlerTest {
    private final CommandHandler handler = new CommandHandler();

    @ParameterizedTest @ValueSource(strings = {"config", "replica-token", "replica-remover", "rajada"})
    void administrationRequiresRole(String command) {
        var reply = handler.handle(command, "r", Map.of("n", "2", "id", "abc", "intervaloms", "10"), false);
        assertTrue(reply.error());
        assertTrue(reply.text().contains("admin-arquivo"));
    }

    @Test void allSevenCommandsEchoArguments() {
        assertEcho("registar", null, Map.of("texto", "Olá"), "texto=Olá");
        assertEcho("estado", null, Map.of(), "sem argumentos");
        assertEcho("config", "r", Map.of("n", "3"), "r=3");
        assertEcho("recuperar", null, Map.of("de", "10", "ate", "20"), "de=10, ate=20");
        assertEcho("replica-token", null, Map.of(), "sem argumentos");
        assertEcho("replica-remover", null, Map.of("id", "replica-a"), "id=replica-a");
        assertEcho("rajada", null, Map.of("n", "5", "intervaloms", "100"), "n=5, intervaloMs=100");
    }

    @ParameterizedTest @ValueSource(strings = {"abc", "0", "-1", "1.5", "9223372036854775808", "9007199254740992"})
    void invalidNumbersAreRejected(String value) {
        assertTrue(handler.handle("config", "r", Map.of("n", value), true).error());
        assertTrue(handler.handle("rajada", null, Map.of("n", "1", "intervaloms", value), true).error());
    }

    @Test void rangesAndMissingArgumentsAreValidated() {
        assertTrue(handler.handle("recuperar", null, Map.of("de", "20", "ate", "10"), false).error());
        assertFalse(handler.handle("recuperar", null, Map.of("de", "10", "ate", "10"), false).error());
        assertTrue(handler.handle("registar", null, Map.of("texto", "  "), false).error());
        assertTrue(handler.handle("config", "x", Map.of("n", "1"), true).error());
        assertTrue(handler.handle("replica-remover", null, Map.of(), true).error());
    }

    @Test void longRepliesFitDiscordLimit() {
        assertTrue(handler.handle("registar", null, Map.of("texto", "x".repeat(6000)), false).text().length() <= 2000);
    }

    private void assertEcho(String command, String sub, Map<String, String> args, String echo) {
        var reply = handler.handle(command, sub, args, true);
        assertFalse(reply.error());
        assertTrue(reply.text().contains(echo));
    }
}
