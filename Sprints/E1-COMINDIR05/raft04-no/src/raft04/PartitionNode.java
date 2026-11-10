package raft04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    private final List<SensorRecord> pendentes =
            new ArrayList<>();

    private final TreeMap<Long, SensorRecord> retidos =
            new TreeMap<>();

    private ScheduledFuture<?> tarefaLotes;

    private long lamport = 0;
    private long ultimoSeq = 0;
    private long ultimoSeqLider = 0;
    private long hb = 0;
    private VectorClock vector;

    private String hostLider;
    private Integer portoLider;
    private boolean recuperacaoEmCurso;

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
            );
        }

        this.k = k;
        this.i = i;
        this.n = n;
        this.endereco = endereco;
        this.api = api;
        this.porto = Wire.nodePort(k, i);

        this.pubSub = new IpfsPubSub(api);

        this.ficheiro = new RecordFile(
                "no-p" + k + "-n" + i + ".txt"
        );

        this.vector = new VectorClock(n);

        SensorRecord ultimo = ficheiro.last();

        if (ultimo != null) {
            this.ultimoSeq = ultimo.getSeq();
            this.lamport = ultimo.getLamport();

            if (ultimo.getVector() != null) {
                this.vector = ultimo.getVector().copy();
            }
        }

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

        iniciarSubscricaoIN();
        iniciarSubscricaoEleicao();
        iniciarSubscricaoLotes();
        iniciarServidorTCP();

        this.agendador.scheduleAtFixedRate(
                this::verificarEleicao,
                Wire.VERIFICACAO_MS,
                Wire.VERIFICACAO_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void iniciarSubscricaoIN() {
        new Thread(
                this::subscreverIN,
                "raft04-in-p" + k + "-n" + i
        ).start();
    }

    private void iniciarSubscricaoEleicao() {
        new Thread(
                this::subscreverEleicao,
                "raft04-eleicao-p" + k + "-n" + i
        ).start();
    }

    private void iniciarSubscricaoLotes() {
        new Thread(
                this::subscreverLotes,
                "raft04-lotes-p" + k + "-n" + i
        ).start();
    }

    private void iniciarServidorTCP() {
        new Thread(
                this::executarServidorTCP,
                "raft04-tcp-p" + k + "-n" + i
        ).start();
    }

    private void subscreverIN() {
        try {
            pubSub.subscribe(
                    Wire.topicIn(k),
                    this::processarIN
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao subscrever IN: " + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void subscreverEleicao() {
        try {
            pubSub.subscribe(
                    Wire.topicElection(k),
                    this::processarMensagemEleicao
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao subscrever eleição: "
                            + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void subscreverLotes() {
        try {
            pubSub.subscribe(
                    Wire.topicData(k),
                    this::processarLote
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao subscrever LOTE: "
                            + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processarIN(String mensagem) {
        try {
            if (!"IN".equals(Wire.type(mensagem))) {
                return;
            }

            String[] campos = Wire.body(mensagem).split(";", 4);

            if (campos.length != 4) {
                System.out.println(
                        "IN rejeitado: mensagem mal formada"
                );
                return;
            }

            String sensorId = campos[0];
            double temperatura = Double.parseDouble(campos[1]);
            long lamportRecebido = Long.parseLong(campos[2]);

            VectorClock vectorRecebido =
                    VectorClock.parse(campos[3], n);

            synchronized (this) {
                synchronized (raftState) {
                    long term = raftState.termoSeLider();

                    if (term < 0) {
                        System.out.println(
                                "IN rejeitado: nó não é líder"
                        );
                        return;
                    }

                    VectorClock novoVector = vector.copy();
                    novoVector.merge(vectorRecebido);

                    long novoLamport = Math.max(
                            lamport,
                            lamportRecebido
                    ) + 1;

                    novoVector.tick(k);

                    SensorRecord registo = new SensorRecord(
                            novoVector.get(k),
                            sensorId,
                            temperatura,
                            LocalDateTime.now()
                                    .withNano(0)
                                    .toString(),
                            novoLamport,
                            novoVector,
                            term
                    );

                    synchronized (ficheiro) {
                        ficheiro.append(registo);
                        ultimoSeq = registo.getSeq();
                        lamport = novoLamport;
                        vector = novoVector.copy();
                        pendentes.add(registo);
                    }
                }
            }

        } catch (IllegalArgumentException e) {
            System.out.println(
                    "IN rejeitado: " + e.getMessage()
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao guardar IN: " + e.getMessage()
            );
        }
    }

    private void processarLote(String mensagem) {
        if (Wire.isolated(k, i)) {
            return;
        }

        boolean precisaRecuperar = false;

        try {
            Batch batch = Batch.decode(mensagem, n);

            synchronized (this) {
                synchronized (raftState) {
                    if (Wire.isolated(k, i)) {
                        return;
                    }

                    boolean aceite =
                            raftState.aoReceberLote(
                                    batch.getTerm(),
                                    batch.getLeader()
                            );

                    if (!aceite) {
                        System.out.println(
                                "IGNORADO;p" + k
                                        + ";term="
                                        + batch.getTerm()
                                        + ";leader="
                                        + batch.getLeader()
                        );
                        return;
                    }

                    hostLider = batch.getAddress();
                    portoLider = batch.getPort();

                    ultimoSeqLider = Math.max(
                            ultimoSeqLider,
                            batch.getLastSeq()
                    );

                    atualizarEnvioLotes();

                    synchronized (ficheiro) {
                        for (SensorRecord registo
                                : batch.getRecords()) {
                            validarEAplicar(
                                    registo,
                                    batch.getTerm()
                            );
                        }
                    }

                    SensorRecord ultimo = ficheiro.last();

                    if (ultimo != null
                            && batch.getLastSeq()
                            == ultimo.getSeq()
                            && ultimo.getTerm()
                            != batch.getLastTerm()) {
                        System.out.println(
                                "DIVERGENCIA_ULTIMO_TERMO;p" + k
                        );
                    }

                    if (ultimoSeq < batch.getLastSeq()
                            && !recuperacaoEmCurso) {
                        recuperacaoEmCurso = true;
                        precisaRecuperar = true;

                        System.out.println(
                                "LACUNA;p" + k
                                        + ";ultimoSeq="
                                        + ultimoSeq
                                        + ";lastSeqLider="
                                        + batch.getLastSeq()
                        );
                    }
                }
            }

            if (precisaRecuperar) {
                recuperarRegistos();
            }

        } catch (IllegalArgumentException e) {
            System.out.println(
                    "LOTE inválido: " + e.getMessage()
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao processar LOTE: "
                            + e.getMessage()
            );
        }
    }

    private void validarEAplicar(
            SensorRecord registo,
            long termLote
    ) throws IOException {
        if (registo.getTerm() < 0
                || registo.getTerm() > termLote) {
            System.out.println(
                    "REGISTO_REJEITADO;p" + k
                            + ";seq=" + registo.getSeq()
                            + ";term=" + registo.getTerm()
            );
            return;
        }

        long seq = registo.getSeq();

        if (seq <= ultimoSeq) {
            List<SensorRecord> existentes =
                    ficheiro.range(seq, seq);

            if (!existentes.isEmpty()
                    && existentes.get(0)
                    .toLine()
                    .equals(registo.toLine())) {
                System.out.println(
                        "DUPLICADO;p" + k + ";seq=" + seq
                );
            } else {
                System.out.println(
                        "DIVERGENCIA;p" + k + ";seq=" + seq
                );
            }

            return;
        }

        SensorRecord anterior = retidos.get(seq);

        if (anterior != null) {
            if (anterior.toLine().equals(registo.toLine())) {
                System.out.println(
                        "DUPLICADO_RETIDO;p" + k
                                + ";seq=" + seq
                );
            } else {
                System.out.println(
                        "DIVERGENCIA_RETIDA;p" + k
                                + ";seq=" + seq
                );
            }

            return;
        }

        retidos.put(seq, registo);

        if (seq > ultimoSeq + 1) {
            System.out.println(
                    "LACUNA_REGISTO;p" + k
                            + ";esperado=" + (ultimoSeq + 1)
                            + ";recebido=" + seq
            );
        }

        aplicarRetidos();
    }

    private void aplicarRetidos() throws IOException {
        while (!retidos.isEmpty()
                && retidos.firstKey() == ultimoSeq + 1) {
            SensorRecord registo =
                    retidos.pollFirstEntry().getValue();

            ficheiro.append(registo);
            ultimoSeq = registo.getSeq();
            lamport = Math.max(
                    lamport,
                    registo.getLamport()
            );

            if (registo.getVector() != null) {
                vector = registo.getVector().copy();
            }

            System.out.println(
                    "APLICADO;p" + k
                            + ";seq=" + ultimoSeq
            );
        }
    }

    private void recuperarRegistos() {
        long de;
        long ate;
        String host;
        int porto;

        synchronized (this) {
            if (hostLider == null || portoLider == null) {
                System.out.println(
                        "RECUPERACAO_FALHOU: líder desconhecido"
                );
                recuperacaoEmCurso = false;
                return;
            }

            de = ultimoSeq + 1;
            ate = ultimoSeqLider;
            host = hostLider;
            porto = portoLider;
        }

        if (de > ate) {
            synchronized (this) {
                recuperacaoEmCurso = false;
            }
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(
                            host,
                            porto
                    ),
                    Wire.T_TCP_MS
            );

            socket.setSoTimeout(Wire.T_TCP_MS);

            PrintWriter saida = new PrintWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8
                    ),
                    true
            );

            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            saida.println("PEDIDO " + de + " " + ate);

            String linha;
            boolean recebeuFim = false;

            while ((linha = entrada.readLine()) != null) {
                if (Wire.isolated(k, i)) {
                    break;
                }

                if ("FIM".equals(linha)) {
                    recebeuFim = true;
                    break;
                }

                if (linha.startsWith("ERRO ")) {
                    System.out.println(
                            "RECUPERACAO_FALHOU: " + linha
                    );
                    break;
                }

                SensorRecord registo =
                        SensorRecord.fromLine(linha);

                synchronized (this) {
                    validarEAplicar(
                            registo,
                            registo.getTerm()
                    );
                }

                System.out.println(
                        "RECUPERADO;p" + k
                                + ";seq="
                                + registo.getSeq()
                );
            }

            if (!recebeuFim) {
                System.out.println(
                        "RECUPERACAO_FALHOU: FIM ausente"
                );
            }

        } catch (IOException e) {
            System.out.println(
                    "RECUPERACAO_FALHOU: "
                            + e.getMessage()
            );
        } finally {
            synchronized (this) {
                recuperacaoEmCurso = false;
            }
        }
    }

    private void verificarEleicao() {
        VoteRequest pedido = null;

        synchronized (this) {
            synchronized (raftState) {
                long term = raftState.pulsar();

                if (term >= 0) {
                    long lastSeq;
                    long lastTerm;

                    synchronized (ficheiro) {
                        SensorRecord ultimo = ficheiro.last();

                        lastSeq = ultimo == null
                                ? 0
                                : ultimo.getSeq();

                        lastTerm = ultimo == null
                                ? 0
                                : ultimo.getTerm();
                    }

                    pedido = new VoteRequest(
                            term,
                            i,
                            lastSeq,
                            lastTerm
                    );
                }

                if (Wire.isolated(k, i)) {
                    pararEnvioLotes();
                } else {
                    atualizarEnvioLotes();
                }
            }
        }

        if (pedido == null || Wire.isolated(k, i)) {
            return;
        }

        try {
            pubSub.publish(
                    Wire.topicElection(k),
                    pedido.encode()
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao publicar PEDIDO_VOTO: "
                            + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processarMensagemEleicao(
            String mensagem
    ) {
        if (Wire.isolated(k, i)) {
            return;
        }

        try {
            String tipo = Wire.type(mensagem);

            if ("PEDIDO_VOTO".equals(tipo)) {
                responderPedidoVoto(
                        VoteRequest.decode(mensagem)
                );
            } else if ("VOTO".equals(tipo)) {
                processarVoto(
                        VoteReply.decode(mensagem)
                );
            }

        } catch (IllegalArgumentException e) {
            System.out.println(
                    "Mensagem de eleição inválida: "
                            + e.getMessage()
            );
        }
    }

    private void responderPedidoVoto(
            VoteRequest pedido
    ) {
        if (Wire.isolated(k, i)
                || pedido.getCandidate() == i) {
            return;
        }

        VoteReply resposta;

        synchronized (this) {
            synchronized (raftState) {
                if (Wire.isolated(k, i)
                        || pedido.getCandidate() == i) {
                    return;
                }

                long lastSeq;
                long lastTerm;

                synchronized (ficheiro) {
                    SensorRecord ultimo = ficheiro.last();

                    lastSeq = ultimo == null
                            ? 0
                            : ultimo.getSeq();

                    lastTerm = ultimo == null
                            ? 0
                            : ultimo.getTerm();
                }

                RaftState.Voto voto =
                        raftState.aoPedidoDeVoto(
                                pedido.getTerm(),
                                pedido.getCandidate(),
                                pedido.getLastSeq(),
                                pedido.getLastTerm(),
                                lastSeq,
                                lastTerm
                        );

                resposta = new VoteReply(
                        voto.termo,
                        i,
                        pedido.getCandidate(),
                        voto.concedido
                );
            }
        }

        if (Wire.isolated(k, i)) {
            return;
        }

        try {
            pubSub.publish(
                    Wire.topicElection(k),
                    resposta.encode()
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao publicar VOTO: "
                            + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processarVoto(VoteReply resposta) {
        if (Wire.isolated(k, i)
                || resposta.getVoter() == i) {
            return;
        }

        synchronized (this) {
            synchronized (raftState) {
                if (Wire.isolated(k, i)
                        || resposta.getVoter() == i) {
                    return;
                }

                boolean tornouSeLider =
                        raftState.aoReceberVoto(
                                resposta.getTerm(),
                                resposta.getVoter(),
                                resposta.getCandidate(),
                                resposta.isGranted()
                        );

                if (tornouSeLider) {
                    atualizarEnvioLotes();
                }
            }
        }
    }

    private void atualizarEnvioLotes() {
        boolean lider = !Wire.isolated(k, i)
                && raftState.termoSeLider() >= 0;

        if (lider && tarefaLotes == null) {
            tarefaLotes = agendador.scheduleAtFixedRate(
                    this::publicarLote,
                    Wire.PERIODO_HB_MS,
                    Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS
            );
        } else if (!lider && tarefaLotes != null) {
            pararEnvioLotes();
        }
    }

    private void pararEnvioLotes() {
        if (tarefaLotes != null) {
            tarefaLotes.cancel(false);
            tarefaLotes = null;
        }
    }

    private void publicarLote() {
        if (Wire.isolated(k, i)) {
            pararEnvioLotes();
            return;
        }

        List<SensorRecord> lote;
        Batch batch;
        long term;

        synchronized (this) {
            synchronized (raftState) {
                if (Wire.isolated(k, i)) {
                    pararEnvioLotes();
                    return;
                }

                term = raftState.termoSeLider();

                if (term < 0) {
                    return;
                }

                synchronized (ficheiro) {
                    lote = new ArrayList<>(pendentes);
                    pendentes.clear();

                    SensorRecord ultimo = ficheiro.last();

                    long lastSeq = ultimo == null
                            ? 0
                            : ultimo.getSeq();

                    long lastTerm = ultimo == null
                            ? 0
                            : ultimo.getTerm();

                    hb++;

                    batch = new Batch(
                            term,
                            i,
                            endereco,
                            porto,
                            hb,
                            lastSeq,
                            lastTerm,
                            lote
                    );
                }
            }
        }

        if (Wire.isolated(k, i)) {
            devolverPendentes(lote);
            pararEnvioLotes();
            return;
        }

        try {
            pubSub.publish(
                    Wire.topicData(k),
                    batch.encode()
            );
        } catch (IOException e) {
            devolverPendentes(lote);
            System.out.println(
                    "Erro ao publicar LOTE: "
                            + e.getMessage()
            );
        } catch (InterruptedException e) {
            devolverPendentes(lote);
            Thread.currentThread().interrupt();
        }
    }

    private void devolverPendentes(
            List<SensorRecord> lote
    ) {
        synchronized (this) {
            pendentes.addAll(0, lote);
        }
    }

    private void executarServidorTCP() {
        try (ServerSocket servidor =
                     new ServerSocket(porto)) {

            while (true) {
                try {
                    atenderPedidoTCP(
                            servidor.accept()
                    );
                } catch (IOException e) {
                    System.out.println(
                            "Erro ao aceitar pedido TCP: "
                                    + e.getMessage()
                    );
                }
            }

        } catch (IOException e) {
            System.out.println(
                    "Erro ao iniciar servidor TCP: "
                            + e.getMessage()
            );
        }
    }

    private void atenderPedidoTCP(Socket socket) {
        try (
                Socket cliente = socket;
                BufferedReader entrada =
                        new BufferedReader(
                                new InputStreamReader(
                                        cliente.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );
                PrintWriter saida =
                        new PrintWriter(
                                new OutputStreamWriter(
                                        cliente.getOutputStream(),
                                        StandardCharsets.UTF_8
                                ),
                                true
                        )
        ) {
            cliente.setSoTimeout(Wire.T_TCP_MS);

            if (Wire.isolated(k, i)) {
                saida.println("ERRO Nó isolado");
                return;
            }

            String pedido = entrada.readLine();

            if (pedido == null) {
                saida.println("ERRO Pedido ausente");
                return;
            }

            String[] campos = pedido.trim().split("\\s+");

            if (campos.length != 3
                    || !"PEDIDO".equals(campos[0])) {
                saida.println("ERRO Pedido mal formado");
                return;
            }

            long de;
            long ate;

            try {
                de = Long.parseLong(campos[1]);
                ate = Long.parseLong(campos[2]);
            } catch (NumberFormatException e) {
                saida.println("ERRO Intervalo inválido");
                return;
            }

            if (de > ate) {
                saida.println("ERRO Intervalo inválido");
                return;
            }

            List<SensorRecord> registos;

            synchronized (this) {
                synchronized (ficheiro) {
                    registos = ficheiro.range(de, ate);
                }
            }

            for (SensorRecord registo : registos) {
                saida.println(registo.toLine());
            }

            saida.println("FIM");

        } catch (IOException e) {
            System.out.println(
                    "Recuperação TCP interrompida: "
                            + e.getMessage()
            );
        }
    }

    public static void main(String[] args)
            throws IOException {
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

        new PartitionNode(
                k,
                i,
                n,
                "127.0.0.1",
                "http://127.0.0.1:5001/api/v0"
        );
    }
}