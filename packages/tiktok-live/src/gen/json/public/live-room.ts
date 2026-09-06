// Generated file. Do not edit manually.
// Source: schema/json/public/live-room.schema.json

export type LiveRoom = {
  uniqueId: string;
  roomId: string;
  nickname: string;
  title: string;
  viewers: number;
};

export const LiveRoomSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/live-room.schema.json",
  "title": "ttl-live Live Room",
  "x-typescript-name": "LiveRoom",
  "type": "object",
  "properties": {
    "uniqueId": {
      "type": "string"
    },
    "roomId": {
      "type": "string",
      "pattern": "^[0-9]+$"
    },
    "nickname": {
      "type": "string"
    },
    "title": {
      "type": "string"
    },
    "viewers": {
      "type": "integer",
      "minimum": 0,
      "maximum": 9007199254740991
    }
  },
  "required": [
    "uniqueId",
    "roomId",
    "nickname",
    "title",
    "viewers"
  ],
  "additionalProperties": false
} as const;
