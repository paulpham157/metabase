// eslint-disable-next-line no-restricted-imports
import { css } from "@emotion/react";
// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import type { AnchorProps, TextProps } from "metabase/ui";
import { Group } from "metabase/ui";

type ResultLinkProps = AnchorProps | TextProps;

export const ResultLink = styled.a<ResultLinkProps>`
  line-height: unset;
  ${({ href }) => {
    return (
      href &&
      css`
        &:hover,
        &:focus,
        &:focus-within {
          color: var(--mb-color-brand);
          outline: 0;
        }
      `
    );
  }};
  transition: color 0.2s ease-in-out;
`;

export const ResultLinkWrapper = styled(Group)`
  overflow: hidden;
`;
