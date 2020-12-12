# Build file for hoard

.PHONY: all clean lint check graal package

version := $(shell grep defproject project.clj | cut -d ' ' -f 3 | tr -d \")
platform := $(shell uname -s | tr '[:upper:]' '[:lower:]')
uberjar_path := target/uberjar/hoard.jar

# Graal settings
GRAAL_ROOT ?= /tmp/graal
graal_dist := graalvm-ce-java11
graal_version := 20.1.0
graal_archive := $(graal_dist)-$(platform)-amd64-$(graal_version).tar.gz
graal_home := $(GRAAL_ROOT)/$(graal_dist)-$(graal_version)

# Rewrite darwin as a more recognizable OS
ifeq ($(platform),darwin)
platform := macos
graal_home := $(graal_home)/Contents/Home
endif

release_jar := hoard-$(version).jar
release_zip := hoard_$(version)_$(platform).zip


all: hoard

clean:
	rm -rf dist hoard target

lint:
	clj-kondo --lint src test
	lein yagni

check:
	lein check

$(uberjar_path): project.clj $(shell find src -type f)
	lein uberjar

$(GRAAL_ROOT)/fetch/$(graal_archive):
	@mkdir -p $(GRAAL_ROOT)/fetch
	curl --location --output $@ https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-$(graal_version)/$(graal_archive)

$(graal_home): $(GRAAL_ROOT)/fetch/$(graal_archive)
	tar -xz -C $(GRAAL_ROOT) -f $<

$(graal_home)/bin/native-image: $(graal_home)
	$(graal_home)/bin/gu install native-image

graal: $(graal_home)/bin/native-image

hoard: $(uberjar_path) $(graal_home)/bin/native-image
	$(graal_home)/bin/native-image \
	    --no-fallback \
	    --allow-incomplete-classpath \
	    --report-unsupported-elements-at-runtime \
	    --initialize-at-build-time \
	    -J-Xms3G -J-Xmx3G \
	    -J-Dclojure.compiler.direct-linking=true \
	    -J-Dclojure.spec.skip-macros=true \
	    --no-server \
	    -jar $<

dist/$(release_zip): hoard
	@mkdir -p dist
	zip $@ $^

dist/$(release_jar): $(uberjar_path)
	@mkdir -p dist
	cp $< $@

package: dist/$(release_jar) dist/$(release_zip)
