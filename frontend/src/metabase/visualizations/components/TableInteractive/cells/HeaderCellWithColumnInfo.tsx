import type React from "react";
import { memo, useMemo } from "react";

import { QueryColumnInfoPopover } from "metabase/common/components/MetadataInfo/ColumnInfoPopover";
import {
  HeaderCellPill,
  type HeaderCellProps,
  HeaderCellWrapper,
} from "metabase/data-grid";
import type { MantineTheme } from "metabase/ui";
import * as Lib from "metabase-lib";
import type Question from "metabase-lib/v1/Question";
import type { DatasetColumn } from "metabase-types/api";

import S from "./HeaderCellWithColumnInfo.module.css";

export interface HeaderCellWithColumnInfoProps extends HeaderCellProps {
  getInfoPopoversDisabled: () => boolean;
  timezone?: string;
  question: Question;
  column: DatasetColumn;
  columnIndex: number;
  theme: MantineTheme;
  className?: string;
  renderTableHeader?: (
    column: DatasetColumn,
    index: number,
    theme: MantineTheme,
  ) => React.ReactNode;
}

export const HeaderCellWithColumnInfo = memo(
  function HeaderCellWithColumnInfoInner({
    name,
    align,
    sort,
    variant = "light",
    getInfoPopoversDisabled,
    question,
    timezone,
    column,
    columnIndex,
    theme,
    className,
    renderTableHeader,
  }: HeaderCellWithColumnInfoProps) {
    const query = question?.query();
    const stageIndex = -1;

    const headerCellOverride = useMemo(() => {
      return renderTableHeader != null
        ? renderTableHeader(column, columnIndex, theme)
        : null;
    }, [renderTableHeader, column, columnIndex, theme]);

    const cellContent = (
      <div className={S.headerPillWrapper}>
        {headerCellOverride != null ? (
          headerCellOverride
        ) : (
          <HeaderCellPill name={name} sort={sort} align={align} />
        )}
      </div>
    );

    return (
      <HeaderCellWrapper className={className} variant={variant} align={align}>
        {getInfoPopoversDisabled() ? (
          cellContent
        ) : (
          <QueryColumnInfoPopover
            position="bottom-start"
            query={query}
            stageIndex={-1}
            column={query && Lib.fromLegacyColumn(query, stageIndex, column)}
            timezone={timezone}
            openDelay={500}
            showFingerprintInfo
          >
            {cellContent}
          </QueryColumnInfoPopover>
        )}
      </HeaderCellWrapper>
    );
  },
);
