package raft04;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final Path ficheiroEventos;

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
        this.ficheiroEventos = Path.of(
                "eventos-p" + k + "-n" + i + ".txt"
        );

        this.pubSub = new IpfsPubSub(api);

        this.ficheiro = new RecordFile(
                "no-p" + k + "-n" + i + ".txt"
        );

        this.vector = new VectorClock(n);

        SensorRecord ultimo = ultimoRegisto();

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

    private void registarEvento(
            String evento,
            String detalhe
    ) {
        String linha = LocalDateTime.now()
                + ";" + evento
                + ";p" + k
                + ";" + detalhe
                + System.lineSeparator();

        try {
            Files.writeString(
                    ficheiroEventos,
                    linha,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.out.println(
                    "Erro ao registar evento: " + e.getMessage()
            );
        }

        System.out.println(
                evento + ";p" + k + ";" + detalhe
        );
    }

    private SensorRecord ultimoRegisto() {
        try {
            return ficheiro.last();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Não foi possível ler o último registo",
                    e
            );
        }
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
                        registarEvento(
                                "IGNORADO",
                                "term=" + batch.getTerm()
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
                        integrarLote(batch);
                    }

                    if (ultimoSeq < batch.getLastSeq()
                            && !recuperacaoEmCurso) {
                        recuperacaoEmCurso = true;
                        precisaRecuperar = true;

                        registarEvento(
                                "LACUNA",
                                "ultimoSeq=" + ultimoSeq
                                        + ";lastSeqLider="
                                        + batch.getLastSeq()
                        );
                    }
                }
            }

            if (precisaRecuperar) {
                recuperarRegistos(batch.getTerm());
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

    private void integrarLote(Batch batch)
            throws IOException {
        for (SensorRecord registo : batch.getRecords()) {
            if (registo.getTerm() < 0
                    || registo.getTerm() > batch.getTerm()) {
                throw new IllegalArgumentException(
                        "Registo fora do termo anunciado: seq="
                                + registo.getSeq()
                                + ", termo="
                                + registo.getTerm()
                );
            }
        }

        long primeiroDivergente = 0;

        for (SensorRecord registo : batch.getRecords()) {
            if (registo.getSeq() > ultimoSeq) {
                continue;
            }

            List<SensorRecord> existente =
                    ficheiro.range(
                            registo.getSeq(),
                            registo.getSeq()
                    );

            if (existente.isEmpty()
                    || !existente.get(0)
                    .toLine()
                    .equals(registo.toLine())) {
                primeiroDivergente = registo.getSeq();
                break;
            }
        }

        if (primeiroDivergente == 0
                && batch.getLastSeq() < ultimoSeq) {
            primeiroDivergente = batch.getLastSeq() + 1;
        }

        SensorRecord ultimo = ultimoRegisto();

        if (primeiroDivergente == 0
                && ultimo != null
                && ultimo.getSeq() == batch.getLastSeq()
                && ultimo.getTerm() != batch.getLastTerm()) {
            primeiroDivergente = ultimo.getSeq();
        }

        if (primeiroDivergente > 0) {
            long seqDivergente = primeiroDivergente;
            long inicioBloco = inicioBlocoTermo(
                    seqDivergente
            );

            ficheiro.truncateAfter(inicioBloco - 1);
            retidos.tailMap(inicioBloco).clear();
            recarregarEstadoDoFicheiro();

            registarEvento(
                    "DIVERGENCIA",
                    "seq=" + seqDivergente
            );

            registarEvento(
                    "TRUNCADO",
                    "ate=" + (inicioBloco - 1)
            );
        }

        for (SensorRecord registo : batch.getRecords()) {
            validarEAplicar(
                    registo,
                    batch.getTerm()
            );
        }
    }

    private void validarEAplicar(
            SensorRecord registo,
            long termLote
    ) throws IOException {
        if (registo.getTerm() < 0
                || registo.getTerm() > termLote) {
            registarEvento(
                    "REGISTO_REJEITADO",
                    "seq=" + registo.getSeq()
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
                registarEvento(
                        "DUPLICADO",
                        "seq=" + seq
                );
                return;
            }

            long inicioBloco =
                    inicioBlocoTermo(seq);

            ficheiro.truncateAfter(inicioBloco - 1);
            retidos.tailMap(inicioBloco).clear();
            recarregarEstadoDoFicheiro();

            registarEvento(
                    "DIVERGENCIA",
                    "seq=" + seq
            );

            registarEvento(
                    "TRUNCADO",
                    "ate=" + (inicioBloco - 1)
            );
        }

        SensorRecord anterior = retidos.get(seq);

        if (anterior != null) {
            if (anterior.toLine()
                    .equals(registo.toLine())) {
                registarEvento(
                        "DUPLICADO_RETIDO",
                        "seq=" + seq
                );
            } else {
                retidos.put(seq, registo);

                registarEvento(
                        "DIVERGENCIA_RETIDA",
                        "seq=" + seq
                );
            }

            return;
        }

        retidos.put(seq, registo);

        if (seq > ultimoSeq + 1) {
            registarEvento(
                    "LACUNA_REGISTO",
                    "esperado=" + (ultimoSeq + 1)
                            + ";recebido=" + seq
            );
        }

        aplicarRetidos();
    }

    private void aplicarRetidos()
            throws IOException {
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

            registarEvento(
                    "APLICADO",
                    "seq=" + ultimoSeq
            );
        }
    }

    private long inicioBlocoTermo(long seq)
            throws IOException {
        List<SensorRecord> todos = ficheiro.all();
        int indice = -1;

        for (int j = 0; j < todos.size(); j++) {
            if (todos.get(j).getSeq() == seq) {
                indice = j;
                break;
            }
        }

        if (indice < 0) {
            return seq;
        }

        long termo = todos.get(indice).getTerm();
        long inicio = todos.get(indice).getSeq();

        while (indice > 0) {
            SensorRecord anterior =
                    todos.get(indice - 1);

            SensorRecord atual =
                    todos.get(indice);

            if (anterior.getSeq() + 1 != atual.getSeq()
                    || anterior.getTerm() != termo) {
                break;
            }

            indice--;
            inicio = todos.get(indice).getSeq();
        }

        return inicio;
    }

    private void recarregarEstadoDoFicheiro()
            throws IOException {
        SensorRecord ultimo = ultimoRegisto();

        ultimoSeq = ultimo == null
                ? 0
                : ultimo.getSeq();

        if (ultimo != null) {
            lamport = ultimo.getLamport();

            if (ultimo.getVector() != null) {
                vector = ultimo.getVector().copy();
            }
        }
    }

    private void recuperarRegistos(long termoLote) {
        long de;
        long ate;
        String host;
        int porto;

        synchronized (this) {
            if (hostLider == null || portoLider == null) {
                registarEvento(
                        "RECUPERACAO_FALHOU",
                        "líder desconhecido"
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
            long proximo = de;

            List<SensorRecord> recuperados =
                    new ArrayList<>();

            while ((linha = entrada.readLine()) != null) {
                if (Wire.isolated(k, i)) {
                    break;
                }

                if ("FIM".equals(linha)) {
                    recebeuFim = true;
                    break;
                }

                if (linha.startsWith("ERRO ")) {
                    throw new IOException(linha);
                }

                SensorRecord registo =
                        SensorRecord.fromLine(linha);

                if (registo.getSeq() != proximo
                        || registo.getSeq() > ate) {
                    throw new IOException(
                            "Intervalo TCP incompleto "
                                    + "ou fora de ordem"
                    );
                }

                if (registo.getTerm() < 0
                        || registo.getTerm() > termoLote) {
                    throw new IOException(
                            "Registo recebido fora "
                                    + "do termo anunciado"
                    );
                }

                recuperados.add(registo);
                proximo++;
            }

            if (!recebeuFim) {
                throw new IOException(
                        "FIM ausente na recuperacao TCP"
                );
            }

            if (proximo != ate + 1) {
                throw new IOException(
                        "Servidor nao devolveu todos "
                                + "os registos pedidos"
                );
            }

            synchronized (this) {
                synchronized (ficheiro) {
                    aplicarRecuperacaoAtomica(
                            de,
                            ate,
                            recuperados
                    );
                }
            }

            registarEvento(
                    "RECUPERACAO",
                    "de=" + de
                            + ";ate=" + ate
                            + ";term=" + termoLote
            );
        } catch (IOException e) {
            registarEvento(
                    "RECUPERACAO_FALHOU",
                    e.getMessage()
            );
        } finally {
            synchronized (this) {
                recuperacaoEmCurso = false;
            }
        }
    }

    private void aplicarRecuperacaoAtomica(
            long de,
            long ate,
            List<SensorRecord> recuperados
    ) throws IOException {
        List<SensorRecord> atuais = ficheiro.all();

        List<SensorRecord> substitutos =
                new ArrayList<>();

        for (SensorRecord registo : atuais) {
            if (registo.getSeq() < de) {
                substitutos.add(registo);
            }
        }

        substitutos.addAll(recuperados);

        ficheiro.replaceAtomically(substitutos);

        retidos.subMap(
                de,
                true,
                ate,
                true
        ).clear();

        recarregarEstadoDoFicheiro();
        aplicarRetidos();

        for (SensorRecord registo : recuperados) {
            registarEvento(
                    "RECUPERACAO_REGISTO",
                    "seq=" + registo.getSeq()
            );
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
                        SensorRecord ultimo =
                                ultimoRegisto();

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

        if (pedido == null
                || Wire.isolated(k, i)) {
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
                    "Mensagem de eleicao invalida: "
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
                    SensorRecord ultimo =
                            ultimoRegisto();

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

    private void processarVoto(
            VoteReply resposta
    ) {
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
        boolean lider =
                !Wire.isolated(k, i)
                        && raftState.termoSeLider() >= 0;

        if (lider && tarefaLotes == null) {
            tarefaLotes = agendador.scheduleAtFixedRate(
                    this::publicarLote,
                    Wire.PERIODO_HB_MS,
                    Wire.PERIODO_HB_MS,
                    TimeUnit.MILLISECONDS
            );
        } else if (!lider
                && tarefaLotes != null) {
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

                    SensorRecord ultimo =
                            ultimoRegisto();

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

    private void atenderPedidoTCP(
            Socket socket
    ) {
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
                saida.println("ERRO No isolado");
                return;
            }

            String pedido = entrada.readLine();

            if (pedido == null) {
                saida.println("ERRO Pedido ausente");
                return;
            }

            String[] campos =
                    pedido.trim().split("\\s+");

            if (campos.length != 3
                    || !"PEDIDO".equals(campos[0])) {
                saida.println(
                        "ERRO Pedido mal formado"
                );
                return;
            }

            long de;
            long ate;

            try {
                de = Long.parseLong(campos[1]);
                ate = Long.parseLong(campos[2]);
            } catch (NumberFormatException e) {
                saida.println(
                        "ERRO Intervalo invalido"
                );
                return;
            }

            if (de > ate) {
                saida.println(
                        "ERRO Intervalo invalido"
                );
                return;
            }

            List<SensorRecord> registos;

            synchronized (this) {
                synchronized (ficheiro) {
                    registos = ficheiro.range(de, ate);
                }
            }

            if (registos.size() != ate - de + 1) {
                saida.println(
                        "ERRO Registos indisponiveis"
                                + " no intervalo"
                );
                return;
            }

            long esperado = de;

            for (SensorRecord registo : registos) {
                if (registo.getSeq() != esperado) {
                    saida.println(
                            "ERRO Intervalo local incompleto"
                    );
                    return;
                }

                saida.println(registo.toLine());
                esperado++;
            }

            saida.println("FIM");
        } catch (IOException e) {
            System.out.println(
                    "Recuperacao TCP interrompida: "
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