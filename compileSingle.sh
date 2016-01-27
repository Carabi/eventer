#!/bin/bash
mvn clean
mvn compile assembly:single
rm -r target/archive-tmp
rm -r target/classes
rm -r target/generated-sources
mv target/eventer*jar target/eventer.jar
cp src/main/shell/* target
