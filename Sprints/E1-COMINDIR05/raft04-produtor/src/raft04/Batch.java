package raft04;

import java.util.ArrayList;
import java.util.List;

public class Batch {
    private final long term;
    private final int leader;
    private final String address;
    private final int port;
    private final long hb;
    private final long lastSeq;
    private final long lastTerm;
    private final List<SensorRecord> records;

    public Batch(
            long term,
            int leader,
            String address,
            int port,
            long hb,
            long lastSeq,
            long lastTerm,
            List<SensorRecord> records
    ) {
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = List.copyOf(records);
    }

    public long getTerm() {
        return term;
    }

    public int getLeader() {
        return leader;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public long getHb() {
        return hb;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public long getLastTerm() {
        return lastTerm;
    }

    public List<SensorRecord> getRecords() {
        return records;
    }

    public String encode() {
        StringBuilder resultado = new StringBuilder();

        resultado.append("LOTE;")
                .append(term).append(";")
                .append(leader).append(";")
                .append(address).append(";")
                .append(port).append(";")
                .append(hb).append(";")
                .append(lastSeq).append(";")
                .append(lastTerm).append(";")
                .append(records.size());

        for (SensorRecord record : records) {
            resultado.append("\n")
                    .append(record.toLine());
        }

        return resultado.toString();
    }

    public static Batch decode(
            String mensagem,
            int numeroParticoes
    ) {
        if (mensagem == null || mensagem.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Mensagem LOTE vazia"
            );
        }

        String[] linhas = mensagem.trim().split("\\R");
        String[] cabecalho = linhas[0].split(";", -1);

        if (cabecalho.length != 9
                || !cabecalho[0].equals("LOTE")) {
            throw new IllegalArgumentException(
                    "Cabeçalho LOTE inválido"
            );
        }

        long term;
        int leader;
        String address = cabecalho[3];
        int port;
        long hb;
        long lastSeq;
        long lastTerm;
        int n;

        try {
            term = Long.parseLong(cabecalho[1]);
            leader = Integer.parseInt(cabecalho[2]);
            port = Integer.parseInt(cabecalho[4]);
            hb = Long.parseLong(cabecalho[5]);
            lastSeq = Long.parseLong(cabecalho[6]);
            lastTerm = Long.parseLong(cabecalho[7]);
            n = Integer.parseInt(cabecalho[8]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cabeçalho LOTE contém valores numéricos inválidos",
                    e
            );
        }

        if (n < 0) {
            throw new IllegalArgumentException(
                    "Número de registos não pode ser negativo"
            );
        }

        if (linhas.length - 1 != n) {
            throw new IllegalArgumentException(
                    "Número de registos não corresponde ao cabeçalho"
            );
        }

        List<SensorRecord> records = new ArrayList<>();

        for (int indice = 1; indice <= n; indice++) {
            records.add(
                    SensorRecord.fromLine(linhas[indice])
            );
        }

        return new Batch(
                term,
                leader,
                address,
                port,
                hb,
                lastSeq,
                lastTerm,
                records
        );
    }
}