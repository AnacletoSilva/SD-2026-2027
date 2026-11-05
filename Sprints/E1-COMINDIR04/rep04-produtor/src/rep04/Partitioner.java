package rep04;

public final class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("NÃƒÂºmero de partiÃƒÂ§ÃƒÂµes invÃƒÂ¡lido: " + n);
        }
        this.n = n;
    }

    // partiÃƒÂ§ÃƒÂ£o de um sensor: determinÃƒÂ­stica e igual em qualquer JVM
    public int of(String sensorId) {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("Sensor ID invÃƒÂ¡lido: " + sensorId);
        }
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}

