#!/bin/sh
# Compiles every example here and prints its diagnostics.
# A file named *-refused* is expected to fail. A file named *-accepted* passes validation
# although the Reference does not admit what it says; see README.md. The rest pass.
cd "$(dirname "$0")/out" || exit 1
CP=/Users/daveclay/code/velle/compiler/build/libs/compiler.jar:/Users/daveclay/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/5380b19fa1924399b62ce3a1faffebb2b4f82272/kotlin-stdlib-2.2.20.jar
for f in ../*.velle; do
    name=$(basename "$f" .velle)
    printf '== %s\n' "$name"
    java -cp "$CP" velle.GenerateKt "$f" Ex 2>&1 | grep -v '^\s*at ' | grep -v '^wrote' | sed 's/^Exception in thread "main" //'
done
