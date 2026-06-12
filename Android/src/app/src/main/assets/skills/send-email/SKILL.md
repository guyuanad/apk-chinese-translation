---
name: send-email
description: Send an email to a recipient.
---

# Send email

## Instructions

This is a sensitive operation. Before calling the tool, you MUST confirm with the user.

1. Show the user the email details (to, subject, body) and ask for confirmation.
2. Only after the user confirms, call the `run_intent` tool with the following exact parameters:
   - intent: send_email
   - parameters: A JSON string with the following fields:
     - extra_email: the email address to send the email to. String.
     - extra_subject: the subject of the email. String.
     - extra_text: the body of the email. String.

## Examples

- "Send an email to john@example.com saying hello" → Show confirmation → Execute
- "Draft an email to boss about the project" → Show confirmation → Execute
