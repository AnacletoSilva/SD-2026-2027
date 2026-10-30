package rep03;

import java.util.Arrays;

public final class VectorClock {
    public enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }

    private final long[] v;

    public VectorClock(int n) { this.v = new long[n]; }
    private VectorClock(long[] v) { this.v = v; }

    public void merge(VectorClock other) {
        for (int i = 0; i < v.length; i++) {
            v[i] = Math.max(v[i], other.v[i]);
        }
    }

    public void tick(int k) {
        v[k]++;
    }

    public long get(int k) { return v[k]; }

    public VectorClock copy() { return new VectorClock(v.clone()); }

    public Order compare(VectorClock other) {
        boolean menor = false, maior = false;
        for (int i = 0; i < v.length; i++) {
            if (v[i] < other.v[i]) menor = true;
            if (v[i] > other.v[i]) maior = true;
        }
        if (menor && maior) return Order.CONCURRENT;
        if (menor) return Order.BEFORE;
        if (maior) return Order.AFTER;
        return Order.EQUAL;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    public static VectorClock parse(String s, int n) {
        String[] f = s.trim().split(",");
        if (f.length != n) throw new IllegalArgumentException("Vetor com dimensão " + f.length + ", esperado " + n + ": " + s);
        long[] v = new long[n];
        for (int i = 0; i < n; i++) v[i] = Long.parseLong(f[i]);
        return new VectorClock(v);
    }

    @Override public boolean equals(Object o) {
        return o instanceof VectorClock && Arrays.equals(v, ((VectorClock)o).v);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(v);
    }
}
