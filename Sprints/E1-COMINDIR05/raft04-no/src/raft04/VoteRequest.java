package raft04;

public final class VoteRequest {
    private final long term;
    private final int candidate;
    private final long lastSeq;
    private final long lastTerm;

    public VoteRequest(
            long term,
            int candidate,
            long lastSeq,
            long lastTerm
    ) {
        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    public long getTerm() {
        return term;
    }

    public int getCandidate() {
        return candidate;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public long getLastTerm() {
        return lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;"
                + term + ";"
                + candidate + ";"
                + lastSeq + ";"
                + lastTerm;
    }

    public static VoteRequest decode(String mensagem) {
        if (mensagem == null || mensagem.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "PEDIDO_VOTO vazio"
            );
        }

        String[] campos = mensagem.trim().split(";", -1);

        if (campos.length != 5
                || !"PEDIDO_VOTO".equals(campos[0])) {
            throw new IllegalArgumentException(
                    "PEDIDO_VOTO mal formado: " + mensagem
            );
        }

        try {
            return new VoteRequest(
                    Long.parseLong(campos[1]),
                    Integer.parseInt(campos[2]),
                    Long.parseLong(campos[3]),
                    Long.parseLong(campos[4])
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "PEDIDO_VOTO contém valores inválidos",
                    e
            );
        }
    }
}