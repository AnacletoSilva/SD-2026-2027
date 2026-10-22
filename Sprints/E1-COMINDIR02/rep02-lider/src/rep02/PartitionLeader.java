package rep02;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class PartitionLeader {
    public static void main(String[] args) throws  IOException {
        // Lógica para o líder da partição
        if (args.length < 2 || args.length > 4) {
            System.out.println("Uso: java PartitionLeader <k> <n> [<endereco>] [<api>]");
            System.out.println("  k: índice da partição (0 a n-1)");
            System.out.println("  n: número total de partições");
            System.out.println("  endereco: endereço IP do líder (opcional, padrão: 127.0.0.1)");
            System.out.println("  api: URL da API (opcional, padrão: http://127.0.0.1:5001/api/v0)");
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
        estado.ultimoSeq = file.lastSeq();
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
                    if (tipo.equals("IN")) {
                        String corpo = Wire.body(mensagem);
                        String[] partes = corpo.split(";");
                        if (partes.length != 2) {
                            System.out.println("Mensagem IN mal formada: " + mensagem);
                            return;
                        }
                        String sensor = partes[0];
                        double temperatura;
                        try {
                            temperatura = Double.parseDouble(partes[1]);
                        } catch (NumberFormatException e) {
                            System.out.println("Temperatura inválida na mensagem: " + mensagem);
                            return;
                        }
                        SensorRecord gravado = null;
                        synchronized (file) {
                            long proximoSeq = estado.ultimoSeq + 1;
                            SensorRecord candidato = SensorRecord.now(proximoSeq, sensor, temperatura);
                            try {
                                file.append(candidato);
                                estado.ultimoSeq = proximoSeq;
                                gravado = candidato;
                            } catch (IOException e) {
                                System.out.println("Erro ao gravar no arquivo: " + e.getMessage());
                            }
                        }
                        if  (gravado != null) {
                            String regMsg = Wire.reg(endereco, porto, gravado);
                            try {
                                ps.publish(Wire.topicData(k), regMsg);
                            } catch (IOException e) {
                                System.out.println("Erro ao publicar: " + e.getMessage());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                System.out.println("Interrompido ao publicar: " + e.getMessage());
                            }
                        }
                    }
                    else {
                        System.out.println("REJEITADO: " + mensagem);
                    }
                });
            } catch (IOException e) {
                System.out.println("Erro ao subscrever: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Interrompido ao subscrever: " + e.getMessage());
            }
        });
        entrada.start();

        try (ServerSocket server = new ServerSocket(porto)) {
            while (true) {
                try (Socket s = server.accept();                       // bloqueia até uma réplica se ligar
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();                     // bloqueia até chegar o pedido
                    /* validar "PEDIDO <de> <até>"; responder com file.range(...) e "FIM", ou "ERRO <motivo>" */
                    if (pedido == null) {
                        out.println("ERRO Pedido ausente");
                        continue;
                    }
                    // Processar o pedido
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
        long ultimoSeq;
    }
}