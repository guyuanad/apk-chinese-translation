
import json

# Load the runs data
with open('/workspace/recent_runs.json', 'r') as f:
    data = json.load(f)

print("=== Build Android APK Workflow Runs ===\n")

found = False
for run in data.get('workflow_runs', []):
    if 'Build Android APK' in run['name'] or 'build_android' in run['path']:
        print(f"Name: {run['name']}")
        print(f"Status: {run['status']}")
        print(f"Conclusion: {run.get('conclusion', '')}")
        print(f"Branch: {run['head_branch']}")
        print(f"Created: {run['created_at']}")
        print(f"Updated: {run['updated_at']}")
        print(f"URL: {run['html_url']}")
        print("-" * 80)
        found = True

if not found:
    print("No 'Build Android APK' workflow runs found in the last 20 runs.")
    print("\n=== Recent Workflows (Names Only) ===\n")
    for i, run in enumerate(data.get('workflow_runs', [])[:10]):
        print(f"{i+1}. {run['name']} (Branch: {run['head_branch']}, Status: {run['status']})")
