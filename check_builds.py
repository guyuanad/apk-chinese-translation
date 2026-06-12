import json
import sys

data = json.load(sys.stdin)
runs = data.get('workflow_runs', [])
print('\n=== Latest Builds ===\n')
for i, run in enumerate(runs[:3], 1):
    print(f'{i}. {run.get("display_title", "N/A")}')
    print(f'   Status: {run.get("status", "N/A")}')
    print(f'   Conclusion: {run.get("conclusion", "N/A")}')
    print(f'   URL: {run.get("html_url", "N/A")}\n')
