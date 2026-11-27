package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ChunkCrypto {
    private static final int KEY_VERSION = 1;
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.US_ASCII);

    public static Sealed seal(byte[] plaintext, byte[] masterKey) throws GeneralSecurityException {
        byte[] salt = random(32);
        byte[] nonce = random(12);
        return seal(plaintext, masterKey, salt, nonce, KEY_VERSION);
    }

    public static Sealed seal(byte[] plaintext, byte[] masterKey, byte[] salt, byte[] nonce, int keyVersion)
            throws GeneralSecurityException {
        validateKey(masterKey); validateLength(salt, 32, "salt"); validateLength(nonce, 12, "nonce");
        if (keyVersion != KEY_VERSION) throw new IllegalArgumentException("Versão da chave não suportada.");

        byte[] dek = hkdf(masterKey, salt);
        Sealed.Aad aad = Sealed.aad(keyVersion, salt, nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.bytes());
        byte[] ciphertext = cipher.doFinal(plaintext);
        String id = sha256Hex(ciphertext);
        return new Sealed(ciphertext, id, keyVersion, salt.clone(), nonce.clone());
    }

    public static byte[] open(byte[] ciphertext, String id, int keyVersion, byte[] salt, byte[] nonce,
                              byte[] masterKey) throws GeneralSecurityException {
        String actual = sha256Hex(ciphertext);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), id.getBytes(StandardCharsets.US_ASCII)))
            throw new GeneralSecurityException("ChunkId não corresponde ao conteúdo cifrado.");
        if (keyVersion != KEY_VERSION) throw new GeneralSecurityException("Versão da chave não suportada.");

        validateKey(masterKey); validateLength(salt, 32, "salt"); validateLength(nonce, 12, "nonce");
        byte[] dek = hkdf(masterKey, salt);
        Sealed.Aad aad = Sealed.aad(keyVersion, salt, nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.bytes());
        return cipher.doFinal(ciphertext);
    }

    public static byte[] hkdf(byte[] masterKey, byte[] salt) throws GeneralSecurityException {
        validateKey(masterKey); validateLength(salt, 32, "salt");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(masterKey);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteBuffer info = ByteBuffer.allocate(INFO.length + 1);
        info.put(INFO).put((byte) 0x01);
        byte[] expanded = mac.doFinal(info.array());
        return Arrays.copyOf(expanded, 32);
    }

    public static String sha256Hex(byte[] data) throws GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] random(int size) {
        byte[] result = new byte[size];
        new SecureRandom().nextBytes(result);
        return result;
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Master key deve ter 32 bytes.");
    }

    private static void validateLength(byte[] value, int length, String name) {
        if (value == null || value.length != length) throw new IllegalArgumentException(name + " deve ter " + length + " bytes.");
    }
}
