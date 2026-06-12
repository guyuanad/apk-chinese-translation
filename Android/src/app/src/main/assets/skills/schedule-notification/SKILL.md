---
name: schedule-notification
description: Schedule a notification for a specific date or repeating daily.
---

# Schedule Notification

## Instructions

This is a sensitive operation. Before calling the tool, you MUST confirm with the user.

1. Show the user the notification details (title, message, time, repeat setting) and ask for confirmation.
2. Only after the user confirms, follow these steps:
   - If the notification doesn't need to repeat daily, call the `run_intent` tool with `intent` as `get_current_date_and_time` and `parameters` as `{}` to get the user's local date and time. Then explicitly calculate the scheduling date and time in your response.
   - Call the `run_intent` tool with the following exact parameters:
     - intent: schedule_notification
     - parameters: A JSON string with the following fields:
       - title: the title of the notification. String.
       - message: the message content of the notification. String.
       - hour: the hour of the day (0-23). Integer.
       - minute: the minute (0-59). Integer.
       - task_id: (optional) target page task ID. String.
       - model_name: (optional) target page model name. String.
       - deeplink: (optional) full deeplink URI. String.
       - year/month/day: (optional) scheduling date. Integers.
       - repeat_daily: (optional) true if repeating daily. Boolean.

## Examples

- "Remind me to drink water at 9 AM every day" → Show confirmation → Execute with repeat_daily=true
- "Set a notification for tomorrow at 3 PM to take medicine" → Calculate date → Show confirmation → Execute
