#!/usr/bin/env node

const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const marked = require('marked');

/**
 * 将 Markdown 转换为长图片
 * 使用方法: node scripts/md-to-image.js README.md
 */

async function mdToImage(mdFilePath, outputPath = null) {
  try {
    // 1. 读取 Markdown 文件
    if (!fs.existsSync(mdFilePath)) {
      throw new Error(`文件不存在: ${mdFilePath}`);
    }

    const mdContent = fs.readFileSync(mdFilePath, 'utf-8');
    console.log(`✓ 已读取: ${mdFilePath}`);

    // 2. 转换为 HTML
    const htmlContent = marked.parse(mdContent);
    console.log('✓ 已转换为 HTML');

    // 3. 创建完整的 HTML 页面
    const fullHtml = `
      <!DOCTYPE html>
      <html lang="zh-CN">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>AgentSphere README</title>
        <style>
          * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
          }

          body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'PingFang SC', 'Microsoft YaHei', sans-serif;
            line-height: 1.8;
            color: #333;
            background: #fff;
            width: 1200px;
            padding: 60px 80px;
          }

          /* 标题样式 */
          h1 {
            font-size: 2.5em;
            color: #1890ff;
            margin-top: 0;
            margin-bottom: 40px;
            border-bottom: 3px solid #1890ff;
            padding-bottom: 20px;
          }

          h2 {
            font-size: 1.8em;
            color: #0050b3;
            margin-top: 40px;
            margin-bottom: 20px;
            padding-left: 15px;
            border-left: 4px solid #0050b3;
          }

          h3 {
            font-size: 1.4em;
            color: #1890ff;
            margin-top: 30px;
            margin-bottom: 15px;
          }

          h4 {
            font-size: 1.1em;
            color: #40a9ff;
            margin-top: 20px;
            margin-bottom: 10px;
          }

          /* 段落与文本 */
          p {
            margin-bottom: 15px;
            font-size: 0.95em;
          }

          a {
            color: #1890ff;
            text-decoration: none;
            border-bottom: 1px dotted #1890ff;
          }

          a:hover {
            color: #40a9ff;
          }

          /* 列表 */
          ul, ol {
            margin: 15px 0 15px 30px;
          }

          li {
            margin-bottom: 8px;
            font-size: 0.95em;
          }

          /* 表格 */
          table {
            border-collapse: collapse;
            width: 100%;
            margin: 20px 0;
            font-size: 0.9em;
            box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
          }

          table thead {
            background: #fafafa;
            border-bottom: 2px solid #1890ff;
          }

          table th {
            padding: 12px;
            text-align: left;
            font-weight: 600;
            color: #000;
          }

          table td {
            padding: 10px 12px;
            border-bottom: 1px solid #e8e8e8;
          }

          table tbody tr:hover {
            background: #f5f5f5;
          }

          /* 代码块 */
          code {
            background: #f5f5f5;
            padding: 2px 6px;
            border-radius: 3px;
            font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
            font-size: 0.9em;
            color: #c41d7f;
          }

          pre {
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 15px;
            border-radius: 5px;
            overflow-x: auto;
            margin: 15px 0;
            border-left: 4px solid #1890ff;
          }

          pre code {
            background: none;
            color: #d4d4d4;
            padding: 0;
          }

          /* 块引用 */
          blockquote {
            border-left: 4px solid #1890ff;
            padding-left: 15px;
            margin: 15px 0;
            color: #666;
            font-style: italic;
          }

          /* 水平线 */
          hr {
            border: none;
            height: 2px;
            background: linear-gradient(to right, #1890ff, transparent);
            margin: 40px 0;
          }

          /* 图片 */
          img {
            max-width: 100%;
            height: auto;
            margin: 20px 0;
            border-radius: 4px;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
          }

          /* 页脚分隔 */
          .page-break {
            page-break-after: always;
            margin: 100px 0;
          }
        </style>
      </head>
      <body>
        ${htmlContent}
      </body>
      </html>
    `;

    // 4. 启动浏览器
    const browser = await puppeteer.launch({
      headless: 'new',
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    });

    const page = await browser.newPage();
    
    // 设置视口大小（长图宽度）
    await page.setViewport({
      width: 1200,
      height: 1600,
      deviceScaleFactor: 2
    });

    // 5. 加载 HTML
    await page.setContent(fullHtml, { waitUntil: 'networkidle0' });
    console.log('✓ 已加载 HTML 页面');

    // 6. 获取完整高度
    const bodyHeight = await page.evaluate(() => {
      return document.documentElement.scrollHeight;
    });
    console.log(`✓ 页面高度: ${bodyHeight}px`);

    // 7. 设置合适的视口高度
    await page.setViewport({
      width: 1200,
      height: bodyHeight,
      deviceScaleFactor: 2
    });

    // 8. 生成截图
    const outputFile = outputPath || path.join(
      path.dirname(mdFilePath),
      `${path.basename(mdFilePath, '.md')}.png`
    );

    await page.screenshot({
      path: outputFile,
      fullPage: true,
      type: 'png'
    });

    console.log(`✓ 已生成长图: ${outputFile}`);

    await browser.close();
    return outputFile;

  } catch (error) {
    console.error('✗ 出错:', error.message);
    process.exit(1);
  }
}

// CLI 执行
const mdFile = process.argv[2] || 'README.md';
const outputFile = process.argv[3];
mdToImage(mdFile, outputFile);
