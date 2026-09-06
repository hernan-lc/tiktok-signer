// Generated file. Do not edit manually.
// Source: schema/json/public/room-lookup.schema.json

export type RoomLookup = {
  uniqueId: string;
  roomId: string;
  nickname: string;
  status: number;
  title: string;
  isLive: boolean;
};

export const RoomLookupSchema = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ttl-live.dev/schema/public/room-lookup.schema.json",
  "title": "ttl-live Room Lookup",
  "x-typescript-name": "RoomLookup",
  "type": "object",
  "properties": {
    "uniqueId": {
      "type": "string"
    },
    "roomId": {
      "type": "string",
      "pattern": "^[0-9]*$"
    },
    "nickname": {
      "type": "string"
    },
    "status": {
      "type": "integer"
    },
    "title": {
      "type": "string"
    },
    "isLive": {
      "type": "boolean"
    }
  },
  "required": [
    "uniqueId",
    "roomId",
    "nickname",
    "status",
    "title",
    "isLive"
  ],
  "additionalProperties": false
} as const;
