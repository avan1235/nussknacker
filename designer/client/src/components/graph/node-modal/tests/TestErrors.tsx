import React from "react";
import TipsWarning from "../../../../assets/img/icons/tipsWarning.svg";
import NodeTip from "../NodeTip";
import { useTestResults } from "../TestResultsWrapper";
import { NodeTableBody } from "../NodeDetailsContent/NodeTable";

export default function TestErrors(): JSX.Element {
    const results = useTestResults();

    if (!results.testResultsToShow?.error) {
        return null;
    }

    return (
        <NodeTableBody>
            <div className="node-row">
                <div className="node-label">
                    <NodeTip title={"Test case error"} icon={<TipsWarning />} />
                </div>
                <div className="node-value">
                    <div className="node-error">
                        <>{results.testResultsToShow.error}</>
                    </div>
                </div>
            </div>
        </NodeTableBody>
    );
}
