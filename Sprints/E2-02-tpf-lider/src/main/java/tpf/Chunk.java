package tpf;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Chunk {
    private static final byte[] MAGIC = "TPFC".getBytes(StandardCharsets.US_ASCII);

    public static byte[] serialize(List<Registo> registos, int padBytes) {
        if (padBytes <= 0) throw new IllegalArgumentException("PAD_BYTES deve ser positivo.");
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(raw)) {
                out.write(MAGIC);
                out.writeInt(1);
                out.writeInt(registos.size());
                for (Registo r : registos) {
                    out.writeLong(r.lsn());
                    writeUtf(out, r.instante());
                    writeUtf(out, r.autor());
                    writeUtf(out, r.canal());
                    writeUtf(out, r.texto());
                }
            }

            byte[] rawPayload = raw.toByteArray();
            int payloadSize = rawPayload.length;
            int paddedSize = paddedSize(12 + payloadSize, padBytes);

            ByteBuffer output = ByteBuffer.allocate(paddedSize);
            output.order(ByteOrder.BIG_ENDIAN);
            output.put(MAGIC);
            output.putInt(1);
            output.putInt(payloadSize);

            output.put(rawPayload);
            while (output.hasRemaining()) output.put((byte) 0);
            return output.array();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao serializar chunk.", e);
        }
    }

    public static List<Registo> deserialize(byte[] padded) throws IOException {
        if (padded.length < 12) throw new IllegalArgumentException("Chunk truncado.");
        ByteBuffer frame = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4]; frame.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("Magic inválido.");
        int version = frame.getInt();
        int payloadSize = frame.getInt();
        if (version != 1 || payloadSize < 12 || payloadSize > padded.length - 12)
            throw new IllegalArgumentException("Cabeçalho ou payload inválido.");

        byte[] payload = new byte[payloadSize];
        frame.position(12);
        frame.get(payload);
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            byte[] innerMagic = new byte[4]; in.readFully(innerMagic);
            if (!java.util.Arrays.equals(innerMagic, MAGIC)) throw new IllegalArgumentException("Magic interno inválido.");
            int innerVersion = in.readInt();
            int count = in.readInt();
            if (innerVersion != 1 || count < 0) throw new IllegalArgumentException("Formato inválido.");
            List<Registo> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long lsn = in.readLong();
                result.add(new Registo(lsn, readUtf(in), readUtf(in), readUtf(in), readUtf(in)));
            }
            return result;
        }
    }

    public static int paddedSize(int payloadSize, int padBytes) {
        if (payloadSize < 0 || padBytes <= 0) throw new IllegalArgumentException("Tamanho inválido.");
        int remainder = payloadSize % padBytes;
        return remainder == 0 ? payloadSize : payloadSize + padBytes - remainder;
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0) throw new IOException("Tamanho UTF inválido.");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Campo UTF truncado.");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
