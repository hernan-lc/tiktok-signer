// Generated file. Do not edit manually.
// Source: schema/json/public/base-event.schema.json

export type BaseEvent = {
  method: string;
  msgId?: string;
  isHistory?: boolean;
};

export const BaseEventSchema = {
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
} as const;
