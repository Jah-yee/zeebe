/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {test, expect} from '#/pw-modules/test-extend';
import {HttpResponse} from 'msw';
import {
	mockCurrentUserEndpoint,
	mockGetProcessInstanceCallHierarchyEndpoint,
	mockGetProcessInstanceEndpoint,
	mockGetProcessInstanceWaitStateStatisticsEndpoint,
	mockLicenseEndpoint,
	mockQueryProcessInstanceIncidentsEndpoint,
	mockQueryVariablesEndpoint,
	mockSystemConfigurationEndpoint,
} from '#/shared-test-modules/mock-handlers';
import {createCurrentUser} from '#/shared-test-modules/api-mocks/current-user';
import {createLicense} from '#/shared-test-modules/api-mocks/license';
import {createSystemConfiguration} from '#/shared-test-modules/api-mocks/system-configuration';
import {createProcessInstance} from '#/shared-test-modules/api-mocks/process-instances';
import {createCallHierarchy} from '#/shared-test-modules/api-mocks/call-hierarchy';
import {createIncident} from '#/shared-test-modules/api-mocks/incidents';
import {createPaginatedResponse, createProblemDetails} from '#/shared-test-modules/api-mocks/shared';
import {createQueryVariablesByUserTaskResponse, createVariable} from '#/shared-test-modules/api-mocks/variables';
import {processInstanceHeaderHandlers} from '#/shared-test-modules/process-instance-header-handlers';
import {processInstanceDiagramHandlers} from '#/shared-test-modules/process-instance-diagram-handlers';

const PROCESS_INSTANCE_ID = '2251799813685280';

test.beforeEach(({network}) => {
	network.use(
		...processInstanceDiagramHandlers(),
		...processInstanceHeaderHandlers(createProcessInstance({processInstanceKey: PROCESS_INSTANCE_ID})),
		mockCurrentUserEndpoint({
			successResponse: HttpResponse.json(createCurrentUser({authorizedComponents: ['operate']})),
		}),
		mockSystemConfigurationEndpoint({
			successResponse: HttpResponse.json(createSystemConfiguration({components: {active: ['operate']}})),
		}),
		mockLicenseEndpoint({
			successResponse: HttpResponse.json(createLicense()),
		}),
		mockGetProcessInstanceEndpoint({
			successResponse: HttpResponse.json(createProcessInstance({processInstanceKey: PROCESS_INSTANCE_ID})),
		}),
		mockGetProcessInstanceWaitStateStatisticsEndpoint({
			successResponse: HttpResponse.json(createPaginatedResponse({items: []})),
		}),
	);
});

