const { H } = cy;
import { SAMPLE_DB_ID, USERS, USER_GROUPS } from "e2e/support/cypress_data";
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";
import {
  NORMAL_USER_ID,
  ORDERS_DASHBOARD_DASHCARD_ID,
  ORDERS_DASHBOARD_ID,
} from "e2e/support/cypress_sample_instance_data";

const {
  ORDERS,
  ORDERS_ID,
  PRODUCTS,
  PRODUCTS_ID,
  REVIEWS,
  REVIEWS_ID,
  PEOPLE,
  PEOPLE_ID,
} = SAMPLE_DATABASE;

const { ALL_USERS_GROUP, DATA_GROUP, COLLECTION_GROUP } = USER_GROUPS;

const VIEW_DATA_PERMISSION_INDEX = 0;

describe("admin > permissions > sandboxes (tested via the API)", () => {
  describe("admin", () => {
    beforeEach(() => {
      H.restore();
      cy.signInAsAdmin();
      H.activateToken("pro-self-hosted");
      preparePermissions();
      cy.visit("/admin/people");
    });

    it("should add key attributes to an existing user", () => {
      cy.findByTestId("admin-people-list-table")
        .icon("ellipsis")
        .first()
        .click();
      H.popover().findByText("Edit user").click();
      H.modal().within(() => {
        cy.findByText("Attributes").click();
        cy.findByText("Add an attribute").click();
        cy.findByPlaceholderText("Key").type("User ID");
        cy.findByPlaceholderText("Value").type("3");
        cy.findByText("Update").click();
      });
    });

    it("should add key attributes to a new user", () => {
      cy.button("Invite someone").click();
      H.modal().within(() => {
        cy.findByPlaceholderText("Johnny").type("John");
        cy.findByPlaceholderText("Appleseed").type("Smith");
        cy.findByPlaceholderText("nicetoseeyou@email.com").type(
          "john@smith.test",
        );

        cy.findByText("Attributes").click();
        cy.findByText("Add an attribute").click();
        cy.findByPlaceholderText("Key").type("User ID");
        cy.findByPlaceholderText("Value").type("1");
        cy.findAllByText("Create").click();
        cy.button("Done").click();
      });
    });
  });

  describe("normal user", () => {
    const USER_ATTRIBUTE = "User ID";
    const ATTRIBUTE_VALUE = "3";
    const TTAG_NAME = "cid";
    const QUESTION_NAME = "Joined test";

    beforeEach(() => {
      H.restore();
      cy.signInAsAdmin();
      H.activateToken("pro-self-hosted");
      preparePermissions();

      // Add user attribute to existing ("normal" / id:2) user
      cy.request("PUT", `/api/user/${NORMAL_USER_ID}`, {
        login_attributes: { [USER_ATTRIBUTE]: ATTRIBUTE_VALUE },
      });

      // Orders join Products
      createJoinedQuestion(QUESTION_NAME);

      cy.sandboxTable({
        table_id: ORDERS_ID,
        group_id: DATA_GROUP,
        attribute_remappings: {
          [USER_ATTRIBUTE]: ["dimension", ["field", ORDERS.USER_ID, null]],
        },
      });

      H.createNativeQuestion({
        name: "sql param",
        native: {
          query: `select id,name,address,email from people where {{${TTAG_NAME}}}`,
          "template-tags": {
            [TTAG_NAME]: {
              id: "6b8b10ef-0104-1047-1e1b-2492d5954555",
              name: TTAG_NAME,
              "display-name": "CID",
              type: "dimension",
              dimension: ["field", PEOPLE.ID, null],
              "widget-type": "id",
            },
          },
        },
      }).then(({ body: { id: QUESTION_ID } }) => {
        cy.sandboxTable({
          table_id: PEOPLE_ID,
          card_id: QUESTION_ID,
          group_id: DATA_GROUP,
          attribute_remappings: {
            [USER_ATTRIBUTE]: ["dimension", ["template-tag", TTAG_NAME]],
          },
        });
      });

      cy.signOut();
      cy.signInAsNormalUser();
    });

    describe("table sandboxed on a user attribute", () => {
      it("should display correct number of orders", () => {
        H.openOrdersTable();
        // 10 rows filtered on User ID
        cy.findAllByText(ATTRIBUTE_VALUE).should("have.length", 10);
        H.assertDatasetReqIsSandboxed({
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(ATTRIBUTE_VALUE),
        });
      });
    });

    describe("question with joins", () => {
      it("should be sandboxed even after applying a filter to the question", () => {
        cy.log("Open saved question with joins");
        H.visitQuestion("@questionId");

        cy.log("Make sure user is initially sandboxed");
        cy.get(".test-TableInteractive-cellWrapper--firstColumn").should(
          "have.length",
          10,
        );

        cy.log("Add filter to a question");
        H.openNotebook();
        H.filter({ mode: "notebook" });
        H.popover().findByText("Total").click();
        H.selectFilterOperator("Greater than");
        H.popover().within(() => {
          cy.findByPlaceholderText("Enter a number").type("100");
          cy.button("Add filter").click();
        });

        H.visualize();
        cy.log("Make sure user is still sandboxed");
        H.assertDatasetReqIsSandboxed({
          columnId: ORDERS.USER_ID,
          columnAssetion: ATTRIBUTE_VALUE,
        });
        cy.get(".test-TableInteractive-cellWrapper--firstColumn").should(
          "have.length",
          6,
        );
      });
    });

    describe("table sandboxed on a saved parameterized SQL question", () => {
      it("should show filtered categories", () => {
        H.openPeopleTable();
        H.assertDatasetReqIsSandboxed({
          columnId: PEOPLE.ID,
          columnAssertion: Number(ATTRIBUTE_VALUE),
        });
        cy.findAllByTestId("header-cell").should("have.length", 4);
        cy.get(".test-TableInteractive-cellWrapper--firstColumn").should(
          "have.length",
          1,
        );
      });
    });
  });

  describe("sandboxed user", () => {
    const allCategories = ["Gadget", "Gizmo", "Doohickey", "Widget"];

    function verifyCategoryList(visibleCategories) {
      H.popover().within(() => {
        allCategories.forEach((value) => {
          if (visibleCategories.includes(value)) {
            cy.findByText(value).should("be.visible");
          } else {
            cy.findByText(value).should("not.exist");
          }
        });
      });
    }

    beforeEach(() => {
      H.restore();
      cy.signInAsAdmin();
      H.activateToken("pro-self-hosted");
      preparePermissions();
    });

    it("should show field values for sandboxed users", () => {
      cy.log("create another sandboxed user");
      const user = {
        email: "u2@metabase.test",
        password: "12341234",
        login_attributes: {
          attr_uid: "2",
          attr_cat: "Gadget",
        },
        user_group_memberships: [
          { id: ALL_USERS_GROUP, is_group_manager: false },
          { id: COLLECTION_GROUP, is_group_manager: false },
        ],
      };
      cy.createUserFromRawData(user);

      cy.log("setup sandboxing");
      cy.visit(
        `/admin/permissions/data/database/${SAMPLE_DB_ID}/schema/PUBLIC/table/${PRODUCTS_ID}`,
      );
      H.modifyPermission("collection", 0, "Sandboxed");
      H.modal().findByText("Pick a column").click();
      H.popover().findByText("Category").click();
      H.modal().findByPlaceholderText("Pick a user attribute").click();
      H.popover().findByText("attr_cat").click();
      H.modal().button("Save").click();
      H.savePermissions();

      cy.log("setup a dashboard");
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.editDashboard();
      H.setFilter("Text or Category", "Is");
      H.selectDashboardFilter(H.getDashboardCard(), "Category");
      H.saveDashboard();

      cy.log("field values for admin");
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.filterWidget().click();
      verifyCategoryList(allCategories);

      cy.log("field values for the first sandboxed user");
      cy.signIn("sandboxed");
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.filterWidget().click();
      verifyCategoryList(["Widget"]);

      cy.log("field values for the second sandboxed user");
      cy.request("POST", "/api/session", {
        username: user.email,
        password: user.password,
      });
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.filterWidget().click();
      verifyCategoryList(["Gadget"]);
    });
  });

  describe("Sandboxing reproductions", () => {
    beforeEach(() => {
      H.restore();
      cy.signInAsAdmin();
      H.activateToken("pro-self-hosted");
      preparePermissions();
    });

    it("should allow using a dashboard question as a sandbox source", () => {
      const USER_ATTRIBUTE = "User ID";
      const ATTRIBUTE_VALUE = "3";
      const TTAG_NAME = "cid";
      const QUESTION_NAME = "Joined test";

      // Add user attribute to existing ("normal" / id:2) user
      cy.request("PUT", `/api/user/${NORMAL_USER_ID}`, {
        login_attributes: { [USER_ATTRIBUTE]: ATTRIBUTE_VALUE },
      });

      // Orders join Products
      createJoinedQuestion(QUESTION_NAME);

      cy.sandboxTable({
        table_id: ORDERS_ID,
        group_id: DATA_GROUP,
        attribute_remappings: {
          [USER_ATTRIBUTE]: ["dimension", ["field", ORDERS.USER_ID, null]],
        },
      });

      H.createNativeQuestion({
        name: "sql param in a dashboard",
        dashboard_id: ORDERS_DASHBOARD_ID,
        native: {
          query: `select id,name,address,email from people where {{${TTAG_NAME}}}`,
          "template-tags": {
            [TTAG_NAME]: {
              id: "6b8b10ef-0104-1047-1e1b-2492d5954555",
              name: TTAG_NAME,
              "display-name": "CID",
              type: "dimension",
              dimension: ["field", PEOPLE.ID, null],
              "widget-type": "id",
            },
          },
        },
      }).then(({ body: { id: QUESTION_ID } }) => {
        cy.sandboxTable({
          table_id: PEOPLE_ID,
          card_id: QUESTION_ID,
          group_id: DATA_GROUP,
          attribute_remappings: {
            [USER_ATTRIBUTE]: ["dimension", ["template-tag", TTAG_NAME]],
          },
        });
      });

      cy.signOut();
      cy.signInAsNormalUser();

      // see that the question is in the dashboard
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.dashboardCards().findByText("sql param in a dashboard").should("exist");

      H.openPeopleTable();
      // 1 row filtered on User ID
      cy.findAllByText(ATTRIBUTE_VALUE).should("have.length", 1);
      H.assertDatasetReqIsSandboxed({
        columnId: PEOPLE.USER_ID,
        columnAssertion: Number(ATTRIBUTE_VALUE),
      });
    });

    it("should allow joins to the sandboxed table (metabase-enterprise#154)", () => {
      cy.updatePermissionsGraph({
        [COLLECTION_GROUP]: {
          [SAMPLE_DB_ID]: {
            "view-data": "unrestricted",
            "create-queries": {
              PUBLIC: {
                [ORDERS_ID]: "query-builder",
                [PRODUCTS_ID]: "query-builder",
                [REVIEWS_ID]: "query-builder",
              },
            },
          },
        },
      });

      cy.sandboxTable({
        table_id: PEOPLE_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field", PEOPLE.ID, null]],
        },
      });

      cy.signOut();
      cy.signInAsSandboxedUser();

      H.openOrdersTable({ mode: "notebook" });
      H.summarize({ mode: "notebook" });
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Count of rows").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Pick a column to group by").click();

      cy.log(
        "Original issue reported failure to find 'User' group / foreign key",
      );

      H.popover().within(() => {
        // Collapse "Order/s/" in order to bring "User" into view (trick to get around virtualization - credits: @flamber)
        cy.get("[data-element-id=list-section-header]")
          .contains(/Orders?/)
          .click();

        cy.get("[data-element-id=list-section-header]")
          .contains("User")
          .click();

        cy.get("[data-element-id=list-item]").contains("ID").click();
      });

      H.visualize();

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Count by User → ID");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("11"); // Sum of orders for user with ID #1
      H.assertQueryBuilderRowCount(2); // test that user is sandboxed - normal users has over 2000 rows
      H.assertDatasetReqIsSandboxed();
    });

    // Note: This issue was ported from EE repo - it was previously known as (metabase-enterprise#548)
    it("SB question with `case` CC should substitute the `else` argument's table (metabase#14859)", () => {
      const QUESTION_NAME = "EE_548";
      const CC_NAME = "CC_548"; // Custom column

      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
        },
      });

      H.createQuestion({
        name: QUESTION_NAME,
        query: {
          expressions: {
            [CC_NAME]: [
              "case",
              [
                [
                  [">", ["field", ORDERS.DISCOUNT, null], 0],
                  ["field", ORDERS.DISCOUNT],
                  null,
                ],
              ],
              { default: ["field", ORDERS.TOTAL, null] },
            ],
          },
          "source-table": ORDERS_ID,
        },
      }).then(({ body: { id: QUESTION_ID } }) => {
        cy.signOut();
        cy.signInAsSandboxedUser();

        // Assertion phase starts here
        H.visitQuestion(QUESTION_ID);
        cy.findByText(QUESTION_NAME);

        cy.log("Reported failing since v1.36.4");
        cy.contains(CC_NAME);
        H.assertQueryBuilderRowCount(11); // test that user is sandboxed - normal users has over 2000 rows
        H.assertDatasetReqIsSandboxed({
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
          requestAlias: `@cardQuery${QUESTION_ID}`,
        });
      });
    });

    ["remapped", "default"].forEach((test) => {
      it(`${test.toUpperCase()} version:\n drill-through should work on implicit joined tables with sandboxes (metabase#13641)`, () => {
        const QUESTION_NAME = "13641";

        if (test === "remapped") {
          cy.log("Remap Product ID's display value to `title`");
          H.remapDisplayValueToFK({
            display_value: ORDERS.PRODUCT_ID,
            name: "Product ID",
            fk: PRODUCTS.TITLE,
          });
        }

        cy.updatePermissionsGraph({
          [COLLECTION_GROUP]: {
            [SAMPLE_DB_ID]: {
              "view-data": {
                PUBLIC: {
                  [PRODUCTS_ID]: "unrestricted",
                },
              },
              "create-queries": {
                PUBLIC: {
                  [PRODUCTS_ID]: "query-builder",
                },
              },
            },
          },
        });

        cy.sandboxTable({
          table_id: ORDERS_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
          },
        });

        cy.log(
          "Create question based on steps in [#13641](https://github.com/metabase/metabase/issues/13641)",
        );
        H.createQuestion({
          name: QUESTION_NAME,
          query: {
            aggregation: [["count"]],
            breakout: [
              [
                "field",
                PRODUCTS.CATEGORY,
                { "source-field": ORDERS.PRODUCT_ID },
              ],
            ],
            "source-table": ORDERS_ID,
          },
          display: "bar",
        });

        cy.signOut();
        cy.signInAsSandboxedUser();

        cy.intercept("POST", "/api/card/*/query").as("cardQuery");
        cy.intercept("POST", "/api/dataset").as("dataset");

        // Find saved question in "Our analytics"
        cy.visit("/collection/root");
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText(QUESTION_NAME).click();

        cy.wait("@cardQuery");
        // Drill-through
        cy.findByTestId("query-visualization-root").within(() => {
          // Click on the first bar in a graph (Category: "Doohickey")
          H.chartPathWithFillColor("#509EE3").eq(0).click();
        });
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText("See these Orders").click();

        cy.log("Reported failing on v1.37.0.2");
        cy.wait("@dataset").then((xhr) => {
          expect(xhr.response.body.error).not.to.exist;
        });
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText("Product → Category is Doohickey");
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText("97.44"); // Subtotal for order #10
        H.assertQueryBuilderRowCount(2); // test that user is sandboxed - normal users has over 2000 rows
        H.assertDatasetReqIsSandboxed({
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
        });
      });
    });

    it("should allow drill-through for sandboxed user (metabase-enterprise#535)", () => {
      const PRODUCTS_ALIAS = "Products";
      const QUESTION_NAME = "EE_535";

      cy.updatePermissionsGraph({
        [COLLECTION_GROUP]: {
          [SAMPLE_DB_ID]: {
            "view-data": "unrestricted",
            "create-queries": {
              PUBLIC: {
                [PRODUCTS_ID]: "query-builder",
              },
            },
          },
        },
      });

      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
        },
      });

      cy.log(
        "Create question based on steps in https://github.com/metabase/metabase-enterprise/issues/535",
      );
      H.createQuestion({
        name: QUESTION_NAME,
        query: {
          aggregation: [["count"]],
          breakout: [
            ["field", PRODUCTS.CATEGORY, { "join-alias": PRODUCTS_ALIAS }],
          ],
          joins: [
            {
              alias: PRODUCTS_ALIAS,
              condition: [
                "=",
                ["field", ORDERS.PRODUCT_ID, null],
                ["field", PRODUCTS.ID, { "join-alias": PRODUCTS_ALIAS }],
              ],
              fields: "all",
              "source-table": PRODUCTS_ID,
            },
          ],
          "source-table": ORDERS_ID,
        },
        display: "bar",
      });

      cy.signOut();
      cy.signInAsSandboxedUser();

      cy.intercept("POST", "/api/card/*/query").as("cardQuery");
      cy.intercept("POST", "/api/dataset").as("dataset");

      // Find saved question in "Our analytics"
      cy.visit("/collection/root");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText(QUESTION_NAME).click();

      cy.wait("@cardQuery");
      // Drill-through
      cy.findByTestId("query-visualization-root").within(() => {
        // Click on the first bar in a graph (Category: "Doohickey")
        H.chartPathWithFillColor("#509EE3").eq(0).click();
      });
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("See these Orders").click();

      cy.wait("@dataset");
      cy.log("Reported failing on v1.36.4");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Products → Category is Doohickey");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("97.44"); // Subtotal for order #10
      H.assertQueryBuilderRowCount(2); // test that user is sandboxed - normal users has over 2000 rows
      H.assertDatasetReqIsSandboxed({
        columnId: ORDERS.USER_ID,
        columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
      });
    });

    describe("with display values remapped to use a foreign key", () => {
      beforeEach(() => {
        cy.intercept("POST", "/api/dataset").as("datasetQuery");
        cy.log("Remap Product ID's display value to `title`");
        H.remapDisplayValueToFK({
          display_value: ORDERS.PRODUCT_ID,
          name: "Product ID",
          fk: PRODUCTS.TITLE,
        });
      });

      /**
       * There isn't an exact issue that this test reproduces, but it is basically a version of (metabase-enterprise#520)
       * that uses a query builder instead of SQL based questions.
       */
      it("should be able to sandbox using query builder saved questions", () => {
        cy.log("Create 'Orders'-based question using QB");
        H.createQuestion({
          name: "520_Orders",
          query: {
            "source-table": ORDERS_ID,
            filter: [">", ["field", ORDERS.TOTAL, null], 10],
          },
        }).then(({ body: { id: CARD_ID } }) => {
          cy.sandboxTable({
            table_id: ORDERS_ID,
            card_id: CARD_ID,
            attribute_remappings: {
              attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
            },
          });
        });

        cy.log("Create 'Products'-based question using QB");
        H.createQuestion({
          name: "520_Products",
          query: {
            "source-table": PRODUCTS_ID,
            filter: [">", ["field", PRODUCTS.PRICE, null], 10],
          },
        }).then(({ body: { id: CARD_ID } }) => {
          cy.sandboxTable({
            table_id: PRODUCTS_ID,
            card_id: CARD_ID,
            attribute_remappings: {
              attr_cat: ["dimension", ["field", PRODUCTS.CATEGORY, null]],
            },
          });
        });

        cy.signOut();
        cy.signInAsSandboxedUser();

        H.openOrdersTable({
          callback: (xhr) => expect(xhr.response.body.error).not.to.exist,
        });

        cy.wait("@datasetQuery");

        H.assertQueryBuilderRowCount(11); // test that user is sandboxed - normal users has over 2000 rows
        H.assertDatasetReqIsSandboxed({
          requestAlias: "@datasetQuery",
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
        });

        H.tableInteractive().findByText("Awesome Concrete Shoes").click();
        H.popover()
          .findByText(/View details/i)
          .click();

        cy.log(
          "It should show object details instead of filtering by this Product ID",
        );
        cy.findByTestId("object-detail");
        cy.findAllByText("McClure-Lockman");
      });

      it("Advanced sandboxing should not ignore data model features like object detail of FK (metabase-enterprise#520)", () => {
        cy.intercept("POST", "/api/card/*/query").as("cardQuery");
        cy.intercept("PUT", "/api/card/*").as("questionUpdate");

        H.createNativeQuestion({
          name: "EE_520_Q1",
          native: {
            query:
              "SELECT * FROM ORDERS WHERE USER_ID={{sandbox}} AND TOTAL > 10",
            "template-tags": {
              sandbox: {
                "display-name": "Sandbox",
                id: "1115dc4f-6b9d-812e-7f72-b87ab885c88a",
                name: "sandbox",
                type: "number",
              },
            },
          },
        }).then(({ body: { id: CARD_ID } }) => {
          runQuestion({ question: CARD_ID, sandboxValue: "1" });

          cy.sandboxTable({
            table_id: ORDERS_ID,
            card_id: CARD_ID,
            attribute_remappings: {
              attr_uid: ["variable", ["template-tag", "sandbox"]],
            },
          });
        });

        H.createNativeQuestion({
          name: "EE_520_Q2",
          native: {
            query:
              "SELECT * FROM PRODUCTS WHERE CATEGORY={{sandbox}} AND PRICE > 10",
            "template-tags": {
              sandbox: {
                "display-name": "Sandbox",
                id: "3d69ba99-7076-2252-30bd-0bb8810ba895",
                name: "sandbox",
                type: "text",
              },
            },
          },
        }).then(({ body: { id: CARD_ID } }) => {
          runQuestion({ question: CARD_ID, sandboxValue: "Widget" });

          cy.sandboxTable({
            table_id: PRODUCTS_ID,
            card_id: CARD_ID,
            attribute_remappings: {
              attr_cat: ["variable", ["template-tag", "sandbox"]],
            },
          });
        });

        cy.signOut();
        cy.signInAsSandboxedUser();

        H.openOrdersTable();

        cy.log("Reported failing on v1.36.x");

        cy.log("It should show remapped Display Values instead of Product ID");
        cy.get("[data-testid=cell-data]")
          .contains("Awesome Concrete Shoes")
          .click();
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText(/View details/i).click();

        cy.log(
          "It should show object details instead of filtering by this Product ID",
        );
        // The name of this Vendor is visible in "details" only
        cy.findByTestId("object-detail");
        cy.findAllByText("McClure-Lockman");

        /**
         * Helper function related to this test only!
         */
        function runQuestion({ question, sandboxValue } = {}) {
          // Run the question
          cy.visit(`/question/${question}?sandbox=${sandboxValue}`);
          // Wait for results
          cy.wait("@cardQuery");
        }
      });

      it("simple sandboxing should work (metabase#14629)", () => {
        cy.updatePermissionsGraph({
          [COLLECTION_GROUP]: {
            [SAMPLE_DB_ID]: {
              "view-data": {
                PUBLIC: {
                  [PRODUCTS_ID]: "unrestricted",
                },
              },
            },
          },
        });

        cy.sandboxTable({
          table_id: ORDERS_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
          },
        });

        cy.signOut();
        cy.signInAsSandboxedUser();
        H.openOrdersTable({
          callback: (xhr) => expect(xhr.response.body.error).not.to.exist,
        });
        H.assertQueryBuilderRowCount(11); // test that user is sandboxed - normal users has over 2000 rows
        H.assertDatasetReqIsSandboxed({
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
        });

        // Title of the first order for User ID = 1
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText("Awesome Concrete Shoes");

        cy.signOut();
        cy.signInAsAdmin();
        cy.visit(
          "/admin/permissions/data/group/3/database/1/schema/PUBLIC/5/segmented",
        );
      });
    });

    ["remapped", "default"].forEach((test) => {
      it(`${test.toUpperCase()} version:\n should work on questions with joins, with sandboxed target table, where target fields cannot be filtered (metabase#13642)`, () => {
        const QUESTION_NAME = "13642";
        const PRODUCTS_ALIAS = "Products";

        if (test === "remapped") {
          cy.log("Remap Product ID's display value to `title`");
          H.remapDisplayValueToFK({
            display_value: ORDERS.PRODUCT_ID,
            name: "Product ID",
            fk: PRODUCTS.TITLE,
          });
        }

        cy.sandboxTable({
          table_id: ORDERS_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
          },
        });

        cy.sandboxTable({
          table_id: PRODUCTS_ID,
          attribute_remappings: {
            attr_cat: ["dimension", ["field", PRODUCTS.CATEGORY, null]],
          },
        });

        H.createQuestion({
          name: QUESTION_NAME,
          query: {
            aggregation: [["count"]],
            breakout: [
              ["field", PRODUCTS.CATEGORY, { "join-alias": PRODUCTS_ALIAS }],
            ],
            joins: [
              {
                fields: "all",
                "source-table": PRODUCTS_ID,
                condition: [
                  "=",
                  ["field", ORDERS.PRODUCT_ID, null],
                  ["field", PRODUCTS.ID, { "join-alias": PRODUCTS_ALIAS }],
                ],
                alias: PRODUCTS_ALIAS,
              },
            ],
            "source-table": ORDERS_ID,
          },
          display: "bar",
        });

        cy.signOut();
        cy.signInAsSandboxedUser();

        cy.intercept("POST", "/api/card/*/query").as("cardQuery");
        cy.intercept("POST", "/api/dataset").as("dataset");

        cy.visit("/collection/root");
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText(QUESTION_NAME).click();

        cy.wait("@cardQuery");
        H.assertQueryBuilderRowCount(2); // test that user is sandboxed - normal users has 4
        H.assertDatasetReqIsSandboxed({ requestAlias: "@cardQuery" });

        // Drill-through
        cy.findByTestId("query-visualization-root").within(() => {
          // Click on the second bar in a graph (Category: "Widget")
          H.chartPathWithFillColor("#509EE3").eq(1).click();
        });
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.findByText("See these Orders").click();

        cy.wait("@dataset").then((xhr) => {
          expect(xhr.response.body.error).not.to.exist;
        });
        // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
        cy.contains("37.65");
        H.assertQueryBuilderRowCount(6); // test that user is sandboxed - normal users has over 2000
        H.assertDatasetReqIsSandboxed({ requestAlias: "@dataset" });
      });
    });

    it("attempt to sandbox based on question with differently-typed columns than a sandboxed table should provide meaningful UI error (metabase#14612)", () => {
      const QUESTION_NAME = "Different type";
      const ERROR_MESSAGE =
        "Sandbox Questions can't return columns that have different types than the Table they are sandboxing.";

      cy.intercept("POST", "/api/mt/gtap/validate").as("sandboxTable");
      cy.intercept("GET", "/api/permissions/group").as("tablePermissions");

      // Question with differently-typed columns than the sandboxed table
      H.createNativeQuestion({
        name: QUESTION_NAME,
        native: { query: "SELECT CAST(ID AS VARCHAR) AS ID FROM ORDERS;" },
      });

      cy.visit(
        `/admin/permissions/data/database/${SAMPLE_DB_ID}/schema/PUBLIC/table/${ORDERS_ID}`,
      );
      cy.wait("@tablePermissions");
      cy.icon("eye")
        .eq(1) // No better way of doing this, unfortunately (see table above)
        .click();
      H.popover().findByText("Sandboxed").click();
      cy.button("Change").click();
      H.modal()
        .findByText(
          "Use a saved question to create a custom view for this table",
        )
        .click();

      H.modal().findByText("Select a question").click();

      H.entityPickerModal().findByText(QUESTION_NAME).click();
      H.modal().button("Save").click();
      cy.wait("@sandboxTable").then(({ response }) => {
        expect(response.statusCode).to.eq(400);
        expect(response.body.message).to.eq(ERROR_MESSAGE);
      });
      H.modal().scrollTo("bottom");
      H.modal().findByText(ERROR_MESSAGE);
    });

    it("should be able to use summarize columns from joined table based on a saved question (metabase#14766)", () => {
      createJoinedQuestion("14766_joined");

      H.startNewQuestion();
      H.entityPickerModal().within(() => {
        H.entityPickerModalTab("Collections").click();
        cy.findByText("14766_joined").click();
      });
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Pick a function or metric").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Count of rows").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Pick a column to group by").click();
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText(/Products? → ID/).click();

      H.visualize((response) => {
        expect(response.body.error).to.not.exist;
      });

      // Number of products with ID = 1 (and ID = 19)
      cy.findAllByText("93");
    });

    it("should be able to remove columns via QB sidebar / settings (metabase#14841)", () => {
      cy.intercept("POST", "/api/dataset").as("dataset");

      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field-id", ORDERS.USER_ID]],
        },
      });

      cy.sandboxTable({
        table_id: PRODUCTS_ID,
        attribute_remappings: {
          attr_cat: ["dimension", ["field", PRODUCTS.CATEGORY, null]],
        },
      });

      cy.signOut();
      cy.signInAsSandboxedUser();
      createJoinedQuestion("14841", { visitQuestion: true });

      H.openVizSettingsSidebar();
      cy.findByTestId("sidebar-left")
        .should("be.visible")
        .within(() => {
          // Remove the "Subtotal" column from within sidebar
          cy.findByTestId("draggable-item-Subtotal")
            .icon("eye_outline")
            .click({ force: true });
        });

      cy.button("Done").click();

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.contains("Subtotal").should("not.exist");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.contains("37.65").should("not.exist");
      H.assertQueryBuilderRowCount(11); // test that user is sandboxed - normal users has over 2000 rows
      H.assertDatasetReqIsSandboxed({
        requestAlias: "@cardQuery",
        columnId: ORDERS.USER_ID,
        columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
      });
    });

    it("should work with pivot tables (metabase#14969)", () => {
      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field-id", ORDERS.USER_ID]],
        },
      });

      cy.sandboxTable({
        table_id: PEOPLE_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field-id", PEOPLE.ID]],
        },
      });

      cy.sandboxTable({
        table_id: PRODUCTS_ID,
        attribute_remappings: {
          attr_cat: ["dimension", ["field-id", PRODUCTS.CATEGORY]],
        },
      });

      cy.request("POST", "/api/card/", {
        name: "14969",
        dataset_query: {
          type: "query",
          query: {
            "source-table": ORDERS_ID,
            joins: [
              {
                fields: "all",
                "source-table": PEOPLE_ID,
                condition: [
                  "=",
                  ["field-id", ORDERS.USER_ID],
                  ["joined-field", "People - User", ["field-id", PEOPLE.ID]],
                ],
                alias: "People - User",
              },
            ],
            aggregation: [["sum", ["field-id", ORDERS.TOTAL]]],
            breakout: [
              ["joined-field", "People - User", ["field-id", PEOPLE.SOURCE]],
              [
                "fk->",
                ["field-id", ORDERS.PRODUCT_ID],
                ["field-id", PRODUCTS.CATEGORY],
              ],
            ],
          },
          database: SAMPLE_DB_ID,
        },
        display: "pivot",
        visualization_settings: {},
      }).then(({ body: { id: QUESTION_ID } }) => {
        cy.signOut();
        cy.signInAsSandboxedUser();

        H.visitQuestion(QUESTION_ID);
        H.assertDatasetReqIsSandboxed({
          requestAlias: `@cardQuery${QUESTION_ID}`,
        });
      });

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Twitter");
      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.findByText("Row totals");
      H.assertQueryBuilderRowCount(6); // test that user is sandboxed - normal users has 30
    });

    it("should show dashboard subscriptions for sandboxed user (metabase#14990)", () => {
      H.setupSMTP();

      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: ["dimension", ["field-id", ORDERS.USER_ID]],
        },
      });

      cy.signInAsSandboxedUser();
      H.visitDashboard(ORDERS_DASHBOARD_ID);
      H.openSharingMenu("Subscriptions");

      // should forward to email since that is the only one setup
      H.sidebar().findByText("Email this dashboard").should("exist");

      // test that user is sandboxed - normal users has over 2000 rows
      H.getDashboardCard().within(() => {
        H.assertTableRowsCount(11);
      });
      H.assertDatasetReqIsSandboxed({
        requestAlias: `@dashcardQuery${ORDERS_DASHBOARD_DASHCARD_ID}`,
        columnId: ORDERS.USER_ID,
        columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
      });
    });

    it("should be able to visit ad-hoc/dirty question when permission is granted to the linked table column, but not to the linked table itself (metabase#15105)", () => {
      cy.sandboxTable({
        table_id: ORDERS_ID,
        attribute_remappings: {
          attr_uid: [
            "dimension",
            ["fk->", ["field-id", ORDERS.USER_ID], ["field-id", PEOPLE.ID]],
          ],
        },
      });

      cy.signOut();
      cy.signInAsSandboxedUser();

      H.openOrdersTable({
        callback: (xhr) => expect(xhr.response.body.error).not.to.exist,
      });

      // eslint-disable-next-line no-unscoped-text-selectors -- deprecated usage
      cy.contains("37.65");
    });

    it(
      "unsaved/dirty query should work on linked table column with multiple dimensions and remapping (metabase#15106)",
      { tags: "@flaky" },
      () => {
        H.remapDisplayValueToFK({
          display_value: ORDERS.USER_ID,
          name: "User ID",
          fk: PEOPLE.NAME,
        });

        // Remap REVIEWS.PRODUCT_ID Field Type to ORDERS.ID
        cy.request("PUT", `/api/field/${REVIEWS.PRODUCT_ID}`, {
          table_id: REVIEWS_ID,
          special_type: "type/FK",
          name: "PRODUCT_ID",
          fk_target_field_id: ORDERS.ID,
          display_name: "Product ID",
        });

        cy.sandboxTable({
          table_id: ORDERS_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field-id", ORDERS.USER_ID]],
          },
        });

        cy.sandboxTable({
          table_id: PEOPLE_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field-id", PEOPLE.ID]],
          },
        });

        cy.sandboxTable({
          table_id: REVIEWS_ID,
          attribute_remappings: {
            attr_uid: [
              "dimension",
              [
                "fk->",
                ["field-id", REVIEWS.PRODUCT_ID],
                ["field-id", ORDERS.USER_ID],
              ],
            ],
          },
        });
        cy.signOut();
        cy.signInAsSandboxedUser();

        H.openReviewsTable({
          callback: (xhr) => expect(xhr.response.body.error).not.to.exist,
        });
        H.assertQueryBuilderRowCount(57); // test that user is sandboxed - normal users has 1,112 rows
        H.assertDatasetReqIsSandboxed();

        // Add positive assertion once this issue is fixed
      },
    );

    it(
      "sandboxed user should receive sandboxed dashboard subscription",
      { tags: "@external" },
      () => {
        H.setupSMTP();

        cy.sandboxTable({
          table_id: ORDERS_ID,
          attribute_remappings: {
            attr_uid: ["dimension", ["field", ORDERS.USER_ID, null]],
          },
        });

        cy.signOut();
        cy.signInAsSandboxedUser();

        H.visitDashboard(ORDERS_DASHBOARD_ID);

        // test that user is sandboxed - normal users has over 2000 rows
        H.getDashboardCard().within(() => {
          H.assertTableRowsCount(11);
        });

        H.assertDatasetReqIsSandboxed({
          requestAlias: `@dashcardQuery${ORDERS_DASHBOARD_DASHCARD_ID}`,
          columnId: ORDERS.USER_ID,
          columnAssertion: Number(USERS.sandboxed.login_attributes.attr_uid),
        });

        H.openSharingMenu("Subscriptions");

        H.sidebar()
          .findByPlaceholderText("Enter user names or email addresses")
          .click();
        H.popover().findByText("User 1").click();
        H.sendEmailAndAssert((email) => {
          expect(email.html).to.include("Orders in a dashboard");
          expect(email.html).to.include("37.65");
          expect(email.html).not.to.include("148.23"); // Order for user with ID 3
        });
      },
    );

    describe("sandbox target matching", () => {
      function verifySandboxModal(target) {
        cy.sandboxTable({
          table_id: PRODUCTS_ID,
          group_id: DATA_GROUP,
          attribute_remappings: {
            attr_cat: target,
          },
        });
        cy.visit(
          `/admin/permissions/data/database/${SAMPLE_DB_ID}/schema/PUBLIC/table/${PRODUCTS_ID}`,
        );
        H.selectPermissionRow("data", VIEW_DATA_PERMISSION_INDEX);
        H.popover().findByText("Edit sandboxed access").click();
        H.modal().findAllByTestId("select-button").contains("Category").click();
        H.popover()
          .findByLabelText("Category")
          .should("have.attr", "aria-selected", "true");
      }

      it("should match targets without dimension of field ref options", () => {
        verifySandboxModal(["dimension", ["field", PRODUCTS.CATEGORY, null]]);
      });

      it("should match targets with dimension options", () => {
        verifySandboxModal([
          "dimension",
          ["field", PRODUCTS.CATEGORY, null],
          { "stage-number": 0 },
        ]);
      });

      it("should match targets with field ref options", () => {
        verifySandboxModal([
          "dimension",
          ["field", PRODUCTS.CATEGORY, { "base-type": "type/Text" }],
          { "stage-number": 0 },
        ]);
      });
    });
  });
});

function createJoinedQuestion(name, { visitQuestion = false } = {}) {
  return H.createQuestion(
    {
      name,

      query: {
        "source-table": ORDERS_ID,
        joins: [
          {
            fields: "all",
            "source-table": PRODUCTS_ID,
            condition: [
              "=",
              ["field", ORDERS.PRODUCT_ID, null],
              ["field", PRODUCTS.ID, { "join-alias": "Products" }],
            ],
            alias: "Products",
          },
        ],
      },
    },
    { wrapId: true, visitQuestion },
  );
}

function preparePermissions() {
  H.blockUserGroupPermissions(USER_GROUPS.ALL_USERS_GROUP);
  H.blockUserGroupPermissions(USER_GROUPS.COLLECTION_GROUP);
  H.blockUserGroupPermissions(USER_GROUPS.READONLY_GROUP);
}
