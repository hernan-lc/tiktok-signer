// Signing, offline. Needs the bundle, which is TikTok's and deliberately not vendored:
//
//   curl -s -o /tmp/webmssdk.js \
//     https://sf16-website-login.neutral.ttwstatic.com/obj/tiktok_web_login_static/webmssdk/1.0.0.388/webmssdk.js
//
// Without it these skip rather than fail, so a fresh clone can still run the suite offline.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { BOOTSTRAP_PATH, PRODUCT, Signer, loadBundle } from '../dist/signer.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BUNDLE = process.env.TTL_BUNDLE ?? '/tmp/webmssdk.js';
const URL_UNDER_TEST =
  'wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300000000000000001&aid=1988';

const bundle = fs.existsSync(BUNDLE) ? fs.readFileSync(BUNDLE, 'utf8') : null;
const bundleSource = bundle ?? undefined;
const needsBundle = { skip: bundle ? false : `no signing bundle at ${BUNDLE}` };

test('a downloaded bundle must match its expected digest', async () => {
  await assert.rejects(
    () => loadBundle({
      url: 'data:text/javascript,wrong',
      cachePath: path.join(os_tmpdir(), 'ttl-deliberately-invalid-bundle.js'),
      expectedSha256: '0'.repeat(64),
    }),
    /did not match its expected SHA-256/,
  );
});

test('a signed socket URL keeps its query and gains a signature', needsBundle, async () => {
  const signer = await Signer.create({ bundleSource, cookie: 'sessionid=test' });
  const signed = signer.sign(URL_UNDER_TEST, PRODUCT.ws);
  assert.ok(signed.startsWith(URL_UNDER_TEST), 'the signed bytes must be the bytes that were signed');
  assert.match(signed, /&X-Gnarly=/);
});

// `registerWsSigner` hands back the signer and deletes itself. A process that signs once and exits
// never notices; a connection that reconnects does, and the second signature fails. This is the
// regression that kept the Rust engine honest and it is the same driver here.
test('a warm signer keeps signing', needsBundle, async () => {
  const signer = await Signer.create({ bundleSource, cookie: 'sessionid=test' });
  const signatures = new Set();
  for (let round = 0; round < 10; round += 1) {
    signatures.add(signer.sign(URL_UNDER_TEST, PRODUCT.ws));
  }
  assert.equal(signatures.size, 10, 'each signature must differ, as it does in a browser');
});

// The frozen profile is what makes a differential mean anything: two runs must agree, and a live
// one must not agree with them.
test('pinning decides whether a signature repeats', needsBundle, async () => {
  const pinned = async () =>
    (await Signer.create({ bundleSource, pinned: true })).sign(URL_UNDER_TEST, PRODUCT.ws);
  assert.equal(await pinned(), await pinned());

  const live = await Signer.create({ bundleSource });
  assert.notEqual(live.sign(URL_UNDER_TEST, PRODUCT.ws), await pinned());
});

test('all three products sign', needsBundle, async () => {
  const signer = await Signer.create({ bundleSource, cookie: 'sessionid=test' });
  for (const product of Object.values(PRODUCT)) {
    const url = product === PRODUCT.ws
      ? URL_UNDER_TEST
      : 'https://webcast.tiktok.com/webcast/im/fetch/?aid=1988&room_id=7300000000000000001';
    assert.ok(signer.sign(url, product).length > url.length, product);
  }
});

// The sandbox is generated from `scripts/headless/shim.mjs`. Committing it is what lets this
// package be installed without the repository, but it also means an edit to the shim can leave
// this shipping last week's sandbox — signing correctly, and differently.
test('the shipped sandbox matches the shim it is generated from', async (t) => {
  const generator = path.join(HERE, '..', '..', '..', 'scripts', 'headless', 'tools', 'build-bootstrap.mjs');
  if (!fs.existsSync(generator)) {
    t.skip('not inside the repository');
    return;
  }
  const { execFileSync } = await import('node:child_process');
  const temporary = path.join(os_tmpdir(), 'ttl-bootstrap-freshness.js');
  execFileSync(process.execPath, [generator, temporary]);
  assert.equal(
    fs.readFileSync(temporary, 'utf8'),
    fs.readFileSync(BOOTSTRAP_PATH, 'utf8'),
    'vendor/bootstrap.js is stale — run `npm run sync-bootstrap`',
  );
  fs.rmSync(temporary, { force: true });
});

function os_tmpdir() {
  return process.env.TMPDIR ?? '/tmp';
}

test('shared compiled bootstrap preserves mutable context and identity isolation', async () => {
  const source = `window.byted_acrawler = {
    init: function() {},
    registerWsSigner: function() {
      let calls = 0;
      return function() { return {'X-Gnarly': document.cookie + '_' + (++calls)}; };
    }
  };`;
  const a = await Signer.create({ bundleSource: source, cookie: 'sessionid=first' });
  const b = await Signer.create({ bundleSource: source, cookie: 'sessionid=second' });
  const first = a.sign(URL_UNDER_TEST, PRODUCT.ws);
  const second = b.sign(URL_UNDER_TEST, PRODUCT.ws);
  assert.match(first, /first.*1/);
  assert.match(second, /second.*1/);
  assert.match(a.sign(URL_UNDER_TEST, PRODUCT.ws), /first.*2/);
});

test('embedded pending timer retention has an explicit capacity and supports cleanup', async () => {
  const vm = await import('node:vm');
  const context = vm.createContext({});
  new vm.Script(fs.readFileSync(BOOTSTRAP_PATH, 'utf8')).runInContext(context);
  vm.runInContext('for (let i = 0; i < 1024; i++) setTimeout(() => {}, 1000)', context);
  assert.equal(vm.runInContext('__ttlPendingTimers.size', context), 1024);
  assert.throws(() => vm.runInContext('setTimeout(() => {}, 1000)', context), /timer capacity exceeded/);
  vm.runInContext('clearTimeout(1); setTimeout(() => {}, 1000)', context);
  assert.equal(vm.runInContext('__ttlPendingTimers.size', context), 1024);
  vm.runInContext('for (const id of __ttlPendingTimers.keys()) clearTimeout(id)', context);
  assert.equal(vm.runInContext('__ttlPendingTimers.size', context), 0);
});

test('sandbox access diagnostics are bounded without changing property reads', async () => {
  const shimPath = path.resolve(HERE, '../../../scripts/headless/shim.mjs');
  const { createSandbox } = await import(shimPath);
  const sandbox = createSandbox();
  for (let i = 0; i < 5000; i++) assert.equal(sandbox.sandbox[`unknown${i}`], undefined);
  assert.equal(sandbox.accesses.size, 4096);
  assert.equal(sandbox.omittedAccesses, 904);
});

test('concurrent bundle loads share a download and release the flight after failure', async () => {
  const original = globalThis.fetch;
  let downloads = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  globalThis.fetch = async () => {
    downloads += 1;
    await gate;
    return new Response('test bundle');
  };
  const options = { url: 'https://example.invalid/bundle', cachePath: `/missing-dir/ttl-bundle-${process.pid}`, maxAgeMs: 0 };
  try {
    const first = loadBundle(options);
    const second = loadBundle(options);
    assert.equal(first, second);
    release();
    assert.equal(await first, 'test bundle');
    assert.equal(downloads, 1);
    globalThis.fetch = async () => { downloads += 1; throw new Error('offline failure'); };
    await assert.rejects(loadBundle(options), /offline failure/);
    await assert.rejects(loadBundle(options), /offline failure/);
    assert.equal(downloads, 3);
  } finally {
    globalThis.fetch = original;
  }
});
