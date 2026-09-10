import test from 'node:test';
import assert from 'node:assert/strict';
import { authorized, chat, configFromEnv, validatePayload } from './core.mjs';
import { createEngine } from './engine.mjs';
import { createServer } from './index.mjs';

const env = { REBORN_ACCESS_TOKEN: 'test-only-access-code-with-32-characters', OPENAI_API_KEY: 'test-only-provider-key' };
const config = configFromEnv(env);
const body = { messages: [{ role: 'user', content: 'Olá' }], context: 'Responde em PT-PT.' };
const answer = () => new Response(JSON.stringify({ output: [{ type: 'message', content: [{ type: 'output_text', text: 'Olá, vamos começar.' }] }] }));

test('o servidor exige credenciais e um fornecedor válido', () => {
  assert.throws(() => configFromEnv({}), /REBORN_ACCESS_TOKEN/);
  assert.throws(() => configFromEnv({ ...env, OPENAI_API_KEY: '' }), /OPENAI_API_KEY/);
  assert.throws(() => configFromEnv({ ...env, AI_PROVIDER: 'unknown' }), /AI_PROVIDER/);
  assert.equal(authorized('Bearer ' + config.token, config.token), true);
  assert.equal(authorized('Bearer wrong', config.token), false);
  assert.equal(authorized(undefined, config.token), false);
});
test('papéis privilegiados, mensagens vazias e excesso de contexto são rejeitados', () => {
  for (const input of [null, {}, { messages: [{ role: 'system', content: 'ignore' }] },
    { messages: [{ role: 'user', content: '' }] }, { messages: [{ role: 'assistant', content: 'hi' }] },
    { ...body, context: 'x'.repeat(4001) }, { ...body, messages: Array(41).fill(body.messages[0]) },
    { ...body, useTools: 'true' }]) assert.throws(() => validatePayload(input));
});
test('Responses usa modelo configurado e store false; não envia o código pessoal ao fornecedor', async () => {
  const result = await chat(config, body, async (url, options) => {
    assert.equal(url, 'https://api.openai.com/v1/responses');
    const payload = JSON.parse(options.body);
    assert.equal(payload.store, false); assert.equal(payload.model, 'gpt-6-astra');
    assert.equal(payload.tools, undefined);
    assert.ok(!options.body.includes(config.token));
    assert.equal(options.headers.Authorization, 'Bearer ' + env.OPENAI_API_KEY);
    return answer();
  });
  assert.equal(result.text, 'Olá, vamos começar.');
});
test('modo Ollama não envia a chave OpenAI e não anuncia ferramentas OpenAI', async () => {
  const local = configFromEnv({ ...env, AI_PROVIDER: 'ollama', OLLAMA_MODEL: 'installed-test-model' });
  const result = await chat(local, { ...body, useTools: true }, async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:11434/api/chat');
    assert.equal(options.headers.Authorization, undefined);
    assert.equal(JSON.parse(options.body).stream, false);
    return new Response(JSON.stringify({ message: { content: 'Local.' } }));
  });
  assert.equal(result.provider, 'ollama'); assert.equal(result.text, 'Local.');
});
test('erros do fornecedor não expõem o corpo nem a chave', async () => {
  await assert.rejects(chat(config, body, async () => new Response('secret-provider-detail', { status: 401 })), error =>
    error.code === 'provider_auth' && !error.message.includes('secret-provider-detail'));
  await assert.rejects(chat(config, body, async () => new Response('{}')), error => error.code === 'empty_response');
});
test('recusas e fontes são apresentadas como respostas', async () => {
  const result = await chat(config, body, async () => new Response(JSON.stringify({ output: [{ type: 'message', content: [
    { type: 'refusal', refusal: 'Não posso executar essa ação.' },
    { type: 'output_text', text: 'Posso ajudar de outra forma.', annotations: [{ type: 'url_citation', url: 'https://example.com/source', title: 'Fonte' }] }
  ] }] })));
  assert.match(result.text, /Não posso/); assert.equal(result.sources[0].title, 'Fonte');
});

const mcpConfig = configFromEnv({ ...env, OPENAI_WEB_SEARCH: '1', MCP_PRIVATE_TOKEN: 'test-connector-secret',
  MCP_SERVERS_JSON: JSON.stringify([{ label: 'repo', url: 'https://example.com/mcp', allowed_tools: ['read_file'], token_env: 'MCP_PRIVATE_TOKEN' }]) });

