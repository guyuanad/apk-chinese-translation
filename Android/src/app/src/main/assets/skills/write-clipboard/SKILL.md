---
name: write-clipboard
description: Write text content to the system clipboard. Use when the user asks to copy something to their clipboard.
---

# Write clipboard

## Instructions

Call the `run_intent` tool with the following exact parameters:
- intent: write_clipboard
- parameters: A JSON string with the following field:
  - `text`: the text content to copy to the clipboard. String.

## Examples

- "Copy this to my clipboard: Hello World"
- "Save this text to clipboard"
- "Put this in my clipboard: https://example.com"
