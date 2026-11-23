package raft04;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class PartitionLeader {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            System.out.println("Uso: java raft04.PartitionLeader <k> <n> [<endereco>] [<api>]");
            return;
        }

        int k = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        if (n < 1 || k < 0 || k >= n) {
            throw new IllegalArgumentException("Partição inválida: k=" + k + ", N=" + n);
        }

        String endereco = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";

        int porto = Wire.PORTO_BASE + k;
        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");

        Estado estado = new Estado();
        estado.lamport = 0;
        estado.vector = new VectorClock(n);
        SensorRecord ultimo = file.last();
        if (ultimo != null) {
            estado.lamport = ultimo.getLamport();
            estado.vector = ultimo.getVector().copy();
            estado.ultimoSeq = ultimo.getSeq();
        }

        Thread entrada = new Thread(() -> {
            try {
                ps.subscribe(Wire.topicIn(k), mensagem -> {
                    String tipo;
                    try {
                        tipo = Wire.type(mensagem);
                    } catch (IllegalArgumentException e) {
                        System.out.println("REJEITADO: " + mensagem);
                        return;
                    }

                    if (!tipo.equals("IN")) {
                        System.out.println("REJEITADO: " + mensagem);
                        return;
                    }

                    String corpo = Wire.body(mensagem);
                    String[] partes = corpo.split(";", 4);
                    if (partes.length != 4) {
                        System.out.println("Mensagem IN mal formada: " + mensagem);
                        return;
                    }

                    String sensor = partes[0];
                    double temperatura;
                    long lamportMsg;
                    VectorClock incoming;
                    try {
                        temperatura = Double.parseDouble(partes[1]);
                        lamportMsg = Long.parseLong(partes[2]);
                        incoming = VectorClock.parse(partes[3], n);
                    } catch (Exception e) {
                        System.out.println("Mensagem IN inválida: " + mensagem);
                        return;
                    }

                    SensorRecord gravado = null;
                    synchronized (file) {
                        VectorClock merged = estado.vector.copy();
                        merged.merge(incoming);
                        long novoLamport = Math.max(estado.lamport, lamportMsg) + 1;
                        merged.tick(k);
                        long proximoSeq = merged.get(k);

                        String timestamp = LocalDateTime.now().withNano(0).toString();
                        gravado = new SensorRecord(proximoSeq, sensor, temperatura, timestamp, novoLamport, merged);
                        try {
                            file.append(gravado);
                            estado.ultimoSeq = proximoSeq;
                            estado.lamport = novoLamport;
                            estado.vector = merged.copy();
                            estado.pendentes.add(gravado);
                        } catch (IOException e) {
                            System.out.println("Erro ao gravar no arquivo: " + e.getMessage());
                            gravado = null;
                        }
                    }


                    if (gravado != null) {
                        System.out.println("EVENTO: seq=" + gravado.getSeq()
                                + " L=" + gravado.getLamport()
                                + " V=" + gravado.getVector());
                    }
                });
            } catch (IOException | InterruptedException e) {
                System.out.println("Erro ao subscrever: " + e.getMessage());
            }
        });
        entrada.start();

        final long PERIODO_MS = 5000;

        ScheduledExecutorService scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.scheduleAtFixedRate(() -> {

            List<SensorRecord> lote;
            long ultimoSeq;
            long heartbeat;
            long incarnacao;

            synchronized (file) {
                // Guardar os registos pendentes
                lote = estado.pendentes;

                // Criar uma nova lista para próximos registos
                estado.pendentes = new ArrayList<>();

                // Capturar estado consistente
                ultimoSeq = estado.ultimoSeq;
                heartbeat = ++estado.hb;
                incarnacao = estado.inc;
            }

            // Construir o lote fora da secção crítica
            Batch batch = new Batch(
                    0,
                    1,
                    endereco,
                    porto,
                    heartbeat,
                    ultimoSeq,
                    0,
                    lote
            );

            try {
                ps.publish(
                        Wire.topicData(k),
                        batch.encode()
                );

                System.out.println(
                        "LOTE_PUBLICADO;p" + k
                                + ";hb=" + heartbeat
                                + ";ultimoSeq=" + ultimoSeq
                                + ";registos=" + lote.size()
                );

            } catch (IOException e) {
                System.out.println(
                        "Erro ao publicar LOTE: " + e.getMessage()
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Publicação interrompida");
            }

        }, PERIODO_MS, PERIODO_MS, TimeUnit.MILLISECONDS);


        try (ServerSocket server = new ServerSocket(porto)) {
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();
                    if (pedido == null) {
                        out.println("ERRO Pedido ausente");
                        continue;
                    }

                    String[] partes = pedido.split("\\s+");
                    if (partes.length != 3 || !partes[0].equals("PEDIDO")) {
                        out.println("ERRO Pedido mal formado");
                        continue;
                    }

                    try {
                        long de = Long.parseLong(partes[1]);
                        long ate = Long.parseLong(partes[2]);
                        if (de > ate) {
                            out.println("ERRO Intervalo inválido");
                            continue;
                        }
                        synchronized (file) {
                            for (SensorRecord r : file.range(de, ate)) {
                                out.println(r.toLine());
                            }
                        }
                        out.println("FIM");
                    } catch (NumberFormatException e) {
                        out.println("ERRO Intervalo inválido");
                    }
                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }

    private static class Estado {
        long ultimoSeq = 0;
        long lamport = 0;
        VectorClock vector = new VectorClock(1);
        List<SensorRecord> pendentes = new ArrayList<>();
        long inc = System.currentTimeMillis();
        long hb = 0;
    }
}

