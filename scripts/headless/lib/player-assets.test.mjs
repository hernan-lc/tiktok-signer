import test from 'node:test';
import assert from 'node:assert/strict';
import { chunkUrl, roomPageUrl, importedChunkIds } from './player-assets.mjs';
const base = 'https://cdn.example/app';
const runtime = 'o.u=e=>"static/js/async/"+(({tp:"main___live-container/(anchorName).live/page",un:"npm-xgplayer"})[e]||e)+"."+({tp:"abcdef1234",un:"0123456789","7x":"0000000000",653:"1111111111"})[e]+".js",o.miniCssF=e=>({tp:"badbadbad0"})[e]';
test('modern route IDs resolve named paths with JavaScript hashes', () => {
  assert.equal(roomPageUrl(base, '{"routeAssets":{"main___live-container/(anchorName).live/page":{"chunkIds":["un","tp"]}}}', runtime), base+'/static/js/async/main___live-container/(anchorName).live/page.abcdef1234.js');
  assert.equal(chunkUrl(base,runtime,'un'), base+'/static/js/async/npm-xgplayer.0123456789.js');
  assert.equal(chunkUrl(base,runtime,'7x'), base+'/static/js/async/7x.0000000000.js');
  assert.equal(chunkUrl(base,runtime,'653'), base+'/static/js/async/653.1111111111.js');
});
test('legacy room asset URLs continue to resolve', () => {
  assert.equal(roomPageUrl(base,'["static/js/async/main.(user).live/page.abcdef.js"]', ''), base+'/static/js/async/main.(user).live/page.abcdef.js');
});
test('missing mappings do not fabricate paths', () => {
  assert.equal(chunkUrl(base,runtime,'missing'),null);
  assert.equal(roomPageUrl(base,'{}',runtime),null);
});
test('imports support mixed numeric and alphanumeric chunk IDs', () => {
  assert.deepEqual(importedChunkIds('r.e("abc"),r.e(653),r.e("abc"),r.e("5-z")'),['abc','653','5-z']);
});

import { signingFacts } from './player-assets.mjs';
test('signing drift is extracted explicitly for both observed player generations', () => {
  assert.deepEqual(signingFacts('s.registerWsSigner();s({"X-MS-Q":q,"X-MS-STUB":""})["X-Gnarly"]'), {
    ws_signer: 'registerWsSigner', ws_sign_input: ['X-MS-Q','X-MS-STUB'], ws_sign_output: 'X-Gnarly',
  });
  assert.deepEqual(signingFacts('t.frontierSign({"X-MS-PAYLOAD":""})["X-Bogus"]'), {
    ws_signer: 'frontierSign', ws_sign_input: ['X-MS-PAYLOAD'], ws_sign_output: 'X-Bogus',
  });
  assert.deepEqual(signingFacts('unrecognized()'), {ws_signer:null,ws_sign_input:[],ws_sign_output:null});
});
