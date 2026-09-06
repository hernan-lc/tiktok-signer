// Generated file. Do not edit manually.
// Source: schema/json/public/top-viewer.schema.json

export type TopViewer = {
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
};

export const TopViewerSchema = {
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
} as const;
