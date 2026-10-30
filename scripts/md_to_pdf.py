#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Markdown → PDF（中文排版优化，A4，保持原格式）。

用法
----
    python scripts/md_to_pdf.py <input.md> [output.pdf]
    python scripts/md_to_pdf.py <input.md> --html-only     # 只出 HTML，便于先看排版

实现：markdown 扩展（tables / fenced_code / sane_lists）转 HTML，
套一层中文排版 CSS，再用无头 Chromium `--print-to-pdf` 打印。
不引 pandoc / LaTeX，仓库里也不留中间产物（HTML 写到临时目录）。
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

# 优先用 playwright 自带的 chromium，其次系统 Chrome / Edge
BROWSERS = [
    r"C:\Users\OneTop-SW002\AppData\Local\ms-playwright\chromium-1248\chrome-win64\chrome.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

CSS = """
@page { size: A4; margin: 16mm 14mm 16mm 14mm; }
* { box-sizing: border-box; }
body {
  font-family: "Microsoft YaHei", "PingFang SC", "Source Han Sans SC", "Segoe UI Emoji", sans-serif;
  font-size: 10.5pt; line-height: 1.65; color: #1f2328; margin: 0;
  -webkit-print-color-adjust: exact; print-color-adjust: exact;
}
h1 { font-size: 20pt; color: #0f4c81; margin: 0 0 6mm; padding-bottom: 3mm;
     border-bottom: 3px solid #0f4c81; letter-spacing: .5px; }
h2 { font-size: 14pt; color: #0f4c81; margin: 7mm 0 3mm; padding-left: 3mm;
     border-left: 4px solid #4a90d9; page-break-after: avoid; }
h3 { font-size: 12pt; color: #1a5f9e; margin: 5mm 0 2mm; page-break-after: avoid; }
p { margin: 2mm 0; }
strong { color: #b02a37; }
a { color: #0f4c81; text-decoration: none; }
code {
  font-family: Consolas, "Cascadia Mono", monospace; font-size: 9pt;
  background: #f2f4f7; padding: .4mm 1.2mm; border-radius: 2px; color: #b02a37;
}
pre {
  background: #f6f8fa; border: 1px solid #e1e4e8; border-left: 4px solid #4a90d9;
  border-radius: 3px; padding: 3mm 4mm; margin: 3mm 0; page-break-inside: avoid;
}
pre code { background: none; padding: 0; color: #1f2328; font-size: 9pt; line-height: 1.5; }
table {
  width: 100%; border-collapse: collapse; margin: 3mm 0; font-size: 9.5pt;
  page-break-inside: auto; box-shadow: 0 0 0 1px #d0d7de;
}
tr { page-break-inside: avoid; page-break-after: auto; }
thead { background: #0f4c81; color: #fff; display: table-header-group; }
th { padding: 2mm 2.5mm; text-align: left; font-weight: 600; border: 1px solid #0f4c81; }
td { padding: 1.8mm 2.5mm; border: 1px solid #d0d7de; vertical-align: top; }
tbody tr:nth-child(even) { background: #f6f8fa; }
tbody tr:hover { background: #eef4fb; }
blockquote {
  margin: 3mm 0; padding: 2mm 3mm 2mm 4mm; background: #fff8e6;
  border-left: 4px solid #e8a33d; color: #4a3b1a; page-break-inside: avoid;
}
blockquote p { margin: 1mm 0; }
ul, ol { margin: 2mm 0; padding-left: 7mm; }
li { margin: .8mm 0; }
hr { border: none; border-top: 1px dashed #c9d1d9; margin: 5mm 0; }
h2, h3, pre, blockquote { break-inside: avoid; }
"""

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
{body}
</body>
</html>
"""


def find_browser(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in BROWSERS:
        if Path(candidate).exists():
            return candidate
    raise SystemExit("找不到 Chrome/Edge/Chromium，可用 --browser 指定可执行文件路径")


def render_html(md_path: Path) -> tuple[str, str]:
    text = md_path.read_text(encoding="utf-8")
    # 去掉一级标题单独渲染（页眉用），避免正文重复
    title_match = re.search(r"^#\s+(.+)$", text, re.MULTILINE)
    title = title_match.group(1).strip() if title_match else md_path.stem
    body = markdown.markdown(text, extensions=["tables", "fenced_code", "sane_lists"])
    return title, HTML_TEMPLATE.format(title=title, css=CSS, body=body)


def to_pdf(html_path: Path, pdf_path: Path, browser: str) -> None:
    pdf_path.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [browser, "--headless=new", "--disable-gpu", "--no-sandbox",
         "--no-pdf-header-footer", "--virtual-time-budget=3000",
         f"--print-to-pdf={pdf_path}", html_path.as_uri()],
        capture_output=True, text=True, timeout=180,
    )
    if not pdf_path.exists():
        raise SystemExit("PDF 生成失败：" + (result.stderr or result.stdout)[-500:])


def main() -> int:
    parser = argparse.ArgumentParser(description="Markdown 转 PDF（中文排版）")
    parser.add_argument("input", help="Markdown 文件路径")
    parser.add_argument("output", nargs="?", help="输出 PDF 路径（默认与 md 同目录同名）")
    parser.add_argument("--browser", help="浏览器可执行文件路径")
    parser.add_argument("--html-only", action="store_true", help="只生成 HTML（调试排版用）")
    args = parser.parse_args()

    md_path = Path(args.input).resolve()
    if not md_path.exists():
        print(f"找不到文件：{md_path}", file=sys.stderr)
        return 1
    pdf_path = Path(args.output).resolve() if args.output else md_path.with_suffix(".pdf")

    title, html = render_html(md_path)
    tmp_dir = Path(tempfile.mkdtemp(prefix="md2pdf_"))
    html_path = tmp_dir / (md_path.stem + ".html")
    html_path.write_text(html, encoding="utf-8")

    if args.html_only:
        keep = md_path.with_suffix(".html")
        shutil.copy(html_path, keep)
        print(f"HTML: {keep}")
        return 0

    try:
        to_pdf(html_path, pdf_path, find_browser(args.browser))
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

    size_kb = pdf_path.stat().st_size / 1024
    print(f"标题: {title}")
    print(f"PDF : {pdf_path}  ({size_kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
