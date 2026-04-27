# AGENTS

Start here:

- [it/README.md](it/README.md)
- [README.md](README.md)
- [NOTES.md](NOTES.md)

Working approach:

- Read the nearest module `README.md` before editing code in that area.
- Keep `console2/`, backend modules, and runtime/agent changes separated unless the task is explicitly cross-cutting.
- When investigating `it/console` UI test failures, inspect screenshots from `it/console/target/screenshots/` and prefer native vision/image tools when available; use OCR or terminal renderers only as fallback.

Fork workflow:

- Keep fork commits on top of the latest upstream `master` tip.
- Integrate upstream PRs with squash-and-merge style commits: use a short commit subject and no commit body.
- Do not edit `CHANGELOG.md` in those PR integration commits.
- Maintain a separate PR named `Fork Changelog` when fork changelog entries are needed.
- In that PR, add entries under the top-level `## Fork Changes` section immediately below `# Change Log`.
- Format fork changelog entries like upstream entries, including PR links.
