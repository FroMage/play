#!/bin/sh

git archive --format=tar --prefix=play-1.2.4-1.2.4/ HEAD | gzip > ../play-1.2.4-1.2.4.tar.gz
