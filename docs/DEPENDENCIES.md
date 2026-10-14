# Dependency inventory

Run `scripts/sbt update 'export Runtime / externalDependencyClasspath'` after changing dependencies.
Then `python3 scripts/dependency-audit.py` writes a CycloneDX 1.6 inventory with SHA-256 hashes
for the actual resolved external runtime JARs. It preserves upstream package names and available
license metadata, and reports unidentified artifacts explicitly.

Add `--online` to query the public OSV Maven API. Only Maven coordinates and versions are sent.
Exit status is `0` for a completed selected operation, `1` for advisory version matches, and `2`
for an incomplete inventory or query. Offline mode performs no vulnerability query.

Outputs default to ignored `.local/dependency-audit/`. An optional `--artifact <native.jar>` records
its hash; this does not establish source-to-binary or assembly provenance. The inventory excludes
application source, build tools, the JDK, operating-system packages and native binaries.
Advisory matches need an applicability review. No matches do not establish vulnerability freedom.

The packaged network profiles explicitly disable Akka HTTP/2. This mitigates the HTTP/2 precondition
of CVE-2023-44487 in that configuration; it does not patch the affected upstream version.
Audit output retains its version match and nonzero finding status. Re-enabling HTTP/2 requires
an HTTP stack upgrade and compatibility review.

The runtime pins Jawn 1.7.0, Guava 32.1.3-jre and Logback 1.6.5. These updates
address the reviewed parser and logging advisories while retaining Java 11.
The [Jawn maintainer advisories](https://github.com/typelevel/jawn/security/advisories)
and [Logback release notes](https://logback.qos.ch/news.html) describe the fixes;
the resolved inventory, rather than declared versions alone, identifies the packaged libraries.

A reproducible CI inventory step after assembly is:

```sh
scripts/sbt update 'export Runtime / externalDependencyClasspath'
python3 -m unittest discover -s scripts -p 'test_dependency_audit.py' -v
python3 scripts/dependency-audit.py --artifact target/scala-2.12/zyrex.jar
```

Publish `sbom.cdx.json` with the native artifact. Online auditing remains a separate explicit
operation so an unavailable vulnerability service cannot turn into a successful security check.

`org.ethereum:leveldbjni-all:1.18.3` has no license metadata in its resolved POM or embedded JAR.
Its POM identifies a FuseSource JNI fork using LevelDB. The upstream BSD notices are retained in
`DEPENDENCY-NOTICES.txt`; this does not establish complete provenance of the redistributed native
binary or replace review of its native dependencies. The inventory keeps this metadata gap visible.
