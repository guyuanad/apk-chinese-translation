---
name: read-clipboard
description: Read text content from the system clipboard. Use when the user asks to read, paste, or use what they copied.
---

# Read clipboard

## Instructions

Call the `run_intent` tool with the following exact parameters:
- intent: read_clipboard
- parameters: {} (empty JSON object, no parameters needed)

The returned value is the plain text content of the clipboard. If the clipboard is empty, you'll receive an error message.

## Examples

- "What did I just copy?"
- "Paste what's in my clipboard"
- "Read my clipboard and summarize it"
- "What's on my clipboard right now?"
