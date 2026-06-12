
import sys, json
data = json.load(sys.stdin)
print("=== Recent GitHub Actions Workflow Runs ===\n")
for run in data.get("workflow_runs", []):
    print(f"Name: {run['name']}")
    print(f"Status: {run['status']}")
    print(f"Conclusion: {run.get('conclusion', '')}")
    print(f"Branch: {run['head_branch']}")
    print(f"Created: {run['created_at']}")
    print(f"URL: {run['html_url']}")
    print("-" * 80)
