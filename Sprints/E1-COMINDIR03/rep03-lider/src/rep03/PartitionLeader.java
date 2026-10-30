package rep03;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public class PartitionLeader {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            System.out.println("Uso: java rep03.PartitionLeader <k> <n> [<endereco>] [<api>]");
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
                        } catch (IOException e) {
                            System.out.println("Erro ao gravar no arquivo: " + e.getMessage());
                            gravado = null;
                        }
                    }

                    if (gravado != null) {
                        System.out.println("EVENTO: seq=" + gravado.getSeq() + " L=" + gravado.getLamport() + " V=" + gravado.getVector());
                        String regMsg = Wire.reg(endereco, porto, gravado);
                        try {
                            ps.publish(Wire.topicData(k), regMsg);
                        } catch (IOException | InterruptedException e) {
                            System.out.println("Erro ao publicar: " + e.getMessage());
                        }
                    }
                });
            } catch (IOException | InterruptedException e) {
                System.out.println("Erro ao subscrever: " + e.getMessage());
            }
        });
        entrada.start();

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
    }
}
