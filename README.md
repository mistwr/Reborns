# Reborn — assistente pessoal para Android

Uma APK tua, com código aberto: conversa, contexto pessoal, voz do Android e acesso opcional a ferramentas. Português de Portugal por defeito. Licença MIT para o código deste projeto.

[Descarregar versões da APK](https://github.com/mistwr/Reborns/releases) · [Compilações e testes](https://github.com/mistwr/Reborns/actions/workflows/android.yml)

## O que esta versão faz

| Capacidade | Implementação |
| --- | --- |
| Chat | OpenAI Responses API, `gpt-6-astra` por defeito; modelo alterável no servidor |
| Modelo alternativo | Ollama a correr num computador/servidor com um modelo instalado |
| Contexto pessoal | Campo editável nas Definições, enviado com cada pedido |
| Histórico | Até 30 conversas, 200 mensagens por conversa; cifrado no Android Keystore |
| Contexto de conversa | Até 40 mensagens recentes / 60 000 caracteres por pedido |
| Voz | Ditado e leitura através do Android; disponibilidade/qualidade depende do serviço instalado |
| Pesquisa web | Ferramenta OpenAI opcional, com fontes clicáveis |
| Conectores | MCP remoto, lista de ferramentas definida no servidor e decisões explícitas na APK |
| Exportação | Guardar a conversa num ficheiro de texto escolhido pelo utilizador |

O projeto não inclui os pesos GPT, não converte o GPT em software open source e não transfere automaticamente a sessão, memórias ou credenciais do ChatGPT. Os modelos e os serviços externos mantêm as suas próprias licenças e condições. Não há controlo físico do dispositivo, execução local de código, voz Realtime, upload de imagens ou autenticação OAuth integrada nesta primeira versão.

## Ligar o assistente

1. Descarrega a APK na página **Releases** e instala-a no Android.
2. Num computador ou servidor com Node.js 22, prepara o servidor:

   ```sh
   cd server
   cp .env.example .env
   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
   ```

3. Edita `.env`: coloca a chave da tua conta API em `OPENAI_API_KEY` e o código acabado de gerar em `REBORN_ACCESS_TOKEN`. Usa `OPENAI_MODEL` para escolher um modelo ao qual a conta tem acesso. Nunca publiques este ficheiro nem coloques a chave OpenAI na APK.
4. Executa `npm start`. O servidor escuta em `127.0.0.1:8787`. Disponibiliza-o num endereço HTTPS com certificado válido, através do teu alojamento ou de um proxy HTTPS. Em Docker, usa o Dockerfile incluído e fornece as variáveis de ambiente pelo gestor de segredos do alojamento.
5. Na app: **Configurar Reborn** → endereço HTTPS do servidor e **código de acesso Reborn** → **Guardar e verificar**. O código é `REBORN_ACCESS_TOKEN`, não a chave OpenAI.
6. Envia uma mensagem curta. A verificação de ligação confirma o servidor; só a mensagem confirma o acesso real ao modelo.

O uso da API é cobrado pela conta do fornecedor e não usa a sessão/assinatura da aplicação ChatGPT. Sem servidor e credenciais, a APK abre e permite configuração, mas não gera respostas.

### Usar Ollama

Instala um modelo que caiba no teu computador e define:

```dotenv
AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=nome-exato-do-modelo-instalado
REBORN_ACCESS_TOKEN=o-teu-codigo-privado-com-pelo-menos-32-caracteres
```

Neste modo não é necessária chave OpenAI. A APK continua a ligar-se ao servidor Reborn por HTTPS; o modelo corre no computador, não dentro do telemóvel. Pesquisa OpenAI e MCP via Responses estão disponíveis apenas no modo OpenAI. O exemplo HTTP do Ollama destina-se à ligação interna no próprio computador; não exponhas a porta Ollama diretamente à internet.

## Ferramentas e conectores

O interruptor **Ferramentas** vem desligado. Quando o ligas, a app permite que o servidor disponibilize ao modelo as ferramentas configuradas. O exemplo `.env` inclui pesquisa web e o MCP público DeepWiki para documentação de repositórios. São opções de configuração; a distribuição não contém contas ou credenciais ativas.

Para ligar outro serviço MCP, define `MCP_SERVERS_JSON` no servidor:

```json
[
  {
    "label": "github",
    "url": "https://api.githubcopilot.com/mcp/",
    "allowed_tools": ["get_file_contents", "search_repositories"],
    "token_env": "MCP_GITHUB_TOKEN"
  }
]
```

Define `MCP_GITHUB_TOKEN` no servidor com autorização própria para os repositórios pretendidos. As credenciais do conector GitHub de outra aplicação não são importadas. Os nomes das ferramentas têm de corresponder aos expostos pelo servidor MCP. Acrescenta ferramentas de escrita à lista apenas quando as quiseres disponibilizar.

Cada chamada MCP devolve uma proposta à APK. **Rever ações** mostra serviço, ferramenta e argumentos; podes autorizar ou recusar cada uma. As decisões são consumidas uma única vez. As propostas expiram após dez minutos ou ao reiniciar o servidor. Se a ligação falhar depois de aprovares uma ação, verifica o serviço: a app não a repete automaticamente. O serviço MCP pode receber os argumentos autorizados; os seus dados e disponibilidade são responsabilidade desse fornecedor.

## Compilar

Requisitos: JDK 17, Android SDK 35 e internet para descarregar as dependências.

```sh
./gradlew :app:assembleDebug :app:lintDebug
cd server
npm test
```

A APK fica em `app/build/outputs/apk/debug/app-debug.apk`. O Gradle Wrapper está incluído e verifica o SHA-256 da distribuição Gradle 8.11.1. O workflow GitHub compila, testa e publica uma pré-versão instalável em cada atualização de `main`.

**Assinatura:** a pré-versão usa uma chave debug de compilação. Para manter atualizações sem desinstalar a app, é necessário configurar uma chave de assinatura privada estável. Desinstalar elimina o histórico local; exporta-o antes.

## Estrutura

| Caminho | Responsabilidade |
| --- | --- |
| `app/` | APK Java nativa, sem WebView e sem bibliotecas de execução externas |
| `server/core.mjs` | Pedidos OpenAI/Ollama, validação e configuração |
| `server/engine.mjs` | Estado efémero e decisões MCP |
| `server/index.mjs` | API HTTP autenticada e limites de utilização |
| `scripts/android_smoke.py` | Instalação, arranque, definições e rotação no emulador |
| `.github/workflows/android.yml` | Compilação, verificações e publicação da APK |

## Privacidade e limites

- O histórico, contexto e código de acesso ficam cifrados no armazenamento privado; cópias automáticas e transferências de dados do Android estão excluídas.
- As mensagens e o contexto são enviados ao servidor escolhido e ao fornecedor configurado. `store: false` desativa a persistência das respostas na API; não equivale a prometer retenção zero de todos os registos do fornecedor.
- O servidor não grava conversas em disco nem regista chaves. Durante uma aprovação guarda temporariamente a continuação em memória. Usa uma única credencial pessoal, até 20 pedidos/minuto e duas operações simultâneas por instância; não é um serviço multiutilizador.
- O ditado pode enviar áudio ao serviço de reconhecimento escolhido no Android. A app não recolhe chamadas telefónicas nem fica a ouvir em segundo plano.
- Os testes usam respostas simuladas para não gastar saldo nem executar ações nas contas. A utilização real tem de ser validada com a configuração do dono do servidor.

## Próximas etapas

Login próprio e OAuth para serviços; imagens/ficheiros; voz Realtime; memória com seleção e eliminação; execução de tarefas em ambientes isolados; integração opcional com hardware. Cada etapa precisa da sua implementação e autorização. Não são capacidades incluídas nesta versão.

## Documentação de referência

- [OpenAI: GPT-6 Astra](https://developers.openai.com/api/docs/guides/latest-model)
- [OpenAI: Responses e geração de texto](https://developers.openai.com/api/docs/guides/text)
- [OpenAI: MCP e conectores](https://developers.openai.com/api/docs/guides/tools-connectors-mcp)
- [OpenAI: estado de raciocínio sem respostas guardadas](https://developers.openai.com/api/docs/guides/reasoning)
- [Ollama: API de conversa](https://docs.ollama.com/api/chat)
- [Android: compatibilidade AGP 8.9](https://developer.android.com/build/releases/agp-8-9-0-release-notes)

O código original deste projeto usa a licença MIT. O Gradle Wrapper incluído é do projeto Gradle, com licença Apache 2.0; as bibliotecas e os serviços externos conservam as suas licenças.
