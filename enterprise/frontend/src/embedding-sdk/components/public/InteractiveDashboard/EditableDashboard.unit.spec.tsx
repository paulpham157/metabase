import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";
import { indexBy } from "underscore";

import {
  setupAlertsEndpoints,
  setupCardEndpoints,
  setupCardQueryEndpoints,
  setupCardQueryMetadataEndpoint,
  setupCollectionItemsEndpoint,
  setupCollectionsEndpoints,
  setupDashboardEndpoints,
  setupDashboardQueryMetadataEndpoint,
  setupDatabasesEndpoints,
  setupLastDownloadFormatEndpoints,
} from "__support__/server-mocks";
import { setupDashcardQueryEndpoints } from "__support__/server-mocks/dashcard";
import { setupNotificationChannelsEndpoints } from "__support__/server-mocks/pulse";
import { screen, waitFor, within } from "__support__/ui";
import type { MetabaseProviderProps } from "embedding-sdk/components/public/MetabaseProvider";
import { renderWithSDKProviders } from "embedding-sdk/test/__support__/ui";
import { createMockSdkConfig } from "embedding-sdk/test/mocks/config";
import { setupSdkState } from "embedding-sdk/test/server-mocks/sdk-init";
import { useLocale } from "metabase/common/hooks/use-locale";
import { Box } from "metabase/ui";
import {
  createMockCard,
  createMockCardQueryMetadata,
  createMockCollection,
  createMockDashboard,
  createMockDashboardCard,
  createMockDashboardQueryMetadata,
  createMockDashboardTab,
  createMockDatabase,
  createMockDataset,
  createMockParameter,
  createMockStructuredDatasetQuery,
  createMockTextDashboardCard,
  createMockUser,
} from "metabase-types/api/mocks";
import {
  ORDERS_ID,
  createSampleDatabase,
} from "metabase-types/api/mocks/presets";
import { createMockDashboardState } from "metabase-types/store/mocks";

import type { EditableDashboardProps } from "./EditableDashboard";
import { EditableDashboard } from "./EditableDashboard";

jest.mock("metabase/common/hooks/use-locale", () => ({
  useLocale: jest.fn(),
}));

const useLocaleMock = useLocale as jest.Mock;

const TEST_DASHBOARD_ID = 1;
const TEST_DB = createMockDatabase({ id: 1 });
const TEST_COLLECTION = createMockCollection();

const dataset_query = createMockStructuredDatasetQuery({
  query: { "source-table": ORDERS_ID },
});

const dashboardTabs = [
  createMockDashboardTab({ id: 1, name: "Foo Tab 1" }),
  createMockDashboardTab({ id: 2, name: "Foo Tab 2" }),
];

const tableCard = createMockCard({
  id: 1,
  dataset_query,
  name: "Here is a card title",
});

const parameter = createMockParameter({
  id: "1",
  type: "string/contains",
  slug: "title",
  name: "Title",
});

const tableDashcard = createMockDashboardCard({
  id: 1,
  card_id: tableCard.id,
  card: tableCard,
  dashboard_tab_id: dashboardTabs[0].id,
  parameter_mappings: [
    {
      card_id: tableCard.id,
      parameter_id: parameter.id,
      target: [
        "dimension",
        ["field", parameter.slug, { "base-type": "type/Text" }],
      ],
    },
  ],
});

const textDashcard = createMockTextDashboardCard({
  id: 2,
  text: "Some card text",
  dashboard_tab_id: dashboardTabs[0].id,
});

const textDashcard2 = createMockTextDashboardCard({
  id: 3,
  text: "Some card text",
  dashboard_tab_id: dashboardTabs[1].id,
});

const dashcards = [tableDashcard, textDashcard, textDashcard2];
const setup = async (
  {
    props,
    providerProps,
    isLocaleLoading,
  }: {
    props?: Partial<EditableDashboardProps>;
    providerProps?: Partial<MetabaseProviderProps>;
    isLocaleLoading?: boolean;
  } = {
    props: {},
    providerProps: {},
    isLocaleLoading: false,
  },
) => {
  useLocaleMock.mockReturnValue({ isLocaleLoading });

  const database = createSampleDatabase();

  const dashboardId = props?.dashboardId || TEST_DASHBOARD_ID;
  const dashboard = createMockDashboard({
    id: dashboardId,
    dashcards,
    tabs: dashboardTabs,
    parameters: [parameter],
  });

  setupDashboardEndpoints(dashboard);

  setupCollectionsEndpoints({ collections: [] });
  setupCollectionItemsEndpoint({
    collection: TEST_COLLECTION,
    collectionItems: [],
  });

  setupDashboardQueryMetadataEndpoint(
    dashboard,
    createMockDashboardQueryMetadata({
      databases: [database],
    }),
  );

  setupCardEndpoints(tableCard);
  setupCardQueryEndpoints(tableCard, createMockDataset());
  setupCardQueryMetadataEndpoint(
    tableCard,
    createMockCardQueryMetadata({
      databases: [TEST_DB],
    }),
  );

  setupDashcardQueryEndpoints(dashboardId, tableDashcard, createMockDataset());

  setupAlertsEndpoints(tableCard, []);

  setupNotificationChannelsEndpoints({});

  setupDatabasesEndpoints([createMockDatabase()]);

  setupLastDownloadFormatEndpoints();

  const user = createMockUser();

  const state = setupSdkState({
    currentUser: user,
    dashboard: createMockDashboardState({
      dashboardId: dashboard.id,
      dashboards: {
        [dashboard.id]: {
          ...dashboard,
          dashcards: dashcards.map((dc) => dc.id),
        },
      },
      dashcards: indexBy(dashcards, "id"),
    }),
  });

  renderWithSDKProviders(
    <Box h="500px">
      <EditableDashboard dashboardId={dashboardId} {...props} />
    </Box>,
    {
      sdkProviderProps: {
        ...providerProps,
        authConfig: createMockSdkConfig(),
      },
      storeInitialState: state,
    },
  );

  if (!isLocaleLoading) {
    expect(await screen.findByTestId("dashboard-grid")).toBeInTheDocument();
  }

  return {
    dashboard,
  };
};

