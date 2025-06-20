export type TagType = (typeof TAG_TYPES)[number];

export const TAG_TYPES = [
  "action",
  "alert",
  "api-key",
  "bookmark",
  "card",
  "channel",
  "cloud-migration",
  "collection",
  "content-translation",
  "dashboard",
  "dashboard-question-candidates",
  "database",
  "field",
  "field-values",
  "indexed-entity",
  "logger-preset",
  "model-index",
  "notification",
  "parameter-values",
  "permissions-group",
  "persisted-info",
  "persisted-model",
  "revision",
  "schema",
  "segment",
  "session-properties",
  "snippet",
  "subscription",
  "subscription-channel",
  "table",
  "task",
  "timeline",
  "timeline-event",
  "user",
  "public-dashboard",
  "embed-dashboard",
  "public-card",
  "embed-card",
  "public-action",
  "unique-tasks",
  "user-key-value",
] as const;

export const TAG_TYPE_MAPPING = {
  collection: "collection",
  "content-translation": "content-translation",
  card: "card",
  dashboard: "dashboard",
  database: "database",
  "indexed-entity": "indexed-entity",
  table: "table",
  dataset: "card",
  action: "action",
  segment: "segment",
  metric: "card",
  snippet: "snippet",
  pulse: "subscription",
} as const;
