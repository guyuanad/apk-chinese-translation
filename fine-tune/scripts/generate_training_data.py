#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
lear#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
learns to call runIntent, captureScreen, and uiAutomation tools
to complete tasks on an#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
learns to call runIntent, captureScreen, and uiAutomation tools
to complete tasks on an Android phone.

Usage:
    python generate_training_data.py --output data/training_data.jsonl --count 360#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
learns to call runIntent, captureScreen, and uiAutomation tools
to complete tasks on an Android phone.

Usage:
    python generate_training_data.py --output data/training_data.jsonl --count 3600

Requires: openai (pip install openai)
Set environment variable: OPENAI_API_KEY or#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
learns to call runIntent, captureScreen, and uiAutomation tools
to complete tasks on an Android phone.

Usage:
    python generate_training_data.py --output data/training_data.jsonl --count 3600

Requires: openai (pip install openai)
Set environment variable: OPENAI_API_KEY or use --teacher-model local
"""

import json
import argparse
import random
import os
from pathlib import Path#!/usr/bin/env python3
"""
Generate synthetic training data for fine-tuning Gemma-4-E2B-it
for Android phone automation (tool calling).

This script generates multi-turn conversations where the model
learns to call runIntent, captureScreen, and uiAutomation tools
to complete tasks on an Android phone.

Usage:
    python generate_training_data.py --output data/training_data.jsonl --count 3600

Requires: openai (pip install openai)
Set environment variable: OPENAI_API_KEY or use --teacher-model local
"""

import json
import argparse
import random
import os
from pathlib import Path

# ============================================================
# Tool definitions (must match AgentTools.kt)
# ============================================================

TOOLS = [
    {
        "name": "