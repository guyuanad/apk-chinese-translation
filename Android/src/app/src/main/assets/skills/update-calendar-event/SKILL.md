---
name: update-calendar-event
description: Update an existing calendar event by its event_id.
---

# Update calendar event

## Instructions

To update an existing calendar event, follow these steps:
1. First, you MUST call the `read-calendar-events` skill to get the list of events and their `event_id` values.
2. Find the event the user wants to modify and note its `event_id`.
3. Explicitly calculate any new dates needed (start/end time) from the user's request, just like in create-calendar-event.
4. Call the `run_intent` tool with:
    - `intent`: update_calendar_event
    - `parameters`: A JSON string with the following fields:
        - `event_id`: the ID of the event to update (required, from step 2). String.
        - `title`: (optional) the new title of the event. String.
        - `description`: (optional) the new description. String.
        - `begin_time`: (optional) new start time in YYYY-MM-DDTHH:MM:SS format. String.
        - `end_time`: (optional) new end time in YYYY-MM-DDTHH:MM:SS format. String.

## Note
Only include fields that the user wants to change. Unchanged fields will keep their existing values.
