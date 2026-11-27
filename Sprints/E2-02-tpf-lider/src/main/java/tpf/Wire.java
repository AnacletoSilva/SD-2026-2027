package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

public final class Wire {
    public static final int CHUNK_MAX_BYTES = property("tpf.chunkMaxBytes", 65536);
    public static final int PAD_BYTES = property("tpf.padBytes", 4096);
    public static final int SELAGEM_MS = property("tpf.selagemMs", 5000);

    private Wire() {}

    private static int property(String name, int defaultValue) {
        String value = System.getProperty(name);
        int parsed = value == null ? defaultValue : Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(name + " deve ser positivo.");
        return parsed;
    }

    static byte[] loadOrCreateMasterKey(Path path) throws IOException {
        if (Files.exists(path)) {
            byte[] key = Files.readAllBytes(path);
            if (key.length != 32) throw new IllegalArgumentException("master.key deve ter exatamente 32 bytes.");
            return key;
        }

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(path.toAbsolutePath().getParent());

        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(key));
            channel.force(true);
        }

        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Sistemas sem POSIX não suportam estas permissões; o ficheiro continua criado localmente.
        }
        return key;
    }

    static byte[] loadOrCreateMasterKey() throws IOException {
        return loadOrCreateMasterKey(Path.of("master.key"));
    }
}
