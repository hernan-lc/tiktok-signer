// Generated file. Do not edit manually.
// Source: schema/json/public/room-owner.schema.json

export type RoomOwner = {
  userId: string;
  uniqueId: string;
  nickname: string;
  secUid: string;
  avatarUrl: string;
  followerCount: number;
};

export const RoomOwnerSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/room-owner.schema.json",
  "title": "ttl-live Room Owner",
  "x-typescript-name": "RoomOwner",
  "type": "object",
  "properties": {
    "userId": {
      "type": "string",
      "pattern": "^[0-9]*$"
    },
    "uniqueId": {
      "type": "string"
    },
    "nickname": {
      "type": "string"
    },
    "secUid": {
      "type": "string"
    },
    "avatarUrl": {
      "type": "string",
      "format": "uri"
    },
    "followerCount": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991
    }
  },
  "required": [
    "userId",
    "uniqueId",
    "nickname",
    "secUid",
    "avatarUrl",
    "followerCount"
  ],
  "additionalProperties": false
} as const;
