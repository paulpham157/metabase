import PropTypes from "prop-types";
import { msgid, ngettext, t } from "ttag";

import { useGetFieldValuesQuery } from "metabase/api";
import { formatNumber } from "metabase/lib/formatting";
import { useSelector } from "metabase/lib/redux";
import { getMetadata } from "metabase/selectors/metadata";

import {
  Container,
  Fade,
  FadeAndSlide,
  Li,
  LoadingSpinner,
  NoWrap,
  RelativeContainer,
} from "./GlobalFingerprint.styled";

const propTypes = {
  className: PropTypes.string,
  fieldId: PropTypes.number,
  showAllFieldValues: PropTypes.bool,
};

const FIELD_VALUES_SHOW_LIMIT = 35;

export function GlobalFingerprint({ className, fieldId, showAllFieldValues }) {
  const metadata = useSelector(getMetadata);
  const field = metadata.field(fieldId);
  const hasListValues = field?.has_field_values === "list";
  const { data: fieldData, isLoading } = useGetFieldValuesQuery(fieldId, {
    skip: !hasListValues,
  });

  const fieldValues = fieldData ? fieldData.values : [];
  const distinctCount = field?.fingerprint?.global?.["distinct-count"];
  const formattedDistinctCount = formatNumber(distinctCount);

  const showDistinctCount = isLoading || distinctCount != null;
  const showFieldValuesBlock = isLoading || fieldValues.length > 0;
  const showComponent = showDistinctCount || showFieldValuesBlock;

  return showComponent ? (
    <Container className={className}>
      {showDistinctCount && (
        <RelativeContainer>
          <Fade aria-hidden={!isLoading} visible={!isLoading}>
            {ngettext(
              msgid`${formattedDistinctCount} distinct value`,
              `${formattedDistinctCount} distinct values`,
              distinctCount || 0,
            )}
          </Fade>
          {hasListValues && (
            <Fade
              aria-hidden={!isLoading}
              visible={isLoading}
            >{t`Getting distinct values...`}</Fade>
          )}
        </RelativeContainer>
      )}
      {showFieldValuesBlock &&
        (showAllFieldValues ? (
          <ExtendedFieldValuesList fieldValues={fieldValues} />
        ) : (
          <ShortenedFieldValuesList
            isLoading={isLoading}
            fieldValues={fieldValues}
          />
        ))}
    </Container>
  ) : null;
}

ExtendedFieldValuesList.propTypes = {
  fieldValues: PropTypes.array.isRequired,
};

function ExtendedFieldValuesList({ fieldValues }) {
  return (
    <ul>
      {fieldValues.map((fieldValue, i) => {
        const value = Array.isArray(fieldValue) ? fieldValue[0] : fieldValue;
        if (value === null) {
          return null;
        }
        return <Li key={i}>{value}</Li>;
      })}
    </ul>
  );
}

ShortenedFieldValuesList.propTypes = {
  isLoading: PropTypes.bool.isRequired,
  fieldValues: PropTypes.array.isRequired,
};

function ShortenedFieldValuesList({ isLoading, fieldValues }) {
  const shortenedValuesStr = fieldValues
    .slice(0, FIELD_VALUES_SHOW_LIMIT)
    .map((value) => (Array.isArray(value) ? value[0] : value))
    .filter((value) => value !== null)
    .join(", ");

  return (
    <RelativeContainer height={isLoading ? "1.8em" : "1.5em"}>
      <Fade visible={isLoading}>
        <LoadingSpinner />
      </Fade>
      <FadeAndSlide visible={!isLoading}>
        <NoWrap>{shortenedValuesStr}</NoWrap>
      </FadeAndSlide>
    </RelativeContainer>
  );
}

GlobalFingerprint.propTypes = propTypes;
