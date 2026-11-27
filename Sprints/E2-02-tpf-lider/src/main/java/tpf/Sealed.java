package tpf;

import java.nio.ByteBuffer;

public record Sealed(byte[] ciphertext, String chunkId, int keyVersion, byte[] salt, byte[] nonce) {
    public static Aad aad(int keyVersion, byte[] salt, byte[] nonce) {
        byte[] prefix = "tpf-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(prefix.length + 4 + salt.length + nonce.length);
        buffer.put(prefix).putInt(keyVersion).put(salt).put(nonce);
        return new Aad(buffer.array());
    }

    public record Aad(byte[] bytes) {}
}
