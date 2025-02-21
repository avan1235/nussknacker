import ace from "ace-builds/src-noconflict/ace";
import { isEmpty, map, overSome } from "lodash";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSelector } from "react-redux";
import { getFeatureSettings, getProcessDefinitionData } from "../../../../../reducers/selectors/settings";
import { getProcessToDisplay } from "../../../../../reducers/selectors/graph";
import { BackendExpressionSuggester, ExpressionSuggester } from "./ExpressionSuggester";
import HttpService from "../../../../../http/HttpService";
import ProcessUtils from "../../../../../common/ProcessUtils";
import ReactDOMServer from "react-dom/server";
import cn from "classnames";
import { allValid, Validator } from "../Validators";
import AceEditor from "./AceWithSettings";
import ValidationLabels from "../../../../modals/ValidationLabels";
import ReactAce from "react-ace/lib/ace";
import { EditorMode, ExpressionLang } from "./types";
import type { Ace } from "ace-builds";

const { TokenIterator } = ace.require("ace/token_iterator");

//to reconsider
// - respect categories for global variables?
// - maybe ESC should be allowed to hide suggestions but leave modal open?

const identifierRegexpsWithoutDot = [/[#a-zA-Z0-9-_]/];

function isSqlTokenAllowed(iterator, modeId): boolean {
    if (modeId === "ace/mode/sql") {
        let token = iterator.getCurrentToken();
        while (token && token.type !== "spel.start" && token.type !== "spel.end") {
            token = iterator.stepBackward();
        }
        return token?.type === "spel.start";
    }
    return false;
}

function isSpelTokenAllowed(iterator, modeId): boolean {
    // We need to handle #dict['Label'], where Label is a string token
    return modeId === "ace/mode/spel" || modeId === "ace/mode/spelTemplate";
}

interface InputProps {
    value: string;
    language: ExpressionLang | string;
    readOnly?: boolean;
    rows?: number;
    onValueChange: (value: string) => void;
    ref: React.Ref<ReactAce>;
    className: string;
    cols: number;
    editorMode?: EditorMode;
}

interface Props {
    inputProps: InputProps;
    validators: Validator[];
    validationLabelInfo: string;
    showValidation?: boolean;
    isMarked?: boolean;
    variableTypes: Record<string, unknown>;
    editorMode?: EditorMode;
}

interface Editor extends Ace.Editor {
    readonly completer: {
        activated: boolean;
    };
}

interface EditSession extends Ace.EditSession {
    readonly $modeId: unknown;
}

class CustomAceEditorCompleter implements Ace.Completer {
    private isTokenAllowed = overSome([isSqlTokenAllowed, isSpelTokenAllowed]);
    // We add hash to identifier pattern to start suggestions just after hash is typed
    public identifierRegexps = identifierRegexpsWithoutDot;
    // This is necessary to make live auto complete works after dot
    public triggerCharacters = ["."];

    constructor(private expressionSuggester: ExpressionSuggester) {}

    replaceSuggester(expressionSuggester: ExpressionSuggester) {
        this.expressionSuggester = expressionSuggester;
    }

    getCompletions(
        editor: Editor,
        session: EditSession,
        caretPosition2d: Ace.Point,
        prefix: string,
        callback: Ace.CompleterCallback,
    ): void {
        const iterator = new TokenIterator(session, caretPosition2d.row, caretPosition2d.column);
        if (!this.isTokenAllowed(iterator, session.$modeId)) {
            callback(null, []);
        }

        this.expressionSuggester.suggestionsFor(editor.getValue(), caretPosition2d).then((suggestions) => {
            callback(
                null,
                map(suggestions, (s) => {
                    const methodName = s.methodName;
                    const returnType = ProcessUtils.humanReadableType(s.refClazz);

                    let docHTML = null;
                    if (s.description || !isEmpty(s.parameters)) {
                        const paramsSignature = s.parameters
                            .map((p) => `${ProcessUtils.humanReadableType(p.refClazz)} ${p.name}`)
                            .join(", ");
                        const javaStyleSignature = `${returnType} ${methodName}(${paramsSignature})`;
                        docHTML = ReactDOMServer.renderToStaticMarkup(
                            <div className="function-docs">
                                <b>{javaStyleSignature}</b>
                                <hr />
                                <p>{s.description}</p>
                            </div>,
                        );
                    }

                    return {
                        value: methodName,
                        score: s.fromClass ? 1 : 1000,
                        meta: returnType,
                        className: `${s.fromClass ? `class` : `default`}Method ace_`,
                        docHTML: docHTML,
                    };
                }),
            );
        });
    }
}

function ExpressionSuggest(props: Props): JSX.Element {
    const { isMarked, showValidation, inputProps, validators, variableTypes, validationLabelInfo } = props;

    const definitionData = useSelector(getProcessDefinitionData);
    const dataResolved = !isEmpty(definitionData);
    const { id, processingType } = useSelector(getProcessToDisplay);

    const { value, onValueChange, language } = inputProps;
    const [editorFocused, setEditorFocused] = useState(false);

    const expressionSuggester = useMemo(() => {
        return new BackendExpressionSuggester(language, id, variableTypes, processingType, HttpService);
    }, [id, processingType, variableTypes, language]);

    const [customAceEditorCompleter] = useState(() => new CustomAceEditorCompleter(expressionSuggester));
    useEffect(() => customAceEditorCompleter.replaceSuggester(expressionSuggester), [customAceEditorCompleter, expressionSuggester]);

    const onChange = useCallback((value: string) => onValueChange(value), [onValueChange]);
    const editorFocus = useCallback((editorFocused: boolean) => () => setEditorFocused(editorFocused), []);

    return dataResolved ? (
        <>
            <div
                className={cn([
                    "row-ace-editor",
                    showValidation && !allValid(validators, [value]) && "node-input-with-error",
                    isMarked && "marked",
                    editorFocused && "focused",
                    inputProps.readOnly && "read-only",
                ])}
            >
                <AceEditor
                    ref={inputProps.ref}
                    value={value}
                    onChange={onChange}
                    onFocus={editorFocus(true)}
                    onBlur={editorFocus(false)}
                    inputProps={inputProps}
                    customAceEditorCompleter={customAceEditorCompleter}
                />
            </div>
            {showValidation && <ValidationLabels validators={validators} values={[value]} validationLabelInfo={validationLabelInfo} />}
        </>
    ) : null;
}

export default ExpressionSuggest;
