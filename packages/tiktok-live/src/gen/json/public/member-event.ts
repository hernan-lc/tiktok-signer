// Generated file. Do not edit manually.
// Source: schema/json/public/member-event.schema.json

export type MemberEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
} & {
  type: "member";
  user: {
    userId: string;
    nickname: string;
    uniqueId: string;
    secUid: string;
    avatarUrl?: string;
  };
  memberCount: number;
  action: number;
};

export const MemberEventSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/member-event.schema.json",
  "title": "ttl-live Member Event",
  "x-typescript-name": "MemberEvent",
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
          "const": "member"
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
        "memberCount": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "action": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        }
      },
      "required": [
        "type",
        "user",
        "memberCount",
        "action"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    }
  ]
} as const;
