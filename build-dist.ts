import { cp, mkdir, rm } from "node:fs/promises"
import { readdirSync, statSync } from "node:fs"
import { join } from "node:path"

// Assembles the deployable static site for the examples.
//
// `examples/` is the source tree (`.scala` sources next to their `index.html`
// and `main.js` shim) and `examples/out/` holds the shared scala-cli bundle —
// every example's `main.js` imports `../out/<name>.js`, so that relative
// layout has to survive into `dist/`. This copies exactly the files a browser
// needs — `index.html`, `main.js` and an optional `assets/` folder of files the
// example loads at runtime — and drops the Scala sources.
//
// Run `bun run examples:build` first; this script does not compile.

const examplesDir = "./examples"
const outDir = "./examples/out"
const distDir = "./dist"

const exists = (path: string) => Bun.file(path).exists()

if (!(await exists(join(outDir, "simple_triangle.js")))) {
	console.error(
		`missing ${outDir} — run \`bun run examples:build\` before assembling dist`,
	)
	process.exit(1)
}

await rm(distDir, { recursive: true, force: true })
await mkdir(distDir, { recursive: true })

await cp(join(examplesDir, "index.html"), join(distDir, "index.html"))
await cp(outDir, join(distDir, "out"), { recursive: true })

const copied: string[] = []
for (const entry of readdirSync(examplesDir)) {
	const dir = join(examplesDir, entry)
	if (entry === "out" || !statSync(dir).isDirectory()) continue
	const html = join(dir, "index.html")
	if (!(await exists(html))) continue

	await mkdir(join(distDir, entry), { recursive: true })
	await cp(html, join(distDir, entry, "index.html"))
	const mainJs = join(dir, "main.js")
	if (await exists(mainJs)) await cp(mainJs, join(distDir, entry, "main.js"))
	const assets = join(dir, "assets")
	if (statSync(assets, { throwIfNoEntry: false })?.isDirectory())
		await cp(assets, join(distDir, entry, "assets"), { recursive: true })
	copied.push(entry)
}

console.log(`dist/ assembled — ${copied.length} examples: ${copied.join(", ")}`)
