package com.gatehill.buildclerk.service

import com.gatehill.buildclerk.api.model.MessageAction
import com.gatehill.buildclerk.api.model.MessageAttachment
import com.gatehill.buildclerk.api.model.UpdatedNotificationMessage
import com.gatehill.buildclerk.api.model.action.*
import com.gatehill.buildclerk.api.service.BuildRunnerService
import com.gatehill.buildclerk.api.service.NotificationService
import com.gatehill.buildclerk.model.slack.ActionTriggeredEvent
import com.gatehill.buildclerk.model.slack.SlackMessageAction
import com.gatehill.buildclerk.model.slack.SlackMessageAttachment
import com.gatehill.buildclerk.service.notify.slack.toMessageAction
import com.gatehill.buildclerk.service.notify.slack.toMessageAttachment
import com.gatehill.buildclerk.service.scm.ScmService
import kotlinx.coroutines.experimental.launch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import javax.inject.Inject

/**
 * Completes pending actions.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
class PendingActionService @Inject constructor(
    private val scmService: ScmService,
    private val buildRunnerService: BuildRunnerService,
    private val notificationService: NotificationService
) {
    private val logger: Logger = LogManager.getLogger(PendingActionService::class.java)
    private val pending = mutableMapOf<String, PendingActionSet>()

    fun enqueue(actionSet: PendingActionSet) {
        logger.info("Enqueuing ${actionSet.actions.size} pending actions: ${actionSet.actions}")
        pending[actionSet.id] = actionSet
    }

    fun handleAsync(event: ActionTriggeredEvent) {
        launch {
            try {
                handle(event)
            } catch (e: Exception) {
                logger.error("Error handling action trigger with callback ID: ${event.callbackId}", e)
            }
        }
    }

    private fun handle(event: ActionTriggeredEvent) {
        logger.info("Handling action trigger with callback ID: ${event.callbackId}")

        event.actions?.let { actions ->
            val actionSetId = event.callbackId

            pending[actionSetId]?.let { actionSet ->
                logger.debug("Found pending action set with ID: $actionSetId [${actionSet.actions.size} actions]")
                resolveActions(event, actions, actionSet)

            } ?: logger.warn("No pending action set found with ID: $actionSetId")

        } ?: logger.warn("No actions found in event: $event")
    }

    private fun resolveActions(
        event: ActionTriggeredEvent,
        actions: List<SlackMessageAction>,
        pendingActionSet: PendingActionSet
    ) {
        val selectedActions = mutableListOf<SelectedAction>()

        actions.forEach { action ->
            pendingActionSet.actions.find { it.name == action.name }?.let { pendingAction ->
                val executed = resolve(event.channel.name, action, pendingAction)

                selectedActions += SelectedAction(
                    actionName = pendingAction.name,
                    exclusive = pendingAction.exclusive,
                    outcomeText = if (executed) {
                        ":white_check_mark: <@${event.user.id}> selected '${pendingAction.title}'"
                    } else {
                        ":-1: <@${event.user.id}> dismissed suggested action: '${pendingAction.title}'"
                    }
                )

                if (executed && pendingAction.exclusive) {
                    logger.debug("Selected action: ${pendingAction.name} is exclusive - removing action set with ID: ${pendingActionSet.id}")
                    pending.remove(pendingActionSet.id)
                }

            } ?: logger.warn("No such action '${action.name}' in pending action set: ${pendingActionSet.id}")
        }

        val attachments = event.originalMessage.attachments?.let { slackAttachments ->
            composeAttachments(
                slackAttachments = slackAttachments,
                selectedActions = selectedActions
            )
        } ?: emptyList()

        // update the original message
        updateOriginalMessage(event, attachments)
    }

    internal fun composeAttachments(
        slackAttachments: List<SlackMessageAttachment>,
        selectedActions: List<SelectedAction>
    ): List<MessageAttachment> {

        val attachments = mutableListOf<MessageAttachment>()

        attachments += slackAttachments.mapNotNull { slackAttachment ->
            if (null == slackAttachment.actions || slackAttachment.actions.isEmpty()) {
                // attachment has no actions - include it
                slackAttachment.toMessageAttachment(emptyList())

            } else if (selectedActions.any { it.exclusive }) {
                // if exclusive action was executed skip all attachments with actions
                null

            } else {
                if (isAttachmentActionSelected(slackAttachment.actions, selectedActions)) {
                    // if attachment's action was selected skip it (outcome rendered later)
                    null

                } else {
                    // for attachments with actions, include only unselected actions
                    val actions = filterUnselectedActions(slackAttachment.actions, selectedActions)
                    slackAttachment.toMessageAttachment(actions)
                }
            }
        }

        // add outcomes for selected actions
        attachments += selectedActions.map { selectedAction ->
            MessageAttachment(
                text = selectedAction.outcomeText
            )
        }

        return attachments
    }

    private fun isAttachmentActionSelected(
        slackActions: List<SlackMessageAction>,
        selectedActions: List<SelectedAction>
    ): Boolean {
        return slackActions.any { slackAction ->
            selectedActions.any { selectedAction ->
                slackAction.name == selectedAction.actionName
            }
        }
    }

    /**
     * @return only actions have have not been selected
     */
    private fun filterUnselectedActions(
        slackActions: List<SlackMessageAction>?,
        selectedActions: List<SelectedAction>
    ): List<MessageAction> {

        return slackActions?.mapNotNull { slackAction ->
            if (selectedActions.any { it.actionName == slackAction.name }) {
                // skip selected actions
                null
            } else {
                // include unresolved actions
                slackAction.toMessageAction()
            }
        } ?: emptyList()
    }

    private fun updateOriginalMessage(
        event: ActionTriggeredEvent,
        attachments: List<MessageAttachment>
    ) {
        event.originalMessage.ts?.let {
            val updatedMessage = UpdatedNotificationMessage(
                messageId = event.originalMessage.ts,
                channel = event.channel.id,
                text = event.originalMessage.text,
                attachments = attachments
            )
            notificationService.updateMessage(updatedMessage)

        } ?: logger.warn(
            "Cannot update original message will callback ID: ${event.callbackId}, as there was no message timestamp"
        )
    }

    private fun resolve(
        triggeringChannel: String,
        action: SlackMessageAction,
        pendingAction: PendingAction
    ): Boolean {
        logger.debug("Attempting to resolve pending action: $action")

        when (action.value) {
            pendingAction.name -> {
                executePendingAction(triggeringChannel, pendingAction)
                return true
            }
            else -> logger.info("Discarding pending action: $pendingAction [actionValue: ${action.value}]")
        }

        return false
    }

    private fun executePendingAction(triggeringChannel: String, pendingAction: PendingAction) {
        logger.info("Executing pending action: $pendingAction")
        when (pendingAction) {
            is RevertCommitAction -> scmService.revertCommit(pendingAction.commit, pendingAction.branch)
            is RebuildBranchAction -> buildRunnerService.rebuild(pendingAction.report)
            is LockBranchAction -> scmService.lockBranch(pendingAction.branch)
            is ShowTextAction -> showText(triggeringChannel, pendingAction)
            else -> throw NotImplementedError("Unsupported pending action: $pendingAction")
        }
    }

    /**
     * Show text on the specified channel, or the triggering channel if it is not specified.
     */
    private fun showText(triggeringChannel: String, action: ShowTextAction) {
        notificationService.notify(action.channelName ?: triggeringChannel, action.body, action.color.hexCode)
    }
}

internal data class SelectedAction(
    val actionName: String,
    val exclusive: Boolean,
    val outcomeText: String
)
