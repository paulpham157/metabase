// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import IconButtonWrapper from "metabase/common/components/IconButtonWrapper";

export const DismissIconButtonWrapper = styled(IconButtonWrapper)`
  color: var(--mb-color-bg-dark);

  &:hover {
    color: var(--mb-color-text-medium);
  }
`;
