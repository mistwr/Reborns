import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { ApiError, authorized, configFromEnv } from './core.mjs';
import { createEngine } from './engine.mjs';

const MAX_BYTES = 280000;
function json(res, status, value) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(value));
}
async function readBody(req) {
  if (!(req.headers['content-type'] || '').toLowerCase().startsWith('application/json'))
    throw new ApiError(415, 'content_type', 'É necessário enviar JSON.');
  let bytes = 0;
  const chunks = [];
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > MAX_BYTES) throw new ApiError(413, 'too_large', 'Pedido demasiado grande.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new ApiError(400, 'invalid_json', 'JSON inválido.'); }
}

export function createServer(config, fetchImpl = fetch) {
  const engine = createEngine(config, fetchImpl);
  // Limite global para a única credencial pessoal; não depende de IPs encaminhados.
  let windowStart = Date.now(), requests = 0, inFlight = 0;
  return http.createServer({ maxHeaderSize: 8192, requestTimeout: 15000, headersTimeout: 10000 }, async (req, res) => {
    try {
      const path = req.url?.split('?')[0];
      if (req.method === 'GET' && path === '/health') return json(res, 200, { ok: true, service: 'reborn' });
      if (!authorized(req.headers.authorization, config.token))
        return json(res, 401, { error: 'unauthorized', message: 'Código de acesso incorreto ou em falta.' });
      if (req.method === 'GET' && path === '/v1/status') return json(res, 200, {
        ok: true, provider: config.provider, model: config.model,
        webSearch: config.provider === 'openai' && config.webSearch,
        connectors: config.provider === 'openai' ? (config.mcp || []).map(t => ({ name: t.server_label, url: t.server_url, tools: t.allowed_tools })) : [],
        message: 'Servidor configurado. Envia uma mensagem para testar o modelo.'
      });
      if (req.method !== 'POST' || !['/v1/chat', '/v1/approve', '/v1/cancel'].includes(path))
        return json(res, 404, { error: 'not_found', message: 'Rota não encontrada.' });
      if (Date.now() - windowStart >= 60000) { windowStart = Date.now(); requests = 0; }
      if (requests >= 20 || inFlight >= 2) {
        res.setHeader('Retry-After', '60');
        return json(res, 429, { error: 'rate_limit', message: 'Aguarda um momento antes de enviar mais mensagens.' });
      }
      requests++; inFlight++;
      const controller = new AbortController();
      const onClose = () => { if (!res.writableEnded) controller.abort(); };
      res.on('close', onClose);
      try {
        const body = await readBody(req);
        const result = path === '/v1/chat' ? await engine.run(body, controller.signal)
          : path === '/v1/approve' ? await engine.approve(body, controller.signal) : engine.cancel(body);
        json(res, 200, result);
      } finally { inFlight--; res.off('close', onClose); }
    } catch (error) {
      const known = error instanceof ApiError;
      json(res, known ? error.status : 500, { error: known ? error.code : 'internal_error',
        message: known ? error.message : 'O servidor não conseguiu processar o pedido.' });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const config = configFromEnv();
    const port = Number(process.env.PORT || 8787);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT inválida.');
    const server = createServer(config);
    server.listen(port, process.env.HOST || '127.0.0.1', () => console.log(`Reborn: servidor iniciado na porta ${port}.`));
    const stop = () => server.close(() => process.exit(0));
    process.on('SIGTERM', stop); process.on('SIGINT', stop);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
