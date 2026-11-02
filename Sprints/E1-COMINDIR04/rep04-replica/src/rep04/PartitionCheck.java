package rep04;

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
            System.out.println("Uso: java rep04.PartitionCheck <numero-de-partiÃƒÂ§ÃƒÂµes> <pasta>");
            return;
        }

        int numeroParticoes;
        try {
            numeroParticoes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("NÃƒÂºmero de partiÃƒÂ§ÃƒÂµes invÃƒÂ¡lido.");
            return;
        }

        String pasta = args[1];
        Partitioner partitioner = new Partitioner(numeroParticoes);
        int totalRegistos = 0;
        for (int k = 0; k < numeroParticoes; k++) {
            Path caminho = Paths.get(pasta, "lider-p" + k + ".txt");
            // verificar aqui o ficheiro da partiÃƒÂ§ÃƒÂ£o k
            List<String> linhas = Files.readAllLines(caminho);
            Set<String> sensores = new HashSet<>();
            long esperado = 1;
            for (String linha : linhas) {
                SensorRecord registo = SensorRecord.fromLine(linha);

                sensores.add(registo.getSensorId());

                if (partitioner.of(registo.getSensorId()) != k) {
                    System.out.println("ERRO: sensor " + registo.getSensorId()
                            + " estÃƒÂ¡ na partiÃƒÂ§ÃƒÂ£o errada.");
                }

                if (registo.getSeq() != esperado) {
                    System.out.println("ERRO: sequÃƒÂªncia invÃƒÂ¡lida na partiÃƒÂ§ÃƒÂ£o "
                            + k + ". Esperado: " + esperado
                            + ", recebido: " + registo.getSeq());
                }

                esperado++;
            }
            totalRegistos += linhas.size();
            System.out.println("PartiÃƒÂ§ÃƒÂ£o " + k + ": "
                    + linhas.size() + " registos, "
                    + sensores.size() + " sensores distintos");
            int[] contador = {0};
            final int particao = k;

            try (var ficheiros = Files.list(Paths.get(pasta))) {
                ficheiros
                        .filter(path -> path.getFileName().toString()
                                .matches("replica-\\d+-p" + particao + "\\.txt"))
                        .forEach(path -> {
                            // ler e comparar esta rÃƒÂ©plica com o lÃƒÂ­der da partiÃƒÂ§ÃƒÂ£o k
                            try {
                                System.out.println("RÃƒÂ©plica encontrada na partiÃƒÂ§ÃƒÂ£o "
                                        + particao + ": " + path.getFileName());
                                contador[0]++;
                                List<String> linhasReplica = Files.readAllLines(path);
                                if (linhasReplica.size() != linhas.size()) {
                                    System.out.println("ERRO: rÃƒÂ©plica " + path.getFileName()
                                            + " tem nÃƒÂºmero diferente de registos do lÃƒÂ­der.");
                                } else {
                                    for (int i = 0; i < linhas.size(); i++) {
                                        if (!linhas.get(i).equals(linhasReplica.get(i))) {
                                            System.out.println("ERRO: rÃƒÂ©plica " + path.getFileName()
                                                    + " difere do lÃƒÂ­der na linha " + (i + 1));
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                System.out.println("ERRO ao ler a rÃƒÂ©plica " + path.getFileName());
                            }
                        });
            }
            if (contador[0] != 2){
                System.out.println("ERRO: partiÃƒÂ§ÃƒÂ£o " + particao + " tem " + contador[0] + " rÃƒÂ©plicas; esperado: 2");
            }
        }
        System.out.println("Total do sistema: " + totalRegistos);
    }
}


