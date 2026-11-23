package rep04;

import java.util.List;
import java.util.ArrayList;

public class Batch {
    private final String address;
    private final int port;
    private final long inc;
    private final long hb;
    private final long lastSeq;
    private final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = List.copyOf(records);
    }

    public String getAddress() {
        return this.address;
    }
    public int getPort() {
        return this.port;
    }
    public long getInc() {
        return this.inc;
    }
    public long getHb() {
        return this.hb;
    }
    public long getLastSeq() {
        return this.lastSeq;
    }
    public List<SensorRecord> getRecords() {
        return this.records;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;")
                .append(address).append(";")
                .append(port).append(";")
                .append(inc).append(";")
                .append(hb).append(";")
                .append(lastSeq).append(";")
                .append(records.size());

        for (SensorRecord record : records) {
            sb.append("\n").append(record.toLine());
        }

        return sb.toString();
    }

    public static Batch decode(String msg, int numeroParticoes){
        if (msg == null || msg.trim().isEmpty()) {
            throw new IllegalArgumentException("Mensagem LOTE vazia");
        }

        String[] linhas = msg.trim().split("\\R");
        String[] cabecalho = linhas[0].split(";", -1);

        if (cabecalho.length != 7 || !cabecalho[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        }

        String address = cabecalho[1];
        int port;
        long inc;
        long hb;
        long lastSeq;
        int n;

        try {
            port = Integer.parseInt(cabecalho[2]);
            inc = Long.parseLong(cabecalho[3]);
            hb = Long.parseLong(cabecalho[4]);
            lastSeq = Long.parseLong(cabecalho[5]);
            n = Integer.parseInt(cabecalho[6]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cabeçalho LOTE contém valores numéricos inválidos", e);
        }

        if (n < 0) {
            throw new IllegalArgumentException("Número de registos não pode ser negativo");
        }

        if (linhas.length - 1 != n) {
            throw new IllegalArgumentException("Número de registos não corresponde ao cabeçalho");
        }

        List<SensorRecord> records = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            SensorRecord record = SensorRecord.fromLine(linhas[i]);
            records.add(record);
        }
        return new Batch(address, port, inc, hb, lastSeq, records);

    }
}
