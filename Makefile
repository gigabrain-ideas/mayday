# Targets use local tools when present and Docker otherwise.
GRADLE := $(shell [ -x ./gradlew ] && echo ./gradlew || echo gradle)
NPM_DOCKER := docker run --rm -v "$(CURDIR)/web":/app -w /app node:24-alpine npm
GRADLE_DOCKER := docker run --rm -v "$(CURDIR)":/src -v mayday-gradle:/home/gradle/.gradle -w /src gradle:9-jdk25 gradle --no-daemon
HAVE_NODE := $(shell command -v npm 2>/dev/null)
HAVE_JAVA := $(shell command -v java 2>/dev/null)

.PHONY: web build test run demo image up down clean

web:
ifdef HAVE_NODE
	cd web && npm ci && npm run build
else
	$(NPM_DOCKER) ci && $(NPM_DOCKER) run build
endif

build: web
ifdef HAVE_JAVA
	$(GRADLE) installDist
else
	$(GRADLE_DOCKER) installDist
endif

test:
ifdef HAVE_JAVA
	$(GRADLE) test
else
	$(GRADLE_DOCKER) test
endif

run: build
	./build/install/mayday/bin/mayday

demo: build
	MAYDAY_DEMO=true MAYDAY_DEV_LOGIN=true MAYDAY_DATA_DIR=none ./build/install/mayday/bin/mayday

image:
	docker build -t mayday:local .

up:
	docker compose up --build

down:
	docker compose down

clean:
	rm -rf build web/dist
