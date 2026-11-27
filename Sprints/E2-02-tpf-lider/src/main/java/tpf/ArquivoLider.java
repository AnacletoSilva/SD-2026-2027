package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ArquivoLider implements AutoCloseable {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;

    private final Path base;
    private final Path log;
    private final Path events;
    private final Path sealed;
    private final byte[] masterKey;
    private final ScheduledExecutorService scheduler;

    private long nextLsn;
    private final List<Registo> open = new ArrayList<>();
    private Instant firstRecord;
    private ScheduledFuture<?> timer;

    public ArquivoLider(Path base) throws IOException {
        this.base = base.toAbsolutePath();
        this.log = base.resolve("registos.log");
        this.events = base.resolve("eventos-lider.txt");
        this.sealed = base.resolve("selados");
        Files.createDirectories(base);
        Files.createDirectories(sealed);
        this.masterKey = Wire.loadOrCreateMasterKey(base.resolve("master.key"));
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "chunk-selagem");
            t.setDaemon(true);
            return t;
        });
        this.nextLsn = readLastLsn() + 1;
    }

    public synchronized long registar(String autor, String canal, String texto) throws IOException {
        long lsn = nextLsn++;
        String instante = UTC.format(Instant.now().atOffset(ZoneOffset.UTC));
        String line = lsn + ";" + instante + ";" + base64(autor) + ";" + base64(canal) + ";"
                + Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));

        try (FileChannel channel = FileChannel.open(log,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line + System.lineSeparator());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }

        Registo registo = new Registo(lsn, instante, autor, canal, texto);
        event("REGISTO", "lsn=" + lsn + ";autor=" + autor + ";canal=" + canal);
        open.add(registo);
        if (firstRecord == null) {
            firstRecord = Instant.now();
            scheduleTimer();
        }

        if (openSerializedEstimate() >= Wire.CHUNK_MAX_BYTES) sealOpenChunk();
        return lsn;
    }

    public synchronized int rajada(int n, int intervaloMs, String autor) throws IOException {
        int count = 0;
        for (int i = 1; i <= n; i++) {
            registar(autor, "arquivo", "rajada-" + i);
            count++;
            if (intervaloMs > 0) {
                try { Thread.sleep(intervaloMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return count;
    }

    private long readLastLsn() throws IOException {
        if (!Files.exists(log)) return 0;
        long last = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int first = line.indexOf(';');
            if (first <= 0) throw new IOException("Linha inválida em registos.log.");
            try { last = Long.parseLong(line.substring(0, first)); }
            catch (NumberFormatException e) { throw new IOException("LSN inválido em registos.log.", e); }
        }
        return last;
    }

    private int openSerializedEstimate() {
        int bytes = 0;
        for (Registo r : open) {
            bytes += 8 + 4 + r.instante().getBytes(StandardCharsets.UTF_8).length;
            bytes += 4 + r.autor().getBytes(StandardCharsets.UTF_8).length;
            bytes += 4 + r.canal().getBytes(StandardCharsets.UTF_8).length;
            bytes += 4 + r.texto().getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes;
    }

    private void scheduleTimer() {
        if (timer != null) timer.cancel(false);
        timer = scheduler.schedule(this::sealOpenChunkFromTimer, Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
    }

    private void sealOpenChunkFromTimer() {
        synchronized (this) {
            if (!open.isEmpty()) sealOpenChunk();
        }
    }

    private synchronized void sealOpenChunk() {
        if (open.isEmpty()) return;
        if (timer != null) timer.cancel(false);

        List<Registo> records = new ArrayList<>(open);
        open.clear();
        firstRecord = null;

        try {
            byte[] plaintext = Chunk.serialize(records, Wire.PAD_BYTES);
            Sealed sealedChunk = ChunkCrypto.seal(plaintext, masterKey);
            Path bin = sealed.resolve(sealedChunk.chunkId() + ".bin");
            Files.write(bin, sealedChunk.ciphertext());

            String hdr = sealedChunk.keyVersion() + ";"
                    + Base64.getEncoder().encodeToString(sealedChunk.salt()) + ";"
                    + Base64.getEncoder().encodeToString(sealedChunk.nonce());
            Files.writeString(sealed.resolve(sealedChunk.chunkId() + ".hdr"), hdr,
                    StandardCharsets.US_ASCII);

            event("SELADO", "chunkId=" + sealedChunk.chunkId()
                    + ";lsn=" + records.get(0).lsn() + "-" + records.get(records.size() - 1).lsn()
                    + ";tamanho=" + plaintext.length);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao selar chunk.", e);
        }
    }

    private void event(String type, String detail) throws IOException {
        String line = UTC.format(Instant.now().atOffset(ZoneOffset.UTC)) + ";" + type + ";" + detail
                + System.lineSeparator();
        try (FileChannel channel = FileChannel.open(events,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void sealAllForShutdown() {
        if (!open.isEmpty()) sealOpenChunk();
    }

    @Override public void close() {
        sealAllForShutdown();
        scheduler.shutdownNow();
    }
}
