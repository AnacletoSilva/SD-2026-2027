package tpf;

import java.util.Objects;

public record Registo(long lsn, String instante, String autor, String canal, String texto) {
    public Registo {
        Objects.requireNonNull(instante);
        Objects.requireNonNull(autor);
        Objects.requireNonNull(canal);
        Objects.requireNonNull(texto);
        if (lsn < 1) throw new IllegalArgumentException("LSN deve ser positivo.");
    }
}
