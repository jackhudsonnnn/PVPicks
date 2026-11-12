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

const sources = [
  ["backend/api/openapi.yaml", "source/openapi.yaml"],
  ["backend/README.md", "source/backend-readme.md"],
  ["docs/architecture-diary.md", "source/architecture-diary.md"],
  ["docs/reference/domain-rules.md", "source/reference/domain-rules.md"],
  ["docs/reference/backend-contract-inventory.md", "source/reference/backend-contract-inventory.md"],
  ["docs/reference/backend-test-map.md", "source/reference/backend-test-map.md"],
  ["docs/reference/realtime-contract.md", "source/reference/realtime-contract.md"],
  ["docs/archive/architecture-diary.md", "source/archive/architecture-diary.md"],
  ["docs/archive/p2picks-backend-kt_v3.md", "source/archive/p2picks-backend-kt_v3.md"],
  ["docs/archive/p2picks-backend-kt_v3.docx", "source/archive/p2picks-backend-kt_v3.docx"],
  ["docs/archive/p2picks-backend-phased-knowledge-transfer-document-2026-11-04.md",
    "source/archive/p2picks-backend-phased-knowledge-transfer-document-2026-11-04.md"],
  ["client/roadmap.md", "source/frontend-roadmap.md"],
  ...["0000-template.md", "0001-modular-monolith.md", "0002-transactional-outbox.md",
    "0003-redis-cache-only.md", "0004-sports-feed.md"].map(name => [`docs/adr/${name}`, `source/adr/${name}`])
];

for (const [source, destination] of sources) {
  const target = resolve(output, destination);
  await mkdir(dirname(target), { recursive: true });
  await copyFile(resolve(root, source), target);
}
