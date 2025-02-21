import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Field, TypedObjectTypingResult, VariableTypes } from "../../../../../types";
import { FieldsRow } from "../../fragment-input-definition/FieldsRow";
import { Items } from "../../fragment-input-definition/Items";
import { NodeRowFields } from "../../fragment-input-definition/NodeRowFields";
import { Error, mandatoryValueValidator, uniqueListValueValidator, Validator } from "../Validators";
import MapKey from "./MapKey";
import MapValue from "./MapValue";
import { isEqual } from "lodash";
import { useDiffMark } from "../../PathsToMark";

export interface MapCommonProps {
    setProperty: (path: string, newValue: unknown) => void;
    readOnly?: boolean;
    showValidation: boolean;
    variableTypes: VariableTypes;
    fieldErrors: Error[];
}

interface MapProps<F extends Field> extends MapCommonProps {
    fields: F[];
    label: string;
    namespace: string;
    addField: (namespace: string, field?: F) => void;
    removeField: (namespace: string, index: number) => void;
    expressionType?: Partial<TypedObjectTypingResult>;
}

export type TypedField = Field & { typeInfo: string };

export function Map<F extends Field>(props: MapProps<F>): JSX.Element {
    const { label, setProperty, addField, removeField, namespace, readOnly, showValidation, fieldErrors, variableTypes, expressionType } =
        props;

    const [isMarked] = useDiffMark();

    const appendTypeInfo = useCallback(
        (expressionObj: F): F & { typeInfo: string } => {
            const fields = expressionType?.fields;
            const typeInfo = fields ? fields[expressionObj.name]?.display : expressionType?.display;
            return { ...expressionObj, typeInfo: typeInfo };
        },
        [expressionType?.display, expressionType?.fields],
    );

    const [fields, setFields] = useState(props.fields);
    useEffect(() => {
        if (!isEqual(props.fields, fields)) {
            setFields(props.fields);
        }
    }, [props.fields, fields]);

    const Item = useCallback(
        ({ index, item, validators }: { index: number; item; validators: Validator[] }) => {
            const path = `${namespace}[${index}]`;
            return (
                <FieldsRow index={index}>
                    <MapKey
                        readOnly={readOnly}
                        showValidation={showValidation}
                        isMarked={isMarked(`${path}.name`)}
                        onChange={(value) => setProperty(`${path}.name`, value)}
                        value={item.name}
                        validators={validators}
                    />
                    <MapValue
                        readOnly={readOnly}
                        showValidation={showValidation}
                        isMarked={isMarked(`${path}.expression.expression`)}
                        onChange={(value) => setProperty(`${path}.expression.expression`, value)}
                        validationLabelInfo={item.typeInfo}
                        value={item.expression}
                        errors={fieldErrors}
                        variableTypes={variableTypes}
                    />
                </FieldsRow>
            );
        },
        // "variableTypes" ignored for reason
        [isMarked, namespace, setProperty, readOnly, showValidation],
    );

    const items = useMemo(
        () =>
            fields?.map(appendTypeInfo).map((item, index, list) => {
                const validators = [
                    mandatoryValueValidator,
                    uniqueListValueValidator(
                        list.map((v) => v.name),
                        index,
                    ),
                ];
                return { item, el: <Item key={index} index={index} item={item} validators={validators} /> };
            }),
        [Item, appendTypeInfo, fields],
    );

    return (
        <NodeRowFields label={label} path={namespace} onFieldAdd={addField} onFieldRemove={removeField} readOnly={readOnly}>
            <Items items={items} />
        </NodeRowFields>
    );
}

export default Map;
