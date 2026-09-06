// Generated file. Do not edit manually.
// Source: schema/json/public/gift-event.schema.json

export type GiftEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
} & {
  type: "gift";
  user: {
    userId: string;
    nickname: string;
    uniqueId: string;
    secUid: string;
    avatarUrl?: string;
  };
  toUser: {
    userId: string;
    nickname: string;
    uniqueId: string;
    secUid: string;
    avatarUrl?: string;
  };
  giftId: string;
  giftName: string;
  diamondCount: number;
  repeatCount: number;
  comboCount: number;
  groupId: string;
  repeatEnd: boolean;
  streakable?: boolean;
  giftIconUrl?: string;
};

export const GiftEventSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/gift-event.schema.json",
  "title": "ttl-live Gift Event",
  "x-typescript-name": "GiftEvent",
  "allOf": [
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "$id": "https://ttl-live.dev/schema/public/base-event.schema.json",
      "title": "ttl-live Base Event",
      "x-typescript-name": "BaseEvent",
      "type": "object",
      "properties": {
        "method": {
          "type": "string"
        },
        "msgId": {
          "type": "string",
          "pattern": "^[0-9]+$"
        },
        "isHistory": {
          "type": "boolean"
        }
      },
      "required": [
        "method"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    },
    {
      "type": "object",
      "properties": {
        "type": {
          "const": "gift"
        },
        "user": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://ttl-live.dev/schema/public/event-user.schema.json",
          "title": "ttl-live Event User",
          "x-typescript-name": "EventUser",
          "type": "object",
          "properties": {
            "userId": {
              "type": "string",
              "pattern": "^[0-9]+$"
            },
            "nickname": {
              "type": "string"
            },
            "uniqueId": {
              "type": "string"
            },
            "secUid": {
              "type": "string"
            },
            "avatarUrl": {
              "type": "string",
              "format": "uri"
            }
          },
          "required": [
            "userId",
            "nickname",
            "uniqueId",
            "secUid"
          ],
          "additionalProperties": false
        },
        "toUser": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://ttl-live.dev/schema/public/event-user.schema.json",
          "title": "ttl-live Event User",
          "x-typescript-name": "EventUser",
          "type": "object",
          "properties": {
            "userId": {
              "type": "string",
              "pattern": "^[0-9]+$"
            },
            "nickname": {
              "type": "string"
            },
            "uniqueId": {
              "type": "string"
            },
            "secUid": {
              "type": "string"
            },
            "avatarUrl": {
              "type": "string",
              "format": "uri"
            }
          },
          "required": [
            "userId",
            "nickname",
            "uniqueId",
            "secUid"
          ],
          "additionalProperties": false
        },
        "giftId": {
          "type": "string",
          "pattern": "^[0-9]+$"
        },
        "giftName": {
          "type": "string"
        },
        "diamondCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "repeatCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "comboCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "groupId": {
          "type": "string",
          "pattern": "^[0-9]+$"
        },
        "repeatEnd": {
          "type": "boolean"
        },
        "streakable": {
          "type": "boolean"
        },
        "giftIconUrl": {
          "type": "string",
          "format": "uri"
        }
      },
      "required": [
        "type",
        "user",
        "toUser",
        "giftId",
        "giftName",
        "diamondCount",
        "repeatCount",
        "comboCount",
        "groupId",
        "repeatEnd"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    }
  ]
} as const;
