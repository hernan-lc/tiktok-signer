import assert from 'node:assert/strict';
import test from 'node:test';

import { TikTokImageSchema } from '../dist/gen/json/tiktok/image.js';
import { JsonValidationError, validateJson } from '../dist/json-validation.js';

test('the generated TikTok image schema asserts URI values', () => {
  const image = { url_list: ['https://p16.tiktokcdn.com/image.webp'] };
  assert.deepEqual(validateJson(image, TikTokImageSchema, 'TikTok image'), image);

  assert.throws(
    () => validateJson({ url_list: ['not-a-uri'] }, TikTokImageSchema, 'TikTok image'),
    (error: unknown) => error instanceof JsonValidationError
      && error.path === 'url_list[0]'
      && error.expected === 'a valid URI',
  );
});

test('unsupported runtime formats fail loudly', () => {
  assert.throws(
    () => validateJson('value', { type: 'string', format: 'email' }, 'test schema'),
    /Unsupported JSON Schema format: email/,
  );
});

test('boolean schemas are enforced, including local definitions', () => {
  assert.deepEqual(validateJson({ ok: true }, true, 'boolean schema'), { ok: true });
  assert.throws(
    () => validateJson('anything', false, 'boolean schema'),
    (error: unknown) => error instanceof JsonValidationError
      && error.path === '$'
      && error.expected === 'a value allowed by the schema',
  );

  const schema = {
    $defs: {
      enabled: { type: 'object', required: ['enabled'], properties: { enabled: { const: true } } },
    },
    $ref: '#/$defs/enabled',
  } as const;
  assert.deepEqual(validateJson({ enabled: true }, schema, 'definition'), { enabled: true });
  assert.throws(() => validateJson({ enabled: false }, schema, 'definition'), JsonValidationError);
});
