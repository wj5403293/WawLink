import test from 'node:test';
import assert from 'node:assert/strict';
import { applyModelBudget } from '../../jvmSharedMain/resources/wawlink-model-payload.mjs';

const model = { provider: 'private', id: 'claude', api: 'anthropic-messages', maxTokens: 16384 };

test('per-model budget changes the actual Message payload, preserving unrelated options', () => {
  const input = { model: 'claude', max_tokens: 8192, temperature: 0.3, thinking: { type: 'adaptive' }, output_config: { effort: 'high', format: 'json' } };
  const result = applyModelBudget(input, model, { 'private/claude': 4096 });
  assert.deepEqual(result.thinking, { type: 'enabled', budget_tokens: 4096 });
  assert.deepEqual(result.output_config, { format: 'json' });
  assert.equal(result.temperature, 0.3);
  assert.deepEqual(input.thinking, { type: 'adaptive' });
});

test('a different provider with the same model id retains its own payload', () => {
  const input = { max_tokens: 8192, thinking: { type: 'adaptive' } };
  assert.equal(applyModelBudget(input, { ...model, provider: 'other' }, { 'private/claude': 4096 }), input);
});

test('invalid budgets fail rather than silently clamp, drop, or send a different budget', () => {
  for (const budget of [0, 1023, 4096.5, 8192, 9000]) {
    assert.throws(() => applyModelBudget({ max_tokens: 8192 }, model, { 'private/claude': budget }));
  }
  assert.throws(() => applyModelBudget({ max_tokens: 8192 }, { ...model, api: 'openai-completions' }, { 'private/claude': 4096 }));
});

test('smallest supported budget and request boundary remain exact', () => {
  const result = applyModelBudget({ max_tokens: 1025 }, model, { 'private/claude': 1024 });
  assert.equal(result.thinking.budget_tokens, 1024);
  assert.equal(result.max_tokens, 1025);
});
