import { readFileSync } from 'node:fs';

/** Apply an explicit per-model budget without changing another model or inventing catalog fields. */
export function applyModelBudget(payload, model, budgets) {
  if (!model || !payload || typeof payload !== 'object' || Array.isArray(payload)) return payload;
  const key = `${model.provider}/${model.id}`;
  if (!Object.prototype.hasOwnProperty.call(budgets, key)) return payload;
  const budget = budgets[key];
  if (model.api !== 'anthropic-messages') {
    throw new Error('数值思考预算仅适用于 Message 接口');
  }
  if (!Number.isSafeInteger(budget) || budget < 1024) {
    throw new Error('思考预算必须是至少 1024 的整数');
  }
  const maximum = payload.max_tokens;
  if (!Number.isSafeInteger(maximum) || budget >= maximum) {
    throw new Error(`思考预算 ${budget} 必须小于当前请求的输出上限 ${maximum ?? '未设置'}`);
  }
  if (Number.isSafeInteger(model.maxTokens) && maximum > model.maxTokens) {
    throw new Error('当前请求的输出上限超过模型容量');
  }
  // Manual token budgets and adaptive effort are mutually exclusive on the Message wire.
  const result = { ...payload, thinking: { type: 'enabled', budget_tokens: budget } };
  if (result.output_config?.effort !== undefined) {
    const { effort, ...rest } = result.output_config;
    if (Object.keys(rest).length) result.output_config = rest;
    else delete result.output_config;
  }
  return result;
}

export default function modelPayloadExtension(pi) {
  pi.on('before_provider_request', (event, context) => {
    try {
      let budgets;
      try {
        budgets = JSON.parse(readFileSync(new URL('./wawlink-model-budgets.json', import.meta.url), 'utf8'));
      } catch (error) {
        if (error?.code === 'ENOENT') return event.payload;
        throw error;
      }
      if (!budgets || typeof budgets !== 'object' || Array.isArray(budgets)) {
        throw new Error('模型思考预算文件必须是键值表');
      }
      return applyModelBudget(event.payload, context.model, budgets);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      context.ui.notify(message, 'error');
      context.abort();
      throw error;
    }
  });
}
