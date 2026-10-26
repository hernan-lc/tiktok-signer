// Independent Node/Rust comparison on private real batches. No participant content in output.
// cargo run -p ttl-live-events --example audit-replay -- CAPTURE_DIR PRIVATE_JSONL
// node packages/tiktok-live/scripts/audit-replay.mjs CAPTURE_DIR PRIVATE_JSONL
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { decodeBatch } from '../dist/frames.js';
import { decodeEvent } from '../dist/events.js';
import { validateJson } from '../dist/json-validation.js';
import { ChatEventSchema } from '../dist/gen/json/public/chat-event.js';
import { EmoteChatEventSchema } from '../dist/gen/json/public/emote-chat-event.js';
import { GiftEventSchema } from '../dist/gen/json/public/gift-event.js';
import { LikeEventSchema } from '../dist/gen/json/public/like-event.js';
import { MemberEventSchema } from '../dist/gen/json/public/member-event.js';
import { SocialEventSchema } from '../dist/gen/json/public/social-event.js';
import { RoomUserEventSchema } from '../dist/gen/json/public/room-user-event.js';
const schemas = { chat: ChatEventSchema, emoteChat: EmoteChatEventSchema, gift: GiftEventSchema,
  like: LikeEventSchema, member: MemberEventSchema, social: SocialEventSchema, roomUser: RoomUserEventSchema };
let schemaChecks = 0;

const [directory, rustOutput] = process.argv.slice(2);
if (!directory || !rustOutput) throw new Error('usage: audit-replay.mjs CAPTURE_DIR RUST_JSONL');
const records = readFileSync(rustOutput, 'utf8').trim().split('\n').map(line => JSON.parse(line));
let count = 0;
const failures = [];
const types = {};
function compare(label, rust, node, location) {
  if (!isDeepStrictEqual(rust, node)) failures.push({ location, field: label });
}
// Rust's precision-safe JSON emits numeric scalars as strings. Preserve presence
// and values while comparing the two declared numeric emote fields consistently.
function projectEmotes(items = []) {
  return items.map(item => ({ ...item,
    ...(Object.hasOwn(item, 'index') ? { index: String(item.index) } : {}),
    ...(Object.hasOwn(item, 'emoteType') ? { emoteType: String(item.emoteType) } : {}),
  }));
}
function user(rust, node, location) {
  for (const [left, right] of Object.entries({ id: 'userId', nickname: 'nickname', unique_id: 'uniqueId', sec_uid: 'secUid' })) {
    compare(`user.${left}`, rust[left], node[right], location);
  }
  compare('user.avatar', rust.avatar_url ?? '', node.avatarUrl ?? '', location);
}
for (const filename of readdirSync(directory).filter(name => /^batch-.*\.pb$/.test(name)).sort()) {
  const batch = decodeBatch(readFileSync(join(directory, filename)));
  for (const [index, message] of batch.messages.entries()) {
    const record = records[count++];
    const location = `${filename}:${index}`;
    if (!record) throw new Error(`missing Rust record at ${location}`);
    compare('order', [record.batch, record.index], [filename, index], location);
    compare('method', record.method, message.method, location);
    compare('message_id', record.message_id, message.msgId.toString(), location);
    compare('history', record.history, message.isHistory, location);
    const event = decodeEvent(message.method, message.payload);
    if (schemas[event.type]) {
      try { validateJson(event, schemas[event.type], 'captured normalized event'); schemaChecks++; }
      catch (error) { failures.push({ location, field: `json_schema:${error.path ?? 'unknown'}` }); }
    }
    const rust = record.normalized;
    const type = { roomUser: 'room_user', emoteChat: 'emote_chat' }[event.type] ?? event.type;
    types[type] = (types[type] ?? 0) + 1;
    compare('type', rust?.type ?? 'unknown', type, location);
    if (!rust || type === 'unknown') continue;
    if (rust.user) user(rust.user, event.user, location);
    const fields = {
      chat: { comment: 'comment' },
      gift: { gift_id: 'giftId', gift_name: 'giftName', diamond_count: 'diamondCount', repeat_count: 'repeatCount', combo_count: 'comboCount', group_id: 'groupId', repeat_end: 'repeatEnd' },
      like: { count: 'count', total: 'total' },
      member: { member_count: 'memberCount', action: 'action' },
      social: { action: 'action', follow_count: 'followCount', share_count: 'shareCount', share_type: 'shareType', share_target: 'shareTarget', share_display_style: 'shareDisplayStyle', follow_type: 'followType', scene: 'scene', target_user_id: 'targetUserId' },
      room_user: { total: 'viewers', popularity: 'popularity', total_user: 'totalUser', anonymous: 'anonymous' },
    }[type] ?? {};
    for (const [left, right] of Object.entries(fields)) compare(left, String(rust[left]), String(event[right]), location);
    if (type === 'chat' || type === 'emote_chat') compare('emotes', projectEmotes(rust.emotes), projectEmotes(event.emotes), location);
    if (type === 'room_user') {
      for (const [left, right] of [['top_viewers', 'topViewers'], ['ranked_viewers', 'rankedViewers']]) {
        compare(left + '.length', rust[left].length, event[right].length, location);
        for (const [rankIndex, rank] of rust[left].entries()) {
          const node = event[right][rankIndex];
          if (!node) continue;
          user(rank.user, node.user, location);
          for (const name of ['rank', 'score', 'delta']) compare(`${left}.${name}`, String(rank[name]), String(node[name]), location);
        }
      }
    }
  }
}
compare('total_records', records.length, count, 'corpus');
console.log(JSON.stringify({ events: count, types, schema_checks: schemaChecks, failures: failures.slice(0, 100), failure_count: failures.length, scope: 'shared normalized fields and metadata; no gift recipient/artwork parity' }, null, 2));
if (failures.length) process.exitCode = 1;
