# Publicar o servidor no Railway

Cria um serviço a partir deste repositório. O Dockerfile da raiz é detetado automaticamente e inclui apenas o servidor. Não é necessário compilar o Android no alojamento.

Configura no serviço:

- Healthcheck: `/health`, timeout 60 segundos.
- Reinício: `ON_FAILURE`, máximo 3 tentativas.
- Uma réplica: as decisões pendentes vivem na memória do processo.
- Watch paths: `/server/**`, `/Dockerfile`, `/.dockerignore`.
- Domínio HTTPS gerado pelo Railway, porta 8787.

Define variáveis apenas neste serviço:

```dotenv
HOST=0.0.0.0
PORT=8787
AI_PROVIDER=openai
OPENAI_MODEL=gpt-6-astra
OPENAI_WEB_SEARCH=1
REBORN_SETUP_MODE=1
```

Acrescenta `REBORN_ACCESS_TOKEN` com 32 bytes aleatórios em hexadecimal e `OPENAI_API_KEY` com uma chave válida da tua conta. Nunca coloques valores reais no GitHub. `MCP_SERVERS_JSON` pode usar o exemplo público de `server/.env.example`.

`REBORN_SETUP_MODE=1` permite publicar o servidor autenticado enquanto falta a chave OpenAI. Neste estado `/health` confirma o processo, `/v1/status` devolve `modelConfigured: false` e o chat devolve `503 provider_not_configured`, sem contactar fornecedores. Não existe uma resposta de IA simulada no servidor. Depois de guardares a chave e reiniciares o serviço, o estado passa a indicar credenciais configuradas; confirma o acesso real enviando uma mensagem. Podes então remover o modo de configuração.

Na APK, guarda o domínio HTTPS e o **código Reborn** (`REBORN_ACCESS_TOKEN`). A chave OpenAI fica exclusivamente no servidor. Quem reutilizar este código deve publicar o seu próprio serviço e definir o seu endereço na app.

As contas privadas de serviços MCP precisam de credenciais próprias. As autorizações dos conectores do ChatGPT não são incluídas nesta publicação.
