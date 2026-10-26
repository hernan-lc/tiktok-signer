// Resolve public runtime chunk maps without executing downloaded JavaScript.
function objectAt(source, start) {
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let at = start; at < source.length; at++) {
    const c = source[at];
    if (quote) {
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === quote) quote = '';
      continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return source.slice(start, at + 1);
  }
  return '';
}
function pairs(source) {
  const out = new Map();
  for (const match of source.matchAll(/(?:^|[,{])\s*(?:"([^"\\]+)"|([\w$]+))\s*:\s*"([^"\\]*)"/g)) {
    out.set(match[1] ?? match[2], match[3]);
  }
  return out;
}
function runtimeMaps(runtime) {
  const start = runtime.search(/\.u\s*=\s*(?:\([^)]*\)|[\w$]+)\s*=>/);
  if (start < 0) return { names: new Map(), hashes: new Map() };
  const end = runtime.indexOf('.miniCssF', start);
  const source = runtime.slice(start, end < 0 ? undefined : end);
  const names = new Map();
  let hashes = new Map();
  for (let at = source.indexOf('{'); at >= 0; at = source.indexOf('{', at + 1)) {
    const object = objectAt(source, at);
    if (!object) break;
    const entries = pairs(object);
    if (entries.size && [...entries.values()].every(value => /^[a-f0-9]{6,}$/i.test(value))) {
      hashes = entries;
      break; // JavaScript resolver first; never substitute CSS hashes.
    }
    for (const [key, value] of entries) names.set(key, value);
    at += object.length - 1;
  }
  return { names, hashes };
}
export function chunkUrl(base, runtime, id) {
  const { names, hashes } = runtimeMaps(runtime);
  const hash = hashes.get(String(id));
  if (!hash) return null;
  const name = names.get(String(id)) ?? String(id);
  if (name.includes('..') || !/^[\w/().$-]+$/.test(name)) return null;
  return `${base}/static/js/async/${name}.${hash}.js`;
}
export function roomPageUrl(base, manifest, runtime) {
  const legacy = manifest.match(/"(static\/js\/async\/[^"\\]*\.live\/page\.[0-9a-f]+\.js)"/i)?.[1];
  if (legacy) return `${base}/${legacy}`;
  const { names } = runtimeMaps(runtime);
  for (const [id, name] of names) {
    if (name.endsWith('.live/page') && manifest.includes(name)) return chunkUrl(base, runtime, id);
  }
  return null;
}
export function importedChunkIds(source) {
  return [...new Set([...source.matchAll(/\.e\(\s*(?:"([\w-]+)"|(\d+))\s*\)/g)].map(match => match[1] ?? match[2]))];
}

export function signingFacts(source) {
  if (/registerWsSigner\s*\(/.test(source)) {
    return {
      ws_signer: 'registerWsSigner',
      ws_sign_input: source.match(/\{"(X-MS-[A-Z]+)":\w+,"(X-MS-[A-Z]+)":""\}/)?.slice(1, 3) ?? [],
      ws_sign_output: source.includes('X-Gnarly') ? 'X-Gnarly' : null,
    };
  }
  const call = source.match(/\.frontierSign\s*\(\s*\{([^{}]*)\}/);
  if (call) {
    return {
      ws_signer: 'frontierSign',
      ws_sign_input: [...call[1].matchAll(/"([^"]+)"\s*:/g)].map(([, key]) => key),
      ws_sign_output: source.includes('X-Bogus') ? 'X-Bogus' : null,
    };
  }
  return { ws_signer: null, ws_sign_input: [], ws_sign_output: null };
}
