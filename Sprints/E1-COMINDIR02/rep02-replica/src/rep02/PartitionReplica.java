package rep02;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

public class PartitionReplica {
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
                System.out.println("Réplica da partição " + k + " inicializada com último seq: " + estado.ultimoSeq);
            }
        } catch (IOException e) {
            System.out.println("Erro ao inicializar o estado da partição: " + e.getMessage());
        }
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
                        if (tipo.equals("REG")) {
                            String[] partes;
                            SensorRecord r;
                            try {
                                partes = Wire.regParts(mensagem);
                                if (partes.length != 3) {
                                    System.out.println("Mensagem REG mal formada: " + mensagem);
                                    return;
                                }
                                int portoLider = Integer.parseInt(partes[1]);
                                r = SensorRecord.fromLine(partes[2]);
                                synchronized (estado) {
                                    estado.hostLider = partes[0];
                                    estado.portoLider = portoLider;
                                }
                                try {
                                    aplicar(estado, r);
                                } catch (IOException e) {
                                    System.out.println("Erro ao aplicar registo: " + e.getMessage());
                                }
                            } catch (NumberFormatException e) {
                                System.out.println("Porto inválido na mensagem REG: " + mensagem);
                                return;
                            } catch (IllegalArgumentException e) {
                                System.out.println("REJEITADO: " + mensagem);
                                return;
                            }
                        }
                        else {
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
        public EstadoParticao(int id, int k) throws IOException {
            this.k = k;
            this.file = new RecordFile("replica-" + id + "-p" + k + ".txt");
            this.ultimoSeq = file.lastSeq();
            this.retidos = new TreeMap<>();
            this.hostLider = null;
            this.portoLider = null;
            this.recuperacaoEmCurso = false;
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
                // Lógica de recuperação
                if (estado.hostLider == null || estado.portoLider == null) {
                    System.out.println("RECUPERAÇÃO FALHOU: líder desconhecido");
                    return;
                }
                if (estado.retidos.size() == 0) {
                    System.out.println("RECUPERAÇÃO FALHOU: não há registos retidos");
                    return;
                }
                de = estado.ultimoSeq + 1;
                ate = estado.retidos.firstKey() - 1;
                host = estado.hostLider;
                porto = estado.portoLider;
            }
            System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + host + ":" + porto);

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
                        System.out.println("RECUPERAÇÃO FALHOU: " + line);
                        return;
                    }
                    else {
                        SensorRecord r;
                        try {
                            r = SensorRecord.fromLine(line);
                        } catch (IllegalArgumentException e) {
                            System.out.println("RECUPERAÇÃO FALHOU: registo inválido recebido: " + line);
                            return;
                        }
                        aplicar(estado, r);
                        System.out.println("RECUPERADO: " + r.getSeq());
                    }
                }
                if (!recebeuFim) {
                    System.out.println("RECUPERAÇÃO FALHOU: não recebeu FIM");
                }
            } catch (IOException e) {
                System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage());
            }
        } finally {
            synchronized (estado) {
                estado.recuperacaoEmCurso = false;
            }
        }
    }
}
