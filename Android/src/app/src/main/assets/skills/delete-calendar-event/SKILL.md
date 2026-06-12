---
name: delete-calendar-event
description: Delete an existing calendar event by its event_id.
---

# Delete calendar event

## Instructions

To delete an existing calendar event, follow these steps:
1. First, you MUST call the `read-calendar-events` skill to get the list of events and their `event_id` values.
2. Find the event the user wants to delete and note its `event_id`.
3. Call the `run_intent` tool with:
    - `intent`: delete_calendar_event
    - `parameters`: A JSON string with the following field:
        - `event_id`: the ID of the event to delete (required, from step 2). String.

## Note
This action is permanent and cannot be undone. Confirm with the user before deleting.
