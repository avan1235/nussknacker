import { WindowButtonProps, WindowContentProps } from "@touk/window-manager";
import React, { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { visualizationUrl } from "../common/VisualizationUrl";
import { useProcessNameValidators } from "../containers/hooks/useProcessNameValidators";
import HttpService from "../http/HttpService";
import "../stylesheets/visualization.styl";
import { WindowContent } from "../windowManager";
import { AddProcessForm } from "./AddProcessForm";
import { allValid, errorValidator } from "./graph/node-modal/editors/Validators";
import { useNavigate } from "react-router-dom";

interface AddProcessDialogProps extends WindowContentProps {
    isFragment?: boolean;
}

export function AddProcessDialog(props: AddProcessDialogProps): JSX.Element {
    const { isFragment, ...passProps } = props;
    const nameValidators = useProcessNameValidators();

    const [value, setState] = useState({ processId: "", processCategory: "" });
    const [processNameError, setProcessNameError] = useState({
        fieldName: "processName",
        message: "",
        description: "",
        typ: "",
    });

    const isValid = useMemo(() => value.processCategory && allValid(nameValidators, [value.processId]), [nameValidators, value]);

    const navigate = useNavigate();
    const createProcess = useCallback(async () => {
        if (isValid) {
            const { processId, processCategory } = value;
            try {
                await HttpService.createProcess(processId, processCategory, isFragment);
                passProps.close();
                navigate(visualizationUrl(processId));
            } catch (error) {
                if (error?.response?.status == 400) {
                    //todo: change to pass error from BE as whole object not just the message
                    setProcessNameError({ fieldName: "processName", message: error?.response?.data, description: "", typ: "" });
                } else {
                    throw error;
                }
            }
        }
    }, [isFragment, isValid, navigate, passProps, value]);

    const { t } = useTranslation();
    const buttons: WindowButtonProps[] = useMemo(
        () => [
            { title: t("dialog.button.cancel", "Cancel"), action: () => passProps.close() },
            { title: t("dialog.button.create", "create"), action: () => createProcess(), disabled: !isValid },
        ],
        [createProcess, isValid, passProps, t],
    );

    return (
        <WindowContent buttons={buttons} {...passProps}>
            <AddProcessForm
                value={value}
                onChange={setState}
                nameValidators={nameValidators.concat(errorValidator([processNameError], "processName"))}
            />
        </WindowContent>
    );
}

export default AddProcessDialog;
