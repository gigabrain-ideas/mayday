# Targets use local tools when present and Docker otherwise.
GRADLE := $(shell [ -x ./gradlew ] && echo ./gradlew || echo gradle)
# The web build generates its gRPC-Web client from ../proto, so Docker mounts the repository root.
NPM_DOCKER := docker run --rm -v "$(CURDIR)":/src -w /src/web node:24-alpine npm
GRADLE_DOCKER := docker run --rm -v "$(CURDIR)":/src -v mayday-gradle:/home/gradle/.gradle -w /src gradle:9-jdk25 gradle --no-daemon
JAVA_DOCKER := docker run --rm -it -p 8080:8080 -p 9090:9090 -v "$(CURDIR)/build/libs":/opt/mayday -e MAYDAY_DATA_DIR -e MAYDAY_DEMO -e MAYDAY_DEV_LOGIN eclipse-temurin:25-jre-alpine java
HAVE_NODE := $(shell command -v npm 2>/dev/null)
HAVE_JAVA := $(shell command -v java 2>/dev/null)
JAR := build/libs/mayday.jar

.PHONY: web build test e2e run demo image up down clean

web:
ifdef HAVE_NODE
	cd web && npm ci && npm run build
else
	$(NPM_DOCKER) ci && $(NPM_DOCKER) run build
endif

build: web
ifdef HAVE_JAVA
	$(GRADLE) bootJar
else
	$(GRADLE_DOCKER) bootJar
endif

test:
ifdef HAVE_JAVA
	$(GRADLE) test
else
	docker run --rm -v "$(CURDIR)":/src -v mayday-gradle:/home/gradle/.gradle -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway -w /src gradle:9-jdk25 gradle --no-daemon test
endif

# Browser use-case tests (Playwright). Headless; `make e2e HEADED=1` shows the browser, SLOWMO=<ms> sets the delay per action.
e2e: web
	MAYDAY_E2E_HEADED=$(if $(filter 1 true yes,$(HEADED)),true,false) MAYDAY_E2E_SLOWMO=$(SLOWMO) $(GRADLE) e2e

run: build
ifdef HAVE_JAVA
	java -jar $(JAR)
else
	$(JAVA_DOCKER) -jar /opt/mayday/mayday.jar
endif

demo: build
ifdef HAVE_JAVA
	MAYDAY_DEMO=true MAYDAY_DEV_LOGIN=true MAYDAY_DATA_DIR=none java -jar $(JAR)
else
	MAYDAY_DEMO=true MAYDAY_DEV_LOGIN=true MAYDAY_DATA_DIR=none $(JAVA_DOCKER) -jar /opt/mayday/mayday.jar
endif

image:
	docker build -t mayday:local .

up:
	docker compose up --build

down:
	docker compose down

clean:
	rm -rf build web/dist
