package rep03;

public final class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("NÃºmero de partiÃ§Ãµes invÃ¡lido: " + n);
        }
        this.n = n;
    }

    // partiÃ§Ã£o de um sensor: determinÃ­stica e igual em qualquer JVM
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}
