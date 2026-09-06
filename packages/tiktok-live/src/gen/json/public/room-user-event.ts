// Generated file. Do not edit manually.
// Source: schema/json/public/room-user-event.schema.json

export type RoomUserEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
} & {
  type: "roomUser";
  viewers: number;
  popularity: number;
  totalUser: number;
  anonymous: number;
  topViewers: Array<{
      rank: number;
      score: number;
      delta: number;
      user: {
        userId: string;
        nickname: string;
        uniqueId: string;
        secUid: string;
        avatarUrl?: string;
      };
    }>;
  rankedViewers: Array<{
      rank: number;
      score: number;
      delta: number;
      user: {
        userId: string;
        nickname: string;
        uniqueId: string;
        secUid: string;
        avatarUrl?: string;
      };
    }>;
};

export const RoomUserEventSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/room-user-event.schema.json",
  "title": "ttl-live Room User Event",
  "x-typescript-name": "RoomUserEvent",
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
          "const": "roomUser"
        },
        "viewers": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "popularity": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "totalUser": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "anonymous": {
          "type": "integer",
          "minimum": 0,
          "maximum": 9007199254740991
        },
        "topViewers": {
          "type": "array",
          "items": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://ttl-live.dev/schema/public/top-viewer.schema.json",
            "title": "ttl-live Top Viewer",
            "x-typescript-name": "TopViewer",
            "type": "object",
            "properties": {
              "rank": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
              },
              "score": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
              },
              "delta": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
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
              }
            },
            "required": [
              "rank",
              "score",
              "delta",
              "user"
            ],
            "additionalProperties": false
          }
        },
        "rankedViewers": {
          "type": "array",
          "items": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://ttl-live.dev/schema/public/top-viewer.schema.json",
            "title": "ttl-live Top Viewer",
            "x-typescript-name": "TopViewer",
            "type": "object",
            "properties": {
              "rank": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
              },
              "score": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
              },
              "delta": {
                "type": "integer",
                "minimum": 0,
                "maximum": 9007199254740991
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
              }
            },
            "required": [
              "rank",
              "score",
              "delta",
              "user"
            ],
            "additionalProperties": false
          }
        }
      },
      "required": [
        "type",
        "viewers",
        "popularity",
        "totalUser",
        "anonymous",
        "topViewers",
        "rankedViewers"
      ],
      "additionalProperties": true,
      "x-typescript-no-index": true
    }
  ]
} as const;
