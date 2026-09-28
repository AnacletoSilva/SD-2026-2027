# Relatório da Tarefa TCP01 — implementação em tarefa-3

Data: 2026-09-28

## Resumo do que foi feito
- Criado o projecto em `tarefa-3/src/tcp01` com as classes: `TCPServer`, `Connection`, `TCPClient`, `Person`, `Place`.
- Implementado exemplo de texto (4.1) — server aceita conexões, um `Connection` por ligação (thread).
- Implementado envio de objetos (4.2): `TCPClient` envia um `Person` (contendo um `Place`) via `ObjectOutputStream`; o servidor usa `ObjectInputStream` para ler o objeto e responde com texto (`DataOutputStream.writeUTF`).
- Implementado 4.3: servidor devolve `place.getLocality()` (prova de serialização automática do grafo de objetos).
- Criadas duas variantes em `tarefa-3/variants`:
  - `working`: alteração compatível (método novo em `Person`).
  - `broken`: `Person` no servidor com `serialVersionUID = 2L` para demonstrar `InvalidClassException`.

## Ficheiros relevantes
- `tarefa-3/src/tcp01/TCPServer.java` — cria `ServerSocket` e aceita ligações
- `tarefa-3/src/tcp01/Connection.java` — thread por ligação; lê objeto e responde
- `tarefa-3/src/tcp01/TCPClient.java` — cria `ObjectOutputStream` e envia `Person`
- `tarefa-3/src/tcp01/Person.java`, `Place.java` — `Serializable`, `serialVersionUID = 1L`
- Variants: `tarefa-3/variants/working` e `tarefa-3/variants/broken`

## Como compilar e executar
(Windows PowerShell / cmd)

1. Compilar (na raiz `tarefa-3` ou em cada variante):
   javac -d out src\tcp01\*.java
2. Terminal A (servidor):
   java -cp out tcp01.TCPServer
3. Terminal B (cliente):
   java -cp out tcp01.TCPClient

Resultado esperado (main / working):
- Cliente: `Received: Lisboa` (o servidor devolve a localidade do Place)

## Testes de falhas (secção 13) — resultados esperados e interpretação
Preencher após execução real. Aqui estão as expectativas e explicações.

| Falha introduzida | Lado onde surge (esperado) | Exceção obtida (esperado) | Momento (ligação/escrita/leitura) | O que a exceção permite concluir |
|---|---:|---|---|---|
| Arrancar o cliente sem o servidor | Cliente | ConnectException / IOException: Connection refused | Ligação (new Socket)
| Retirar `implements Serializable` do `Place` | Cliente (escritor) | NotSerializableException (java.io.NotSerializableException: tcp01.Place) | Escrita (oos.writeObject) | Indica que algum objecto do grafo não é serializável — a escrita falha no lado do emissor
| `serialVersionUID` diferente em `Person` (server) | Servidor (leitor) | InvalidClassException (java.io.InvalidClassException: tcp01.Person; local class incompatible) | Leitura (ois.readObject) | Indica incompatibilidade de versão entre classes serializadas (UID diferente)
| `Person` num pacote diferente no servidor | Servidor (leitor) | ClassNotFoundException ou InvalidClassException | Leitura (ois.readObject) | O class loader não encontra a classe esperada — nomes de pacote/qualificação têm de coincidir

> Nota: na prática, capture a stacktrace completa para registar o local exacto da excepção.

## Tabela: construções usadas (preenchida)
| Construção | Onde a usou | O que ficou a ser garantido | O que continua a não ser garantido |
|---|---|---|---|
| `ServerSocket` / `accept()` | `tarefa-3/src/tcp01/TCPServer.java` | Aceita ligações TCP; bloqueio até novo cliente | Não garante interpretação dos bytes (apenas transporte)
| `Connection extends Thread` | `tarefa-3/src/tcp01/Connection.java` | Permite atender múltiplos clientes em paralelo | Não limita número de threads; não gere recursos para 10k clientes
| `implements Serializable` | `Person`, `Place` em `tarefa-3/src/tcp01` | Permite serializar objectos Java automaticamente | Não garante compatibilidade se a classe muda sem `serialVersionUID`
| `ObjectOutputStream` / `ObjectInputStream` | Cliente escreve com OOS; Servidor lê com OIS | Serialização automática do grafo de objectos (Person+Place) | Não interoperável com outras linguagens; cabeçalho do OOS/ OIS pode bloquear se usado simetricamente sem cuidado
| `serialVersionUID` | Declarado em `Person` e `Place` | Controla compatibilidade entre versões serializáveis | Requer gestão manual quando se alteram fields incompativelmente
| Referência para `Place` | `Person.place` | `Place` é incluído automaticamente na serialização do `Person` | Não impede envio de campos sensíveis sem `transient` (fora do âmbito desta ficha)

