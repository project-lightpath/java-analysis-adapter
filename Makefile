.PHONY: package adapter-test adapter-package validator-test java-lint java-lint-report java-lint-test manual-analysis evidence-validate framework-enrichment semantic-facts-spike fixture-test fixture-package spring-test download-jdtls jdtls-probe sootup-quarkus sootup-quarkus-full sootup-spring sootup-fsi codeql-quarkus codeql-spring codeql-fsi normalize-static-analysis e2e-test

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
	"$${JAVA_BIN}" -cp target/classes com.projectlightpath.analysis.java.SurefireTestCounter 734
e2e-test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp -Dtest=ServiceLoaderEndToEndTest test -f pom.xml

validator-test:
	python3 -m unittest spike/scripts/test_validators.py

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

evidence-validate:
	python3 spike/scripts/validate_spike.py

framework-enrichment:
	python3 spike/scripts/enrich_framework_facts.py

semantic-facts-spike: normalize-static-analysis framework-enrichment
	python3 spike/scripts/assemble_semantic_facts.py

fixture-test:
	bash spike/scripts/run-fixture.sh test

fixture-package:
	bash spike/scripts/run-fixture.sh production package

spring-test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp test -f ../spring-boot-example-application/pom.xml

download-jdtls:
	bash spike/scripts/download-jdtls.sh

jdtls-probe:
	python3 spike/scripts/probe_jdtls.py

sootup-quarkus:
	python3 spike/scripts/run_static_analysis.py --provider sootup --fixture quarkus --classpath-mode none

sootup-quarkus-full:
	python3 spike/scripts/run_static_analysis.py --provider sootup --fixture quarkus --classpath-mode full --allow-failure

sootup-spring:
	python3 spike/scripts/run_static_analysis.py --provider sootup --fixture spring --classpath-mode full

sootup-fsi: sootup-spring

codeql-quarkus:
	python3 spike/scripts/run_static_analysis.py --provider codeql --fixture quarkus --replace

codeql-spring:
	python3 spike/scripts/run_static_analysis.py --provider codeql --fixture spring --replace

codeql-fsi: codeql-spring

normalize-static-analysis:
	python3 spike/scripts/normalize_static_analysis.py --provider sootup --fixture quarkus --classpath-mode full --input spike/runs/sootup-java21-quarkus-with-deps.json --output spike/results/sootup-3.0.1-java21-quarkus-observation.json
	python3 spike/scripts/normalize_static_analysis.py --provider sootup --fixture spring --classpath-mode full --input spike/runs/sootup-fsi-spring-with-deps.json --output spike/results/sootup-3.0.1-fsi-spring-observation.json
	python3 spike/scripts/normalize_static_analysis.py --provider codeql --fixture quarkus --input spike/runs/codeql-java21-quarkus.csv --output spike/results/codeql-2.27.4-java21-quarkus-observation.json
	python3 spike/scripts/normalize_static_analysis.py --provider codeql --fixture spring --input spike/runs/codeql-fsi-spring.csv --output spike/results/codeql-2.27.4-fsi-spring-observation.json
