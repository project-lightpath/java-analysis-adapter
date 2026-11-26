.PHONY: package adapter-test adapter-package java-lint java-lint-report java-lint-test manual-analysis spring-test e2e-test

# JUnit runs the tests; this target only sizes the pool and heap from the host.
# The default lane is lightweight: SootUp scene tests are tagged 'sootup' and
# run in their own bounded execution (like large-archive) with a fixed 6 GB
# heap and parallelism 2, sized for the 16 GB CI runner. The default pool is
# capped by CPU count and at 8; the heap is 1.5 GB per slot (the lane runs
# in-JVM javac compilations; re-measured 2026-11 after issue #41 grew the
# suite past 590 executions with more concurrent javac-backed analyses:
# 1 GB per slot OOMs intermittently at four parallel slots, 512 MB per slot
# OOMs under four parallel javac initializations). Override with
# ADAPTER_TEST_PARALLELISM=N (heap follows at 1.5 GB per slot).
ADAPTER_TEST_PARALLELISM ?= $(shell sh -c '\
	n=$$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4); \
	p=$$n; [ "$$p" -gt 8 ] && p=8; \
	[ "$$p" -lt 1 ] && p=1; echo "$$p"')

adapter-test:
	rm -rf target/surefire-reports target/surefire-reports-large target/surefire-reports-sootup
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && \
	JAVA_TOOL_OPTIONS="$${JAVA_TOOL_OPTIONS:-} -Djunit.jupiter.execution.parallel.config.fixed.parallelism=$(ADAPTER_TEST_PARALLELISM)" \
	"$${MAVEN_BIN}" -B -ntp test -f pom.xml -Dtest.heap=-Xmx$$(($(ADAPTER_TEST_PARALLELISM) * 1536))m
	JAVA_BIN="$${JAVA_BIN:-$${JAVA:-$${JAVA_HOME:+$${JAVA_HOME}/bin/}java}}" && \
	"$${JAVA_BIN}" -cp target/classes com.projectlightpath.analysis.java.SurefireTestCounter 717
e2e-test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp -Dtest=ServiceLoaderEndToEndTest test -f pom.xml

manual-analysis:
	@test -n "$(CONTEXT)" || (echo 'CONTEXT is required' >&2; exit 2)
	@test -n "$(OUTPUT)" || (echo 'OUTPUT is required' >&2; exit 2)
	MAVEN_BIN="$${MAVEN_BIN:-$$(bash spike/scripts/bootstrap-maven.sh)}" && \
	JAVA_BIN="$${JAVA_BIN:-$${JAVA:-$${JAVA_HOME:+$${JAVA_HOME}/bin/}java}}" && \
	"$${MAVEN_BIN}" -B -ntp package -DskipTests -f pom.xml && \
	"$${MAVEN_BIN}" -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/manual-analysis-classpath.txt -DincludeScope=runtime -f pom.xml && \
	"$${JAVA_BIN}" -cp "target/classes:$$(cat target/manual-analysis-classpath.txt)" com.projectlightpath.analysis.java.ManualAnalysisMain --context="$(CONTEXT)" --output="$(OUTPUT)"

adapter-package package:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp package -DskipTests -f pom.xml

java-lint-test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && \
	"$${MAVEN_BIN}" -B -ntp test -f tools/java-lint-maven-plugin/pom.xml

java-lint: java-lint-test
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && \
	"$${MAVEN_BIN}" -B -ntp install -DskipTests -f tools/java-lint-maven-plugin/pom.xml && \
	"$${MAVEN_BIN}" -B -ntp help:effective-pom -Doutput=target/java-lint-effective-pom.xml -f pom.xml && \
	"$${MAVEN_BIN}" -B -ntp pmd:pmd pmd:cpd -f pom.xml && \
	"$${MAVEN_BIN}" -B -ntp org.projectlightpath:java-lint-maven-plugin:0.1.0:validate -DjavaLint.enforce=true -f pom.xml

java-lint-report:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && \
	"$${MAVEN_BIN}" -B -ntp install -DskipTests -f tools/java-lint-maven-plugin/pom.xml && \
	"$${MAVEN_BIN}" -B -ntp help:effective-pom -Doutput=target/java-lint-effective-pom.xml -f pom.xml && \
	"$${MAVEN_BIN}" -B -ntp pmd:pmd pmd:cpd -f pom.xml && \
	"$${MAVEN_BIN}" -B -ntp org.projectlightpath:java-lint-maven-plugin:0.1.0:validate -DjavaLint.enforce=false -f pom.xml

spring-test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp test -f ../spring-boot-example-application/pom.xml
