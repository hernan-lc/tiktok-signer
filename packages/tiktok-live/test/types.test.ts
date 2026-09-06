import assert from 'node:assert/strict';
import test from 'node:test';
import type { GiftEvent, RoomLookup } from '../dist/index.js';

type Equal<Left, Right> =
  (<Value>() => Value extends Left ? 1 : 2) extends
  (<Value>() => Value extends Right ? 1 : 2) ? true : false;
type Expect<Value extends true> = Value;

type RoomIdIsAString = Expect<Equal<RoomLookup['roomId'], string>>;
// @ts-expect-error Room IDs are not public numbers.
type RoomIdIsNotANumber = Expect<Equal<RoomLookup['roomId'], number>>;

function publicAutocomplete(event: GiftEvent): void {
  event.user.uniqueId;
  event.giftId;
  event.diamondCount;

  // @ts-expect-error Upstream snake_case names are not part of the normalized public API.
  event.gift_id;
  // @ts-expect-error Public identifiers are strings.
  event.giftId = 123;
}

// Keep the declarations in the emitted test module type-only; the runtime suite does not need to
// execute a fake event just to verify autocomplete.
void (null as unknown as RoomIdIsAString);
void (null as unknown as RoomIdIsNotANumber);
void publicAutocomplete;

test('public schema types compile with normalized names', () => {
  assert.ok(true);
});
