import { copyFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const docs = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const root = resolve(docs, "..");
const output = resolve(docs, "site/.vitepress/dist");
const vitepress = resolve(docs, "node_modules/vitepress/bin/vitepress.js");
const build = spawnSync(process.execPath, [vitepress, "build", "site"], {
  cwd: docs,
  stdio: "inherit"
});
if (build.error) throw build.error;
if (build.status !== 0) process.exit(build.status ?? 1);

// Files offered as downloads next to the site; everything else is a page under site/.
const sources = [["backend/api/openapi.yaml", "source/openapi.yaml"]];

for (const [source, destination] of sources) {
  const target = resolve(output, destination);
  await mkdir(dirname(target), { recursive: true });
  await copyFile(resolve(root, source), target);
}
