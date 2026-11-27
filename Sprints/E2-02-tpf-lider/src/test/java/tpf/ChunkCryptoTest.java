package tpf;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkCryptoTest {
    private byte[] key() { byte[] k = new byte[32]; new SecureRandom().nextBytes(k); return k; }

    @Test void encryptsAndDecrypts() throws Exception {
        byte[] key = key();
        byte[] text = "texto secreto".getBytes(StandardCharsets.UTF_8);
        Sealed sealed = ChunkCrypto.seal(text, key);
        assertArrayEquals(text, ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), sealed.keyVersion(), sealed.salt(), sealed.nonce(), key));
    }

    @Test void alteredCiphertextFails() throws Exception {
        byte[] key = key();
        Sealed sealed = ChunkCrypto.seal("teste".getBytes(StandardCharsets.UTF_8), key);
        sealed.ciphertext()[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), sealed.keyVersion(), sealed.salt(), sealed.nonce(), key));
    }

    @Test void alteredParametersFail() throws Exception {
        byte[] key = key();
        Sealed sealed = ChunkCrypto.seal("teste".getBytes(StandardCharsets.UTF_8), key);
        byte[] wrongSalt = sealed.salt().clone(); wrongSalt[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), sealed.keyVersion(), wrongSalt, sealed.nonce(), key));

        byte[] wrongNonce = sealed.nonce().clone(); wrongNonce[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), sealed.keyVersion(), sealed.salt(), wrongNonce, key));

        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), 2, sealed.salt(), sealed.nonce(), key));

        byte[] wrongKey = key.clone(); wrongKey[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> ChunkCrypto.open(sealed.ciphertext(), sealed.chunkId(), sealed.keyVersion(), sealed.salt(), sealed.nonce(), wrongKey));
    }
}