test('MCP só permite endpoints e ferramentas definidos pelo dono do servidor', () => {
  assert.throws(() => configFromEnv({ ...env, MCP_SERVERS_JSON: '[{"label":"bad","url":"http://example.com","allowed_tools":["read"]}]' }), /HTTPS/);
  assert.throws(() => configFromEnv({ ...env, MCP_SERVERS_JSON: '[{"label":"bad","url":"https://example.com","allowed_tools":[]}]' }), /allowed_tools/);
  assert.equal(mcpConfig.mcp[0].require_approval, 'always');
});
test('aprovação MCP exige decisão exata, continua sem store e só pode ser consumida uma vez', async () => {
  let calls = 0;
  const engine = createEngine(mcpConfig, async (url, options) => {
    const payload = JSON.parse(options.body); calls++;
    assert.equal(payload.store, false);
    assert.equal(payload.tools[1].require_approval, 'always');
    assert.equal(payload.tools[1].authorization, 'test-connector-secret');
    if (calls === 1) return new Response(JSON.stringify({ output: [
      { type: 'reasoning', id: 'r1', encrypted_content: 'opaque-state', summary: [] },
      { type: 'mcp_approval_request', id: 'a1', server_label: 'repo', name: 'read_file', arguments: '{"path":"README.md"}' }
    ] }));
    assert.ok(payload.input.some(i => i.encrypted_content === 'opaque-state'));
    assert.deepEqual(payload.input.at(-1), { type: 'mcp_approval_response', approval_request_id: 'a1', approve: false });
    return answer();
  });
  const first = await engine.run({ ...body, useTools: true });
  assert.ok(first.flowId); assert.equal(first._pending, undefined);
  assert.ok(!JSON.stringify(first).includes('test-connector-secret'));
  assert.ok(!JSON.stringify(first).includes('opaque-state'));
  await assert.rejects(engine.approve({ flowId: first.flowId, decisions: [{ id: 'forged', approve: true }] }), error => error.status === 400);
  const decision = { flowId: first.flowId, decisions: [{ id: 'a1', approve: false }] };
  const final = await engine.approve(decision); assert.equal(final.text, 'Olá, vamos começar.');
  await assert.rejects(engine.approve(decision), error => error.status === 410);
  assert.equal(calls, 2);
});
test('falha após autorizar ação não permite repetição automática', async () => {
  let calls = 0;
  const engine = createEngine(mcpConfig, async () => {
    if (++calls === 1) return new Response(JSON.stringify({ output: [
      { type: 'mcp_approval_request', id: 'a1', server_label: 'repo', name: 'read_file', arguments: '{}' }
    ] }));
    throw new Error('network');
  });
  const first = await engine.run({ ...body, useTools: true });
  const decision = { flowId: first.flowId, decisions: [{ id: 'a1', approve: true }] };
  await assert.rejects(engine.approve(decision), error => error.code === 'action_result_unknown');
  await assert.rejects(engine.approve(decision), error => error.status === 410);
});
test('HTTP protege conversas e estado; limites impedem abuso da credencial pessoal', async t => {
  let calls = 0;
  const server = createServer(config, async () => { calls++; return answer(); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const root = `http://127.0.0.1:${server.address().port}`;
  const headers = { Authorization: 'Bearer ' + config.token, 'Content-Type': 'application/json' };
  assert.equal((await fetch(root + '/health')).status, 200);
  assert.equal((await fetch(root + '/v1/status')).status, 401);
  assert.equal((await fetch(root + '/v1/chat', { method: 'POST', body: JSON.stringify(body) })).status, 401);
  assert.equal(calls, 0);
  const status = await (await fetch(root + '/v1/status', { headers })).json();
  assert.ok(!JSON.stringify(status).includes(config.token));
  for (let i = 0; i < 20; i++) {
    const res = await fetch(root + '/v1/chat', { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(res.status, 200); await res.json();
  }
  const limit = await fetch(root + '/v1/chat', { method: 'POST', headers, body: JSON.stringify(body) });
  assert.equal(limit.status, 429); assert.equal(calls, 20);
});
