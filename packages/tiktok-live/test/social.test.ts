// Social classification: the `action` -> kind table is shared with Rust and
// Java through `fixtures/events/social-classification.json`, and the share
// fixture is decoded through this package's own normaliser so a regression in
// either the table or the decoder fails here.

import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';

import { create, toBinary } from '@bufbuild/protobuf';

import { EVENT, METHOD, SOCIAL_ACTION, classifySocialAction, decodeEvent } from '../dist/events.js';
import { WebcastMemberMessageSchema } from '../dist/gen/webcast/model/message/messages_pb.js';

const fixtures = new URL('../../../fixtures/events/', import.meta.url);

function fixture(name: string): Uint8Array {
  return new Uint8Array(fs.readFileSync(new URL(name, fixtures)));
}

test('the classification table matches the shared golden', () => {
  const { cases } = JSON.parse(
    fs.readFileSync(new URL('social-classification.json', fixtures), 'utf8'),
  ) as { cases: { action: number; kind: string; confidence: string }[] };
  assert.ok(cases.length > 0, 'fixture must contain at least one case');

  for (const example of cases) {
    const classification = classifySocialAction(example.action);
    assert.equal(classification.kind, example.kind, `kind for action ${example.action}`);
    assert.equal(
      classification.confidence,
      example.confidence,
      `confidence for action ${example.action}`,
    );
    assert.equal(classification.rawAction, example.action);
  }
});

test('only the confirmed actions are classified as follow or share', () => {
  assert.equal(SOCIAL_ACTION.follow, 1);
  assert.equal(SOCIAL_ACTION.share, 3);
  for (const action of [-1, 0, 2, 4, 5, 8, 97, 1000]) {
    const { kind } = classifySocialAction(action);
    assert.ok(kind === 'other' || kind === 'unknown', `action ${action} became ${kind}`);
  }
});

test('a share with a cumulative follow count stays a share', () => {
  const event = decodeEvent(METHOD.social, fixture('share.pb'));
  if (event.type !== EVENT.social) assert.fail(`expected social, got ${event.type}`);
  assert.equal(event.action, SOCIAL_ACTION.share);
  // The wire carries the room's running follower total on share messages too;
  // classifying by "the first positive counter" would call this a follow.
  assert.ok(event.followCount > 0, 'fixture keeps a cumulative follow count');
  assert.equal(classifySocialAction(event.action).kind, 'share');
  assert.equal(event.shareType, 1);
  assert.equal(event.shareTarget, 'https://example.invalid/live');
  assert.equal(event.shareDisplayStyle, 1);
  assert.equal(event.scene, 'share_scene');
  assert.equal(event.targetUserId, '0');
});

test('an unobserved action stays unclassified with its raw code', () => {
  const event = decodeEvent(METHOD.social, fixture('social-unknown.pb'));
  if (event.type !== EVENT.social) assert.fail(`expected social, got ${event.type}`);
  assert.equal(event.action, 97);
  const classification = classifySocialAction(event.action);
  assert.equal(classification.kind, 'other');
  assert.equal(classification.rawAction, 97);
});

test('a follow fixture keeps its discriminator and its evidence', () => {
  const event = decodeEvent(METHOD.social, fixture('social.pb'));
  if (event.type !== EVENT.social) assert.fail(`expected social, got ${event.type}`);
  assert.equal(event.action, SOCIAL_ACTION.follow);
  assert.equal(event.followType, 2);
  assert.equal(event.targetUserId, '6820914516086834');
  assert.equal(classifySocialAction(event.action).kind, 'follow');
});

// `WebcastMemberMessage.action` and `WebcastSocialMessage.action` are different
// enums that happen to share the value 3 (subscribe vs share). They must never
// be read as one another.
test('member action 3 is not social action 3', () => {
  const event = decodeEvent(
    METHOD.member,
    toBinary(WebcastMemberMessageSchema, create(WebcastMemberMessageSchema, { action: 3 })),
  );
  assert.equal(event.type, EVENT.member);
  if (event.type === EVENT.member) assert.equal(event.action, 3);
  // The social classifier has no opinion about member actions.
  assert.equal(classifySocialAction(3).kind, 'share');
});
