"""检查当前文档的引用、范围覆盖和旧版本残留；仅依赖 Python 标准库。"""

from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "项目文档"


def check_links(path, links):
    errors = []
    for target in links:
        parsed = urlsplit(target)
        if parsed.scheme or target.startswith("#"):
            continue
        resolved = (path.parent / unquote(parsed.path)).resolve()
        if not resolved.is_relative_to(ROOT) or not resolved.exists():
            errors.append(f"失效或越界链接：{path.name} -> {target}")
    return errors


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.targets = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self.targets.extend(value for key, value in attrs if key == "href" and value)


def check():
    errors = []
    paths = [ROOT / "README.md", ROOT / "AGENTS.md", ROOT / "UI演示/README.md", *sorted(DOCS.rglob("*.md"))]
    texts = {}
    stale = re.compile(r"-v\d+\.md|项目计划书评估报告\.md|方案讨论版|推荐方案讨论稿|语言与框架未选定", re.I)
    for path in paths:
        if not path.exists():
            errors.append(f"缺少文档：{path.name}")
            continue
        body = path.read_text(encoding="utf-8-sig")
        texts[path.name] = body
        if stale.search(path.name) or stale.search(body):
            errors.append(f"仍含过时版本或选型入口：{path.name}")
        if len(re.findall(r"^```", body, re.M)) % 2:
            errors.append(f"代码块未闭合：{path.name}")
        errors.extend(check_links(path, re.findall(r"\]\(([^)]+)\)", body)))

    plan = texts.get("项目计划书.md", "")
    checklist = texts.get("功能开发清单.md", "")
    module_pattern = r"^\| (M\d{2}) \| ([^|]+?) \|"
    expected_modules = dict(re.findall(module_pattern, plan, re.M))
    listed_modules = dict(re.findall(module_pattern, checklist, re.M))
    if len(expected_modules) != 12 or expected_modules != listed_modules:
        errors.append("计划书与开发清单的模块编号或名称不一致")
    acceptance_pattern = r"^\| (A\d{2}) \|"
    expected_acceptance = re.findall(acceptance_pattern, plan, re.M)
    actual_acceptance = re.findall(acceptance_pattern, checklist, re.M)
    if (not expected_acceptance or set(expected_acceptance) != set(actual_acceptance)
            or len(actual_acceptance) != len(set(actual_acceptance))):
        errors.append("验收场景存在缺漏或重复")
    stages = re.findall(r"^### (P\d+) ", checklist, re.M)
    sub_stages = re.findall(r"^#### (P\d+\.\d+) ", checklist, re.M)
    if stages != [f"P{i}" for i in range(10)] or len(sub_stages) != len(set(sub_stages)):
        errors.append("开发阶段缺漏或编号重复")
    if not set(re.findall(r"P\d+\.\d+", checklist)) <= set(sub_stages):
        errors.append("开发清单引用了不存在的小阶段")

    html_path = ROOT / "图示成果/项目ER图.html"
    if html_path.exists():
        html = html_path.read_text(encoding="utf-8")
        links = Links()
        links.feed(html)
        errors.extend(check_links(html_path, links.targets))
        if stale.search(html) or "技术栈和字段类型尚未确定" in html:
            errors.append("图示页面仍引用旧版本或旧技术状态")
    else:
        errors.append("缺少图示 HTML")

    if errors:
        raise SystemExit("\n".join(errors))
    pending = len(re.findall(r"^- \[ \] ", checklist, re.M))
    completed = len(re.findall(r"^- \[[xX]\] ", checklist, re.M))
    print(f"PASS: {len(paths)} Markdown files; {len(expected_modules)} modules; "
          f"{len(expected_acceptance)} acceptance scenarios; {len(stages)} stages; "
          f"{len(sub_stages)} sub-stages; {pending} pending / {completed} completed items.")
    print("PASS: local links, diagram links, identifiers and stale-entry checks.")


if __name__ == "__main__":
    check()
