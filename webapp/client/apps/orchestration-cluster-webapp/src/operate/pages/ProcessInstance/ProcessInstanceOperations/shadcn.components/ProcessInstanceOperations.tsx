/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {useMemo, useState} from 'react';
import {Button, DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@camunda/design-system';
import {
	Ban,
	CircleAlert,
	CircleCheck,
	CirclePause,
	CirclePlay,
	LoaderCircle,
	RotateCcw,
} from '@camunda/design-system/icons';
import {useMachine} from '@xstate/react';
import {useQueryClient} from '@tanstack/react-query';
import {useNavigate} from '@tanstack/react-router';
import {useTranslation} from 'react-i18next';
import {CancelConfirmationModal} from '#/operate/shared/Operations/shadcn.components/CancelConfirmationModal';
import {DeleteConfirmationModal} from '#/operate/shared/Operations/shadcn.components/DeleteConfirmationModal';
import {useMatchMedia} from '#/operate/shared/useMatchMedia';
import {useProcessInstancePage} from '#/operate/pages/ProcessInstance/useProcessInstancePage';
import {processInstanceHeaderOperationMachine, type HeaderAction} from '../processInstanceHeaderOperationMachine';

// Carbon's "xlg" breakpoint (82rem); the DS operations must collapse at the same width as the Carbon ones.
const COLLAPSED_QUERY = '(max-width: 82rem)';

const ICONS = {retry: RotateCcw, cancel: Ban, delete: undefined, suspend: CirclePause, resume: CirclePlay};

function useHeaderOperation(action: HeaderAction, processInstanceKey: string) {
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const [snapshot, send] = useMachine(processInstanceHeaderOperationMachine, {
		input: {
			action,
			processInstanceKey,
			queryClient,
			onDeleted: () => {
				void navigate({
					to: '/operate-preview/processes',
					search: {active: true, incidents: true, suspended: true, completed: false, canceled: false},
					replace: true,
				});
			},
		},
	});
	return useMemo(
		() => ({action, status: snapshot.value, execute: () => send({type: 'execute'})}),
		[action, snapshot.value, send],
	);
}

const ProcessInstanceOperations: React.FC = () => {
	const {processInstance} = useProcessInstancePage();
	const {processInstanceKey, state, hasIncident} = processInstance;
	const {t} = useTranslation();
	const isCollapsed = useMatchMedia(COLLAPSED_QUERY);
	const [confirmation, setConfirmation] = useState<'cancel' | 'delete' | null>(null);
	const retry = useHeaderOperation('retry', processInstanceKey);
	const cancel = useHeaderOperation('cancel', processInstanceKey);
	const deletion = useHeaderOperation('delete', processInstanceKey);
	const suspend = useHeaderOperation('suspend', processInstanceKey);
	const resume = useHeaderOperation('resume', processInstanceKey);

	const isRunning = state === 'ACTIVE' || state === 'SUSPENDED';
	const operations = useMemo(
		() =>
			state === 'ACTIVE'
				? [...(hasIncident ? [retry] : []), suspend, cancel]
				: state === 'SUSPENDED'
					? [resume, cancel]
					: [deletion],
		[state, hasIncident, retry, suspend, cancel, resume, deletion],
	);

	const labels = {
		retry: t('operate.processes.toolbar.retry'),
		cancel: t('operate.processes.toolbar.cancel'),
		delete: t('operate.processes.toolbar.delete'),
		suspend: t('operate.processes.toolbar.suspend'),
		resume: t('operate.processes.toolbar.resume'),
	};
	const pendingLabels = {
		retry: t('operate.processInstance.operations.retrying'),
		cancel: t('operate.processInstance.operations.canceling'),
		delete: t('operate.processInstance.operations.deleting'),
		suspend: t('operate.processInstance.operations.suspending'),
		resume: t('operate.processInstance.operations.resuming'),
	};
	const titles = {
		retry: t('operate.shared.operations.retryTitle', {processInstanceKey}),
		cancel: t('operate.shared.operations.cancelTitle', {processInstanceKey}),
		delete: t('operate.shared.operations.deleteTitle', {processInstanceKey}),
		suspend: t('operate.shared.operations.suspendTitle', {processInstanceKey}),
		resume: t('operate.shared.operations.resumeTitle', {processInstanceKey}),
	};

	const execute = (action: HeaderAction, run: () => void) => {
		if (action === 'cancel' || action === 'delete') {
			setConfirmation(action);
		} else {
			run();
		}
	};

	const renderStatus = ({action, status}: (typeof operations)[number]) => {
		const Icon = status === 'pending' ? LoaderCircle : status === 'success' ? CircleCheck : CircleAlert;
		return (
			<span key={action} role="status" aria-live="polite" className="flex items-center gap-2 text-sm whitespace-nowrap">
				<Icon aria-hidden="true" className={status === 'pending' ? 'size-4 animate-spin' : 'size-4'} />
				{status === 'pending'
					? pendingLabels[action]
					: status === 'success'
						? t('operate.processInstance.operations.successful')
						: t('operate.processInstance.operations.failed')}
			</span>
		);
	};

	return (
		<>
			{isCollapsed && isRunning ? (
				<DropdownMenu>
					<DropdownMenuTrigger asChild>
						<Button variant="ghost" size="sm">
							{t('operate.processInstance.operations.actions')}
						</Button>
					</DropdownMenuTrigger>
					<DropdownMenuContent align="end">
						{operations.map(({action, status, execute: run}) => {
							const Icon = ICONS[action];
							return (
								<DropdownMenuItem key={action} disabled={status === 'pending'} onSelect={() => execute(action, run)}>
									{Icon !== undefined && <Icon aria-hidden="true" />}
									{labels[action]}
								</DropdownMenuItem>
							);
						})}
					</DropdownMenuContent>
				</DropdownMenu>
			) : (
				operations.map((operation) => {
					const {action, status, execute: run} = operation;
					if (status !== 'idle') {
						return renderStatus(operation);
					}
					const Icon = ICONS[action];
					return (
						<Button
							key={action}
							variant={action === 'delete' ? 'destructive' : 'ghost'}
							size="sm"
							title={titles[action]}
							aria-label={titles[action]}
							onClick={() => execute(action, run)}
						>
							{Icon !== undefined && <Icon aria-hidden="true" data-icon="inline-start" />}
							{labels[action]}
						</Button>
					);
				})
			)}
			{confirmation === 'cancel' && isRunning && (
				<CancelConfirmationModal
					processInstanceKey={processInstanceKey}
					open
					onCancel={() => setConfirmation(null)}
					onConfirm={() => {
						setConfirmation(null);
						cancel.execute();
					}}
				/>
			)}
			{confirmation === 'delete' && !isRunning && (
				<DeleteConfirmationModal
					processInstanceKey={processInstanceKey}
					open
					onCancel={() => setConfirmation(null)}
					onConfirm={() => {
						setConfirmation(null);
						deletion.execute();
					}}
				/>
			)}
		</>
	);
};

export {ProcessInstanceOperations};