## Provas e evidências (como demonstrar em avaliação oral)
- Mostrar código: a linha `new Socket(...)` no cliente (explicar `ConnectException` quando o servidor não está a escutar).
- Mostrar `ObjectOutputStream oos = new ObjectOutputStream(...)` no cliente e `ObjectInputStream ois = new ObjectInputStream(...)` no servidor; explicar que `readObject()` devolve `Object` e que é necessário fazer cast e tratar `ClassNotFoundException`.
- Executar variante `broken` e mostrar `InvalidClassException` no servidor como prova do papel do `serialVersionUID`.
- Remover `implements Serializable` de `Place` e mostrar `NotSerializableException` no cliente.

## Perguntas possíveis (para preparar respostas curtas)
- Por que é que `readObject()` devolve `Object` e porque temos de fazer cast? (Porque o stream é tipado de runtime; o receptor pode não conhecer a classe em compile-time.)
- O que acontece se ambos os lados criarem `ObjectInputStream` primeiro? (Deadlock — ambos bloqueiam à espera do cabeçalho do outro lado.)
- Por que declarar `serialVersionUID`? (Para controlar compatibilidade manualmente; o valor calculado automaticamente muda com pequenas alterações.)
- Como impedir que um campo (ex.: password) seja enviado? (Marcar `transient`, que impede a serialização desse campo — nota: fora do escopo desta ficha.)
- Pode um servidor em outra linguagem ler os objectos Java serializados? (Não — o formato é Java-specific; usar JSON/Protobuf/etc. para interoperabilidade.)

## Próximos passos sugeridos
- Executar os testes da secção 4.4 e registar as excepções reais na tabela de falhas.
- Preparar respostas orais para os Critérios de Aceitação (CA1–CA5). Posso gerar um guião de respostas com base no código.

## Respostas modelo para CA1–CA5 (resumo)
CA1 — Modelo TCP: o cliente escreve (writeUTF/writeObject) para o Socket; o SO fragmenta, retransmite e ordena os segmentos. O servidor lê (readUTF/readObject) da stream; leituras e accept() são bloqueantes. Operações bloqueantes a apontar: new Socket(...) (conexão), ServerSocket.accept(), readUTF()/readObject(). TCP garante integridade e ordem, não garante semântica.

CA2 — Concorrência: a classe Connection estende Thread; o listenSocket.accept() devolve um Socket por cliente, e Connection.start() cria uma nova thread que executa run(). Se usasses run() direct, o trabalho seria sequencial e outros clientes aguardariam; com 3 clientes existem pelo menos 4 threads (main + 3 connections), que terminam quando fecharem.

CA3 — Serialização: para enviar Person, ambas as partes precisam da classe tcp01.Person (mesmo pacote) e da definição serialVersionUID. Quem escreve falha com NotSerializableException se qualquer objecto do grafo não for Serializable. readObject() devolve Object e exige cast; ClassNotFoundException surge no leitor se a classe não existir.

CA4 — Dependências e versões: Place chega porque ObjectOutputStream percorre o grafo alcançável a partir de Person e serializa Place automaticamente. Se Place não for Serializable a escrita falha no cliente (NotSerializableException). Se serialVersionUID divergir, o leitor lança InvalidClassException ao desserializar.

CA5 — Limites: limites conhecidos: a serialização Java não é interoperável com outras linguagens; envia todo o grafo (risco de tamanho e de exposição de campos); thread-per-connection não escala a 10k clientes (consumo de memória/threads). Soluções não permitidas nesta ficha (p.ex. ExecutorService, JSON, Protobuf) seriam abordagens alternativas.

## 4.5 — Respostas propostas (reflexão crítica)
- Bytes intactos vs significado: TCP protege contra corrupção e perda; problemas observados (formato N,payload, versões incompatíveis) são da aplicação e têm de ser resolvidos pela aplicação (validação, contratos, versionamento).
- Distribuição de classes: em produção, cópias de Person espalhadas exigem coordenação; serialVersionUID serve para indicar compatibilidade intencional quando a classe muda.
- Risco do grafo: pode enviar objectos grandes e campos sensíveis inadvertidamente; usar transient (fora do âmbito) ou formatos explícitos para controle fino.
- Thread por ligação vs sequencial: a thread por ligação melhora latência/concurrency, mas aumenta uso de memória e contexto; para muitas ligações é preciso um pool ou I/O multiplexado.
- Quando preferir datagramas: aplicações tolerantes a perda e com requisitos baixos de latência e overhead (p.ex. telemetria de sensores em redes confiáveis) — quando o custo de estabelecer/gerir ligações é inaceitável.

---
Arquivo atualizado automaticamente — `tarefa-3/REPORT.md`.
