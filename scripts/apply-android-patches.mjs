#!/usr/bin/env node
import { copyFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = process.argv[2];
if (!root) {
  console.error("usage: apply-android-patches.mjs <dshroot>");
  process.exit(1);
}

const file = join(
  root,
  "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
);
let text = readFileSync(file, "utf8");
const before = text;

text = text.replace(
  "await internals.fs.link(staged, currentPath);",
  `try { await internals.fs.link(staged, currentPath); } catch (linkErr) {
		if (isEEXIST(linkErr)) return false;
		const { copyFile } = await import("node:fs/promises");
		await copyFile(staged, currentPath); /* zsdsh-copyfile-fallback-current */
	}`,
);

text = text.replace(
  "await link(tmp, finalPath);",
  `try { await link(tmp, finalPath); } catch (linkErr) {
			const { copyFile } = await import("node:fs/promises");
			await copyFile(tmp, finalPath); /* zsdsh-copyfile-fallback */
		}`,
);

if (text === before) {
  console.error("android patch: persistence markers not found");
  process.exit(2);
}
writeFileSync(file, text);
console.log("patched session-persistence-jsonl link() -> rename() fallback");

const bash = join(root, "node_modules/@deepseek-ai/dsh-bash-local/lib/index.js");
let bashText = readFileSync(bash, "utf8");
if (!bashText.includes('get sandboxMode() { return "danger-full-access"; }')) {
  const needle = 'static inject = ["subprocess"];';
  if (!bashText.includes(needle)) {
    console.error("android patch: bash-local inject marker not found");
    process.exit(3);
  }
  bashText = bashText.replace(
    needle,
    `${needle}\n\tget sandboxMode() { return "danger-full-access"; }`,
  );
  writeFileSync(bash, bashText);
}
console.log("patched bash-local sandboxMode -> danger-full-access");

if (!bashText.includes("zsdsh-android-sh")) {
  bashText = readFileSync(bash, "utf8");
  bashText = bashText.replaceAll(
    '["bash",\n\t\t\t"-c",',
    '["/system/bin/sh", /* zsdsh-android-sh */\n\t\t\t"-c",',
  );
  writeFileSync(bash, bashText);
  console.log("patched bash-local bash -> /system/bin/sh");
}

const flockSrc = join(dirname(fileURLToPath(import.meta.url)), "../shims/node-addon-system-flock.js");
const flockDst = join(root, "node_modules/@deepseek-ai/node-addon-system/lib/flock.js");
if (existsSync(flockSrc) && existsSync(flockDst)) {
  copyFileSync(flockSrc, flockDst);
  console.log("patched node-addon-system flock for Android");
}
