/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {useTranslation} from 'react-i18next';
import {Button, MultiSelect, type MultiSelectOption} from '@camunda/design-system';
import type {IncidentErrorType} from '@camunda/camunda-api-zod-schemas/8.11';
import {useIncidentsFilter} from '../useIncidentsFilter';

const INCIDENT_ERROR_TYPES: IncidentErrorType[] = [
	'UNSPECIFIED',
	'UNKNOWN',
	'IO_MAPPING_ERROR',
	'JOB_NO_RETRIES',
	'EXECUTION_LISTENER_NO_RETRIES',
	'TASK_LISTENER_NO_RETRIES',
	'CONDITION_ERROR',
	'EXTRACT_VALUE_ERROR',
	'CALLED_ELEMENT_ERROR',
	'UNHANDLED_ERROR_EVENT',
	'MESSAGE_SIZE_EXCEEDED',
	'CALLED_DECISION_ERROR',
	'DECISION_EVALUATION_ERROR',
	'FORM_NOT_FOUND',
	'RESOURCE_NOT_FOUND',
	'AD_HOC_SUB_PROCESS_NO_RETRIES',
	'SECRET_RESOLUTION_ERROR',
];

const IncidentsFilter: React.FC = () => {
	const {t} = useTranslation();
	const {errorTypes, setErrorTypes} = useIncidentsFilter();
	const options: MultiSelectOption[] = INCIDENT_ERROR_TYPES.map((errorType) => ({
		value: errorType,
		label: t(`operate.processInstance.incidents.errorTypes.${errorType}`),
	}));

	return (
		<div className="ml-auto flex items-center gap-2">
			<MultiSelect
				id="incidents-by-errorType"
				className="w-72"
				size="sm"
				options={options}
				value={errorTypes}
				placeholder={t('operate.processInstance.incidents.filterByType')}
				aria-label={t('operate.processInstance.incidents.filterByType')}
				onValueChange={(value) => setErrorTypes(value as IncidentErrorType[])}
			/>
			<Button
				type="button"
				variant="ghost"
				size="sm"
				disabled={errorTypes.length === 0}
				onClick={() => setErrorTypes([])}
			>
				{t('operate.processInstance.incidents.resetFilters')}
			</Button>
		</div>
	);
};

export {IncidentsFilter};
