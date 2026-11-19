# FLPATH-5123 ssc-demo acceptance probe

External acceptance evidence for FLPATH-5123: scan the sibling
`spring-boot-example-application` dependency classpath with the production
`ServiceLoaderDescriptorScanner` and print descriptor and budget-diagnostic
counts.

This probe is **not** part of `make evidence-validate` and is not compiled by
Maven: it needs the sibling checkout and its resolved dependency classpath.
Expected result for the pinned baseline: 104 jars, 54 descriptors, 0
`descriptor_enumeration_limit` / `descriptor_retention_limit` /
`archive_decompression_limit` diagnostics, plus the pre-existing
`versioned_module_info_unsupported` diagnostics.

Run from the adapter repository root, with the sibling checkout at
`../spring-boot-example-application`:

```bash
MAVEN_BIN="$(bash spike/scripts/bootstrap-maven.sh)"
"$MAVEN_BIN" -B -ntp -q -DskipTests package -f pom.xml
"$MAVEN_BIN" -B -ntp -q dependency:build-classpath \
  -Dmdep.outputFile=target/adapter-runtime-cp.txt -DincludeScope=runtime -f pom.xml

( cd ../spring-boot-example-application
  "$MAVEN_BIN" -B -ntp -q -DskipTests package
  "$MAVEN_BIN" -B -ntp -q dependency:build-classpath \
    -Dmdep.outputFile=target/deps.txt -DincludeScope=runtime )

rm -rf /tmp/flpath-5123-probe
javac -cp "target/classes:$(cat target/adapter-runtime-cp.txt)" \
  -d /tmp/flpath-5123-probe spike/probes/flpath-5123/BaselineScan.java
java -cp "/tmp/flpath-5123-probe:target/classes:$(cat target/adapter-runtime-cp.txt)" \
  com.projectlightpath.analysis.java.BaselineScan \
  ../spring-boot-example-application ../spring-boot-example-application/target/deps.txt
```

The probe class lives in the production package only to reach the
package-private scanner; it is not production code.
