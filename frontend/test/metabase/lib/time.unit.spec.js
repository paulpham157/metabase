import moment from "moment-timezone"; // eslint-disable-line no-restricted-imports -- deprecated usage

import { parseTime, parseTimestamp } from "metabase/lib/time";

describe("time", () => {
  afterEach(() => {
    moment.updateLocale(moment.locale(), { week: { dow: 0 } });
  });

  describe("parseTimestamp", () => {
    const NY15_TOKYO = moment(1420038000000); // 2014-12-31 15:00 UTC
    const NY15_UTC = moment(1420070400000); // 2015-01-01 00:00 UTC
    const NY15_LA = moment(1420099200000); // 2015-01-01 00:00 UTC

    const TEST_CASES = [
      ["2015-01-01T00:00:00.000Z", 0, NY15_UTC],
      ["2015-01-01", 0, NY15_UTC],
      ["2015-01-01T00:00:00.000+00:00", 0, NY15_UTC],
      ["2015-01-01T00:00:00.000+0000", 0, NY15_UTC],
      ["2015-01-01T00:00:00Z", 0, NY15_UTC],
      [2015, 0, NY15_UTC],

      ["2015-01-01T00:00:00.000+09:00", 540, NY15_TOKYO],
      ["2015-01-01T00:00:00.000+0900", 540, NY15_TOKYO],
      ["2015-01-01T00:00:00+09:00", 540, NY15_TOKYO],
      ["2015-01-01T00:00:00+0900", 540, NY15_TOKYO],

      ["2015-01-01T00:00:00.000-08:00", -480, NY15_LA],
      ["2015-01-01T00:00:00.000-0800", -480, NY15_LA],
      ["2015-01-01T00:00:00-08:00", -480, NY15_LA],
      ["2015-01-01T00:00:00-0800", -480, NY15_LA],
    ];

    TEST_CASES.map(([str, expectedOffset, expectedMoment]) => {
      it(
        str +
          " should be parsed as moment reprsenting " +
          expectedMoment +
          " with the offset " +
          expectedOffset,
        () => {
          const result = parseTimestamp(str);

          expect(moment.isMoment(result)).toBe(true);
          expect(result.utcOffset()).toBe(expectedOffset);
          expect(result.unix()).toEqual(expectedMoment.unix());
        },
      );
    });

    // See https://github.com/metabase/metabase/issues/11615
    it("parse sqlite date with unit=year correctly", () => {
      const result = parseTimestamp("2015-01-01", "year");
      expect(moment.isMoment(result)).toBe(true);
      expect(result.unix()).toEqual(NY15_UTC.unix());
    });

    it("should parse week of year correctly", () => {
      const daysOfWeek = [0, 1, 2, 3, 4, 5, 6];
      daysOfWeek.forEach((dayOfWeek) => {
        moment.updateLocale(moment.locale(), { week: { dow: dayOfWeek } });
        expect(parseTimestamp(1, "week-of-year").isoWeek()).toBe(1);
        expect(parseTimestamp(2, "week-of-year").isoWeek()).toBe(2);
        expect(parseTimestamp(52, "week-of-year").isoWeek()).toBe(52);
        expect(parseTimestamp(53, "week-of-year").isoWeek()).toBe(53);
      });
    });
  });

  describe("parseTime", () => {
    const PARSE_TIME_TESTS = [
      ["01:02:03.456+07:00", "1:02 AM"],
      ["01:02", "1:02 AM"],
      ["22:29:59.26816+01:00", "10:29 PM"],
      ["22:29:59.412459+01:00", "10:29 PM"],
      ["19:14:42.926221+01:00", "7:14 PM"],
      ["19:14:42.13202+01:00", "7:14 PM"],
      ["13:38:58.987352+01:00", "1:38 PM"],
      ["13:38:58.001001+01:00", "1:38 PM"],
      ["17:01:23+01:00", "5:01 PM"],
    ];

    test.each(PARSE_TIME_TESTS)(
      `parseTime(%p) to be %p`,
      (value, resultStr) => {
        const result = parseTime(value);
        expect(moment.isMoment(result)).toBe(true);
        expect(result.format("h:mm A")).toBe(resultStr);
      },
    );
  });
});
