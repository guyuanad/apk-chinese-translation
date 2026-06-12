import json

with open('/workspace/latest_build.json', 'r') as f:
    data = json.load(f)

print("=== 构建状态 ===\n")
for run in data.get("workflow_runs", [])[:3]:
    status_emoji = "✅" if run["status"] == "completed" else "⏳"
    conclusion = run.get("conclusion", "N/A")
    conclusion_emoji = "✅" if conclusion == "success" else "❌" if conclusion == "failure" else "⚠️"
    
    print(f"名称: {run['name']}")
    print(f"状态: {status_emoji} {run['status']}")
    print(f"结果: {conclusion_emoji} {conclusion}")
    print(f"分支: {run['head_branch']}")
    print(f"提交: {run['head_sha'][:8]}")
    print(f"时间: {run['created_at']}")
    print(f"链接: {run['html_url']}")
    print("-" * 60)
