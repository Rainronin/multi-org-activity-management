"""文档链接回归：避免存在文件但章节失效、示例标题被当成真实章节。"""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import check_docs


class DocumentLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.patch = patch.object(check_docs, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.page = self.root / "入口.md"
        self.page.write_text("# 入口\n", encoding="utf-8")
        (self.root / "说明.md").write_text(
            "# 部署说明\n## 五、AI 模型接入（可选功能）\n"
            "## 重复标题\n## 重复标题\n"
            "```md\n## 示例章节\n```\n"
            "~~~md\n## 波浪示例\n~~~\n", encoding="utf-8")

    def test_missing_markdown_section_is_rejected(self):
        self.assertTrue(check_docs.check_links(self.page, ["说明.md#五ai-接入参数"]))

    def test_self_section_is_checked(self):
        self.assertTrue(check_docs.check_links(self.page, ["#不存在"]))
        self.assertEqual([], check_docs.check_links(self.page, ["#入口"]))

    def test_chinese_punctuation_duplicate_and_encoded_anchors(self):
        self.assertEqual([], check_docs.check_links(self.page, [
            "说明.md#五ai-模型接入可选功能", "说明.md#重复标题-1",
            "说明.md#%E9%87%8D%E5%A4%8D%E6%A0%87%E9%A2%98",
        ]))

    def test_code_example_is_not_a_real_section(self):
        for fragment in ("示例章节", "波浪示例"):
            with self.subTest(fragment=fragment):
                self.assertTrue(check_docs.check_links(self.page, [f"说明.md#{fragment}"]))

    def test_html_id_is_checked(self):
        (self.root / "图.html").write_text('<section id="schema"></section>', encoding="utf-8")
        self.assertEqual([], check_docs.check_links(self.page, ["图.html#schema"]))
        self.assertTrue(check_docs.check_links(self.page, ["图.html#missing"]))

    def test_angle_path_with_spaces_and_link_title(self):
        (self.root / "带 空格.md").write_text("# 内容\n", encoding="utf-8")
        self.assertEqual([], check_docs.check_links(self.page, ['<带 空格.md#内容> "说明"']))

    def test_missing_and_outside_files_are_rejected(self):
        for target in ("缺少.md", "../入口.md"):
            with self.subTest(target=target):
                self.assertTrue(check_docs.check_links(self.page, [target]))
        self.assertEqual([], check_docs.check_links(self.page, ["https://example.com/#remote"]))

    def test_invalid_link_format_is_reported(self):
        for target in ("https://[broken", "说明.md extra"):
            with self.subTest(target=target):
                self.assertTrue(check_docs.check_links(self.page, [target]))

    def test_shorter_or_different_fence_does_not_close_block(self):
        for closer in ("```", "~~~~"):
            with self.subTest(closer=closer):
                _, unclosed = check_docs.markdown_body(f"````md\n## 示例\n{closer}\n")
                self.assertTrue(unclosed)
        prose, unclosed = check_docs.markdown_body("````md\n## 示例\n````\n## 正文\n")
        self.assertFalse(unclosed)
        self.assertEqual("正文", prose.strip().removeprefix("## "))


if __name__ == "__main__":
    unittest.main()
