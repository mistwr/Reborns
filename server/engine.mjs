import { randomUUID } from 'node:crypto';
import { ApiError, chat } from './core.mjs';

// Estado efémero, só em RAM. Nunca devolver a continuação nem credenciais à app.
export function createEngine(config, fetchImpl = fetch) {
  const pending = new Map();
  function prune() { for (const [id, p] of pending) if (p.expires < Date.now()) pending.delete(id); }
  function present(result) {
    prune();
    const { _pending, ...publicResult } = result;
    if (_pending) {
      if (_pending.step > 6 || JSON.stringify(_pending).length > 2000000)
        throw new ApiError(422, 'tool_limit', 'Limite de ferramentas atingido. Nenhuma nova ação foi aprovada.');
      if (pending.size >= 20) throw new ApiError(429, 'pending_limit', 'Há demasiadas ações à espera de decisão.');
      const flowId = randomUUID();
      pending.set(flowId, { ..._pending, approvals: result.approvals, expires: Date.now() + 600000 });
      publicResult.flowId = flowId;
    }
    return publicResult;
  }
  return {
    async run(body, signal) { return present(await chat(config, body, fetchImpl, signal)); },
    async approve(body, signal) {
      prune();
      const p = pending.get(body?.flowId);
      if (!p) throw new ApiError(410, 'approval_expired', 'A ação expirou ou já foi decidida. Verifica o serviço antes de repetir o pedido.');
      if (!Array.isArray(body.decisions) || body.decisions.length !== p.approvals.length ||
          new Set(body.decisions.map(d => d?.id)).size !== p.approvals.length ||
          body.decisions.some(d => !p.approvals.some(a => a.id === d?.id) || typeof d.approve !== 'boolean'))
        throw new ApiError(400, 'invalid_decision', 'É necessária uma decisão para cada ação apresentada.');
      // Consumir antes da chamada: impede repetição da mesma aprovação.
      pending.delete(body.flowId);
      const input = [...p.input, ...body.decisions.map(d => ({ type: 'mcp_approval_response',
        approval_request_id: d.id, approve: d.approve }))];
      try { return present(await chat(config, null, fetchImpl, signal, { ...p, input })); }
      catch (error) {
        if (body.decisions.some(d => d.approve))
          throw new ApiError(502, 'action_result_unknown', 'Não foi possível confirmar o resultado. A ação pode ter sido executada. Verifica o serviço antes de repetir.');
        throw error;
      }
    },
    cancel(body) { pending.delete(body?.flowId); return { ok: true }; }
  };
}
