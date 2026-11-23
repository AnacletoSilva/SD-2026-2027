package raft04;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PartitionNode {
    private final int k;
    private final int i;
    private final int n;

    private final String endereco;
    private final String api;
    private final int porto;

    private final IpfsPubSub pubSub;
    private final RecordFile ficheiro;
    private final RaftState raftState;
    private final ScheduledExecutorService agendador;

    private PartitionNode(
            int k,
            int i,
            int n,
            String endereco,
            String api
    ) throws IOException {
        if (n < 1 || k < 0 || k >= n) {
            throw new IllegalArgumentException(
                    "Partição inválida: k=" + k + ", N=" + n
            );
        }

        if (i < 1 || i > Wire.GRUPO) {
            throw new IllegalArgumentException(
                    "Nó inválido: i=" + i
                            + ". O identificador deve estar entre 1 e "
                            + Wire.GRUPO
            );
        }

        this.k = k;
        this.i = i;
        this.n = n;
        this.endereco = endereco;
        this.api = api;
        this.porto = Wire.PORTO_BASE + k;

        this.pubSub = new IpfsPubSub(api);

        this.ficheiro = new RecordFile(
                "no-p" + k + "-n" + i + ".txt"
        );

        Path ficheiroEstado = Path.of(
                "estado-p" + k + "-n" + i + ".txt"
        );

        this.raftState = new RaftState(
                k,
                i,
                Wire.GRUPO,
                Wire.ELEICAO_MIN_MS,
                Wire.ELEICAO_MAX_MS,
                ficheiroEstado,
                System.out::println
        );

        this.agendador = new ScheduledThreadPoolExecutor(1);

        this.agendador.scheduleAtFixedRate(
                this::pulsar,
                Wire.VERIFICACAO_MS,
                Wire.VERIFICACAO_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void pulsar() {
        long termo = raftState.pulsar();

        if (termo >= 0) {
            System.out.println(
                    "PEDIDO_VOTO;p" + k
                            + ";n" + i
                            + ";termo=" + termo
            );
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.out.println(
                    "Uso: java raft04.PartitionNode "
                            + "<k> <i> [N]"
            );
            return;
        }

        int k = Integer.parseInt(args[0]);
        int i = Integer.parseInt(args[1]);
        int n = args.length == 3
                ? Integer.parseInt(args[2])
                : 3;

        String endereco = "127.0.0.1";
        String api = "http://127.0.0.1:5001/api/v0";

        new PartitionNode(k, i, n, endereco, api);
    }
}