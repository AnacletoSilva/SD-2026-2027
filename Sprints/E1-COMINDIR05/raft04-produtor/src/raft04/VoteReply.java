package raft04;

public final class VoteReply {
    private final long term;
    private final int voter;
    private final int candidate;
    private final boolean granted;

    public VoteReply(
            long term,
            int voter,
            int candidate,
            boolean granted
    ) {
        this.term = term;
        this.voter = voter;
        this.candidate = candidate;
        this.granted = granted;
    }

    public long getTerm() {
        return term;
    }

    public int getVoter() {
        return voter;
    }

    public int getCandidate() {
        return candidate;
    }

    public boolean isGranted() {
        return granted;
    }

    public String encode() {
        return "VOTO;"
                + term + ";"
                + voter + ";"
                + candidate + ";"
                + granted;
    }

    public static VoteReply decode(String mensagem) {
        if (mensagem == null || mensagem.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "VOTO vazio"
            );
        }

        String[] campos = mensagem.trim().split(";", -1);

        if (campos.length != 5
                || !"VOTO".equals(campos[0])) {
            throw new IllegalArgumentException(
                    "VOTO mal formado: " + mensagem
            );
        }

        try {
            boolean granted;

            if ("true".equalsIgnoreCase(campos[4])) {
                granted = true;
            } else if ("false".equalsIgnoreCase(campos[4])) {
                granted = false;
            } else {
                throw new IllegalArgumentException(
                        "Decisão de voto inválida"
                );
            }

            return new VoteReply(
                    Long.parseLong(campos[1]),
                    Integer.parseInt(campos[2]),
                    Integer.parseInt(campos[3]),
                    granted
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "VOTO contém valores inválidos",
                    e
            );
        }
    }
}