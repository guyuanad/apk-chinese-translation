---
name: read-calendar-events
description: Read OS calendar events for a specific date or date range.
---

# Read calendar events

## Instructions

To read calendar events, you must follow these exact steps:
1. First, call the `run_intent` tool with `intent` as `get_current_date_and_time` and `parameters` as `{}` to get the user's local date, time, and the current day of the week.
2. Before reading the events, explicitly calculate the exact target date(s) requested by the user in your response. Figure out:
- Today's exact date and day of the week.
- The target day or relative time requested by the user (e.g., "tomorrow", "this Friday", "May 15").
- The final calculated target date(s) in YYYY-MM-DD format.
3. Once you have calculated the correct date(s), call the `run_intent` tool with the following exact parameters:
- `intent`: read_calendar_events
- `parameters`: A JSON string with ONE of the following:
   - Single day: `{"date": "YYYY-MM-DD"}` 
   - Date range: `{"start_date": "YYYY-MM-DD", "end_date": "YYYY-MM-DD"}`
   - If no date specified, use today's date: `{"date": "YYYY-MM-DD"}` (calculate today from step 1)
4. The returned JSON contains a list of events, each with `event_id`, `title`, `description`, `begin_time`, and `end_time`.
5. Interpret the events and provide a clear, friendly answer to the user detailing their schedule.

## Examples

- "What's on my calendar today?" → `{"date": "2026-06-06"}`
- "What's on my calendar tomorrow?" → calculate tomorrow's date → `{"date": "2026-06-07"}`
- "What's on my calendar this week?" → `{"start_date": "2026-06-06", "end_date": "2026-06-12"}`
- "Do I have any events next Monday?" → calculate next Monday's date → `{"date": "2026-06-09"}`
