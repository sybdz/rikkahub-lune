package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ScheduledTask
import me.rerere.rikkahub.data.model.ScheduledTaskRun
import me.rerere.rikkahub.data.model.TaskRunStatus
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

private const val TAG = "ScheduledPromptWorker"
private const val MAX_RUN_HISTORY_PER_TASK = 100
const val EXTRA_SCHEDULED_TASK_RUN_ID = "scheduledTaskRunId"

class ScheduledPromptWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(INPUT_TASK_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return Result.failure()

        val settings = settingsStore.settingsFlow.first()
        val task = settings.scheduledTasks.firstOrNull { it.id == taskId } ?: return Result.success()
        if (!task.enabled || task.prompt.isBlank()) return Result.success()

        val startedAt = System.currentTimeMillis()
        updateTask(taskId) { it.copy(lastStatus = TaskRunStatus.RUNNING, lastError = "") }

        return chatService.executeScheduledTask(task).fold(
            onSuccess = { result ->
                val run = ScheduledTaskRun(
                    taskId = task.id,
                    taskTitle = taskDisplayTitle(task),
                    status = TaskRunStatus.SUCCESS,
                    runAt = startedAt,
                    conversationId = result.conversationId,
                )
                updateTaskAndAppendRun(taskId, run) {
                    it.copy(
                        lastStatus = TaskRunStatus.SUCCESS,
                        lastRunAt = startedAt,
                        lastError = ""
                    )
                }
                maybeNotifySuccess(task, run.id, result.replyPreview)
                Result.success()
            },
            onFailure = { error ->
                Log.e(TAG, "Scheduled task execution failed: ${task.id}", error)
                val run = ScheduledTaskRun(
                    taskId = task.id,
                    taskTitle = taskDisplayTitle(task),
                    status = TaskRunStatus.FAILED,
                    runAt = startedAt,
                    error = error.message.orEmpty().take(200),
                )
                updateTaskAndAppendRun(taskId, run) {
                    it.copy(
                        lastStatus = TaskRunStatus.FAILED,
                        lastRunAt = startedAt,
                        lastError = error.message.orEmpty().take(200)
                    )
                }
                maybeNotifyFailure(task, run.id, error)
                Result.retry()
            }
        )
    }

    private suspend fun updateTask(
        taskId: Uuid,
        transform: (ScheduledTask) -> ScheduledTask
    ) {
        settingsStore.update { settings ->
            settings.copy(
                scheduledTasks = settings.scheduledTasks.map { task ->
                    if (task.id == taskId) transform(task) else task
                }
            )
        }
    }

    private suspend fun updateTaskAndAppendRun(
        taskId: Uuid,
        run: ScheduledTaskRun,
        transform: (ScheduledTask) -> ScheduledTask
    ) {
        settingsStore.update { settings ->
            val nextTaskRuns = (listOf(run) + settings.scheduledTaskRuns.filter {
                it.taskId == taskId && it.id != run.id
            })
                .sortedByDescending { it.runAt }
                .take(MAX_RUN_HISTORY_PER_TASK)
            val otherRuns = settings.scheduledTaskRuns.filter { it.taskId != taskId }
            settings.copy(
                scheduledTasks = settings.scheduledTasks.map { task ->
                    if (task.id == taskId) transform(task) else task
                },
                scheduledTaskRuns = (otherRuns + nextTaskRuns).sortedByDescending { it.runAt }
            )
        }
    }

    private suspend fun maybeNotifySuccess(task: ScheduledTask, runId: Uuid, replyPreview: String?) {
        val settings = settingsStore.settingsFlow.first()
        if (!settings.displaySetting.enableScheduledTaskNotification) return

        applicationContext.sendNotification(
            channelId = SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(task.id)
        ) {
            title = applicationContext.getString(
                R.string.notification_scheduled_task_success_title,
                taskDisplayTitle(task)
            )
            content = replyPreview?.ifBlank {
                applicationContext.getString(R.string.notification_scheduled_task_success_fallback)
            } ?: applicationContext.getString(R.string.notification_scheduled_task_success_fallback)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = getPendingIntent(runId)
        }
    }

    private suspend fun maybeNotifyFailure(task: ScheduledTask, runId: Uuid, error: Throwable) {
        val settings = settingsStore.settingsFlow.first()
        if (!settings.displaySetting.enableScheduledTaskNotification) return

        applicationContext.sendNotification(
            channelId = SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(task.id)
        ) {
            title = applicationContext.getString(
                R.string.notification_scheduled_task_failed_title,
                taskDisplayTitle(task)
            )
            content = error.message.orEmpty().ifBlank {
                applicationContext.getString(R.string.notification_scheduled_task_failed_fallback)
            }
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_ERROR
            contentIntent = getPendingIntent(runId)
        }
    }

    private fun getPendingIntent(runId: Uuid): PendingIntent {
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SCHEDULED_TASK_RUN_ID, runId.toString())
        }
        return PendingIntent.getActivity(
            applicationContext,
            runId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notificationId(taskId: Uuid): Int = taskId.hashCode() + 20000

    private fun taskDisplayTitle(task: ScheduledTask): String {
        return task.title.ifBlank {
            task.prompt.lineSequence().firstOrNull().orEmpty().take(24)
                .ifBlank { applicationContext.getString(R.string.assistant_schedule_untitled) }
        }
    }
}
