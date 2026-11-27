package tpf;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @TempDir Path temp;

    @Test void createsAndReloadsMasterKey() throws Exception {
        Path key = temp.resolve("master.key");
        byte[] created = Wire.loadOrCreateMasterKey(key);
        byte[] reloaded = Wire.loadOrCreateMasterKey(key);

        assertEquals(32, created.length);
        assertArrayEquals(created, reloaded);
    }

    @Test void rejectsWrongSizedMasterKey() throws Exception {
        Path key = temp.resolve("master.key");
        Files.write(key, new byte[31]);
        assertThrows(IllegalArgumentException.class, () -> Wire.loadOrCreateMasterKey(key));
    }
}
