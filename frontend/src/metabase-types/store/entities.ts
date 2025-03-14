import type {
  NormalizedCard,
  NormalizedCollection,
  NormalizedDashboard,
  NormalizedDatabase,
  NormalizedField,
  NormalizedIndexedEntity,
  NormalizedNativeQuerySnippet,
  NormalizedSchema,
  NormalizedSegment,
  NormalizedTable,
  NormalizedUser,
  NormalizedWritebackAction,
} from "metabase-types/api";

export interface EntitiesState {
  actions: Record<string, NormalizedWritebackAction>;
  collections: Record<string, NormalizedCollection>;
  dashboards: Record<string, NormalizedDashboard>;
  databases: Record<string, NormalizedDatabase>;
  schemas: Record<string, NormalizedSchema>;
  tables: Record<string, NormalizedTable>;
  fields: Record<string, NormalizedField>;
  segments: Record<string, NormalizedSegment>;
  indexedEntities: Record<string, NormalizedIndexedEntity>;
  snippets: Record<string, NormalizedNativeQuerySnippet>;
  users: Record<string, NormalizedUser>;
  questions: Record<string, NormalizedCard>;
}
