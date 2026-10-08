# Zyrex - Developer Guide

## Build & Test Commands
- `sbt compile` - Build project
- `sbt test` - Run unit tests
- `sbt it:test` - Integration tests (requires Docker)
- `sbt it2:test` - Bootstrap/mainnet sync tests
- `sbt "testOnly *ClassName"` - Run specific test class
- `sbt ergoWallet/test` - Test wallet module only
- `sbt scalafmtCheck` - Check code formatting
- `sbt assembly` - Create fat JAR

## Code Style Guidelines
- **Scala**: 2.12.20 (primary), scalafmt with 90 char limit
- **Imports**: Sorted, no wildcards, grouped by package
- **Naming**: PascalCase classes, camelCase methods, UPPER_SNAKE constants
- **Error Handling**: Use `Try`, `Either`, `ValidationResult` - avoid exceptions
- **Logging**: Extend `ScorexLogging` trait for proper logging
- **File Limits**: Max 800 lines per file, 160 chars per line
- **Formatting**: Follow .scalafmt.conf and scalastyle-config.xml rules

## Project Structure
- **ergo/**: Main node application with Akka HTTP API
- **ergo-core/**: Core protocols (P2P, blocks, Autolykos PoW)
- **ergo-wallet/**: Transaction signing and wallet operations
- **avldb/**: Authenticated AVL+ tree with LevelDB persistence

## Key Patterns
- Use `ErgoCorePropertyTest` base for property tests
- Follow existing test patterns in similar files
- Type annotations for public methods
- Prefer immutable data structures and functional patterns

## Fork scope
The user explicitly authorized production changes for an independent Zyrex fork.
Keep changes limited to network identity, emission, launch tooling, and branding.
Preserve eUTXO, ErgoScript, Autolykos v2, serialization, packages and licenses.
Changes to consensus require executable positive and adversarial tests.
Public testnet and mainnet stay unlaunched until private-testnet acceptance.
## Public content

- Use English for public documentation, interfaces, source comments and commit messages.
- Use `zyrex` as the author of Zyrex commits; retain upstream license and attribution notices.
- Do not publish operator LAN addresses, host usernames, absolute home paths or deployment reports.
- Keep wallet backups, keys, credentials and execution reports in ignored local storage.
- Preserve immutable cryptographic fixture values and standardized mnemonic dictionaries.
- Run `python3 scripts/check-public-content.py` before committing public content.
