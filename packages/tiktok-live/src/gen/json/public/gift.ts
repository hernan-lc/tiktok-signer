// Generated file. Do not edit manually.
// Source: schema/json/public/gift.schema.json

export type Gift = {
  id: string;
  name: string;
  describe: string;
  diamondCount: number;
  combo: boolean;
  giftType: number;
  iconUrl: string;
};

export const GiftSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/gift.schema.json",
  "title": "ttl-live Gift",
  "x-typescript-name": "Gift",
  "type": "object",
  "properties": {
    "id": {
      "type": "string",
      "pattern": "^[0-9]+$"
    },
    "name": {
      "type": "string"
    },
    "describe": {
      "type": "string"
    },
    "diamondCount": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991
    },
    "combo": {
      "type": "boolean"
    },
    "giftType": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991
    },
    "iconUrl": {
      "type": "string",
      "format": "uri"
    }
  },
  "required": [
    "id",
    "name",
    "describe",
    "diamondCount",
    "combo",
    "giftType",
    "iconUrl"
  ],
  "additionalProperties": false
} as const;
