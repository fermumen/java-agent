// Screenshots every recorded frame to out/<frame>.png, plus a cropped spinner row per
// shimmer frame (out/row-tNN.png) for building a GIF. Needs `python3 -m http.server`
// serving this directory on 18090; CHROMIUM may point at a system Chromium binary.
import { chromium } from 'playwright';
import fs from 'fs';

const marks = JSON.parse(fs.readFileSync(new URL('./marks.json', import.meta.url))).marks;
fs.mkdirSync(new URL('./out/', import.meta.url), { recursive: true });
const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const page = await browser.newPage({ viewport: { width: 1000, height: 760 }, deviceScaleFactor: 2 });
for (const frame of Object.keys(marks)) {
  await page.goto(`http://127.0.0.1:18090/index.html?frame=${frame}`);
  await page.waitForFunction(() => document.title.startsWith('ready'));
  await page.waitForTimeout(150);
  const out = new URL(`./out/${frame}.png`, import.meta.url).pathname;
  await page.locator('.win').screenshot({ path: out });
  if (/^t\d\d$/.test(frame)) {
    // Row 4 is the spinner line in this scenario (welcome wraps to two rows at 92 columns).
    const box = await page.locator('.xterm-rows > div').nth(4).boundingBox();
    await page.screenshot({ path: new URL(`./out/row-${frame}.png`, import.meta.url).pathname,
      clip: { x: box.x - 8, y: box.y + 3, width: 260, height: box.height + 3 } });
  }
}
await browser.close();
