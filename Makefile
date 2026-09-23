# Copyright 2010 Rebel Media
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

SHELL    := /bin/bash
MVNW     := ./mvnw
ARTIFACT := pagerduty-v2

# VERSION is the one place the version is written. pom.xml says ${revision},
# and every build started from here fills it in, so a jar built locally carries
# the same version a release of this commit would.
VERSION   := $(shell tr -d '[:space:]' < VERSION)
# The JDKs the build accepts. Any from 25 up locally; CI and releases set
# JAVA_RANGE='[25,26)' so a released jar is only ever built on JDK 25.
JAVA_RANGE ?= [25,)
MVN_FLAGS := -B -ntp -Drevision=$(VERSION) "-Djava.range=$(JAVA_RANGE)"


# The image `make verify-docker` builds in: the JDK 25 CI uses, for a machine
# that has another JDK. The local Maven repository is shared with the container
# so dependencies are downloaded once.
DOCKER_IMAGE ?= maven:3-eclipse-temurin-25

# The development Jenkins' home for `make run`, passed explicitly because
# hpi:run would otherwise prefer an exported $JENKINS_HOME over ./work, and
# `make seed-job` writes here. RUN_FLAGS adds options to hpi:run only, e.g.
# `make run RUN_FLAGS=-Dhost=172.17.0.1` on Linux (see docker-compose.yml).
DEV_HOME  ?= $(CURDIR)/work
RUN_FLAGS ?=

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Compile, test and package (no lint, no coverage floor)
	$(MVNW) $(MVN_FLAGS) package -Dspotless.check.skip=true -Dcheckstyle.skip=true

.PHONY: test
test: ## Run the unit tests
	$(MVNW) $(MVN_FLAGS) test

.PHONY: verify
verify: ## Everything Maven checks: tests, coverage floor, Spotless, Checkstyle, enforcer
	$(MVNW) $(MVN_FLAGS) verify

# The same `verify`, in a JDK 25 container: exactly what CI runs, for checking
# that a change builds byte-identically on the release JDK. unzip is installed first because the
# wrapper checks the Maven zip's SHA-256, and without unzip it fetches the
# tar.gz instead, whose checksum is different.
.PHONY: verify-docker
verify-docker: ## Run `verify` in a JDK 25 container
	docker run --rm -v "$(CURDIR)":/src -v "$(HOME)/.m2":/root/.m2 -w /src $(DOCKER_IMAGE) sh -c '\
		command -v unzip >/dev/null || { apt-get update -qq && apt-get install -y -qq unzip >/dev/null; }; \
		./mvnw $(MVN_FLAGS) verify'

.PHONY: cover
cover: ## Run the tests and write the coverage report to target/site/jacoco
	$(MVNW) $(MVN_FLAGS) test jacoco:report
	@echo ">> open target/site/jacoco/index.html"

.PHONY: lint
lint: ## Check formatting (Spotless) and run Checkstyle
	$(MVNW) $(MVN_FLAGS) spotless:check checkstyle:check

.PHONY: fmt
fmt: ## Format the Java sources with palantir-java-format
	$(MVNW) $(MVN_FLAGS) spotless:apply

.PHONY: run
run: ## Start a Jenkins at http://localhost:8080/jenkins with the plugin loaded
	$(MVNW) $(MVN_FLAGS) -DjenkinsHome="$(DEV_HOME)" $(RUN_FLAGS) hpi:run

# An inbound agent in Docker, attached to the `make run` Jenkins, for testing
# what the plugin does when an agent disconnects mid-build. The agent's secret
# is read from .env, which git ignores; see CONTRIBUTING.md.
.PHONY: seed-job
seed-job: ## Copy the disconnect-test node and job from dev/ into the `make run` Jenkins
	mkdir -p "$(DEV_HOME)/nodes/disconnect-test" "$(DEV_HOME)/jobs/disconnect-test"
	cp dev/nodes/disconnect-test/config.xml "$(DEV_HOME)/nodes/disconnect-test/config.xml"
	cp dev/jobs/disconnect-test/config.xml "$(DEV_HOME)/jobs/disconnect-test/config.xml"
	@echo ">> seeded $(DEV_HOME); start (or restart) \`make run\` to load them"

.PHONY: agent-up
agent-up: ## Start the inbound agent container (needs JENKINS_SECRET in .env)
	docker compose up -d agent

.PHONY: agent-kill
agent-kill: ## Kill the agent abruptly, to simulate a disconnect mid-build
	docker compose kill agent

.PHONY: agent-logs
agent-logs: ## Follow the agent's logs
	docker compose logs -f agent

.PHONY: agent-down
agent-down: ## Remove the agent container and delete its work volume
	docker compose down -v

# What the release attaches, built the way the release builds it.
.PHONY: dist
dist: verify ## Build the release artefacts into dist/ with sha256sums.txt
	rm -rf dist && mkdir -p dist
	cp target/$(ARTIFACT).hpi dist/$(ARTIFACT)-$(VERSION).hpi
	@cd dist && files="$$(ls)" && { sha256sum $$files 2>/dev/null || shasum -a 256 $$files; } > sha256sums.txt
	@echo; echo "Built:"; ls -1 dist

# Only the first three lines are checked, which is what the header's copyright
# line occupies. Cheap, and enough to catch a file added without one.
.PHONY: check-license
check-license: ## Fail if any Java file is missing the licence header
	@missing=$$(for f in $$(find src -name '*.java'); do \
		awk 'NR<=3' $$f | grep -Eq '(Copyright|generated|GENERATED)' || echo $$f; \
	done); \
	if [ -n "$$missing" ]; then echo ">> missing licence header:"; echo "$$missing"; exit 1; fi
	@echo ">> licence headers present"

# VERSION names the next release and CHANGELOG.md must describe it before
# develop is merged to master; tag.yml refuses to tag otherwise. Checking it here
# means the mismatch is found on the release pull request, not after the merge.
.PHONY: check-changelog
check-changelog: ## Fail if CHANGELOG.md has no release heading for VERSION
	@v=$$(cat VERSION); \
	grep -Eq "^## $$v / [0-9]{4}-[0-9]{2}-[0-9]{2}$$" CHANGELOG.md || { \
		echo ">> CHANGELOG.md has no '## $$v / YYYY-MM-DD' heading for VERSION $$v"; exit 1; }
	@echo ">> CHANGELOG.md describes $$(cat VERSION)"

.PHONY: check
check: check-license lint verify ## Everything CI runs, locally

.PHONY: clean
clean: ## Remove build output and dist/
	$(MVNW) $(MVN_FLAGS) clean
	rm -rf dist
