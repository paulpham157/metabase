import { SAMPLE_DB_ID, SAMPLE_DB_TABLES } from "e2e/support/cypress_data";

import { runNativeQuery } from "./e2e-misc-helpers";
import { NativeEditor } from "./e2e-native-editor-helpers";

const {
  STATIC_ORDERS_ID,
  STATIC_PRODUCTS_ID,
  STATIC_PEOPLE_ID,
  STATIC_REVIEWS_ID,
} = SAMPLE_DB_TABLES;

export function adhocQuestionHash(question) {
  if (question.display) {
    // without "locking" the display, the QB will run its picking logic and override the setting
    question = Object.assign({}, question, { displayIsLocked: true });
  }
  return btoa(decodeURIComponent(encodeURIComponent(JSON.stringify(question))));
}

function newCardHash(type) {
  const card = {
    type,
    creationType: "custom_question",
    dataset_query: {
      database: null,
      query: {
        "source-table": null,
      },
      type: "query",
    },
    visualization_settings: {},
  };
  return adhocQuestionHash(card);
}

/**
 * This is the query generated by clicking "New" and then choosing the (gui) "Question".
 */
export function startNewQuestion() {
  const hash = newCardHash("question");
  cy.visit(`/question/notebook#${hash}`);
}

/**
 * This is the query generated by clicking "New" and then choosing "Model" and "Use the notebook editor"
 */
export function startNewModel() {
  const hash = newCardHash("model");
  cy.visit(`/model/query#${hash}`);
}

/**
 * This is the query generated by clicking "New" and then choosing "Metric".
 */
export function startNewMetric() {
  const hash = newCardHash("metric");
  cy.visit(`/metric/query#${hash}`);
}

/**
 * @param {("question" | "model")} type
 * @param {Object} [config]
 * @param {("number" | null)} [config.database]
 * @param {string} [config.query]
 * @param {number} [config.collection_id]
 * @param {string} [config.display]
 */
function newNativeCardHash(
  type,
  {
    database = SAMPLE_DB_ID,
    query = "",
    collection_id = null,
    display = "scalar",
  } = {},
) {
  const card = {
    collection_id,
    dataset_query: {
      database,
      native: { query, "template-tags": {} },
      type: "native",
    },
    display,
    parameters: [],
    visualization_settings: {},
    type,
  };

  return adhocQuestionHash(card);
}

/**
 * This is the query generated by clicking "New" and then choosing "SQL Query".
 *
 * @example
 * H.startNewNativeQuestion({ query: "SELECT * FROM ORDERS" });
 * @param {object} [config]
 * @param {number} [config.database]
 * @param {string} config.query
 * @param {number} [config.collection_id]
 * @param {string} [config.display]
 */
export function startNewNativeQuestion(config) {
  const hash = newNativeCardHash("question", config);

  cy.visit("/question#" + hash);

  return NativeEditor.get();
}

/**
 * This is the query generated by clicking "New" > "Model" and then choosing "Use a native query".
 */
export function startNewNativeModel(config) {
  const hash = newNativeCardHash("model", config);

  cy.visit("/model/query#" + hash);

  return NativeEditor.get();
}

/**
 * Visit any valid query in an ad-hoc manner.
 *
 * @param {object} question
 * @param {{callback?: function, mode: (undefined|"notebook")}} config
 */
export function visitQuestionAdhoc(
  question,
  { callback, mode, autorun = true, skipWaiting = false } = {},
) {
  const questionMode = mode === "notebook" ? "/notebook" : "";

  const [url, alias] = getInterceptDetails(question, mode, autorun);

  cy.intercept(url).as(alias);

  cy.visit(`/question${questionMode}#` + adhocQuestionHash(question));

  runQueryIfNeeded(question, autorun);

  if (mode !== "notebook" && !skipWaiting) {
    cy.wait("@" + alias).then(xhr => callback && callback(xhr));
  }
}

/**
 * Open a table as an ad-hoc query in a simple or a notebook mode, and optionally limit the number of results.
 *
 * @param {Object} config
 * @param {number} [config.database=SAMPLE_DB_ID]
 * @param {number} config.table
 * @param {("notebook"|undefined)} [config.mode]
 * @param {number} [config.limit]
 * @param {function} [config.callback]
 */
export function openTable({
  database = SAMPLE_DB_ID,
  table,
  mode = null,
  limit,
  callback,
} = {}) {
  visitQuestionAdhoc(
    {
      dataset_query: {
        database,
        query: {
          "source-table": table,
          limit,
        },
        type: "query",
      },
    },
    { mode, callback },
  );
}

/**
 *
 * @typedef {{mode?: "notebook", limit?: number, callback?: function }} OpenTablesProps
 */

/**
 * @param {OpenTablesProps} props
 */
export function openProductsTable({ mode, limit, callback } = {}) {
  return openTable({ table: STATIC_PRODUCTS_ID, mode, limit, callback });
}

/**
 * @param {OpenTablesProps} props
 */
export function openOrdersTable({ mode, limit, callback } = {}) {
  return openTable({ table: STATIC_ORDERS_ID, mode, limit, callback });
}

/**
 * @param {OpenTablesProps} props
 */
export function openPeopleTable({ mode, limit, callback } = {}) {
  return openTable({ table: STATIC_PEOPLE_ID, mode, limit, callback });
}

/**
 * @param {OpenTablesProps} props
 */
export function openReviewsTable({ mode, limit, callback } = {}) {
  return openTable({ table: STATIC_REVIEWS_ID, mode, limit, callback });
}

function getInterceptDetails(question, mode, autorun) {
  const {
    display,
    dataset_query: { type },
  } = question;

  // When visiting notebook mode directly, we don't render any results to the page.
  // Therefore, there is no `dataset` to wait for.
  // But we need to make sure the schema for our database is loaded before we can proceed.
  if (mode === "notebook") {
    return [`/api/database/${SAMPLE_DB_ID}/schema/PUBLIC`, "publicSchema"];
  }

  // Ad-hoc native queries are not autorun by default.
  // Therefore, there is no `dataset` to wait for.
  // We need to make sure data for the native query builder has loaded before we can proceed.
  if (type === "native" && !autorun) {
    return ["/api/native-query-snippet", "snippets"];
  }

  // native queries should use the normal dataset endpoint even when set to pivot
  const isPivotEndpoint = display === "pivot" && type === "query";

  const url = isPivotEndpoint ? "/api/dataset/pivot" : "/api/dataset";
  const alias = isPivotEndpoint ? "pivotDataset" : "dataset";

  return [url, alias];
}

function runQueryIfNeeded(question, autorun) {
  const {
    dataset_query: { type },
  } = question;

  if (type === "native" && autorun) {
    runNativeQuery({ wait: false });
  }
}
