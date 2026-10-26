// Reduce one captured inline-emote event to a shareable regression fixture.
// Retains emote counts, numeric metadata and text encoding lengths; replaces all identities/assets/text.
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { create, fromBinary, toBinary } from '@bufbuild/protobuf';
import { decodeBatch } from '../dist/frames.js';
import { decodeEvent, normalizeEmote, METHOD } from '../dist/events.js';
import { WebcastChatMessageSchema } from '../dist/gen/webcast/model/message/messages_pb.js';
import { EmoteModelSchema, EmoteWithIndexSchema } from '../dist/gen/webcast/model/base/emoji_pb.js';
import { ImageModelSchema } from '../dist/gen/webcast/model/base/messages_pb.js';
import { UserSchema } from '../dist/gen/webcast/model/base/user_2_pb.js';
const [input, indexText, output] = process.argv.slice(2);
if (!input || !indexText || !output) throw new Error('usage: sanitize-emote-fixture.mjs CAPTURE_BATCH EVENT_INDEX OUTPUT_PREFIX');
const original = fs.readFileSync(input);
const event = decodeBatch(original).messages[Number(indexText)];
if (!event || event.method !== METHOD.chat) throw new Error('expected captured chat');
const source = fromBinary(WebcastChatMessageSchema, event.payload);
if (!source.emotes.length) throw new Error('no wire emotes');
const originalNormalized = decodeEvent(METHOD.chat, event.payload);
if (originalNormalized.type !== 'chat' || originalNormalized.emotes?.length !== source.emotes.length) throw new Error('choose an event whose raw emotes all normalize successfully');
const content = [...source.content].map(char => {
  if (/\s/u.test(char)) return char;
  return ['','x','é','★','🟦'][Buffer.byteLength(char)];
}).join('');
const sanitized = create(WebcastChatMessageSchema, {
  user: create(UserSchema, { id: 1n, nickname: 'fixture', displayId: 'fixture' }), content,
  emotes: source.emotes.map((entry, index) => create(EmoteWithIndexSchema, {
    index: entry.index,
    emote: entry.emote ? create(EmoteModelSchema, {
      emoteId: entry.emote.emoteId ? `fixture-emote-${index + 1}` : '',
      emoteType: entry.emote.emoteType,
      packageId: entry.emote.packageId ? `fixture-package-${index + 1}` : '',
      image: entry.emote.image ? create(ImageModelSchema, {
        urlList: entry.emote.image.urlList.map(url => normalizeEmote(create(EmoteModelSchema, { emoteId: 'fixture', image: create(ImageModelSchema, { urlList: [url] }) }))?.imageUrl ? 'https://fixture.invalid/emote.webp' : 'invalid-url'),
      }) : undefined,
    }) : undefined,
  })),
});
const bytes = toBinary(WebcastChatMessageSchema, sanitized);
const normalized = decodeEvent(METHOD.chat, bytes);
if (normalized.type !== 'chat' || normalized.emotes?.length !== source.emotes.length) throw new Error('sanitization changed usable emote count');
fs.writeFileSync(output + '.pb', bytes, { flag: 'wx' });
fs.writeFileSync(output + '.json', JSON.stringify(normalized, null, 2) + '\n', { flag: 'wx' });
fs.writeFileSync(output + '.provenance.json', JSON.stringify({
  origin: 'Reduced and sanitized from a real LIVE capture',
  capture_date_utc: fs.statSync(input).mtime.toISOString().slice(0, 10),
  original_batch_sha256: createHash('sha256').update(original).digest('hex'),
  original_event_index: Number(indexText),
  preserved: ['emote count', 'raw index', 'raw emote type', 'presence of id/image/package', 'content UTF-8 and UTF-16 lengths'],
  removed: ['common metadata', 'participant identities', 'chat text', 'asset identifiers and URLs', 'unrelated nested fields'],
}, null, 2) + '\n', { flag: 'wx' });
