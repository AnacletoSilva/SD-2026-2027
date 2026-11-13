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
                LocalDateTime.now()
                        .withNano(0)
                        .toString()
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
                LocalDateTime.now()
                        .withNano(0)
                        .toString(),
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
                LocalDateTime.now()
                        .withNano(0)
                        .toString(),
                lamport,
                vector.copy(),
                term
        );
    }

    public String toLine() {
        return seq
                + ";" + term
                + ";" + sensorId
                + ";" + temperature
                + ";" + timestamp
                + ";" + lamport
                + ";" + (vector == null ? "" : vector);
    }

    public static SensorRecord fromLine(String line) {
        String[] campos = line.trim().split(";", -1);

        if (campos.length != 6
                && campos.length != 7) {
            throw new IllegalArgumentException(
                    "Registo mal formado: " + line
            );
        }

        long seq = Long.parseLong(campos[0]);
        long term = 0;
        String sensor;
        double temperatura;
        String timestamp;
        long lamport;
        String vectorText;

        if (campos.length == 7) {
            try {
                term = Long.parseLong(campos[1]);
                sensor = campos[2];
                temperatura = Double.parseDouble(campos[3]);
                timestamp = campos[4];
                lamport = Long.parseLong(campos[5]);
                vectorText = campos[6];
            } catch (NumberFormatException e) {
                sensor = campos[1];
                temperatura = Double.parseDouble(campos[2]);
                timestamp = campos[3];
                lamport = Long.parseLong(campos[4]);
                vectorText = campos[5];
                term = Long.parseLong(campos[6]);
            }
        } else {
            sensor = campos[1];
            temperatura = Double.parseDouble(campos[2]);
            timestamp = campos[3];
            lamport = Long.parseLong(campos[4]);
            vectorText = campos[5];
        }

        VectorClock vector = null;

        if (!vectorText.isEmpty()) {
            String[] componentes = vectorText.split(",");
            vector = VectorClock.parse(
                    vectorText,
                    componentes.length
            );
        }

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