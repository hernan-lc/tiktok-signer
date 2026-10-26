// Generated file. Do not edit manually.
// Source: schema/json/public/social-event.schema.json

export type SocialEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
} & {
  type: "social";
  user: {
    userId: string;
    nickname: string;
    uniqueId: string;
    secUid: string;
    avatarUrl?: string;
  };
  action: number;
  followCount: number;
  shareCount: number;
  shareType?: number;
  shareTarget?: string;
  shareDisplayStyle?: number;
  followType?: number;
  scene?: string;
  targetUserId?: string;
};

export const SocialEventSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/social-event.schema.json",
  "title": "ttl-live Social Event",
  "x-typescript-name": "SocialEvent",
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
          "const": "social"
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
        "action": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "followCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "shareCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "shareType": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "shareTarget": {
          "type": "string"
        },
        "shareDisplayStyle": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "followType": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "scene": {
          "type": "string"
        },
        "targetUserId": {
          "type": "string",
          "pattern": "^-?[0-9]+$",
          "description": "Raw int64 target; negative wire sentinels are preserved."
        }
      },
      "required": [
        "type",
        "user",
        "action",
        "followCount",
        "shareCount"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    }
  ]
} as const;
