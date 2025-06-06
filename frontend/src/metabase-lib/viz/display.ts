import * as Lib from "metabase-lib";
import type {
  CardDisplayType,
  VisualizationSettings,
} from "metabase-types/api";

type DefaultDisplay = {
  display: CardDisplayType;
  settings?: Partial<VisualizationSettings>;
};

export const defaultDisplay = (query: Lib.Query): DefaultDisplay => {
  const { isNative } = Lib.queryDisplayInfo(query);

  if (isNative) {
    return { display: "table" };
  }

  const stageIndex = -1;
  const aggregations = Lib.aggregations(query, stageIndex);
  const breakouts = Lib.breakouts(query, stageIndex);

  if (aggregations.length === 0 && breakouts.length === 0) {
    return { display: "table" };
  }

  if (aggregations.length === 1 && breakouts.length === 0) {
    return { display: "scalar" };
  }

  if (aggregations.length === 1 && breakouts.length === 1) {
    const [{ column }] = getBreakoutsWithColumns(query, stageIndex, breakouts);

    if (column != null && Lib.isState(column)) {
      return {
        display: "map",
        settings: {
          "map.type": "region",
          "map.region": "us_states",
        },
      };
    }

    if (column != null && Lib.isCountry(column)) {
      return {
        display: "map",
        settings: {
          "map.type": "region",
          "map.region": "world_countries",
        },
      };
    }
  }

  if (aggregations.length >= 1 && breakouts.length === 1) {
    const [{ breakout, column }] = getBreakoutsWithColumns(
      query,
      stageIndex,
      breakouts,
    );

    const breakoutInfo = Lib.displayInfo(query, stageIndex, breakout);
    if (breakoutInfo.isTemporalExtraction) {
      return { display: "bar" };
    }
    if (column != null && Lib.isTemporal(column)) {
      return { display: "line" };
    }

    const binning = Lib.binning(breakout);
    const isBinned = binning !== null;

    if (isBinned) {
      return { display: "bar" };
    }

    if (column != null && (Lib.isBoolean(column) || Lib.isCategory(column))) {
      return { display: "bar" };
    }
  }

  if (aggregations.length === 1 && breakouts.length === 2) {
    const breakoutsWithColumns = getBreakoutsWithColumns(
      query,
      stageIndex,
      breakouts,
    );

    const isAnyBreakoutTemporal = breakoutsWithColumns.some(({ column }) => {
      return column != null && Lib.isTemporal(column);
    });
    if (isAnyBreakoutTemporal) {
      return { display: "line" };
    }

    const areBreakoutsCoordinates = breakoutsWithColumns.every(({ column }) => {
      return column != null && Lib.isCoordinate(column);
    });
    if (areBreakoutsCoordinates) {
      const binningOne = Lib.binning(breakouts[0]);
      const binningTwo = Lib.binning(breakouts[1]);
      const areBothBinned = binningOne !== null && binningTwo !== null;

      if (areBothBinned) {
        return {
          display: "map",
          settings: {
            "map.type": "grid",
          },
        };
      }

      return {
        display: "map",
        settings: {
          "map.type": "pin",
        },
      };
    }

    const areBreakoutsCategories = breakoutsWithColumns.every(({ column }) => {
      return (
        column != null && (Lib.isBoolean(column) || Lib.isCategory(column))
      );
    });
    if (areBreakoutsCategories) {
      return { display: "bar" };
    }
  }

  return { display: "table" };
};

const getBreakoutsWithColumns = (
  query: Lib.Query,
  stageIndex: number,
  breakouts: Lib.BreakoutClause[],
) => {
  return breakouts.map((breakout) => {
    const column = Lib.breakoutColumn(query, stageIndex, breakout);
    return { breakout, column };
  });
};
