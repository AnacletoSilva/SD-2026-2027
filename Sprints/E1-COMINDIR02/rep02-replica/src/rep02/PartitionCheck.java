package rep02;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PartitionCheck {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Uso: java rep02.PartitionCheck <numero-de-partições> <pasta>");
            return;
        }

        int numeroParticoes;
        try {
            numeroParticoes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Número de partições inválido.");
            return;
        }

        String pasta = args[1];
        Partitioner partitioner = new Partitioner(numeroParticoes);
        int totalRegistos = 0;
        for (int k = 0; k < numeroParticoes; k++) {
            Path caminho = Paths.get(pasta, "lider-p" + k + ".txt");
            // verificar aqui o ficheiro da partição k
            List<String> linhas = Files.readAllLines(caminho);
            Set<String> sensores = new HashSet<>();
            long esperado = 1;
            for (String linha : linhas) {
                SensorRecord registo = SensorRecord.fromLine(linha);

                sensores.add(registo.getSensorId());

                if (partitioner.of(registo.getSensorId()) != k) {
                    System.out.println("ERRO: sensor " + registo.getSensorId()
                            + " está na partição errada.");
                }

                if (registo.getSeq() != esperado) {
                    System.out.println("ERRO: sequência inválida na partição "
                            + k + ". Esperado: " + esperado
                            + ", recebido: " + registo.getSeq());
                }

                esperado++;
            }
            totalRegistos += linhas.size();
            System.out.println("Partição " + k + ": "
                    + linhas.size() + " registos, "
                    + sensores.size() + " sensores distintos");
            int[] contador = {0};
            final int particao = k;

            try (var ficheiros = Files.list(Paths.get(pasta))) {
                ficheiros
                        .filter(path -> path.getFileName().toString()
                                .matches("replica-\\d+-p" + particao + "\\.txt"))
                        .forEach(path -> {
                            // ler e comparar esta réplica com o líder da partição k
                            try {
                                System.out.println("Réplica encontrada na partição "
                                        + particao + ": " + path.getFileName());
                                contador[0]++;
                                List<String> linhasReplica = Files.readAllLines(path);
                                if (linhasReplica.size() != linhas.size()) {
                                    System.out.println("ERRO: réplica " + path.getFileName()
                                            + " tem número diferente de registos do líder.");
                                } else {
                                    for (int i = 0; i < linhas.size(); i++) {
                                        if (!linhas.get(i).equals(linhasReplica.get(i))) {
                                            System.out.println("ERRO: réplica " + path.getFileName()
                                                    + " difere do líder na linha " + (i + 1));
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                System.out.println("ERRO ao ler a réplica " + path.getFileName());
                            }
                        });
            }
            if (contador[0] != 2){
                System.out.println("ERRO: partição " + particao + " tem " + contador[0] + " réplicas; esperado: 2");
            }
        }
        System.out.println("Total do sistema: " + totalRegistos);
    }
}
