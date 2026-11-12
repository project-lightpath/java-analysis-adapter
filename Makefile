.PHONY: test package adapter-test adapter-package java-lint java-lint-report java-lint-test manual-analysis evidence-validate framework-enrichment semantic-facts-spike fixture-test fixture-package spring-test download-jdtls jdtls-probe sootup-quarkus sootup-quarkus-full sootup-spring sootup-fsi codeql-quarkus codeql-spring codeql-fsi normalize-static-analysis

adapter-test test:
	MAVEN_BIN="$$(bash spike/scripts/bootstrap-maven.sh)" && "$${MAVEN_BIN}" -B -ntp test -f pom.xml

manual-analysis:
	@test -n "$(CONTEXT)" || (echo 'CONTEXT is required' >&2; exit 2)
	@test -n "$(OUTPUT)" || (echo 'OUTPUT is required' >&2; exit 2)
	MAVEN_BIN="$${MAVEN_BIN:-$$(bash spike/scripts/bootstrap-maven.sh)}" && \
	JAVA_BIN="$${JAVA_BIN:-$${JAVA:-java}}" && \
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
