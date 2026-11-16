# Sprint E2-01 — líder Discord

Java 17+, Maven e JDA 5.6.1. Todos os comandos apenas confirmam a receção:
não são criados registos, tokens de réplica, cifras, réplicas ou rajadas reais.

## Tarefas e estado

- [x] Projeto Maven, pacote `tpf`, classe `LeaderBot`.
- [x] Sete guild commands, validação e respostas provisórias.
- [x] Verificação do papel `admin-arquivo` nos quatro comandos administrativos.
- [x] Deteção provisória de mensagens no canal `arquivo`, ignorando bots e outros servidores/canais.
- [x] Exclusão de segredos e instruções de arranque.
- [ ] Criar/configurar aplicação, servidor, canal e papel no Discord.
- [ ] Convidar o bot e executar a verificação manual abaixo.
- [ ] Enviar a branch para o fork do grupo e abrir PR para avaliação.

## 1. Configurar o Discord

1. No [Developer Portal](https://discord.com/developers/applications), cria uma aplicação e o respetivo bot.
2. Em **Bot → Privileged Gateway Intents**, ativa **Message Content Intent**.
3. Gera o token e guarda-o num gestor de segredos ou numa variável de ambiente local. Nunca o coloques em código, commits, capturas de ecrã ou mensagens.
4. Cria o servidor de teste do grupo, o canal de texto `#arquivo` e o papel `admin-arquivo`; atribui o papel aos membros do grupo.
5. No gerador OAuth2, seleciona os scopes `bot` e `applications.commands` e as permissões **View Channels** e **Send Messages**. Não é necessária a permissão Administrator. Confirma também as permissões efetivas do canal `#arquivo`.
6. Abre o convite e escolhe o servidor de teste. Modelo (substitui o ID):

   `https://discord.com/oauth2/authorize?client_id=ID_DA_APLICACAO&scope=bot%20applications.commands&permissions=3072`

7. Ativa o modo de programador do Discord e copia o ID do servidor.

## 2. Arrancar

No PowerShell, define o ID e introduz o token sem o guardar no histórico:

```powershell
$env:DISCORD_GUILD_ID = 'ID_DO_SERVIDOR'
$segredo = Read-Host 'Token do bot' -AsSecureString
$env:DISCORD_TOKEN = [System.Net.NetworkCredential]::new('', $segredo).Password
Set-Location Sprints/E2-01/tpf-lider
mvn test
mvn compile exec:java
```

Se já estiveres na pasta `tpf-lider`, omite `Set-Location`. Também podes abrir
`pom.xml` no IntelliJ, escolher um JDK 17+ e definir estas variáveis na configuração
de execução de `tpf.LeaderBot`. Não partilhes essa configuração com o token.
O programa lê variáveis de ambiente; não carrega `.env` automaticamente.

O bot aparece online apenas enquanto o processo estiver em execução.
O arranque substitui a lista de comandos desta aplicação no servidor configurado:
usa uma aplicação dedicada ao sprint. Confirma a mensagem de sete comandos
registados antes de testar. Termina com Ctrl+C e remove o token da sessão:

```powershell
Remove-Item Env:DISCORD_TOKEN -ErrorAction SilentlyContinue
Remove-Variable segredo -ErrorAction SilentlyContinue
```

## 3. Comandos

| Comando | Argumentos | Papel necessário |
|---|---|---|
| `/registar` | `texto` obrigatório, não vazio | qualquer membro |
| `/estado` | nenhum | qualquer membro |
| `/config r` | `n` inteiro positivo | `admin-arquivo` |
| `/recuperar` | `de`, `ate` inteiros positivos; `de <= ate` | qualquer membro |
| `/replica-token` | nenhum | `admin-arquivo` |
| `/replica-remover` | `id` textual obrigatório, não vazio | `admin-arquivo` |
| `/rajada` | `n`, `intervaloms` inteiros positivos | `admin-arquivo` |

O Discord exige nomes de opções em minúsculas: `intervaloms` corresponde a
`intervaloMs` na issue. `/config r` é um subcomando com a opção `n`.
Os inteiros têm mínimo 1; este sprint assume identificadores de registo a partir
de 1. O ID da réplica é tratado como texto, sem assumir um formato ainda não especificado.
`/config r n:abc` é recusado pelo próprio Discord por usar uma opção INTEGER;
a aplicação também valida números e intervalos. Todas as respostas aos comandos
só são visíveis ao autor. O bot confirma imediatamente a receção com `deferReply`
antes de validar, e depois edita essa resposta. A consola indica o tempo de
confirmação e eventuais códigos de erro, sem registar argumentos ou segredos.
O papel é verificado em cada interação, pelo nome exato, sem exceção para o dono
do servidor. Respostas não provocam menções; textos muito longos são abreviados
para respeitar o limite de mensagens do Discord.

## 4. Verificação de aceitação no servidor

Executa e regista o resultado de cada passo; os testes locais não comprovam conectividade Discord.

- [ ] Bot online e sete comandos visíveis no autocomplete.
- [ ] `/registar texto:Olá`, `/estado`, `/config r n:3`, `/recuperar de:10 ate:20`, `/replica-token`, `/replica-remover id:replica-a`, `/rajada n:5 intervaloms:100`: todos respondem provisoriamente e ecoam os argumentos.
- [ ] `/config r n:abc` e valores zero/negativos recusados; `/recuperar de:20 ate:10` recebe erro claro.
- [ ] Com uma conta sem `admin-arquivo`, os quatro comandos administrativos são recusados; os três restantes continuam disponíveis. Volta a atribuir o papel e confirma o acesso.
- [ ] Mensagem humana em `#arquivo` recebe confirmação; mensagem noutro canal não recebe resposta. Mensagens de bots não desencadeiam respostas.
- [ ] Token e `master.key` ausentes dos ficheiros preparados para commit.

## 5. Submissão

Trabalha no fork do grupo e numa branch `E2-01`, atualizada a partir
de `upstream/master`. Revê o diff, executa `mvn test`, faz commit e push para
o fork e abre PR para `cunhaestgv/SD-2026-2027`, base `master`.
Não marques os testes Discord como concluídos sem os executar.

Referências: [issue #20](https://github.com/cunhaestgv/SD-2026-2027/issues/20),
[arranque JDA](https://jda.wiki/using-jda/getting-started/),
[interações JDA](https://jda.wiki/using-jda/interactions/).