describe("EditableDashboard", () => {
  it("should render a loader when a locale is loading", async () => {
    await setup({ isLocaleLoading: true });

    expect(screen.getByTestId("loading-indicator")).toBeInTheDocument();
  });

  it("should render dashboard cards", async () => {
    await setup();

    expect(screen.getByText("Here is a card title")).toBeInTheDocument();
    expect(screen.getByText("Some card text")).toBeInTheDocument();
  });

  it("should allow to navigate to a question from dashboard", async () => {
    await setup();

    await userEvent.click(screen.getByText("Here is a card title"));

    expect(
      await screen.findByTestId("query-visualization-root"),
    ).toBeInTheDocument();
  });

  it("should allow to navigate back to dashboard from a question", async () => {
    await setup();

    await userEvent.click(screen.getByText("Here is a card title"));

    expect(
      await screen.findByTestId("query-visualization-root"),
    ).toBeInTheDocument();

    expect(screen.getByLabelText("Back to Dashboard")).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText("Back to Dashboard"));

    expect(await screen.findByTestId("dashboard-grid")).toBeInTheDocument();

    // do not reload dashboard data on navigate back
    expect(
      fetchMock.calls(`path:/api/dashboard/${TEST_DASHBOARD_ID}`),
    ).toHaveLength(1);
  });

  it("should allow to navigate back to dashboard from a question with empty results", async () => {
    await setup();

    await userEvent.click(screen.getByText("Here is a card title"));

    expect(
      await screen.findByTestId("query-visualization-root"),
    ).toBeInTheDocument();

    expect(screen.getByLabelText("Back to Dashboard")).toBeInTheDocument();

    await userEvent.click(screen.getByText("Back to previous results"));

    expect(await screen.findByTestId("dashboard-grid")).toBeInTheDocument();

    // do not reload dashboard data on navigate back
    expect(
      fetchMock.calls(`path:/api/dashboard/${TEST_DASHBOARD_ID}`),
    ).toHaveLength(1);
  });

  it("should support onLoad, onLoadWithoutCards handlers", async () => {
    const onLoad = jest.fn();
    const onLoadWithoutCards = jest.fn();
    const { dashboard } = await setup({
      props: { onLoad, onLoadWithoutCards },
    });

    expect(onLoadWithoutCards).toHaveBeenCalledTimes(1);
    expect(onLoadWithoutCards).toHaveBeenLastCalledWith(dashboard);

    await waitFor(() => {
      return fetchMock.called(
        `path:/api/card/${dashboard.dashcards[0].card_id}/query`,
      );
    });
    expect(onLoad).toHaveBeenCalledTimes(1);
    expect(onLoad).toHaveBeenLastCalledWith(dashboard);
  });

  it("should support global dashboard load event handlers", async () => {
    const onLoad = jest.fn();
    const onLoadWithoutCards = jest.fn();

    const { dashboard } = await setup({
      providerProps: {
        eventHandlers: {
          onDashboardLoad: onLoad,
          onDashboardLoadWithoutCards: onLoadWithoutCards,
        },
      },
    });

    expect(onLoadWithoutCards).toHaveBeenCalledTimes(1);
    expect(onLoadWithoutCards).toHaveBeenLastCalledWith(dashboard);

    await waitFor(() => {
      return fetchMock.called(
        `path:/api/card/${dashboard.dashcards[0].card_id}/query`,
      );
    });

    expect(onLoad).toHaveBeenCalledTimes(1);
    expect(onLoad).toHaveBeenLastCalledWith(dashboard);
  });

  it("should support dashboard editing", async () => {
    await setup();

    await waitFor(() => {
      expect(screen.getByTestId("dashboard-header")).toBeInTheDocument();
    });

    const editButton = within(
      screen.getByTestId("dashboard-header"),
    ).getByLabelText(`pencil icon`);

    expect(editButton).toBeInTheDocument();

    await userEvent.click(editButton);

    expect(
      screen.getByText("You're editing this dashboard."),
    ).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.getByText("Save")).toBeInTheDocument();
  });
});
