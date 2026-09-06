#!/usr/bin/env node

// Generate TypeScript types and embedded runtime schemas from the committed JSON Schema files.
// This intentionally implements the small JSON Schema subset used by this package so the package
// does not need a runtime code-generation dependency just to build its contracts.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SCHEMA_ROOT = path.join(PACKAGE_ROOT, 'schema', 'json');
const OUTPUT_ROOT = path.join(PACKAGE_ROOT, 'src', 'gen', 'json');

const documents = new Map();

function walk(directory) {
  const entries = fs.readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    left.name.localeCompare(right.name));
  const files = [];
  for (const entry of entries) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...walk(fullPath));
    else if (entry.isFile() && entry.name.endsWith('.schema.json')) files.push(fullPath);
  }
  return files;
}

function readDocument(filePath) {
  const absolute = path.resolve(filePath);
  let document = documents.get(absolute);
  if (!document) {
    document = JSON.parse(fs.readFileSync(absolute, 'utf8'));
    documents.set(absolute, document);
  }
  return document;
}

function pointer(document, fragment) {
  if (!fragment || fragment === '#') return document;
  if (!fragment.startsWith('#/')) throw new Error(`unsupported JSON Schema reference: ${fragment}`);
  return fragment.slice(2).split('/').reduce((value, segment) => {
    const key = segment.replaceAll('~1', '/').replaceAll('~0', '~');
    if (!value || typeof value !== 'object' || !(key in value)) {
      throw new Error(`JSON Schema reference does not exist: ${fragment}`);
    }
    return value[key];
  }, document);
}

function resolveReference(reference, fromFile, stack) {
  const [filePart, fragment = ''] = reference.split('#');
  const targetFile = filePart ? path.resolve(path.dirname(fromFile), filePart) : fromFile;
  const target = pointer(readDocument(targetFile), fragment ? `#${fragment}` : '#');
  const marker = `${targetFile}#${fragment}`;
  if (stack.has(marker)) throw new Error(`cyclic JSON Schema reference: ${reference}`);
  const nextStack = new Set(stack);
  nextStack.add(marker);
  return resolveNode(target, targetFile, nextStack);
}

function resolveNode(node, fromFile, stack = new Set()) {
  if (Array.isArray(node)) return node.map((item) => resolveNode(item, fromFile, stack));
  if (!node || typeof node !== 'object') return node;
  if (typeof node.$ref === 'string') return resolveReference(node.$ref, fromFile, stack);

  const resolved = {};
  for (const [key, value] of Object.entries(node)) {
    resolved[key] = resolveNode(value, fromFile, stack);
  }
  return resolved;
}

function pascalCase(value) {
  return value
    .replace(/\.schema\.json$/, '')
    .split(/[^a-zA-Z0-9]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join('');
}

function typeName(document, filePath) {
  return document['x-typescript-name'] || pascalCase(path.basename(filePath));
}

function propertyName(name) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) ? name : JSON.stringify(name);
}

function literal(value) {
  return JSON.stringify(value);
}

function typeFor(schema, level = 0) {
  if (!schema || typeof schema !== 'object') return 'unknown';
  if (typeof schema['x-typescript-type'] === 'string') return schema['x-typescript-type'];
  if (schema.const !== undefined) return literal(schema.const);
  if (Array.isArray(schema.enum)) return schema.enum.map(literal).join(' | ') || 'never';

  if (Array.isArray(schema.anyOf) || Array.isArray(schema.oneOf)) {
    const branches = schema.anyOf || schema.oneOf;
    return branches.map((branch) => typeFor(branch, level)).join(' | ') || 'never';
  }
  if (Array.isArray(schema.allOf)) {
    return schema.allOf.map((branch) => typeFor(branch, level)).join(' & ') || 'unknown';
  }

  const kind = Array.isArray(schema.type) ? schema.type : [schema.type];
  if (kind.length > 1) return kind.map((item) => typeFor({ ...schema, type: item }, level)).join(' | ');
  switch (kind[0]) {
    case 'null': return 'null';
    case 'boolean': return 'boolean';
    case 'integer':
    case 'number': return 'number';
    case 'string': return 'string';
    case 'array': return `Array<${typeFor(schema.items, level + 1)}>`;
    case 'object': {
      const properties = schema.properties && typeof schema.properties === 'object'
        ? Object.entries(schema.properties)
        : [];
      const required = new Set(Array.isArray(schema.required) ? schema.required : []);
      const indent = '  '.repeat(level);
      const childIndent = '  '.repeat(level + 1);
      const lines = ['{'];
      for (const [name, value] of properties) {
        lines.push(`${childIndent}${propertyName(name)}${required.has(name) ? '' : '?'}: ${typeFor(value, level + 1)};`);
      }
      if (schema.additionalProperties === true && schema['x-typescript-no-index'] !== true) {
        lines.push(`${childIndent}[key: string]: unknown;`);
      }
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
        lines.push(`${childIndent}[key: string]: ${typeFor(schema.additionalProperties, level + 1)};`);
      }
      lines.push(`${indent}}`);
      return lines.join('\n');
    }
    default: return schema.properties ? typeFor({ ...schema, type: 'object' }, level) : 'unknown';
  }
}

function removeGeneratedFiles(directory) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) removeGeneratedFiles(fullPath);
    else if (entry.isFile() && entry.name.endsWith('.ts')) fs.rmSync(fullPath);
  }
}

function generate(filePath) {
  const document = readDocument(filePath);
  const resolved = resolveNode(document, filePath);
  const name = typeName(document, filePath);
  const relativeSchema = path.relative(SCHEMA_ROOT, filePath).replaceAll(path.sep, '/');
  const relativeOutput = relativeSchema.replace(/\.schema\.json$/, '.ts');
  const outputPath = path.join(OUTPUT_ROOT, relativeOutput);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  const source = [
    '// Generated file. Do not edit manually.',
    `// Source: schema/json/${relativeSchema}`,
    '',
    `export type ${name} = ${typeFor(resolved)};`,
    '',
    `export const ${name}Schema = ${JSON.stringify(resolved, null, 2)} as const;`,
    '',
  ].join('\n');
  fs.writeFileSync(outputPath, source);
}

fs.mkdirSync(OUTPUT_ROOT, { recursive: true });
removeGeneratedFiles(OUTPUT_ROOT);
for (const filePath of walk(SCHEMA_ROOT)) {
  if (path.basename(filePath) === 'common.schema.json') continue;
  generate(filePath);
}
