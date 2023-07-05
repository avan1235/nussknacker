describe("Expression suggester", () => {
    const seed = "suggester";

    before(() => {
        cy.deleteAllTestProcesses({ filter: seed, force: true });
    });

    after(() => {
        cy.deleteAllTestProcesses({ filter: seed });
    });

    it("should display colorfull and sorted completions", () => {
        cy.visitNewProcess(seed, "variables");
        cy.layoutScenario();
        cy.get("[model-id=kafka-string]").trigger("dblclick");
        cy.get("[data-testid=window]").as("modal");
        cy.get("[title=Value]").next().find(".ace_editor").click().type(".").contains(/\.$/);
        cy.get(".ace_autocomplete")
            .should("be.visible")
            .matchImage({
                maxDiffThreshold: 0.0025,
                screenshotConfig: { padding: [25, 1, 1] },
            });
        cy.get("[title=Value]").next().find(".ace_editor").click().type("c").contains(/\.c$/);
        cy.get(".ace_autocomplete")
            .should("be.visible")
            .matchImage({
                maxDiffThreshold: 0.0025,
                screenshotConfig: { padding: [25, 1, 1] },
            });
    });

    it.only("should display javadocs", () => {
        cy.visitNewProcess(seed, "variables");
        cy.get("[title='toggle left panel']").click();
        cy.layoutScenario();
        cy.get("[model-id=kafka-string]").trigger("dblclick");
        cy.get("[data-testid=window]").as("modal");
        cy.intercept("POST", "/api/nodes/*/validation", (request) => {
            if (request.body.nodeData.ref?.parameters[1]?.expression.expression == "#DATE.parseDat") {
                request.alias = "validation";
            }
        });
        cy.get("[title=Value]").next().find(".ace_editor").click().type("{selectall}#DATE.parseDat");
        // We wait for validation result to be sure that red message below the form field will be visible
        cy.wait("@validation").its("response.statusCode").should("eq", 200);
        cy.get(".ace_autocomplete").should("be.visible");
        cy.get("[data-testid=graphPage]").matchImage({
            screenshotConfig: {
                blackout: ["> :not(#nk-graph-main) > div"],
            },
        });
    });

    it("should display completions for second line (bugfix)", () => {
        cy.visitNewProcess(seed, "variables");
        cy.layoutScenario();
        cy.get("[model-id=kafka-string]").trigger("dblclick");
        cy.get("[data-testid=window]").as("modal");
        cy.get("[title=Value]").next().find(".ace_editor").click().type(" +{enter}#").contains(/^.$/m);
        cy.get(".ace_autocomplete")
            .should("be.visible")
            .matchImage({
                maxDiffThreshold: 0.0025,
                screenshotConfig: { padding: [45, 1, 1] },
            });
    });
});
