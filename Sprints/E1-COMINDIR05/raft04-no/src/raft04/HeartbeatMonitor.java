package raft04;

import java.util.function.Consumer;

public final class HeartbeatMonitor {
    public enum State {
        DESCONHECIDO,
        ATIVO,
        SUSPEITO
    }

    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;

    private State state = State.DESCONHECIDO;
    private long lastNanos;
    private long term = -1;
    private int leader = 0;
    private long hb = 0;

    public HeartbeatMonitor(
            int partition,
            long periodMs,
            int factor,
            Consumer<String> log
    ) {
        this.partition = partition;
        this.timeoutNanos =
                periodMs * factor * 1_000_000L;
        this.log = log;
        this.lastNanos = System.nanoTime();
    }

    public synchronized void onHeartbeat(
            long term,
            int leader,
            long hb
    ) {
        if (term < this.term) {
            return;
        }

        if (term == this.term
                && leader == this.leader
                && hb <= this.hb) {
            return;
        }

        if (term == this.term
                && leader == this.leader
                && hb != this.hb + 1) {
            log.accept(
                    "HB_PERDIDO;p" + partition
                            + ";esperado=" + (this.hb + 1)
                            + ";recebido=" + hb
            );
        }

        if (term > this.term) {
            log.accept(
                    "NOVO_LIDER;p" + partition
                            + ";term=" + term
                            + ";leader=n" + leader
            );
        }

        if (state == State.SUSPEITO) {
            log.accept(
                    "RETOMA;p" + partition
                            + ";term=" + term
                            + ";leader=n" + leader
                            + ";hb=" + hb
            );
        }

        this.term = term;
        this.leader = leader;
        this.hb = hb;
        this.lastNanos = System.nanoTime();
        this.state = State.ATIVO;
    }

    public synchronized void check() {
        long elapsed =
                System.nanoTime() - lastNanos;

        if (state != State.SUSPEITO
                && elapsed > timeoutNanos) {
            state = State.SUSPEITO;

            log.accept(
                    "SUSPEITA;p" + partition
                            + ";silencio_ms="
                            + elapsed / 1_000_000
            );
        }
    }

    public synchronized State state() {
        return state;
    }
}