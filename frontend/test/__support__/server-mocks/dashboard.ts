import fetchMock from "fetch-mock";

import { getNextId } from "__support__/utils";
import type {
  Dashboard,
  DashboardId,
  DashboardQueryMetadata,
  FieldId,
  GetPublicDashboard,
} from "metabase-types/api";
import { createMockDashboard } from "metabase-types/api/mocks";

export function setupDashboardEndpoints(dashboard: Dashboard) {
  fetchMock.get(`path:/api/dashboard/${dashboard.id}`, dashboard);
  fetchMock.put(`path:/api/dashboard/${dashboard.id}`, async (url) => {
    const lastCall = fetchMock.lastCall(url);
    return createMockDashboard(await lastCall?.request?.json());
  });
}

export function setupDashboardCreateEndpoint(dashboard: Partial<Dashboard>) {
  fetchMock.post(
    `path:/api/dashboard`,
    createMockDashboard({
      id: getNextId(),
      ...dashboard,
    }),
  );
}

export function setupDashboardQueryMetadataEndpoint(
  dashboard: Dashboard,
  metadata: DashboardQueryMetadata,
) {
  fetchMock.get(`path:/api/dashboard/${dashboard.id}/query_metadata`, metadata);
}

export function setupDashboardsEndpoints(dashboards: Dashboard[]) {
  fetchMock.get("path:/api/dashboard", dashboards);
  dashboards.forEach((dashboard) => setupDashboardEndpoints(dashboard));
}

export function setupDashboardNotFoundEndpoint(dashboard: Dashboard) {
  fetchMock.get(`path:/api/dashboard/${dashboard.id}`, 404);
}

export function setupDashboardPublicLinkEndpoints(dashboardId: DashboardId) {
  fetchMock.post(`path:/api/dashboard/${dashboardId}/public_link`, {
    id: dashboardId,
    uuid: "mock-uuid",
  });
  fetchMock.delete(`path:/api/dashboard/${dashboardId}/public_link`, {
    id: dashboardId,
  });
}

export function setupListPublicDashboardsEndpoint(
  publicDashboards: GetPublicDashboard[],
) {
  fetchMock.get("path:/api/dashboard/public", publicDashboards);
}

export function setupValidFilterFieldsEndpoint(
  filteringIdsByFilteredId: Record<FieldId, FieldId[]>,
) {
  fetchMock.get(
    "path:/api/dashboard/params/valid-filter-fields",
    filteringIdsByFilteredId,
  );
}
