#!/bin/sh
mkdir -p out
javac -d out src/courtzone/*.java && java -cp out courtzone.App
