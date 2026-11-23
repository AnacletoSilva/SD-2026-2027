package rep04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PartitionReplica {
    private static final int NUMERO_PARTICOES = 3;
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java PartitionReplica <id> <partições> [portoApi]");
            return;
        }
        int id = Integer.parseInt(args[0]);
        String partitionText = args[1];
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5001;
        String apiUrl = "http://127.0.0.1:" + port + "/api/v0";

        String[] partitions = partitionText.split(",");

        List<Integer> partitionList = new java.util.ArrayList<>();
        try {
            for (String partition : partitions) {
                if (!partition.isEmpty()) {
                    partitionList.add(Integer.parseInt(partition.trim()));
                }
                if (partitionList.size() > 0 && partitionList.get(partitionList.size() - 1) < 0) {
                    throw new NumberFormatException("Partição inválida: " + partition);
                }
            }
        } catch (NumberFormatException e) {
            System.out.println("Erro ao converter partições: " + e.getMessage());
            return;
        }
        System.out.println("Partições: " + partitionList);
        List<EstadoParticao> estados = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < partitionList.size(); i++) {
                int k = partitionList.get(i);
                EstadoParticao estado = new EstadoParticao(id, k);
                estados.add(estado);
                System.out.println("Réplica da Partição " + k + " inicializada com Último seq: " + estado.ultimoSeq);
            }
        } catch (IOException e) {
            System.out.println("Erro ao inicializar o estado da partição: " + e.getMessage());
        }

        final long VERIFICACAO_MS = 1000;

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(() -> {
            for (EstadoParticao estado : estados) {
                estado.monitor.check();
            }
        }, VERIFICACAO_MS, VERIFICACAO_MS, TimeUnit.MILLISECONDS);

        IpfsPubSub ps = new IpfsPubSub(apiUrl);
        for (EstadoParticao estado : estados) {
            Thread thread = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(estado.k), mensagem -> {
                        String tipo;
                        try {
                            tipo = Wire.type(mensagem);
                        } catch (IllegalArgumentException e) {
                            System.out.println("REJEITADO: " + mensagem);
                            return;
                        }

                        if (tipo.equals("LOTE")) {
                            try {
                                Batch batch = Batch.decode(mensagem, NUMERO_PARTICOES);

                                // Atualizar o monitor de heartbeat
                                estado.monitor.onHeartbeat(
                                        batch.getInc(),
                                        batch.getHb()
                                );

                                // Guardar endereço e porta do líder
                                synchronized (estado) {
                                    estado.hostLider = batch.getAddress();
                                    estado.portoLider = batch.getPort();
                                }

                                // Aplicar os registos recebidos no lote
                                for (SensorRecord r : batch.getRecords()) {
                                    try {
                                        aplicar(estado, r);
                                    } catch (IOException e) {
                                        System.out.println(
                                                "Erro ao aplicar registo: " + e.getMessage()
                                        );
                                    }
                                }


                                boolean precisaRecuperar = false;

                                synchronized (estado) {
                                    // Memorizar o maior limite anunciado pelo líder.
                                    // Assim, até um lote vazio permite detetar lacunas.
                                    estado.ultimoSeqLider = Math.max(
                                            estado.ultimoSeqLider,
                                            batch.getLastSeq()
                                    );

                                    if (estado.ultimoSeq < estado.ultimoSeqLider
                                            && !estado.recuperacaoEmCurso) {

                                        estado.recuperacaoEmCurso = true;
                                        precisaRecuperar = true;

                                        System.out.println(
                                                "LACUNA;p" + estado.k
                                                        + ";ultimoSeq=" + estado.ultimoSeq
                                                        + ";lastSeqLider=" + estado.ultimoSeqLider
                                        );
                                    }
                                }

                                if (precisaRecuperar) {
                                    recuperar(estado);
                                }


                            } catch (IllegalArgumentException e) {
                                System.out.println(
                                        "LOTE inválido: " + e.getMessage()
                                );
                                return;
                            }
                        } else {
                            System.out.println("REJEITADO: " + mensagem);
                        }
                    });
                } catch (IOException e) {
                    System.out.println("Erro ao subscrever o tópico: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Interrompido durante a subscrição do tópico.");
                }
            });
            thread.start();
        }
    }

    private static class EstadoParticao {
        int k;
        RecordFile file;
        long ultimoSeq;
        TreeMap<Long, SensorRecord> retidos;
        String hostLider;
        Integer portoLider;
        boolean recuperacaoEmCurso;
        HeartbeatMonitor monitor;
        long ultimoSeqLider = 0;
        public EstadoParticao(int id, int k) throws IOException {
            this.k = k;
            this.file = new RecordFile("replica-" + id + "-p" + k + ".txt");
            this.ultimoSeq = file.lastSeq();
            this.retidos = new TreeMap<>();
            this.hostLider = null;
            this.portoLider = null;
            this.recuperacaoEmCurso = false;
            this.monitor = new HeartbeatMonitor(k, 5000, 3,
                    evento -> System.out.println(evento));
        }
    }

    public static void aplicar(EstadoParticao estado, SensorRecord registo) throws IOException {
        long seq = registo.getSeq();
        boolean precisaRecuperar = false;
        synchronized (estado) {
            if (estado.retidos.containsKey(seq) || seq <= estado.ultimoSeq) {
                System.out.println("DUPLICADO: " + seq);
            } else if (seq == estado.ultimoSeq + 1) {
                estado.file.append(registo);
                estado.ultimoSeq = seq;
                System.out.println("Aplicado: " + registo.toLine());
                while (!estado.retidos.isEmpty() && estado.retidos.firstKey() == estado.ultimoSeq + 1) {
                    var e = estado.retidos.pollFirstEntry();
                    estado.file.append(e.getValue());
                    estado.ultimoSeq = e.getKey();
                    System.out.println("Aplicado (retido): " + e.getValue().toLine());
                }
            } else {
                estado.retidos.put(seq, registo);
                if (estado.retidos.size() > Wire.LIMITE_RETIDOS && !estado.recuperacaoEmCurso) {
                    System.out.println("EM ESPERA: " + seq + " (falta o registo " + (estado.ultimoSeq + 1) + ")");
                    estado.recuperacaoEmCurso = true;
                    precisaRecuperar = true;
                }
            }
        }
        if (precisaRecuperar) {
            recuperar(estado);
        }
    }

    public static void recuperar(EstadoParticao estado) {
        try {
            long de, ate;
            String host;
            int porto;
            synchronized (estado) {
                if (estado.hostLider == null || estado.portoLider == null) {
                    System.out.println("RECUPERAÇÃO FALHOU: líder desconhecido");
                    return;
                }

                de = estado.ultimoSeq + 1;
                ate = estado.ultimoSeqLider;

                // Quando existe um limite anunciado pelo LOTE, recuperar
                // até esse valor, mesmo que não haja registos retidos.
                // Sem limite anunciado, preservar a recuperação antiga
                // usando o primeiro registo retido como referência.
                if (ate < de && !estado.retidos.isEmpty()) {
                    ate = estado.retidos.firstKey() - 1;
                }

                if (de > ate) {
                    return;
                }

                host = estado.hostLider;
                porto = estado.portoLider;
            }
            System.out.println("RECUPERAÃƒâ€¡ÃƒÆ’O: " + de + ".." + ate + " pedida a " + host + ":" + porto);

            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(host, porto),
                        Wire.T_TCP_MS
                );
                socket.setSoTimeout(Wire.T_TCP_MS);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out.println("PEDIDO " + de + " " + ate);
                String line;
                boolean recebeuFim = false;
                while ((line = in.readLine()) != null) {
                    if (line.equals("FIM")) {
                        recebeuFim = true;
                        break;
                    }
                    if (line.startsWith("ERRO ")) {
                        System.out.println("RECUPERAÃƒâ€¡ÃƒÆ’O FALHOU: " + line);
                        return;
                    }
                    else {
                        SensorRecord r;
                        try {
                            r = SensorRecord.fromLine(line);
                        } catch (IllegalArgumentException e) {
                            System.out.println("RECUPERAÃƒâ€¡ÃƒÆ’O FALHOU: registo invÃƒÂ¡lido recebido: " + line);
                            return;
                        }
                        aplicar(estado, r);
                        System.out.println("RECUPERADO: " + r.getSeq());
                    }
                }
                if (!recebeuFim) {
                    System.out.println("RECUPERAÃƒâ€¡ÃƒÆ’O FALHOU: nÃƒÂ£o recebeu FIM");
                }
            } catch (IOException e) {
                System.out.println("RECUPERAÃƒâ€¡ÃƒÆ’O FALHOU: " + e.getMessage());
            }
        } finally {
            synchronized (estado) {
                estado.recuperacaoEmCurso = false;
            }
        }
    }
}
