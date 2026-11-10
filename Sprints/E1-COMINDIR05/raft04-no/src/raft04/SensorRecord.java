package raft04;

import java.time.LocalDateTime;

public class SensorRecord {
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;
    private final long term;

    public SensorRecord(
            long seq,
            String sensorId,
            double temperature,
            String timestamp
    ) {
        this(seq, sensorId, temperature, timestamp, 0, null, 0);
    }

    public SensorRecord(
            long seq,
            String sensorId,
            double temperature,
            String timestamp,
            long lamport,
            VectorClock vector
    ) {
        this(seq, sensorId, temperature, timestamp, lamport, vector, 0);
    }

    public SensorRecord(
            long seq,
            String sensorId,
            double temperature,
            String timestamp,
            long lamport,
            VectorClock vector,
            long term
    ) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
        this.term = term;
    }

    public static SensorRecord now(
            long seq,
            String sensorId,
            double temperature
    ) {
        return new SensorRecord(
                seq,
                sensorId,
                temperature,
                LocalDateTime.now().withNano(0).toString()
        );
    }

    public static SensorRecord now(
            long seq,
            String sensorId,
            double temperature,
            long lamport,
            VectorClock vector
    ) {
        return new SensorRecord(
                seq,
                sensorId,
                temperature,
                LocalDateTime.now().withNano(0).toString(),
                lamport,
                vector.copy()
        );
    }

    public static SensorRecord now(
            long seq,
            String sensorId,
            double temperature,
            long lamport,
            VectorClock vector,
            long term
    ) {
        return new SensorRecord(
                seq,
                sensorId,
                temperature,
                LocalDateTime.now().withNano(0).toString(),
                lamport,
                vector.copy(),
                term
        );
    }

    public String toLine() {
        return seq
                + ";" + sensorId
                + ";" + temperature
                + ";" + timestamp
                + ";" + lamport
                + ";" + (vector == null ? "" : vector)
                + ";" + term;
    }

    public static SensorRecord fromLine(String line) {
        String[] campos = line.trim().split(";", -1);

        if (campos.length != 6 && campos.length != 7) {
            throw new IllegalArgumentException(
                    "Registo mal formado: " + line
            );
        }

        long seq = Long.parseLong(campos[0]);
        String sensor = campos[1];
        double temperatura = Double.parseDouble(campos[2]);
        String timestamp = campos[3];
        long lamport = Long.parseLong(campos[4]);

        VectorClock vector = null;
        if (!campos[5].isEmpty()) {
            String[] componentes = campos[5].split(",");
            vector = VectorClock.parse(
                    campos[5],
                    componentes.length
            );
        }

        long term = campos.length == 7
                ? Long.parseLong(campos[6])
                : 0;

        return new SensorRecord(
                seq,
                sensor,
                temperatura,
                timestamp,
                lamport,
                vector,
                term
        );
    }

    public long getSeq() {
        return seq;
    }

    public String getSensorId() {
        return sensorId;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public long getLamport() {
        return lamport;
    }

    public VectorClock getVector() {
        return vector;
    }

    public long getTerm() {
        return term;
    }
}