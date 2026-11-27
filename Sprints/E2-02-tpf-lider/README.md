# Sprint E2-02 — Líder TPF

## Execução

Defina `DISCORD_TOKEN` e `DISCORD_GUILD_ID` e execute o projeto com Maven:

```bash
mvn -q test
mvn -q compile exec:java
```

Propriedades opcionais:

- `-Dtpf.chunkMaxBytes=65536`
- `-Dtpf.padBytes=4096`
- `-Dtpf.selagemMs=5000`

## Persistência

Cada `registar(autor, canal, texto)` é sincronizado e atribui o próximo LSN. Depois grava em `dados-lider/registos.log` a linha `lsn;instante;autor;canal;textoBase64`, com `FileChannel.force(true)`, e só depois responde.

Os eventos persistem em `dados-lider/eventos-lider.txt` no formato `instante;evento;detalhe`.

## Formato binário do chunk

Magic `TPFC`, número de versão 1, tamanho útil do payload e payload com padding:

- magic: 4 bytes ASCII
- versão: int32 BE
- `payloadSize`: int32 BE
- payload: sequência interna `TPFC|versao|númeroDeRegistos|registos`
- cada registo: `lsn` int64 BE seguido de tamanho int32 BE e bytes UTF-8 para instante, autor, canal e texto
- zeros de padding até múltiplo de `PAD_BYTES`

`payloadSize` indica quantos bytes do payload são úteis. `Chunk.deserialize` lê esses bytes e ignora o restante.

## `.hdr` e cifra

Cada selo gera em `dados-lider/selados/`:

- `<sha256>.bin`: ciphertext AES-256-GCM com tag de 128 bits
- `<sha256>.hdr`: `keyVersion;saltBase64;nonceBase64`

HKDF-SHA256 deriva a DEK da `master.key` com um salt aleatório de 32 bytes e `info=tpf-chunk-v1`. A AAD é `tpf-v1` concatenado com keyVersion int32 BE, salt e nonce.
