---
name: search-web
description: Search the web for real-time information about any topic. Use this when the user needs current news, live data, or information that might not be in the training data.
---

# Search Web

## Instructions

You MUST use the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following fields:
  - query: The search query string. Be concise and specific.
  - num_results: (optional) Number of results to return, default 5.

## Examples

- "What are the latest news about AI?"
- "Search for weather in Beijing today"
- "Find current stock price of Apple"
- "What is happening in the world right now?"
