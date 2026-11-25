Respect [PRODUCT](PRODUCT.md) and implement everything as guided by it.

On design matters that are not defined, first consult PRODUCT, then [Ztyle](https://izzardry.github.io/ztyle/#layout).

- Prefer standard libraries and pure implementations.
- The software must be plug and play easily in a cloud environment, provisioned via terraform or helm charts or manually.
- Avoid comment bloat.
- User must be able to start and run the software on their machine.
- Performance is key. Optimize for speed and 50K-100K concurrent users scale, with 1000-10K incidents and alerts per day.
- Use conventional commits. Full commit messages explain WHY something was done and use up to 10 bullet points to explain what was done. Linear history on main ONLY.
- ASD-STE100 for technical texts.
- DO NOT modify top level Markdown files without prior approval. Exception: README.md
- Keep the README.md on the top level short, up to date and start with the WHY of the product with its benefits, followed by an example workflow involving incident management with an agent, screenshots from the interface, and then installation / deployment instructions.
- Mermaid for diagrams.
- hexagonal architecture so the domain can be reconfigured and/or extended.
- contract tests for testing. Always.
- bugs must be confirmed by providing hard evidence. No hard evidence (eg. a test that fails) = no bug.
- Each section / domain must be fully documented in a concise manner as a sort of product brief (including positive and negative use-cases), so that the section can be completely deleted (as code) and regenerated / written anew with all the gained knowledge only using the product brief.
- Keep documentation up to date. If some 2 sources disagree, complete all the work you can complete before raising it; never forget to raise the question so disagreement is curbed early on.