test.describe('Operate Process Instance DS preview (/operate-preview/processes/:processInstanceId)', () => {
	test('should render the instance diagram with state overlays', async ({operatePreviewProcessInstancePage}) => {
		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(operatePreviewProcessInstancePage.heading).toBeAttached();
		await expect(operatePreviewProcessInstancePage.header).toBeVisible();
		await expect(operatePreviewProcessInstancePage.diagram).toBeVisible();
		await expect(operatePreviewProcessInstancePage.stateOverlay('Review invoice, Active: 1')).toBeVisible();
		await expect(operatePreviewProcessInstancePage.stateOverlay('Review invoice, Incidents: 1')).toBeVisible();
		await expect(operatePreviewProcessInstancePage.variablesTab).toBeVisible();
	});

	test('should list the variables of the process instance in the Variables tab', async ({
		network,
		operatePreviewProcessInstancePage,
	}) => {
		network.use(
			mockQueryVariablesEndpoint({
				successResponse: HttpResponse.json(
					createQueryVariablesByUserTaskResponse({
						items: [createVariable({name: 'amount', value: '100', variableKey: '1'})],
					}),
				),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(operatePreviewProcessInstancePage.variableRow('amount')).toBeVisible();
		await expect(operatePreviewProcessInstancePage.variableRow('amount')).toContainText('100');
	});

	test('should show [REDACTED] without actions for a redacted variable', async ({
		network,
		operatePreviewProcessInstancePage,
	}) => {
		network.use(
			mockQueryVariablesEndpoint({
				successResponse: HttpResponse.json(
					createQueryVariablesByUserTaskResponse({
						items: [
							createVariable({name: 'amount', value: '100', variableKey: '1'}),
							createVariable({
								name: 'password',
								value: 'secret',
								variableKey: '2',
								metadata: {protectionModes: ['REDACT']},
							}),
						],
					}),
				),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		const redactedRow = operatePreviewProcessInstancePage.variableRow('password');
		await expect(redactedRow).toContainText('[REDACTED]');
		await expect(redactedRow).not.toContainText('secret');
		await expect(redactedRow.getByRole('button')).toHaveCount(0);
		await expect(
			operatePreviewProcessInstancePage.variableRow('amount').getByRole('button', {name: 'Open'}),
		).toBeVisible();
	});

	test('should list the incidents of the process instance and select the failing element on row click', async ({
		network,
		operatePreviewProcessInstancePage,
		page,
	}) => {
		network.use(
			mockQueryProcessInstanceIncidentsEndpoint({
				successResponse: HttpResponse.json(
					createPaginatedResponse({
						items: [createIncident({errorMessage: 'Payment failed'})],
						page: {totalItems: 1, startCursor: null, endCursor: null, hasMoreTotalItems: false},
					}),
				),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);
		await operatePreviewProcessInstancePage.incidentsTab.click();

		const row = operatePreviewProcessInstancePage.incidentsTable.getByRole('row', {name: /Payment failed/});
		await expect(row).toBeVisible();

		await row.click();

		await expect(page).toHaveURL(/elementId=/);
	});

	test('should keep the selected element in the URL and clear it on a second canvas click', async ({
		operatePreviewProcessInstancePage,
		page,
	}) => {
		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);
		await expect(operatePreviewProcessInstancePage.diagram).toBeVisible();

		await operatePreviewProcessInstancePage.diagramElement('task-1').click();

		await expect(page).toHaveURL(/elementId=task-1/);

		await operatePreviewProcessInstancePage.diagram.click({position: {x: 5, y: 5}});

		await expect(page).not.toHaveURL(/elementId=/);
	});

	test('should redirect to the default tab and switch tabs through the URL', async ({
		operatePreviewProcessInstancePage,
		page,
	}) => {
		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(page).toHaveURL(`/operate-preview/processes/${PROCESS_INSTANCE_ID}/variables`);

		await operatePreviewProcessInstancePage.incidentsTab.click();

		await expect(page).toHaveURL(`/operate-preview/processes/${PROCESS_INSTANCE_ID}/incidents`);
		await expect(operatePreviewProcessInstancePage.incidentsTab).toHaveAttribute('aria-selected', 'true');
	});

	test('should redirect to the details tab when the instance waits at process level', async ({
		network,
		operatePreviewProcessInstancePage,
		page,
	}) => {
		network.use(
			mockGetProcessInstanceWaitStateStatisticsEndpoint({
				successResponse: HttpResponse.json(
					createPaginatedResponse({items: [{elementId: 'my-process', waitingCount: 1}]}),
				),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(page).toHaveURL(`/operate-preview/processes/${PROCESS_INSTANCE_ID}/details`);
	});

	test('should not render a breadcrumb for a root instance', async ({network, operatePreviewProcessInstancePage}) => {
		network.use(mockGetProcessInstanceCallHierarchyEndpoint({successResponse: HttpResponse.json([])}));

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(operatePreviewProcessInstancePage.header).toBeVisible();
		await expect(operatePreviewProcessInstancePage.breadcrumb).toHaveCount(0);
	});

	test('should render the ancestors in the breadcrumb of a called instance', async ({
		network,
		operatePreviewProcessInstancePage,
	}) => {
		network.use(
			mockGetProcessInstanceCallHierarchyEndpoint({
				successResponse: HttpResponse.json([
					createCallHierarchy({processInstanceKey: '1', processDefinitionName: 'Parent Process'}),
					createCallHierarchy({processInstanceKey: PROCESS_INSTANCE_ID, processDefinitionName: 'Child Process'}),
				]),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(
			operatePreviewProcessInstancePage.breadcrumb.getByRole('link', {
				name: 'View Process Parent Process - Instance 1',
			}),
		).toBeVisible();
	});

	test('should show the permission message when access to the instance is forbidden', async ({
		network,
		operatePreviewProcessInstancePage,
		page,
	}) => {
		network.use(
			mockGetProcessInstanceEndpoint({
				successResponse: HttpResponse.json(createProblemDetails({status: 403}), {status: 403}),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(
			page.getByRole('heading', {name: 'You do not have permission to view this information'}),
		).toBeVisible();
		await expect(operatePreviewProcessInstancePage.header).not.toBeVisible();
	});

	test('should notify and redirect to the process list when the instance does not exist', async ({
		network,
		operatePreviewProcessInstancePage,
		page,
	}) => {
		network.use(
			mockGetProcessInstanceEndpoint({
				successResponse: HttpResponse.json(createProblemDetails({status: 404}), {status: 404}),
			}),
		);

		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);

		await expect(page).toHaveURL(/\/operate-preview\/processes\?/);
		await expect(page.getByText(`Instance ${PROCESS_INSTANCE_ID} could not be found`)).toBeVisible();
	});

	test('should offer a retry that recovers from a generic error', async ({
		network,
		operatePreviewProcessInstancePage,
		page,
	}) => {
		network.use(mockGetProcessInstanceEndpoint({successResponse: new HttpResponse(null, {status: 500})}));
		await operatePreviewProcessInstancePage.goto(PROCESS_INSTANCE_ID);
		await expect(page.getByRole('button', {name: 'Try again'})).toBeVisible();

		network.use(
			mockGetProcessInstanceEndpoint({
				successResponse: HttpResponse.json(createProcessInstance({processInstanceKey: PROCESS_INSTANCE_ID})),
			}),
		);
		await page.getByRole('button', {name: 'Try again'}).click();

		await expect(operatePreviewProcessInstancePage.header).toBeVisible();
	});
});
